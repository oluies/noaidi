# Prima — numerical notes and known gaps

Prima is a first-order linear programming solver: restarted PDHG, the algorithm
behind Google's PDLP. It exists because the PyPSA port needs an LP solver whose
inner loop is sparse matrix-vector products rather than a factorisation, since
that is the shape that survives being moved onto a GPU.

This note records what the current implementation actually does, how far its
answers were checked, and what is missing. It is meant to be read before
trusting a number that came out of this module.

## What is implemented

| Piece | Status |
| --- | --- |
| LP standard form, CSR matrix, builder with range-row expansion | complete |
| `Kernels` interface with a pure-Scala fp64 reference backend | complete |
| Ruiz + Pock-Chambolle preconditioning with exact unscaling | complete |
| Restarted PDHG: adaptive step size, primal weight, KKT restarts | complete |
| Termination on relative primal/dual/gap criteria | complete |
| Farkas certificates for infeasibility and unboundedness | complete |
| ojAlgo backend, doubling as the correctness oracle | complete |
| ZIO effect boundary with cooperative interruption and streaming | complete |
| Cyfra GPU backend | **not started** |
| MILP / branch-and-bound | **not started** |
| Presolve | **not started** |

## How the answers were checked

Two independent checks, because agreement between a first-order method and a
simplex method is worth much more than either one's self-report.

**Against hand-derived optima.** Every instance in `LpFixtures` has an optimum
worked out by inspection rather than recorded from a solver run — merit-order
reasoning for the dispatch case, vertex enumeration for the small ones. The
suite asserts objective *and* solution vector against those.

**Against ojAlgo.** `prima-validation` runs both solvers over the fixture ladder
and twelve random sparse instances. Objective values are compared; solution
vectors deliberately are not, because several fixtures are degenerate and two
solvers landing on different vertices of the same optimal face is correct.

Measured on the ladder (Apple aarch64, JDK 26, `epsAbs = epsRel = 1e-9`):

```
instance             size                   status                   prima obj       ojalgo obj   rel gap    iters  restarts  prima ms  ojalgo ms
tiny-lower-bound     1v/1c/1nz              Optimal                   3.000000         3.000000  4.84e-12       64         0         0        78
product-mix          2v/3c/4nz              Optimal                 -36.000000       -36.000000  3.92e-12      128         1         1         0
equality-split       2v/1c/2nz              Optimal                  24.000000        24.000000  1.95e-13       64         0         0         0
degenerate           2v/2c/4nz              Optimal                   2.000000         2.000000  3.55e-14       64         0         0         0
free-sign            2v/1c/2nz              Optimal                  -2.000000        -2.000000  0.00e+00        3         0         0         0
range-row            2v/2c/4nz              Optimal                   2.000000         2.000000  1.03e-11       64         0         0         0
economic-dispatch    8v/4c/10nz             Optimal                3700.000000      3700.000000  3.46e-12      256         3         0         0
infeasible           1v/2c/2nz              PrimalInfeasible                 -                -         -     1280         6         0         0
unbounded            1v/1c/1nz              DualInfeasible                   -                -         -       64         0         0         0
random-60x30         60v/30c/439nz          Optimal                -773.369199      -773.369199  1.05e-11     1344        10         2         7
random-200x120       200v/120c/2464nz       Optimal               -1796.551033     -1796.551031  8.05e-10     5888        15        25        28
random-600x400       600v/400c/9529nz       Optimal               -5285.522442     -5285.522445  5.55e-10    59840        26       749       813
```

Worst relative objective gap against the oracle: **8.0e-10**.

Regenerate with `sbt "primaValidation/Test/runMain org.noaidi.prima.validation.Report"`.

## Numerical deltas worth knowing

**Objective agreement is about 1e-10 relative, not machine precision.** At
`eps = 1e-9` the solver stops when the KKT residual is small, and the residual
bounds the objective error only up to the problem's conditioning. Tightening
`epsAbs`/`epsRel` tightens this proportionally, at a cost in iterations.

**Iteration count grows steeply with size.** 1.3k iterations at 60 variables,
60k at 600. That is expected — first-order methods trade a low per-iteration
cost against a high iteration count, which is the whole reason the per-iteration
work needs to be on a GPU. Against ojAlgo's simplex the crossover on these dense
random instances is around 600 variables, and dense random LPs are close to the
worst case for a first-order method; power-system LPs are far sparser and
better structured.

**Certificate-based statuses are heuristic in timing, not in soundness.** A
reported `PrimalInfeasible` or `DualInfeasible` always rests on a direction that
passed the Farkas test to within `infeasibilityTolerance`, so a false positive
would require the tolerance to be set absurdly loose. The converse is not
guaranteed: an infeasible problem whose certificate direction emerges slowly
will hit the iteration limit instead. `SolveStatus.isConclusive` distinguishes
the two cases and callers should check it.

**Reductions are order-dependent.** The reference backend sums in index order.
A GPU backend will reduce in tree order and produce slightly different values
from the same inputs. The kernel contract suite therefore compares within a
tolerance rather than exactly, and any backend that only matches bit-for-bit is
matching by accident.

**Degenerate problems: compare objectives, not solution vectors.** Several
fixtures have a face of optimal solutions. Prima's averaged iterate tends to
land in the interior of that face where simplex lands on a vertex. Both are
optimal.

## Design decisions that are load-bearing

**Convergence is judged on the original problem, never the scaled one.** A
tolerance means nothing in equilibrated units. Because the preconditioner is
diagonal, the original matrix products come from the scaled ones by an
elementwise divide, so this costs `O(m+n)` per evaluation and no extra sparse
products.

**The `Kernels` interface covers only the inner loop.** Restart bookkeeping,
convergence testing and certificates all run on the host at evaluation points
tens of iterations apart. Keeping them out of the interface is what makes it
small enough to be worth porting to Vulkan or to hardware — eight operations,
not forty.

**Running sums include the matrix products.** Averaging `Kx` and `K'y` alongside
`x` and `y` costs two extra vector operations per iteration and saves two sparse
products per evaluation, which is the better trade at any realistic evaluation
frequency.

## Known gaps

**No GPU backend yet.** `Kernels` is the seam, and `KernelContractSuite` is
written against the trait so a new backend inherits the whole contract by
supplying one method. Cyfra 0.1.0-RC1 is on Maven Central
(`io.computenode::cyfra-{core,dsl,runtime,foton}`, built for Scala 3.6.4, which
3.7.4 reads). Two things to settle before adopting it: it is LGPL-2.1 where the
rest of this stack is Apache-2.0, and the CSR SpMV kernel will have to be
hand-written in its DSL, since its demonstrated use is dense arrays.

**No fp32 path or refinement pass.** `KernelCapabilities.supportsFloat64` is
declared and `requiresDoublePrecisionRefinement` is derived from it, but nothing
consumes them yet. Apple GPUs offer no fp64 through either Vulkan/MoltenVK or
Metal, so an Apple backend will need fp32 iterations finished by a CPU
double-precision pass. The hook exists; the pass does not.

**No presolve.** Empty rows, fixed variables, singleton rows and duplicate
columns are all passed straight to the iteration. PDLP gets a substantial part
of its performance from presolve, so this is the largest single piece of
missing performance, and it needs a postsolve to map duals back.

**No MILP.** Unit commitment needs branch-and-bound around the LP. Nothing here
is specific to continuous problems, but the warm-start path a tree needs does
not exist yet.

**Iteration limit is not adaptive.** `maxIterations` defaults to 100k, which the
600-variable random instance nearly reaches. Anything larger needs the limit
raised explicitly, and there is no heuristic that scales it with problem size.

**ojAlgo's duals are not used.** Its multipliers are exposed only on some solver
paths and follow its own sign convention. Prima's duals are validated by
complementary slackness against its own primal instead, which is a weaker check
than a cross-solver comparison would be. A HiGHS backend would give a proper
dual oracle.

**No golden files from PyPSA yet.** That gate belongs to the layers above this
one — this module has no power-system concepts in it. The `economic-dispatch`
fixture is shaped like a PyPSA LOPF and its congestion prices are asserted, but
it was constructed here rather than exported from a PyPSA run.
