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
tiny-lower-bound     1v/1c/1nz              Optimal                   3.000000         3.000000  4.84e-12       64         0         1       102
product-mix          2v/3c/4nz              Optimal                 -36.000000       -36.000000  1.99e-10      128         1         2         0
equality-split       2v/1c/2nz              Optimal                  24.000000        24.000000  1.52e-13       64         0         0         0
degenerate           2v/2c/4nz              Optimal                   2.000000         2.000000  3.55e-14       64         0         0         0
free-sign            2v/1c/2nz              Optimal                  -2.000000        -2.000000  0.00e+00        2         0         0         0
range-row            2v/2c/4nz              Optimal                   2.000000         2.000000  1.03e-11       64         0         0         0
economic-dispatch    8v/4c/10nz             Optimal                3700.000000      3700.000000  1.51e-11      256         3         0         1
infeasible           1v/2c/2nz              PrimalInfeasible                 -                -         -     1088         5         0         0
unbounded            1v/1c/1nz              DualInfeasible                   -                -         -       64         0         0         0
random-60x30         60v/30c/439nz          Optimal                -773.369199      -773.369199  3.68e-11     1280         9         2         7
random-200x120       200v/120c/2464nz       Optimal               -1796.551031     -1796.551031  1.46e-10     6464        18        29        31
random-600x400       600v/400c/9529nz       Optimal               -5285.522448     -5285.522445  4.86e-10    35392        17       474       802
```

Worst relative objective gap against the oracle: **4.9e-10**.

Regenerate with `sbt "primaValidation/Test/runMain org.noaidi.prima.validation.Report"`.

## Numerical deltas worth knowing

**Objective agreement is about 1e-10 relative, not machine precision.** At
`eps = 1e-9` the solver stops when the KKT residual is small, and the residual
bounds the objective error only up to the problem's conditioning. Tightening
`epsAbs`/`epsRel` tightens this proportionally, at a cost in iterations.

**Iteration count grows steeply with size.** 1.3k iterations at 60 variables,
35k at 600. That is expected — first-order methods trade a low per-iteration
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

## Reduced precision: what the measurements say

This is the most consequential result so far, and it complicates the GPU plan.

**Cyfra has no double type.** Version 0.1.0-RC1 exposes `Float32`, `Int32` and
`UInt32` and nothing else. That is not an Apple limitation being inherited — the
DSL has no fp64 on any target, so a Cyfra backend runs single precision on
NVIDIA hardware that could do better. Combined with Apple GPUs offering no fp64
through Vulkan or Metal either, **every accelerator backend in prospect is
float32-only**, which makes reduced precision the default case rather than a
special one.

To measure the consequences without first writing a Vulkan backend,
`Float32Kernels` runs the same arithmetic on the CPU with every operation
rounded to float32. That isolates the algorithm's precision sensitivity from
SPIR-V correctness and driver behaviour, which a GPU experiment would confound.
Reductions accumulate in float32 too — pessimistic, since a real GPU reduces in
tree order and is typically more accurate than sequential float32.

`MixedPrecision` then runs the reduced pass to a tolerance float32 can support
and refines on the CPU in double precision from that warm start. The reported
result always comes from the double-precision pass, so accuracy does not depend
on which device did the bulk of the work.

Iterations of double-precision work removed, against a cold fp64 solve:

| instance | at 1e-9 | at 1e-6 |
| --- | --- | --- |
| economic-dispatch | **75%** | **100%** |
| equality-split | 13% | 100% |
| range-row | 22% | 100% |
| random-60x30 | 35% | 70% |
| random-200x120 | 55% | 65% |
| random-600x400 | **−14%** | **−621%** |

Three things follow.

**Carrying the adaptive state is not optional.** Handing over only the point
made the worst case −71% at 1e-9; also carrying the settled step size and primal
weight brought it to −14%. The adaptive rules take thousands of iterations to
converge, and a solve resuming near the optimum with freshly-initialised state
spends longer unwinding that mismatch than a cold solve spends converging. This
is why `WarmStart` carries `stepSize` and `primalWeight`.

**The expensive part is the tail, and float32 cannot reach it.** On
random-600x400 the float32 pass reaches 1e-5 in 1,792 iterations while the cold
solve needs 35,392 for 1e-9. The first few digits are nearly free and the last
few cost everything. A float32 device therefore removes only the cheap prefix,
unless the caller's tolerance is loose enough that float32 can deliver the whole
answer — which at 1e-6 it does for most of the ladder.

**On dense random LPs at tight tolerance the float32 point is worse than
starting from zero.** At 1e-6, random-600x400 costs 2,752 iterations cold and
19,840 refining. Not merely unhelpful — actively harmful, and not yet explained.
Dense random LPs are close to the worst case for a first-order method and are
not representative of power-system LPs, but this is unresolved and is the reason
`MixedPrecision` is opt-in rather than the default path.

The table above is a hand-filtered view: the validation report prints the two
tolerance regimes as separate tables covering all twelve ladder instances, and
the six rows here are the ones worth carrying. Run it to regenerate the
underlying numbers, then merge.

## Known gaps

**Why the float32 warm start hurts on dense instances is unexplained.** The
prime suspects are the restart schedule — `errorAtRestart` is seeded from an
already-small error, so the sufficient-decay test needs a fivefold reduction
from a low base and rarely fires — and a step size the adaptive rule settled on
while measuring float32 noise. Neither has been confirmed. Until it is, do not
enable mixed precision by default.

**No GPU backend yet, but the gating question is answered.** `Kernels` is the
seam, and `KernelContractSuite` is written against the trait so a new backend
inherits the whole contract by supplying one method. What was unknown was
whether Cyfra's DSL could express the one kernel PDHG cannot do without.

`modules/prima-cyfra` is a spike that settles it: **CSR SpMV runs on the GPU and
matches the CPU reference.** Verified on Apple M5 Pro through MoltenVK, on a
4x4 case including an empty row and on a 500x300 random matrix with ~3000
non-zeros and widely varying row lengths, agreeing to float32.

The construction that works:

```scala
val sum = GSeq.gen(start, p => p + 1)      // start = rowPtr(row)
  .takeWhile(p => p < end)                 // end   = rowPtr(row+1), runtime bound
  .limit(maxRowNnz)                        // static cap SPIR-V needs
  .map(p => GIO.read(values, p) * GIO.read(x, GIO.read(colIndices, p)))
  .fold[Float32](0.0f, _ + _)
```

`GIO.read` supports the indexed gather — a column index read from one buffer
used to address another — which is the operation a dense-array DSL has no
reason to provide, and the one this whole question hung on.

Three constraints the spike surfaced, none fatal:

- **The kernel is specialised per matrix shape.** The DSL body cannot read a
  runtime dispatch parameter, so `rows`, `cols` and `maxRowNnz` are captured
  when the program is built. That is only tolerable if a built program can be
  dispatched repeatedly, which the spike exercises rather than assumes: one
  `GProgram` reused across 25 dispatches costs 17.3 ms on the first and a
  median of 1.4 ms thereafter. A twelvefold drop is consistent with SPIR-V
  compilation being hoisted out of `execute` and paid once, which is what makes
  this a per-solve cost rather than a per-iteration one. The timing is
  informational — wall clock on a shared machine is too noisy to assert on —
  but correctness across all 25 dispatches is checked.
- **`limit` needs a static cap**, satisfied by the maximum row non-zero count,
  which is a plain Scala `Int` at construction time. A pathological matrix with
  one very long row makes every invocation's loop bound that long, though
  `takeWhile` still exits early.
- **The environment is not self-contained.** LWJGL's natives need explicit
  classifier dependencies, and the Vulkan loader and MoltenVK ICD come from
  Homebrew via `VK_ICD_FILENAMES`. `prima-cyfra` is therefore not aggregated
  into the root build — `sbt testFull` does not run it — since no CI runner is
  guaranteed a working Vulkan stack.

Still open: a full `CyfraKernels` implementing all eight operations, and any
performance measurement at all. The spike establishes feasibility, not value.
And the licensing question stands: Cyfra is LGPL-2.1 where the rest of this
build is Apache-2.0, which is why the backend lives in its own module.

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

**ojAlgo reports no duals at all.** Its multipliers are exposed only on some
solver paths and follow its own `<=` sign convention rather than the `>=`
standard form used here. Rather than pass along numbers that cannot be vouched
for, `OjAlgoSolver` returns `NaN` for `dual`, `reducedCosts`,
`dualObjectiveValue` and the dual half of its `KktError` — a caller doing
arithmetic on them gets an unmistakable answer instead of a quietly wrong one.
The consequence is that Prima's duals have no cross-solver oracle: they are
validated by complementary slackness against Prima's own primal, which is a
weaker check. A HiGHS backend would fix this.

**Initial step size uses a bound, not an estimate.** `eta` starts at
`1 / sqrt(||K||_1 * ||K||_inf)`, which bounds `||K||_2` from above by Holder's
inequality. Neither induced norm alone would do: for an `m x 1` column of ones
`||K||_inf` is 1 while `||K||_2` is `sqrt(m)`, so using it would start every
solve outside the PDHG stability condition by a factor of `sqrt(m)`. The bound
can be loose by up to `sqrt(min(m,n))`, which costs a conservative first step
that the adaptive rule grows away — the safe direction to err in, since an
underestimate would start the method outside the region where it converges.

**No golden files from PyPSA yet.** That gate belongs to the layers above this
one — this module has no power-system concepts in it. The `economic-dispatch`
fixture is shaped like a PyPSA LOPF and its congestion prices are asserted, but
it was constructed here rather than exported from a PyPSA run.
