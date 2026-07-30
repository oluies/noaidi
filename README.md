# noaidi — PyPSA on the JVM

A port of [PyPSA](https://github.com/PyPSA/PyPSA) to Scala 3, built
libraries-first: each layer of the dependency stack becomes a standalone,
tested Scala module before any power-system domain logic is written on top of
it.

Effects and concurrency go through ZIO; numeric kernels are isolated behind a
small interface so they can later be staged to GPU (Cyfra → SPIR-V/Vulkan) or to
hardware (Spatial/Chisel) without touching the algorithms that call them.

## Current state

**Prima**, the LP solver, is implemented and validated. Nothing above it exists
yet.

Prima comes first because it is the highest-risk and most reusable piece: a
GPU-accelerated first-order LP solver in Scala did not previously exist, and
every optimisation feature of PyPSA — economic dispatch, LOPF, SCLOPF, unit
commitment — depends on it.

| Module | What it is |
| --- | --- |
| `prima-core` | LP model, sparse matrix, kernel interface, restarted PDHG solver. No third-party dependencies. |
| `prima-zio` | Effect boundary: solves on the blocking pool, cooperative interruption, ZStream fan-out over scenario sweeps. |
| `prima-ojalgo` | ojAlgo behind the common solver interface — CPU fallback and correctness oracle. |
| `prima-validation` | Prima against ojAlgo over a ladder of LP instances. |
| `prima-mps` | MPS reader, for reaching the standard LP corpora. |
| `prima-cyfra` | GPU spike. Not in the root build — see below. |
| `prima-netlib` | Netlib LP corpus. Not in the root build; fetches on first run. |
| `network-model` | L0: the PyPSA network data model and topology — schema-driven, round-tripping PyPSA's CSV directory format. |

223 tests pass in the aggregated build, plus 48 in the opt-in Netlib module.
Against Netlib — the first oracle here independent of ojAlgo — 16 of 19 feasible
instances solve to optimality, agreeing with the published optima to 2.2e-08 or
better, and **none of the 29 infeasible instances is reported optimal**.
Presolve moved that from 14 to 16 and settles four infeasible instances without
iterating at all.

Worst relative objective disagreement with ojAlgo across the
validation ladder is 4.9e-10 on macOS/aarch64/JDK 26 and 5.9e-10 on
Linux/x86_64/JDK 25 — a difference between configurations rather than between
solvers, unchanged across two major versions of ojAlgo, and asserted not to
exceed 1e-8.

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — how the pieces fit together and why.
- [`modules/prima-core/NOTES.md`](modules/prima-core/NOTES.md) — the numbers,
  the numerical caveats, and the known gaps.

### What the GPU investigation found

Three findings, in the order they changed the plan.

**Cyfra is float32-only, on every target.** Version 0.1.0-RC1 exposes `Float32`,
`Int32` and `UInt32` and nothing else — verified against the published jar and
against `main`, which has no newer published release despite tags through
`rc7`. This is not an Apple limitation being inherited: a Cyfra backend runs
single precision on NVIDIA hardware that could do better. Apple GPUs offer no
fp64 through Vulkan or Metal either, since Metal has no `double` type at all and
MoltenVK therefore cannot expose `shaderFloat64`.

So reduced precision is the normal case for every accelerator in prospect, which
makes the mixed-precision path a prerequisite for a GPU backend rather than a
follow-up to one. `Float32Kernels` runs the same arithmetic on the CPU with
every operation rounded to float32, and `MixedPrecision` composes it with a
double-precision refinement. Building that first isolates the algorithm's
precision sensitivity from SPIR-V correctness and driver behaviour — a GPU
experiment would confound all three.

**The measurements are mixed, and that is the honest headline.** Double-precision
work removed against a cold solve: 75% on the PyPSA-shaped dispatch fixture at a
1e-9 tolerance and 100% at 1e-6, 35–70% on mid-size random instances, but −14%
at 1e-9 and −621% at 1e-6 on the largest dense random LP, where the float32
point is measurably a *worse* starting point than zero. That is unexplained and
is why `MixedPrecision` is opt-in. The underlying pattern: float32 reaches 1e-5
on the 600-variable instance in 1,792 iterations where a cold solve needs 35,392
for 1e-9 — the first digits are nearly free and the last cost everything, so a
float32 device removes only the cheap prefix unless the caller's tolerance is
loose enough for it to deliver the whole answer.

**CSR SpMV does run on the GPU.** This was the gating question for a Cyfra
backend, and it was genuinely open: SpMV is the one kernel PDHG cannot do
without, and it is the one Cyfra was not designed around — its demonstrated use
is dense arrays and ray tracing, where every invocation does identical work. A
CSR row needs an indexed gather through a column-index buffer and an inner loop
whose trip count is known only at runtime and differs per row.

Both are expressible, and verified end to end on Apple M5 Pro through MoltenVK
against the CPU reference:

```scala
GSeq.gen(start, p => p + 1)      // start = rowPtr(row)
  .takeWhile(p => p < end)       // end   = rowPtr(row+1) — the runtime bound
  .limit(maxRowNnz)              // static cap SPIR-V needs
  .map(p => GIO.read(values, p) * GIO.read(x, GIO.read(colIndices, p)))
  .fold[Float32](0.0f, _ + _)
```

The nested `GIO.read` is the indexed gather the question hung on.

One measurement came out of it. Reusing a single built program across 25
dispatches costs 17.3 ms on the first and a median of 1.4 ms thereafter, which
is consistent with SPIR-V compilation being hoisted out of `execute` and paid
once — the property that makes per-matrix specialisation a per-solve cost rather
than a per-iteration one.

What this does *not* establish is value. There is no `CyfraKernels` implementing
the other seven operations, and nothing has been timed against the CPU
reference. Given the mixed-precision result above, that comparison is worth
having before building the full backend.

## Building

Requires JDK 17+ (developed on 26) and sbt 2.0.4, which the build pins.

```bash
sbt testFull                                                    # all modules
sbt "primaValidation/Test/runMain org.noaidi.prima.validation.Report"  # cross-solver comparison
```

Use `testFull`, not `test`: under sbt 2 the `test` task is incremental and will
report success having run nothing.

The report prints the cross-solver ladder and then the mixed-precision
comparison at two tolerance regimes, which give opposite answers — see the GPU
findings above.

### Running the GPU spike

`prima-cyfra` is deliberately **not** aggregated into the root build, so
`sbt testFull` never touches it. It needs a Vulkan loader and an ICD at runtime,
which no CI runner is guaranteed to have, and Cyfra is LGPL-2.1 where the rest
of this build is Apache-2.0. Keeping it separate and opt-in contains both.

On macOS:

```bash
brew install molten-vk vulkan-loader
sbt primaCyfra/testFull
```

The module as configured targets **macOS on Apple Silicon**, which is where
the spike was run. Everything host-specific is gated on the host in
`build.sbt`, so another platform gets a build that compiles and simply has no
Vulkan wiring, rather than one that fails at runtime in a way that looks like a
missing driver. Running it elsewhere means adding the right LWJGL `natives-*`
classifier for that platform — the macOS one is not a default — and leaving the
ICD to normal loader discovery.

## Using the solver

```scala
import org.noaidi.prima.*

val b = LpProblem.builder(2)
b.objectiveCoefficient(0, 2.0)
b.objectiveCoefficient(1, 3.0)
b.bounds(0, 0.0, 6.0)
b.bounds(1, 0.0, 10.0)
b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 10.0)

val (problem, rows) = b.build()
val solution        = Pdhg.solve(problem)

solution.status          // SolveStatus.Optimal
solution.objectiveValue  // 24.0
solution.primal          // IArray(6.0, 4.0)
rows.originalDuals(solution.dual)  // duals in the caller's own row numbering
```

Rows are written as two-sided bounds (`lo <= a'x <= hi`), which is how
power-system constraints are naturally expressed; the builder converts to the
equalities-then-inequalities standard form the solver needs and hands back the
mapping required to read duals in the original numbering.

Under ZIO:

```scala
import org.noaidi.prima.zio.PrimaZio

PrimaZio.solve(problem)                     // Task[LpSolution], interruptible
PrimaZio.solveAll(problems, parallelism = 8) // ZStream fan-out over a sweep
```

## Roadmap

The migration order from the brief. Prima and the GPU groundwork are done;
everything from the foundation layer up is ahead:

1. ~~**Prima**: restarted PDHG LP solver, fp64 reference, validated against ojAlgo.~~ ✅
2. ~~**Reduced precision**: `Float32Kernels`, warm start, `MixedPrecision` with fp64 refinement.~~ ✅
3. ~~**Cyfra feasibility**: CSR SpMV on the GPU, matching the CPU reference.~~ ✅
4. **Prima GPU**: the remaining seven kernel operations behind `Kernels`, then a timing against the CPU reference, then validation against cuPDLP-C. Worth resolving the dense-instance warm-start regression first — it is a concrete, reproducible anomaly with two named suspects in NOTES.md, and it decides how much a GPU can actually buy.
5. ~~**L0 foundation**: typed columnar store, CSV round-trip, sub-network topology.~~ ✅ (dense linear algebra deferred to L2, where Newton-Raphson needs it)
6. **L1 I/O and solver plumbing**: CSV/netCDF/HDF5 round-tripping PyPSA byte-for-byte; solver-agnostic modeling layer over ojAlgo, OR-Tools and Prima.
7. **L2 physics**: linearised power flow, Newton-Raphson AC/DC, economic dispatch, LOPF, SCLOPF, unit commitment.
8. **L3 features**: clustering, statistics, sector coupling, plotting.
9. **L4 acceleration**: remaining kernels onto Cyfra/MLX/CUDA, plus Spatial/Chisel for FPGA.
10. **L5 runtime**: ZIO Streams over snapshots and contingencies, Pekko cluster distribution.

From L1 onwards, every module is gated on golden-file comparison against a
pinned PyPSA version run on the same example networks. That harness does not
exist yet, because nothing below L1 has power-system semantics to compare.
