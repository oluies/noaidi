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

82 tests pass. Worst relative objective disagreement with ojAlgo across the
validation ladder is 8.0e-10. See
[`modules/prima-core/NOTES.md`](modules/prima-core/NOTES.md) for the numbers,
the design decisions behind them, and the known gaps.

## Building

Requires JDK 17+ (developed on 26) and sbt 2.0.4, which the build pins.

```bash
sbt testFull                                                    # all modules
sbt "primaValidation/Test/runMain org.noaidi.prima.validation.Report"  # cross-solver comparison
```

Use `testFull`, not `test`: under sbt 2 the `test` task is incremental and will
report success having run nothing.

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

The migration order from the brief, with Prima done and everything else ahead:

1. ~~**Prima**: restarted PDHG LP solver, fp64 reference, validated against ojAlgo.~~ ✅
2. **Prima GPU**: Cyfra backend behind the existing `Kernels` seam; validate against cuPDLP-C.
3. **L0 foundation**: numeric core (Breeze/ND4J), typed columnar store for component tables and snapshot series, graph module.
4. **L1 I/O and solver plumbing**: CSV/netCDF/HDF5 round-tripping PyPSA byte-for-byte; solver-agnostic modeling layer over ojAlgo, OR-Tools and Prima.
5. **L2 physics**: linearised power flow, Newton-Raphson AC/DC, economic dispatch, LOPF, SCLOPF, unit commitment.
6. **L3 features**: clustering, statistics, sector coupling, plotting.
7. **L4 acceleration**: remaining kernels onto Cyfra/MLX/CUDA, plus Spatial/Chisel for FPGA.
8. **L5 runtime**: ZIO Streams over snapshots and contingencies, Pekko cluster distribution.

From L1 onwards, every module is gated on golden-file comparison against a
pinned PyPSA version run on the same example networks. That harness does not
exist yet, because nothing below L1 has power-system semantics to compare.
