# Architecture

How this codebase is put together and why. Read
[`README.md`](README.md) first for what it is;
[`modules/prima-core/NOTES.md`](modules/prima-core/NOTES.md) has the numerical
detail this document deliberately skips.

Only Prima, the LP solver, exists today. The layers above it are drawn here
because the shape of Prima was chosen to serve them.

## The governing constraint

Everything below follows from one requirement: the numeric hot paths must be
able to run on a GPU, and eventually be compiled to hardware, from Scala.

That rules out the usual JVM answer of "write it in idiomatic Scala and
optimise later". Immutable collections and higher-order iteration box primitives
and block inlining, which is fatal in a sparse matrix-vector product. It equally
rules out writing the whole thing in mutable low-level style, because then the
model, the effects and the orchestration become unreadable and unsafe.

The resolution is a hard boundary. Above it: immutable values, ZIO effects,
composition. Below it: primitive arrays, `while` loops, and a small interface
that an accelerator can implement. The boundary is the `Kernels` trait, and
almost every structural decision in this document is about keeping it in the
right place.

## Modules

```mermaid
graph TD
    primaCore["prima-core<br/><i>LP model, kernels, PDHG</i><br/>no dependencies"]
    primaZio["prima-zio<br/><i>effects, streaming</i>"]
    primaOjalgo["prima-ojalgo<br/><i>simplex backend + oracle</i>"]
    primaValidation["prima-validation<br/><i>cross-solver agreement</i>"]

    zioLib["ZIO / ZIO Streams"]:::ext
    ojalgoLib["ojAlgo"]:::ext

    primaZio --> primaCore
    primaOjalgo --> primaCore
    primaValidation --> primaCore
    primaValidation --> primaOjalgo
    primaZio -.-> zioLib
    primaOjalgo -.-> ojalgoLib

    classDef ext fill:#f6f6f6,stroke:#bbb,stroke-dasharray:4 3,color:#555;
```

`prima-core` has no third-party dependencies at all. That is not minimalism for
its own sake: it is what lets the solver be called from a Pekko actor, a ZIO
fiber, a test harness or an FPGA toolchain's host program without dragging an
effect system along. The effect system lives in `prima-zio`, one module out.

`prima-validation` exists as a separate module rather than as tests inside
`prima-ojalgo` because its job is to compare two backends, so it belongs to
neither.

## The solve pipeline

```mermaid
graph LR
    subgraph Caller["Caller's units"]
        builder["LpBuilder<br/>two-sided rows"]
        solution["LpSolution<br/>primal, dual, status"]
    end

    subgraph Standard["Standard form"]
        problem["LpProblem<br/>equalities, then Kx &gt;= q"]
        translation["RowTranslation<br/>row expansion map"]
    end

    subgraph Equilibrated["Equilibrated space"]
        scaled["ScaledProblem<br/>Dr K Dc"]
        loop["restarted PDHG"]
    end

    builder -->|"build()"| problem
    builder --> translation
    problem -->|"Ruiz + Pock-Chambolle"| scaled
    scaled --> loop
    loop -->|"elementwise unscale"| solution
    translation -.->|"originalDuals"| solution
```

Three coordinate systems, and a value is never allowed to be ambiguous about
which one it is in.

**Caller's units.** Rows are written as `lo <= a'x <= hi`, which is how a power
flow limit or a generator capacity is actually expressed.

**Standard form.** Equalities first, then `Kx >= q`. The ordering is load
bearing: it makes the dual cone a suffix condition — `y` free on the head,
non-negative on the tail — so the dual projection is a contiguous clamp. On a
GPU that is one branch-free kernel rather than a per-row predicate.

Converting to this form expands range rows into two, which is why `build()`
returns a `RowTranslation` as well as a problem. Without it, a caller asking for
nodal prices would get duals indexed by rows they never wrote.

**Equilibrated space.** The iteration runs on `Dr K Dc`. Power-system LPs mix
line susceptances and generator capacities across many orders of magnitude, and
a first-order method's convergence rate depends directly on that conditioning.

The important rule: **convergence is judged in the caller's units, never the
equilibrated ones.** A tolerance of `1e-9` on a rescaled residual means nothing.
Because the preconditioner is diagonal, the original matrix products come back
from the scaled ones by an elementwise divide — `Kx = (K~ x~) / Dr` — so this
costs `O(m+n)` per evaluation and not a single extra sparse product.

## Anatomy of one iteration

```mermaid
sequenceDiagram
    participant S as Solve loop
    participant K as Kernels (device)
    participant H as Host (evaluation only)

    Note over S,K: every iteration, twice per rejected trial
    S->>K: primalStep(x, ktY, c, l, u, tau)
    K-->>S: xNext, projected onto the box
    S->>K: axpby -- extrapolate xBar = 2 xNext - x
    S->>K: spmv(K, xBar)
    S->>K: dualStep(y, kxBar, q, sigma, nEq)
    K-->>S: yNext, projected onto the dual cone
    S->>K: squaredNorm(dx), squaredNorm(dy), dot(dy, kdx)
    K-->>S: scalars -- the only device sync

    alt step size accepted
        S->>K: copy iterates, spmv(Kt, y), accumulate averages
    else rejected
        Note over S: retry with a smaller step,<br/>Kt y is unchanged so it is not recomputed
    end

    Note over S,H: every evaluationFrequency iterations only
    S->>K: spmv(K, x) -- recompute, bounding drift
    S->>H: download x, y, Kx, Kt y
    H->>H: unscale, KKT residuals, Farkas certificates
    H->>H: compare current against running average, decide restart
    H-->>S: continue, restart, or terminate
```

Two sparse products per iteration and no factorisation. That is the whole reason
this algorithm was chosen over simplex or interior-point: it is the only family
whose inner loop survives being moved onto an accelerator.

Three details in that diagram are worth naming, because each one removes work
that a naive implementation would do:

- **`K dx` is never computed.** It falls out of the extrapolation:
  `K xBar = 2 K xNext - K x`, so `K dx = (K xBar - K x) / 2`.
- **A rejected step costs one sparse product, not two.** `y` has not moved, so
  `Kt y` is still valid.
- **The running averages include the matrix products.** Averaging `Kx` and
  `Kt y` alongside `x` and `y` costs two vector operations per iteration and
  saves two sparse products at every evaluation.

Reductions are the only place the device must synchronise with the host, which
is why the adaptive step-size rule is written to need exactly three of them.

## The kernel seam

```mermaid
graph TD
    loop["Pdhg.Solve<br/><i>the algorithm, written once</i>"]
    iface["Kernels<br/><b>8 operations</b><br/>spmv, axpby, scale, copy,<br/>dot, squaredNorm,<br/>primalStep, dualStep"]

    ref["ScalaKernels<br/>fp64 reference oracle"]
    cyfra["CyfraKernels<br/>SPIR-V / Vulkan"]:::todo
    mlx["MlxKernels<br/>Metal, via Panama"]:::todo
    cuda["Nd4jKernels<br/>CUDA"]:::todo
    spatial["SpatialKernels<br/>Chisel to Verilog"]:::todo

    contract["KernelContractSuite<br/><i>one suite, every backend</i>"]

    loop --> iface
    iface --> ref
    iface --> cyfra
    iface --> mlx
    iface --> cuda
    iface --> spatial
    contract -.->|"holds each to<br/>the same behaviour"| iface

    classDef todo fill:#fafafa,stroke:#bbb,stroke-dasharray:4 3,color:#666;
```

Solid boxes exist; dashed ones do not yet.

The interface is eight operations, and keeping it that small is a deliberate,
ongoing constraint rather than a happy accident. Restart bookkeeping, KKT
evaluation and infeasibility certificates all run on the host, at evaluation
points tens of iterations apart, precisely so they never become something a
backend has to implement. Adding a ninth operation should feel expensive.

`Vec` and `Mat` are abstract type members, so a backend can hold iterates in
device memory and the solver loop never sees a host array. The CPU reference
instantiates them to `Array[Double]`.

`KernelContractSuite` is written against the trait, not against
`ScalaKernels`. A new backend inherits the entire behavioural contract by
supplying one method that constructs it. Tolerances there admit a different
reduction order — a GPU reduces in tree order and will not match the host's
index order bit-for-bit — but nothing looser than that.

### Precision is a declared capability

`KernelCapabilities.supportsFloat64` is declared per backend, and
`requiresDoublePrecisionRefinement` derives from it, because **every accelerator
in prospect is float32-only**. Cyfra's DSL has no double type on any target, and
Apple GPUs expose none through Vulkan or Metal regardless. Reduced precision is
the normal case, not a vendor quirk.

`Float32Kernels` is a CPU backend that rounds every operation to float32. It
exists so the reduced-precision question can be answered without a GPU: running
the experiment on real hardware would confound the algorithm's precision
sensitivity with SPIR-V correctness and driver behaviour, and only the first of
those is a design input.

`MixedPrecision` composes the two — reduced pass on the device, then a
double-precision refinement on the CPU warm-started from it. The result always
comes from the double-precision pass, so accuracy never depends on the device.

```mermaid
flowchart TD
    start(["LP, tolerance 1e-9"])
    check{"device<br/>supports fp64?"}
    direct["solve on device"]
    reduced["float32 pass<br/>to 1e-5"]
    conclusive{"infeasible or<br/>unbounded?"}
    refine["fp64 refinement on CPU,<br/>warm-started with point,<br/>step size and primal weight"]
    done(["LpSolution"])

    start --> check
    check -->|"yes"| direct --> done
    check -->|"no"| reduced --> conclusive
    conclusive -->|"yes -- a float32 certificate<br/>holds in fp64"| done
    conclusive -->|"no"| refine --> done
```

The warm start carries the adapted step size and primal weight, not just the
point. That is not tuning: handing over the point alone makes large instances
*slower* than a cold solve, because the adaptive rules need thousands of
iterations to settle and a solve resuming near the optimum with fresh state
spends longer unwinding the mismatch than a cold solve spends converging.

This path is opt-in, and deliberately so. It removes 75–100% of double-precision
work on structured problems but is measurably worse on dense random LPs at tight
tolerance, for reasons not yet understood.
[`NOTES.md`](modules/prima-core/NOTES.md) has the numbers.

## Where this fits in the port

```mermaid
graph TD
    prima["<b>Prima</b><br/>restarted PDHG LP solver"]
    primaGpu["Prima GPU<br/>Cyfra backend"]
    l0["<b>L0</b> foundation<br/>numeric core, columnar store, graph"]
    l1["<b>L1</b> I/O and solver plumbing<br/>CSV, netCDF, HDF5, modeling layer"]
    l2["<b>L2</b> physics<br/>LPF, Newton-Raphson, LOPF, SCLOPF, UC"]
    l3["<b>L3</b> features<br/>clustering, statistics, sector coupling"]
    l4["<b>L4</b> acceleration<br/>remaining kernels, FPGA"]
    l5["<b>L5</b> runtime<br/>ZIO Streams, Pekko cluster"]

    prima --> primaGpu
    prima --> l1
    l0 --> l1
    l1 --> l2
    l2 --> l3
    l2 --> l4
    primaGpu --> l4
    l3 --> l5

    classDef done fill:#eef6ee,stroke:#4a7,color:#243;
    class prima done;
```

Prima came first, ahead of the foundation layer, because it is the highest-risk
piece and the one nothing else could substitute for: a GPU-accelerated
first-order LP solver in Scala did not previously exist. L0 is conventional work
over well-understood libraries; if it had been built first, the project's
riskiest assumption would still be untested.

From L1 onward every module is gated on golden-file comparison against a pinned
PyPSA run. That harness does not exist yet because nothing below L1 has any
power-system semantics to compare — Prima is validated against ojAlgo instead,
which is the right oracle for an LP solver and the wrong one for a network model.

## Invariants

Rules that hold across the codebase. Breaking one is a design change, not a
refactor.

| Invariant | Why |
| --- | --- |
| `prima-core` has no third-party dependencies | It must be callable from any host, including an FPGA toolchain's driver program |
| Public API speaks `IArray`, kernels take `Array` | Immutability at the boundary at zero runtime cost; every conversion goes through `Unsafe` |
| Convergence is judged in the caller's units | A tolerance on an equilibrated residual is meaningless |
| Equality rows precede inequality rows | Makes the dual projection a contiguous clamp |
| Infeasible or unbounded is reported only from a certificate | Hitting the iteration limit is `IterationLimit`; `SolveStatus.isConclusive` separates them |
| Host work stays out of `Kernels` | The interface has to stay small enough to port to Vulkan and to hardware |
| Every backend passes `KernelContractSuite` | A backend that is fast and wrong is worse than none |

## Reading order

For the solver itself: `LpProblem` for the data model, then `Pdhg` for the
algorithm, then `Kernels` for the seam. `Scaling`, `Kkt` and `Certificates` are
supporting cast and can be read on demand.
