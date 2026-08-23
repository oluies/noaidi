# noaidi — PyPSA on the JVM

A port of [PyPSA](https://github.com/PyPSA/PyPSA) to Scala 3, built
libraries-first: each layer of the dependency stack becomes a standalone,
tested Scala module before any power-system domain logic is written on top of
it.

Effects and concurrency go through ZIO; numeric kernels are isolated behind a
small interface so they can later be staged to GPU (Cyfra → SPIR-V/Vulkan) or to
hardware (Spatial/Chisel) without touching the algorithms that call them.

## Current state

**Prima**, the LP solver, is implemented and validated. On top of it sit the L0
network data model and the first two L2 physics modules — linear optimal power
flow and linear power flow — each gated on golden-file comparison against a
pinned PyPSA.

Prima came first because it is the highest-risk and most reusable piece: a
GPU-accelerated first-order LP solver in Scala did not previously exist, and
every optimisation feature of PyPSA — economic dispatch, LOPF, SCLOPF, unit
commitment — depends on it. Linear power flow does not, and `network-pf`
accordingly does not depend on Prima: it is a linear solve rather than an
optimisation.

| Module | What it is |
| --- | --- |
| `prima-core` | LP model, sparse matrix, kernel interface, restarted PDHG solver, branch-and-bound for MILP. No third-party dependencies. |
| `prima-zio` | Effect boundary: solves on the blocking pool, cooperative interruption, ZStream fan-out over scenario sweeps. |
| `prima-ojalgo` | ojAlgo behind the common solver interface — CPU fallback and correctness oracle. |
| `prima-validation` | Prima against ojAlgo over a ladder of LP instances. |
| `prima-mps` | MPS reader, for reaching the standard LP corpora. |
| `prima-cyfra` | GPU spike. Not in the root build — see below. |
| `prima-netlib` | Netlib LP corpus. Not in the root build; fetches on first run. |
| `network-model` | L0: the PyPSA network data model and topology — schema-driven, round-tripping PyPSA's CSV directory format, single-period and multi-period alike. Ships PyPSA's standard line and transformer type library, which no network export contains. |
| `network-lopf` | L2: linear optimal power flow with storage, stores, capacity expansion, delayed links and multi-investment periods, N-1 security-constrained LOPF, and unit commitment via Prima's branch-and-bound. |
| `network-io` | L1: PyPSA's netCDF export, read into the same model the CSV reader produces. |
| `network-pf` | L2: power flow — linear (one SPD solve per sub-network) and non-linear Newton-Raphson AC. No LP solver involved. |

666 tests pass in the aggregated build, plus 48 in the opt-in Netlib module.
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
- [`HPC.md`](HPC.md) — a plan for running this on NAISS Arrhenius, and what it would change.
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

Requires JDK 17+ (developed on 26) and sbt 2.0.6, which the build pins.

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
missing driver.

On **Linux** the LWJGL natives arrive transitively with Cyfra, so nothing needs
adding to the build and the ICD is left to normal loader discovery. The
classifier is added explicitly only on macOS on Apple Silicon, where it is not a
default — which is what `build.sbt` gates on the host. On any **other** platform
you would need the matching `natives-*` classifier as well as the ICD.

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
6. **L1 I/O and solver plumbing**: CSV round-trips PyPSA byte-for-byte and netCDF reads into the same model; PyPSA's own `.h5` is pandas' PyTables layout with pickled column metadata and is not read — see NOTES. A solver-agnostic modeling layer over ojAlgo, OR-Tools and Prima remains.
7. **L2 physics**: LPF matches PyPSA's voltage angles to 1e-9 and its line flows and slack dispatch to 1e-6 on every reference network, `scigrid-de` included — 585 buses, 852 lines and 96 transformers, where every impedance comes from a standard type name rather than from a file. LOPF matches PyPSA's objective, dispatch and line flows on the dispatch fixture, and its objective under a binding CO2 cap; nodal prices agree wherever the dual is unique and are a different point of the same optimal dual face elsewhere — settled, see NOTES. Newton-Raphson AC power flow matches PyPSA's voltage magnitudes and angles to 1e-9 on both fixtures that have one. Its DC counterpart is not implemented because the pinned PyPSA does not implement it either — see NOTES. A phase-shifting transformer is modelled in both the linear flow — where it moves 991 MW on the reference fixture, and was silently dropped before because no code read the attribute — and the AC path, along with off-nominal tap ratios and the transformer T model. Those three were refused until the assumption behind the refusal was tested: an asymmetric `Y` needed no solver change at all, and the admittance matches PyPSA's angles to 3e-16. Unit commitment reproduces PyPSA's schedule, dispatch and objective on a purpose-built fixture, solved by Prima's own branch-and-bound, and SCLOPF reproduces PyPSA's N-1 secure dispatch via outage distribution factors. Storage carries state across snapshots — the one constraint here that is not separable by snapshot — which makes `scigrid-de` the first realistic network the port optimises: 60,552 variables, solved to 1e-4 in 13 s and to 1e-6 in 145 s. Capacity expansion makes `p_nom` a decision, reproducing PyPSA's objective and chosen capacities on `ac-dc-meshed` and `storage-hvdc` — the last two stock examples the port could not touch. `Store` is modelled too: one signed power variable, no efficiencies and no power rating at all. Ramp limits and the `e_sum_max`/`e_sum_min` energy budgets are built as well — both were silently dropped before, for the same reason the phase shift was, and dropping the budgets under-priced the reference answer by 23,280. A committable generator is now refused by LOPF rather than solved with the flag ignored, which cost 18,500 against PyPSA's 17,000 — dearer, not cheaper, because dropping the status turns `p_min_pu` into a floor the unit can never leave. A link may deliver late: `delay` shifts its output into a later snapshot, measured in elapsed time against the `generators` weighting rather than in snapshots, and `cyclic_delay` wraps what is still in flight at the end of the horizon. It was refused before, having been silently delivered instantly for 500 against PyPSA's 9,000; implementing it took one new file, because shifting the receiving term changes which column a balance row references and nothing else. Multi-investment periods are modelled too, which was the other silent mis-solve: nothing read `investment_periods.csv` at all, so a network whose cheap generator is built in the second period cost 2,000 against PyPSA's 17,000. Snapshots are now `(period, timestep)` pairs through the model layer, the CSV round-trip and the netCDF reader; an asset is active only between its `build_year` and the end of its `lifetime`; and every cost carries its period's discount factor while a global constraint sums against its `years` — two columns of one small file that do different jobs. Capacity expansion *across* periods is still refused, along with per-period storage cycling, growth limits and commitment on a multi-period network: each is a different formulation rather than the same one with an extra factor.
8. **L3 features**: clustering, statistics, sector coupling, plotting.
9. **L4 acceleration**: remaining kernels onto Cyfra/MLX/CUDA, plus Spatial/Chisel for FPGA.
10. **L5 runtime**: ZIO Streams over snapshots and contingencies, Pekko cluster distribution.

From L1 onwards, every module is gated on golden-file comparison against a
pinned PyPSA version run on the same example networks. That harness does not
exist yet, because nothing below L1 has power-system semantics to compare.
