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
| MILP: branch-and-bound over the LP relaxation | complete |
| Presolve: exact reductions with dual postsolve | complete |

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

Measured on the ladder (Apple aarch64, JDK 26, ojAlgo 57.1.0, `epsAbs = epsRel = 1e-9`):

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

Worst relative objective gap against the oracle: **4.9e-10** here, **5.9e-10**
on CI's Linux x86_64 runners.

That difference is configuration-dependent and ojAlgo-version-independent,
which is worth knowing before reading anything into a change in it. Both figures
were reproduced under ojAlgo 55.2.0 and 57.1.0 — two major versions apart — and
neither figure moved; running either version on the other configuration does
move it.

The two configurations differ in more than one way — macOS/aarch64/JDK 26
against Linux/x86_64/JDK 25 — so the JDK was a second uncontrolled variable. CI
now reports the figure on two JDKs on the same OS, and it comes out
bit-identical at 5.874e-10 on both JDK 21 and JDK 25. That rules out JVM
sensitivity between those two versions — but the macOS figure was taken on JDK
26, which is not in the matrix, so it does not rule the JDK out entirely.
Architecture remains the leading hypothesis, via summation order in ojAlgo's
simplex and its hardware-profile-dependent blocking; it is not yet established.

The practical consequence stands either way: a shift in this number across a
dependency bump is not evidence about the bump unless both measurements came
from the same machine. The bound is enforced by
`OracleAgreementSuite`, which fails if the worst gap exceeds 1e-8.

Regenerate with `sbt "primaValidation/Test/runMain org.noaidi.prima.validation.Report"`.

**Against Netlib.** `prima-mps` reads MPS; `prima-netlib` runs the standard LP
corpus. This is the first oracle in the project independent of ojAlgo — every
earlier cross-check ran against a solver that is part of this build, so a shared
misunderstanding of a model would have gone unnoticed. Reference optima come
from the corpus, computed by Gurobi at 1e-8.

At `eps = 1e-8`, 50k iterations, 30s per instance:

| | without presolve | with presolve |
| --- | --- | --- |
| Feasible instances solved to optimality | 14 / 19 | **16 / 19** |
| Worst disagreement with the published optimum | 2.2e-08 | **2.2e-08** |
| Infeasible instances reported optimal | 0 / 29 | **0 / 29** |
| Infeasible instances proven infeasible | 11 / 29 | **12 / 29** (4 by presolve alone) |

Presolve was built on the strength of the left-hand column and the right-hand
one is what it bought. `scagr7` and `bandm` moved from the iteration limit to
converged, both after heavy singleton-row elimination — 34 and 47 rows. Four
infeasible instances are now settled without iterating at all, by an empty row
that cannot hold or bounds driven into contradiction.

Three feasible instances still do not converge (`share2b`, `share1b`, `lotfi`),
along with 16 infeasible ones. They hit the iteration limit rather than failing.
Getting them needs the approximate reductions — forcing and dominated rows,
coefficient tightening — which is a different kind of decision from the exact
ones, since those can change the answer.

The assertions are deliberately asymmetric, since the two failure directions are
not equally bad:

- Whenever Prima reports an optimum it must match the published value — asserted
  for every instance, always.
- No known-feasible instance may be reported infeasible, and no known-infeasible
  instance may be reported optimal — asserted for every instance, always. These
  are the errors the certificate bugs produced.
- *Reaching* an optimum is asserted only for a named subset that currently does.
  Asserting it for all of Netlib would produce a permanently red build that
  everyone learns to ignore; the subset is a regression gate and should grow when
  presolve lands.

One infeasible instance is reported `DualInfeasible` rather than
`PrimalInfeasible`. That is not a defect: an LP can be both, and
`Certificates.classify` prefers the primal answer only when its certificate
passes first.

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

## L2: what LOPF reproduces, and what it does not

`network-lopf` builds a dispatch LP from a `Network` and solves it with Prima.
Against PyPSA on `ac-dc-dispatch`:

| | |
| --- | --- |
| Objective | matches to 1e-6 relative |
| Generator dispatch | matches to 1e-3 MW |
| Line flows | matches to 1e-3 MW |
| Nodal prices | matches at 4 of 10 snapshots; the rest are a different optimal dual |

The primal is exact. Adding Kirchhoff voltage constraints over a cycle basis is
what achieved that: without them the model is a relaxation, and because capacity
in this fixture was sized from the expansion optimum every line rating binds, so
the relaxation actively relieved tight limits and undercut the true cost by
4.4e-06.

Two details of that formulation are load-bearing. The cycle impedance is
'''reactance for an AC sub-network and resistance for a DC one''' — the reference
network's DC lines carry `x = 0` with `r` non-zero, so using reactance
unconditionally gives every DC cycle a vacuous all-zero constraint. And the
orientation of each branch around the cycle must be right: getting it backwards
does not perturb the flows, it makes the LP infeasible, because the cycle
equation then contradicts bus balance.

### Nodal prices: settled, and the wrong answer first

An earlier version of this section blamed the price gap on generic degeneracy and
called that a hypothesis. It has now been settled, and along the way a confident
intermediate explanation turned out to be flatly wrong. Both are worth recording,
because the wrong one was much more plausible than the right one.

**The wrong explanation.** The fixture's `global_constraints.csv` carries a
`co2_limit` of 1000 with `mu = -2178.29`, and the dispatch emits exactly 1000.
That looks conclusive: a binding constraint with a large shadow price, absent from
the model, whose multiplier would feed every gas-attached bus price. It is not.
The exported `mu` is a '''stale dual from the sizing solve''' — the expansion
problem that produced `p_nom_opt` — written into the CSV by the same export that
fixed capacity. Solving the dispatch problem itself gives `mu = -0.0`. The cap is
touched but weakly binding, and no tighter cap is even feasible, because gas here
is simultaneously the dirty carrier and the expensive one (4.09–5.89 against
wind's 0.09–0.11), so minimising cost already minimises emissions. The
constraint never influenced the optimum it sits on. The golden generator now
captures `global_constraint_mu` from the solved model precisely so the two cannot
be confused again.

**The actual explanation.** Dual non-uniqueness, now demonstrated rather than
assumed. Two facts establish it:

- Tightening the solver from 1e-9 to 1e-12 moves the worst price gap by exactly
  zero — 3.874938 either way, with a dual residual of 0.0. Whatever this is, it
  is not an unconverged dual.
- At six of ten snapshots every generator sits precisely at a bound and total
  output equals total load, so there is no marginal generator and nothing pins
  the price. That is a direct consequence of sizing capacity from the expansion
  optimum.

The discriminator between degeneracy and a real bug is complementary slackness: a
generator strictly '''inside''' its bounds pins its bus price in every optimal
dual. Snapshots 6 and 8 have such a generator and the price matches PyPSA
exactly; snapshot 9 has one whose price sat 0.678 high, which is
`0.24/0.3517 × 0.9935` — the CO2 intensity of gas times this solver's CO2
multiplier, where PyPSA's is zero. Same optimal face, different point on it.
`LopfSuite` asserts that forced condition instead of equality with PyPSA's vertex,
because only the former fails on a genuine dual error.

**The constraint is implemented anyway**, and not for the price gap. Omitting a
global constraint drops a restriction, so the objective comes out too '''low''' —
and no fixture that existed at the time could catch it, since on
`ac-dc-dispatch` omitting it reproduces the objective exactly. Hence `ac-dc-co2`,
which prices gas below wind so the cap has to displace economic generation: it
emits 6702 unconstrained at a cost of 2819.52, and 2000 under the cap at 3178.55.
That 12.7% spread is what a silent drop would have cost, and it is now a golden.

Not implemented, and rejected rather than mis-solved in every case:

- **Annuitised and modular capacity.** Capacity expansion itself is implemented —
  see *Capacity expansion* below — but `overnight_cost` is annuitised over
  `lifetime` at `discount_rate`, and `p_nom_mod` makes capacity an integer number
  of blocks. Its default is NaN rather than zero, so it cannot be handled by
  falling back to `capital_cost`.
- **Security-constrained expansion of the transmission.** `Sclopf` refuses an
  extendable *branch*; extendable generation is allowed. See the SCLOPF section
  below for why the distinction matters and how it was got wrong first.
- **Set points on a storage unit's power, and on either of a store's variables.**
  `p_set` on a `StorageUnit` pins the net `p_dispatch − p_store` and
  `p_dispatch_set`/`p_store_set` pin them individually — three different
  constraints. A `Store`'s `e_set` and `p_set` are both refused, the latter
  because PyPSA pins it too, out of the generic loop, and admitting it silently
  would drop exactly the constraint the other refusal exists for.
- **Off-nominal tap ratios and the transformer T model, in the AC path only.**
  Transformers themselves are modelled — see *Transformers* below — but an
  off-nominal tap makes `Y` asymmetric rather than scaled, and `model = "t"` with
  a non-zero shunt needs a wye–delta conversion before `Y` is built. The linear
  models handle `tap_ratio` as PyPSA does, as a plain multiplier.
- **Phase shift, in the AC path only.** The linear flow models it — see *Phase
  shift* below. `Y` needs `exp(jφ)` on one off-diagonal and its conjugate on the
  other, which is refused rather than approximated.
- **Global constraints other than `primary_energy` with sense `<=`.** PyPSA
  dispatches on `type` to entirely different builders, so assuming one would
  build an energy cap as an emissions cap wearing the same right-hand side.

## L2: linear power flow

`network-pf` reproduces PyPSA's `n.lpf()` on all three reference networks —
voltage angles to 1e-9 rad, line flows and slack dispatch to 1e-6 MW. It does not
depend on Prima: LPF takes dispatch as given and the only unknowns are the angles
that carry it, so the problem is one symmetric positive-definite solve per
sub-network per snapshot, not an optimisation.

`storage-hvdc` becomes usable evidence here, having been only a rejection test for
LOPF. The asymmetry is real rather than an inconsistency: LOPF must refuse a
storage unit because its energy balance couples consecutive snapshots, whereas LPF
takes dispatch as an input, so a storage unit is just another one-port with a
`p_set` and a `sign`.

Three conventions were established by solving the reference network, not read off
documentation, and each would have been wrong otherwise:

- **`p_set` defaults to NaN**, not zero, for every one-port except Load. So the
  arithmetic has to special-case it — propagating the NaN silences the whole
  solve, and coercing it without comment hides a genuinely missing value. PyPSA
  reads it as zero, which is why `ac-dc-meshed`'s gas generators are idle under
  LPF while its wind generators are not.
- **The slack is the first generator's bus in file order**, falling back to the
  first bus only for an island with no generator. That makes Manchester the slack
  of {London, Manchester, Norwich} — not London, which is first both
  alphabetically and in `buses.csv` — and Norwich DC the slack of the DC island,
  where sorted order says Bremen DC. `SubNetwork.buses` is sorted for
  determinism, so file order has to be recovered from the tables.
- **Susceptance is `v_nom²/x` for AC and `v_nom²/r` for DC.** The same split as
  the cycle constraints, and for the same reason: the reference network's DC lines
  carry `x = 0` and its AC lines carry `r = 0`, so either mistake divides by zero
  rather than degrading gracefully. Unlike the cycle constraints, the `v_nom²`
  does not cancel here — it sets the magnitude of every angle.

The slack rule is the one worth dwelling on, because getting it wrong produces a
'''plausible''' answer: every angle is measured against the slack, so a different
choice shifts the entire profile by a constant and leaves the flows correct. It
would have read as a scaling or sign bug for as long as it took to doubt the
convention instead. `LinearPowerFlowSuite` therefore asserts the slack choice
directly against the manifest, separately from the angles.

An island with no generator at all is legal — the DC island's only attachments are
converters at their default zero flow — so the flows are well-defined but the slack
power has nowhere to be attributed. That is reported through `LpfResult.slacks`
rather than rejected or silently assigned; PyPSA warns in the same situation.
Transformers are refused: they would decompose correctly, but their per-unit base
involves `s_nom` and an off-nominal `tap_ratio` shifts the angle across them, so
reusing the line formula would put a plausible number on a wrong model.

## L2: Newton-Raphson AC power flow

`network-pf` also solves the non-linear power flow, matching PyPSA's `n.pf()` on
both fixtures that have one: voltage magnitudes and angles to 1e-9, and bus P and
Q to 1e-5.

The tolerance asymmetry is physics, not slack. On `storage-hvdc` the voltages
agree with PyPSA to **3e-12**, but P and Q are computed *from* those voltages
through admittances of order 1e5, so agreement to 1e-12 in per-unit voltage is
agreement to a few times 1e-6 in MVAr. Asserting P and Q at 1e-9 would be
asserting that two implementations round identically.

The admittance conventions were taken from PyPSA's own `Y` matrix rather than
from a formula, and reproducing a three-bus matrix to six significant figures is
a much stronger check than a derivation that looks right:

- `r_pu = r / v_nom²` and `x_pu = x / v_nom²` — impedance is **divided**.
- `g_pu = g · v_nom²` and `b_pu = b · v_nom²` — admittance is **multiplied**.
  Getting this backwards reads as plausible, since both are "per unit".
- The shunt term is halved at each end: `(g_pu + j·b_pu) / 2` into each of the
  branch's two diagonal entries. Dropping the halving doubles the charging
  current.

**The default tolerance of 1e-6 MW is a floor, not a preference.** Every snapshot
of `storage-hvdc` converges within three iterations at 1e-6; at 1e-7 three of
them do not converge in sixty. Injections are of order 1000 MW and admittances of
order 1e5, so a 1e-7 MW mismatch is about 1e-13 relative and the iteration is
chasing round-off. Newton's quadratic convergence leaves nothing in between: an
iterate either lands inside the noise or bounces around in it.

Partial pivoting in `Lu` is likewise not defensive. At a flat start `∂P/∂|V|`
vanishes for a lossless branch, so structural zeros on the Jacobian's diagonal
are the *expected* first iteration.

### Why there is no DC power flow

The brief asks for "Newton-Raphson AC/DC" and the DC half is not implemented,
because **PyPSA 1.2.4 does not implement it either** and this project gates every
physics module on agreeing with it. `SubNetwork.calculate_Y` is documented as
"Calculate bus admittance matrices for AC sub-networks" and returns early for any
other carrier, logging `Non-AC networks not supported for Y!`; `Y` is then never
assigned and `pf()` raises `AttributeError`. That is why the three ac-dc fixtures
record an error where their power flow should be, and it holds for a pure DC
network too — checked directly rather than inferred from the mixed case failing.

A DC sub-network is therefore refused with that reason rather than solved with
the AC equations. Doing the latter would not even fail cleanly: a DC line has
`x = 0`, so every off-diagonal susceptance is zero, `∂P/∂θ` vanishes at a flat
start and the Jacobian is singular.

The linear flow does handle DC islands, since `lpf` supports them. The asymmetry
is PyPSA's, not this port's.

## Branch-and-bound on an inexact bound

`BranchAndBound` solves mixed-integer problems by branching over Prima's own LP
relaxation. The textbook algorithm assumes an **exact** bound, and every
published correctness argument rests on one. Prima is a first-order method: it
returns a point converged to a tolerance, not a vertex, so a node's reported
objective is accurate only to roughly that tolerance. Two consequences, both
handled explicitly rather than hoped past.

**Pruning needs a margin, everywhere.** A node whose true bound sits just below
the incumbent can report just above it, and pruning on that discards the subtree holding the
optimum — silently, returning a suboptimal answer labelled `Optimal`. So a node
is pruned only when its bound exceeds the incumbent by more than a multiple of
*that node's own duality gap*: the accuracy achieved, not the accuracy requested.
The cost is exploring some nodes an exact solver would have cut.

**A non-conclusive relaxation is not a bound at all.** If a node's LP hits its
iteration limit, pruning on its objective is unsound and so is declaring its
solution integral. Such a node is branched rather than pruned, and counted in
`MilpSolution.unprovenNodes`; an incumbent found while any exist is reported
`Feasible`, never `Optimal`, because optimality was not proven.

The margin has to apply at *both* pruning points. The pre-solve skip — where a
node is discarded on its parent's bound without being solved — originally used an
exact comparison, so it bypassed the entire argument above and could drop the
subtree holding the optimum while the answer stayed labelled `Optimal`. Each node
now carries its parent's bound with the margin already subtracted, and a node
descended from an inconclusive relaxation carries `-infinity`, since such an
objective is not a bound at all.

The reported objective is `c'x` at the **returned** point, not the relaxation's
value at the fractional iterate it was snapped from. Those differ by up to
`integralityTolerance` times the integer columns' costs, which for
unit-commitment-scale coefficients is far more than the gap the result claims to
have closed — so reporting the wrong one would make every downstream comparison
measure the snapping rather than the search.

The search is depth-first on the most fractional variable. Depth-first because it
reaches an incumbent early — which is what makes pruning possible at all — and
because a child differs from its parent in exactly one bound, so the parent's
iterate warm-starts it well. That warm-start property is the reason a first-order
method is attractive inside branch-and-bound in the first place, and it is
asserted to leave the answer unchanged rather than assumed to, given the
unexplained dense-instance warm-start regression recorded above.

**Checked against ojAlgo's mixed-integer solver**, whose bound *is* exact, over
random mixed instances with deliberately fractional relaxations. That cross-check
matters more than the LP one it mirrors: the pruning rule is a judgement about
how much slack to leave rather than a theorem, and the failure it guards against
is invisible from inside. The suite also asserts the asymmetric half — Prima must
never report an objective *better* than the true optimum, which would mean its
reported point is not integer-feasible at all.

### The MILP ladder, and a flaky oracle

`MilpReport` runs the same comparison for mixed-integer problems that `Report`
does for LPs, and CI runs it as a separate step so a MILP regression is
attributed to branch-and-bound rather than to the LP solver.

```
instance           size              int prima     ojalgo         prima obj     ojalgo obj   rel gap   int gap   nodes  unprv
fractional-pair    2v/2c/4nz           2 Optimal   Optimal       -20.000000     -20.000000  0.00e+00  5.00e-02       5      0
knapsack-8         8v/1c/8nz           8 Optimal   Optimal       -59.000000     -59.000000  0.00e+00  5.65e-03      23      0
knapsack-16        16v/1c/16nz        16 Optimal   Optimal      -271.000000    -271.000000  0.00e+00  1.26e-02     305      0
set-cover-triangle 3v/3c/6nz           3 Optimal   Optimal         2.000000       2.000000  0.00e+00  2.50e-01       5      0
set-cover-k5-edges 10v/5c/20nz        10 Optimal   Optimal         3.000000       3.000000  0.00e+00  1.67e-01      59      0
random-mixed-1     8v/4c/22nz          4 Optimal   Optimal       -27.800000     -27.800000  1.01e-11  9.11e-02       5      0
random-mixed-9     10v/5c/35nz         5 Optimal   Optimal       -31.400000     -31.400000  2.81e-12  6.46e-02       7      0
random-mixed-3     12v/6c/51nz         6 Optimal   Optimal       -14.000000     -14.000000  9.07e-12  2.07e-01       9      0
random-mixed-4     14v/7c/61nz         7 Optimal   Optimal       -33.512820     -33.512821  7.28e-10  1.70e-02       5      0
```

Worst relative objective gap against the oracle: **7.3e-10**. No instance where
Prima reported an objective better than the true optimum, and no unproven nodes.

The report's exit code gates all of that, and it gates it directly. It previously
dropped a row from the comparison unless *both* solvers reported `Optimal`, so a
regression that turned instances into `Feasible` removed them from the check
rather than failing it — and had every instance degraded, the gap would have come
out 0.0 over an empty set and the step would have exited green having compared
nothing. Every status, every unproven node and every unsolved relaxation now
fails it by name.

The `int gap` column is there to stop the table flattering itself. It is the
distance from the LP relaxation to the integer optimum, so a near-zero entry
means the instance barely needed branching and its agreement says little about
the search. Two instances in the first version of the ladder had an integrality
gap of exactly zero and solved in one node; they are gone, and
`MilpAgreementSuite` now asserts that every instance has a fractional relaxation
so the same thing cannot creep back.

**The oracle had to be pinned before it could be an oracle.** ojAlgo's integer
solver stops on `time_suffice` once it holds a solution it considers good enough
and reports that incumbent with state `OPTIMAL`, and its branch-and-bound is
multi-threaded, so which node closes the search varies between runs. The same
ladder on the same input gave a worst gap of 7.3e-10 and then 1.7e-2, with the
report accusing Prima of "claiming a better objective than the oracle" — when
what had happened was the oracle giving up early. Time limits are now pushed out
of reach, the gap tolerance tightened to 1e-12 and parallelism set to one; four
consecutive runs then agree bit-for-bit. An oracle that is sometimes right is
worse than no oracle, because a disagreement stops meaning anything.

## L3: unit commitment

`UnitCommitment` reproduces PyPSA's mixed-integer commitment solve on the
`unit-commitment` fixture: objective, dispatch and the commitment schedule
itself. It runs on `BranchAndBound` over Prima, so nothing leaves this project's
own solver.

Formulated **without branch flows**, so it models a single bus. A network with
any transmission is refused rather than solved with every bus forced to balance
locally, which would delete import and export and return either a spurious
infeasibility or a schedule far dearer than the real network's. Dangling bus
references, ramp limits, `stand_by_cost` and `marginal_cost_quadratic` are
refused for the same reason: each would price the schedule below the truth.

The formulation is otherwise the standard one — a binary status per unit per
snapshot, the disjunctive output bound `p_min_pu · p_nom · u <= p <= p_max_pu · p_nom · u`, and
start-up/shut-down variables linked by `su − sd = u[t] − u[t−1]` with rolling
windows for minimum up and down time. Start-up and shut-down are left
**continuous**: the linking equality and the non-negative costs drive them to 0
or 1 whenever the status is integral, so declaring them integer would multiply
the search tree for decisions already determined.

`up_time_before` defaults to **1**, meaning a unit counts as having run before
the horizon. Not cosmetic: with the opposite convention every unit that starts
the horizon committed is charged a spurious start-up at `t = 0`, and the
objective is wrong by the sum of those costs while the schedule looks identical.

Both counters are read as **counts, not flags**, because PyPSA enforces the
*residual* initial condition: a unit that has already run `up_time_before`
snapshots must stay committed for a further `min_up_time − up_time_before`, and
the mirror holds for one that has been down. Seeding `u[-1]` for the linking
equality alone leaves that unconstrained, and the model then accepts schedules
PyPSA rejects — at a *lower* cost. In this fixture `base` has `min_up_time = 3`
against `up_time_before = 1`, so it is held on at `t = 0` and `1`; that the
omission went unnoticed is precisely because `base` runs throughout anyway.

### What the fixture does and does not prove

Built deliberately, and checked before anything was written against it:

- `mid` is off at snapshots 3–5, so the golden's status frame is **not**
  all-ones. An implementation that never switched anything off would reproduce an
  all-ones frame and prove nothing.
- `min_down_time` on `mid` **binds**: PyPSA gives 16700 without it and 17000 with
  it, so an implementation that dropped it comes out 300 *cheap* — the direction
  that cannot be caught downstream.
- `base`'s `min_up_time` does **not** bind; it runs throughout. Said here rather
  than left for a reader to infer coverage that is not there.
- `peak` is **inert**: it produces nothing at any snapshot and deleting it leaves
  the objective at 17000. It exists so a non-committable generator sits in the
  same model as committable ones, which take different treatment — ordinary
  bounds against a status variable. It exercises a code path, not the economics.

PyPSA's `generators_t.status` spans every generator and reports 0 for a
non-committable one, which is a placeholder rather than a decision — `peak` has
no status to report. `UcResult.committed` reports `true` for such a unit instead,
since that is what the model does with it. The two are not compared.

Ramp limits, `stand_by_cost`, `marginal_cost_quadratic` and global constraints
are not modelled and are rejected rather than ignored, since each would yield a
schedule cheaper than the network permits. `GlobalConstraint` in particular was
briefly admitted by a `handled` set copied from `Lopf` — which does model
primary-energy caps — so a commitment network carrying a CO2 cap would have been
solved with the cap deleted. The two cost attributes are `static or series`, so
they are checked per snapshot rather than from the static column, where a value
arriving only as `generators-stand_by_cost.csv` would have slipped past.

## L2: security-constrained LOPF

`Sclopf` reproduces PyPSA's `optimize_security_constrained` on the
`sclopf-triangle` fixture — objective, generator dispatch and line flows.

**Extendable transmission is refused; extendable generation is not.** The
security rows cap post-contingency flow at `s_nom · s_max_pu` read from the file,
which under capacity expansion is no longer the rating being built to. Where the
optimum shrinks a line below its given rating — what happens on `ac-dc-meshed` —
the contingency limit is looser than the network can carry and the answer comes
out cheaper than reality, reporting `Optimal`.

Only passive branches, and that distinction was got wrong twice. The first
version refused every extendable component, which blocks secure dispatch under
*generation* expansion — a headline use of SCLOPF — with no correctness argument
behind it: no security row reads a generator's capacity, every branch rating
stays static, and the base model's capacity rows are inequalities the row-by-row
copy already handles.

The second version put the check after `Lodf.of`. Under the documented default of
every branch as a contingency, that computes the factors first — so an extendable
network with a bridge threw `Lodf.Unsupported` about the bridge instead of
`Sclopf.UnsupportedNetwork` about extendability. A *different exception type*, so
a caller catching the documented one caught nothing. The check now sits between
the empty-outage return and `Lodf.of`, and reads its component set off `Role`
rather than naming Line and Transformer for a third time.

**No post-contingency variables.** Removing a branch redistributes its flow onto
the rest by factors that depend only on the impedances, so the post-outage flow
is a linear function of the pre-outage ones and security is just more rating rows
on variables the dispatch model already has:

```
−s_nom · s_max_pu  <=  f_l + LODF[l, o] · f_o  <=  s_nom · s_max_pu
```

one pair per monitored branch per credible outage per snapshot. The obvious
alternative — a full copy of the flow variables per contingency — multiplies the
problem size by the number of outages for the same answer.

The factors live in `network-pf` as `Lodf`, not in the optimisation module, since
they are a power-flow sensitivity; `network-lopf` depends on `network-pf` for
them, which keeps one definition of susceptance and slack across both layers.
They follow PyPSA's construction exactly: `PTDF = H · B⁻¹` with the slack row and
column struck out, `branchPTDF = PTDF · K`, then
`LODF[l,o] = branchPTDF[l,o] / (1 − branchPTDF[o,o])` with `−1` on the diagonal.
`B⁻¹` is obtained by solving against the same Cholesky factorisation the linear
flow builds, one column per free bus, rather than inverting a matrix.

**A bridge is not a credible contingency**, and is refused rather than
approximated. Removing one disconnects the network, so `1 − branchPTDF[o,o]` goes
to zero and the factor diverges; an infinity would land in a constraint
coefficient and the LP would come back infeasible with nothing to explain it.
`ac-dc-meshed`'s Bremen–Frankfurt line is exactly such a branch, which is part of
why that network cannot serve as an SCLOPF fixture.

There is **no bound on the factors themselves**, and the reasoning behind that
needed correcting twice.

A bound of 1e6 was added on the argument that a denominator just past the
threshold would yield a huge finite factor. I removed it after measuring the
largest factor at exactly 1.0000 while driving a network towards a bridge — but
that measurement was taken on a **single cycle**, where `|LODF| = 1` is a
topological identity: one alternative path means the whole outaged flow moves to
it, whatever the impedances. The sweep varied nothing and could not have produced
another answer.

Repeated on a two-loop network with impedances spread over eight orders of
magnitude, the largest factor is still 1.0000 — and **PyPSA's own BODF agrees**,
maxing at 1.0000 with interior values of −0.333 and −0.500. So the conclusion
survives a test that could have refuted it, which the first one could not. The
bound stays out; only a non-finite factor is refused.

That exercise exposed a real gap. `sclopf-triangle` cannot validate the LODF
computation at all: every factor there is ±1 by topology, so an implementation
returning ±1 unconditionally would match it *and* match the SCLOPF objective it
feeds. `lodf-mesh` — two loops sharing an edge, PyPSA's factors including
−0.5455, −0.6429 and +0.3571 — is now checked against PyPSA's BODF entry by
entry, with an assertion that enough entries differ from ±1 for the fixture to
discriminate.

The refusal belongs to the **outage column**, not the whole matrix, and getting
that wrong made the module far less usable than the contract said. Validating
every column meant a single radial load spur anywhere in the network refused
every solve — including outages on a meshed part with nothing to do with it, and
including the empty-outage case that is supposed to be exactly the dispatch
model. Since almost any real network has a radial branch somewhere, SCLOPF worked
only on purpose-built meshed fixtures. The bridge test is scaled to the
self-sensitivity rather than absolute, too: for a true bridge the quantity is
analytically 1, but it arrives through a Cholesky solve, so a fixed `1e-9` could
pass on an ill-conditioned island and let a factor of order `1e8` through.

### Why the fixture is purpose-built

`ac-dc-dispatch` cannot be adapted, and finding out why corrected an assumption.
The obvious reading is that N-1 needs transmission headroom, so I scaled its line
ratings to 3× — still infeasible. The real obstacle is *generation*: capacity
there comes from the expansion optimum, and at six of ten snapshots every
generator sits exactly at its limit with total output equal to total load. With
zero redispatch headroom no rating makes an outage survivable.

`sclopf-triangle` is three buses in a triangle, so every single-line outage
leaves the network connected. Its rating of 150 is chosen: the plain LOPF costs
6900 there, exactly what it costs at 200, so the pre-contingency limits are slack
and the **only** thing separating LOPF from SCLOPF is the contingency constraint.
The secure optimum is 14100 — an implementation that dropped the N-1 rows returns
6900, wrong by more than a factor of two rather than by a tolerance.

Beyond matching PyPSA, the suite reconstructs each post-outage flow from the
solved pre-outage ones and checks it against the rating, which a model that built
the rows with a wrong sign or a transposed factor could not satisfy even if it
happened to match an objective.

## L1: PyPSA's binary formats

`network-io` reads PyPSA's netCDF export into the same `Network` the CSV reader
produces, checked across all seven goldens: snapshots, entity ids, column sets,
values, time series — including *which* entities vary — and snapshot weightings.

That comparison is worth more than a round-trip. The two formats share no code on
this side and almost none on PyPSA's, so agreeing means both genuinely decode the
network rather than each round-tripping its own representation.

PyPSA's netCDF is **netCDF-4**, which is an HDF5 container — the file opens with
the HDF5 signature, not `CDF` — so this needs an HDF5 reader rather than a
netCDF-3 parser. jhdf is a pure-Java one (MIT), which keeps the module free of
native libraries and their per-platform packaging.

Two conventions the file does not advertise:

- **Time is CF-encoded.** `snapshots_snapshot` holds `0, 1, 2` with a `units`
  attribute of `hours since 2015-01-01 00:00:00`. Reading it raw gives integer
  snapshots where the CSV says timestamps. A network whose index is *genuinely*
  integers — `unit-commitment` — carries no `units` at all, so the attribute's
  presence is what separates the two cases.
- **Booleans are bytes.** netCDF has no boolean type, so xarray writes `int8` and
  records the real type in a `dtype` attribute. Without checking it,
  `p_nom_extendable` comes back as an integer column.

One benign difference remains, and the suite states rather than hides it:
`SubNetwork.obj` is PyPSA's in-memory object handle, not network data. It is
absent from the schema, so its type is inferred, and each format writes its own
placeholder — an empty string in CSV, NaN in netCDF. Both render to the same
empty text. Types are therefore compared only where the schema declares one;
values always.

### Why PyPSA's `.h5` is not read

It is not netCDF-in-HDF5. It is pandas' PyTables layout: each component is a
group whose `table` dataset has compound fields named `values_block_0..3` rather
than column names. The names live in `values_block_N_kind` attributes, and those
are **Python pickles** — the bytes begin `(lp0\nVsub_network\np1\na.`, which is
protocol 0.

Reading it therefore means writing a pickle parser and reimplementing pandas'
block-manager layout, brittle against pandas versions, for a format that needs an
optional dependency (`tables`) even in Python and that PyPSA treats as secondary
to netCDF. DuckDB is no help either: its community repository has no HDF5
extension (`INSTALL hdf5 FROM community` 404s on v1.5.5), only unofficial
third-party projects, and it would be a native dependency besides.

The `.h5` goldens are still written, so the decision can be revisited against
real files rather than re-derived.

## Transformers

Transformers are modelled rather than refused, in all three L2 modules.

The per-unit base differs by component, and that is the whole reason they were
refused:

```
Line:        z_pu = z / v_nom²         susceptance = v_nom² / z
Transformer: z_pu = z / s_nom          susceptance = s_nom / (z · tap_ratio)
             z_pu_eff = z_pu · tap_ratio
```

The difference is not a rounding matter. On `scigrid-de` a transformer comes out
at 20000 against a line's 3707 — a factor of five — and on a 380 kV unit rated
500 MVA it is six orders of magnitude. Reusing the line formula gives a feasible
LP with wrong flows and no diagnostic, which is why refusing was the right call
until there was a golden.

`transformer-levels` is that golden: two voltage levels, a line at each, and a
transformer at each end, so the network has a cycle crossing both transformers.
The cycle matters — in a radial network the flows are fixed by topology and any
per-unit base reproduces them, so a radial fixture would validate nothing.

**Nominal taps only.** `tap_ratio` folds into the linear models as a plain
multiplier, exactly as PyPSA does it, so `Cycles.impedance` and the linear flow
honour it. The AC admittance is different: an off-nominal tap makes `Y`
asymmetric, and `model = "t"` with a non-zero shunt needs a wye–delta conversion
before `Y` is built at all. Both are refused in `Admittance` rather than
approximated. `scigrid-de`'s 96 transformers all sit at `tap_ratio = 1` with
`b = g = 0`, so the T-model path there is inert.

The shared `Branches` object is now public. Its stated purpose was one definition
of susceptance across the power-flow modules, and the transformer conversion was
the case that would have been added to one and not the other — `network-lopf`'s
cycle constraints read it rather than carrying a third copy.

## `active`, and PyPSA disagreeing with itself

`active` is a boolean on every physical component. It does not mean "runs at
zero" — PyPSA removes the component from the model, and that reaches further than
dispatch: an inactive branch is excluded from topology, so a two-bus network
joined by one inactive line has **two** sub-networks rather than one.

This port read the attribute nowhere. It was found the same way as phase shift,
by the same check: enumerate what the schema declares, subtract what the source
ever names, read the remainder. That check now has two confirmed bugs to its
name and remains the only technique that finds this shape — an attribute nothing
reads has no call site to refuse at and nothing to sabotage in a test.

`Active.only` filters the network once, at each physics entry point, rather than
at the several dozen sites that enumerate `table.ids`. One transformation cannot
be half-applied; a filter per call site can, and the missed one is silent.
Results still report an inactive component at 0.0, matching PyPSA's frames,
while a genuinely unknown name still throws.

### The `optimize` golden for `inactive` is not a target

PyPSA's two L2 models disagree about the same network, and the LOPF one is
wrong. `cycle_matrix` keeps the loop through an inactive branch; the Kirchhoff
builder then selects flow variables over *active* branches only, so the inactive
branch's term drops out and the row collapses to a voltage law for a loop that is
not closed. On `inactive` that pins `hv31` to 27.6 MW on a branch rated 1700 and
costs **68,407.58**, running the expensive generator at 272 MW while cheap
capacity sits idle.

Delete the same line instead of deactivating it and PyPSA gives **19,800** with
the cheap unit serving all 435 MW — one cycle instead of two, and exactly what
this port produces. Its own `n.lpf()` excludes inactive branches from topology
properly, which is why the `lpf` block on that fixture *is* a target while the
`optimize` block is evidence.

So `inactive-removed` exists as the comparison, and the golden carries a
`not_a_target` note beside the numbers. This is the second golden in the
repository that records what PyPSA does rather than what should be reproduced —
`scigrid-de`'s degenerate dispatch is the other.

The objective is not the discriminator here: both networks cost 19,800, because
the cheap generator serves everything either way and no rating binds. The flows
are — `hv31` carries the whole 300 MW instead of sharing it, and `hv12` drops
from 197.2 to 46.7. A test asserting only the objective would have passed against
an implementation that ignored `active` entirely.

## Phase shift, and a bug with nowhere to live

A phase-shifting transformer moves power without an angle difference across it.
PyPSA's linear flow carries it as a constant:

```
p_branch_shift = −b · φ            (φ in radians, transformers only)
B θ = p − K · p_branch_shift        flow = b (θ₀ − θ₁) + p_branch_shift
```

This port ignored it. Not approximated — **ignored**, in the strict sense that no
line of code anywhere read `phase_shift`. On `transformer-levels` with 30° on one
transformer, t1 carries **−840.53 MW** against the **+150.66** it carries
unshifted: reversed, and 5.6 times the power. The port returned the unshifted
number.

The interesting part is how it escaped a codebase that refuses off-nominal taps,
the T model, `Store`, `overnight_cost` and a dozen other things by name. **A
refusal needs a code site, and an attribute nothing reads has none.** Every other
gap here was found at the moment some code had to decide what to do with a value;
this one had no such moment. Searching for attributes the schema declares and the
port never mentions would have found it, and nothing else in the process would.

### PyPSA's own two models disagree

Worth knowing before concluding either is broken. `n.lpf()` applies the shift.
`n.optimize()` does not: its Kirchhoff row is `Σ x_l s_l = 0` with no shift term,
so the optimised flows are **identical** at 0° and 30°.

So the LOPF here was never wrong — it matches PyPSA including this omission — and
`phase-shift` pins both halves: that the linear flow honours the shift, and that
the optimisation does not.

The AC path refuses it. `Y` needs `exp(jφ)` on one off-diagonal and its conjugate
on the other, which is asymmetry rather than scaling, and PyPSA does converge on
this fixture — so the golden records an answer the port declines to compute,
which is the honest shape for a gap.

## Standard types, and the 585-bus network they unblock

`scigrid-de` runs. 585 buses, 852 lines, 96 transformers, 24 snapshots: angles to
1e-9 of PyPSA's and flows to 1e-6, two orders of magnitude past every other
fixture here.

It was unreadable until now for a reason that is not about physics at all. All
852 of its lines carry `x = 0` and a **type name**, so the impedance comes from

```
r = r_per_length · length / num_parallel
x = x_per_length · length / num_parallel
b = 2π · 1e-9 · f_nom · c_per_length · length · num_parallel
```

and `export_to_csv_folder` writes **no type library**. It drops exactly the rows
a fresh `Network()` was born with, correctly — they are library data, and PyPSA's
own reader repopulates them from the installed package. A reader outside Python
has nothing to repopulate from.

So `network-model` ships the pinned library as a resource, and
`StandardTypesSuite` holds that copy to a golden written from the same PyPSA. A
version bump that changed an impedance then fails a comparison rather than
silently changing every answer on a typed network. A network carrying its own
`line_types.csv` — which is what PyPSA exports once a user adds a type — wins
row by row over the shipped one.

Expansion happens where PyPSA does it, in the physics entry points rather than
at read time. Doing it in the reader would put computed columns into a
round-tripped export that PyPSA's own export does not have.

**Two asymmetries that are invisible at the default.** Parallel circuits divide
the series impedance and multiply the shunt; and a transformer's `x` is formed
from the *undivided* `r`:

```
r = vscr/100                    x = sqrt((vsc/100)² − r²)     then both ÷ num_parallel
g = pfe/(1000 · s_nom)          b = −sqrt((i0/100)² − g²)     then both × num_parallel
```

Transposing the `num_parallel` direction is a factor of `num_parallel²`;
computing `x` from the divided `r` is about 5e-9 on the 160 MVA type — far too
small to show up in a flow comparison. Every other fixture in the repository
sits at `num_parallel = 1`, where both mistakes are exactly invisible, which is
why `standard-types` puts a 2 and a 4 in and asserts the ratios directly.

`s_nom` comes from the transformer type and is **not** scaled by `num_parallel`.
Two 160 MVA units in parallel are rated 160 MVA by PyPSA. Surprising, so it is
pinned.

The line shunt `b` only enters an AC solve, so `standard-types` also carries a
`pf` golden — zeroing that term moves a 380 kV bus voltage by 2.7%, which is the
measurement that says the term is load-bearing rather than decorative.

## Capacity expansion

Capacity is a decision, not a given. `ac-dc-meshed` — the brief's own starting
network — and `storage-hvdc` both solve, and both were rejected until now. They
were the last two stock PyPSA examples the port could not touch.

The formulation is small. A capacity variable per extendable entity, bounded by
`<attr>_min` and `<attr>_max`; the operational limits move out of the dispatch
column and into two rows per snapshot:

```
min_pu · capacity  <=  dispatch  <=  max_pu · capacity
```

which is why the dispatch column itself becomes unbounded. Leaving the old
`p_nom` bound on it would silently cap the expansion at the capacity the network
came with — a feasible answer, reported `Optimal`.

### The objective is the cost of the change, and reads negative

This is the part that is not guessable, and it is worth stating in full because
three different numbers are all called "the objective" somewhere:

```
objective          = Σ capital_cost · capacity  −  Σ capital_cost · p_nom  +  operating cost
objective_constant = Σ capital_cost · p_nom                      (extendables only)
total_system_cost  = objective + objective_constant
```

PyPSA charges capital cost on the **whole** optimal capacity and then subtracts
the capital cost of what was already there. So on `ac-dc-meshed` the objective is
**−3,474,256** against a total system cost of 18,441,021: the optimum builds less
than the network came with, and the saving is what gets reported.

An implementation that reported the total instead would be out by 21.9 million
and look entirely plausible. Deleting the subtraction in the port produces
exactly 18,441,021.478 — the total, to the digit — which is why all three numbers
are asserted rather than just the first.

Agreement is 3.1e-10 relative on `ac-dc-meshed` and 2.0e-11 on `storage-hvdc`,
with the chosen capacities compared entity by entity. The objective alone would
not be enough: a wrong build-out that happens to cost the right amount is exactly
the failure a single scalar cannot see.

### What `storage-hvdc` needed beyond expansion

One more thing: `state_of_charge_set`, which pins a storage unit's energy at a
given snapshot. It is sparse — two values across 6 units and 12 snapshots, with
NaN meaning "not set" rather than zero — and it binds: deleting it moves that
fixture's objective from 14,670,509 to 14,661,496. I had assumed it did not, and
wrote that into a comment before a sabotage run showed otherwise.

The three *power* set points (`p_set`, `p_dispatch_set`, `p_store_set`) are still
refused. They are three different constraints — PyPSA fixes `p_set` on the net
`p_dispatch − p_store` rather than on either variable — and no golden uses any of
them.

## `Store`: energy with no power rating

A Store is not a StorageUnit under another name, and three differences carry the
whole component:

- **One signed power variable, not two.** No charging or discharging efficiency,
  so there is nothing for two variables to carry differently. `p > 0` discharges
  into the bus.
- **No power rating at all.** PyPSA generates no operational bound for `Store-p`.
  How fast a store moves energy follows only from its energy band and the elapsed
  hours. Bounding `p` by `e_nom` is the obvious reading, is strictly tighter, and
  gives a more expensive answer.
- **The energy band is `[e_min_pu, e_max_pu] · e_nom`**, not `[0, e_nom]`.

```
e(t) = (1 − standing_loss)^eh · e(t−1)  −  eh · p(t)
```

`p` is *subtracted*. Reversing it gives a store that charges when it should
discharge and still balances every bus at every snapshot — the objective moves
from 118,020 to 103,071 and nothing else looks wrong.

`store-bank` is the fixture, and it took several reshapes, each failing in a way
worth naming: the extendable store went unbuilt; the cyclic store ended its
horizon empty, which makes its wrap indistinguishable from a non-cyclic store
starting at zero; the upper band was never touched.

The last failure is the interesting one. **A lossless store that begins and ends
the horizon at the same level has `Σ p = 0`, so its `marginal_cost` contributes
nothing to the objective whatever the schedule.** Two of the three stores were
therefore free to move energy around at zero cost: Prima and HiGHS agreed on the
objective to 5e-11 and disagreed on both trajectories. Standing losses and a
`marginal_cost_storage` make holding energy costly, which picks a single optimum
out — and the two solvers then agree cell for cell.

## Storage, and the first realistic LOPF

`StorageUnit` is modelled, and with it `scigrid-de` solves an optimal power flow
— 60,552 variables and 23,688 rows, against the few hundred every other fixture
poses. Storage was the only thing blocking it: that network has no extendable
component, no committable generator and no global constraint.

Storage is the **only** constraint in this model that couples two snapshots.
Everything else is separable — a generator's output at 3am constrains nothing at
4am — so a dispatch model without it is really a sequence of independent LPs
sharing one matrix. The energy balance is what makes the horizon a single
problem:

```
soc(t) = (1 − standing_loss)^eh · soc(t−1)
       + eh · efficiency_store · p_store(t)
       − eh / efficiency_dispatch · p_dispatch(t)
       − eh · spill(t)
       + eh · inflow(t)
```

**Four variables per unit, not one.** Charging and discharging meet the state of
charge through *different* efficiencies, so a single signed power variable cannot
carry both — it would be exact only when both efficiencies are 1, which no real
unit has. `spill` exists so inflow that will not fit has somewhere to go; without
it a unit whose inflow exceeds its remaining capacity makes the LP **infeasible**
rather than merely different, which is how that term is tested.

At `t = 0` the previous state of charge is either the *last* snapshot's variable
(cyclic) or absent, with `state_of_charge_initial` moving to the right-hand side.
Those are different constraint matrices rather than different numbers.

### Why `storage-cycle` exists when `scigrid-de` already has 38 units

Because those 38 exercise one path. They are all non-cyclic with no inflow, no
standing loss, a zero initial state of charge, and snapshot weightings of exactly
1 — so they validate the plain balance and nothing around it.

Two design points in the purpose-built fixture were arrived at by getting them
wrong first:

- **The optimum has to be unique.** The first version gave the cheap generator a
  flat marginal cost, so charging at one snapshot cost exactly what charging at
  another did. Prima and HiGHS agreed on the objective to 6e-10 and disagreed on
  the whole trajectory — a degenerate optimum, useless as a golden. Giving the
  generator a per-snapshot price made it unique, and the two now agree cell for
  cell.
- **A flag that is set but never binds tests nothing.** The first version's
  spills all came out zero, and its cyclic unit ended the horizon empty — which
  makes its wrap indistinguishable from a non-cyclic unit starting at zero. The
  fixture now forces at least 8.98 MW of spill by construction, and its cyclic
  unit ends holding 120 MWh and discharges it at the first snapshot. A separate
  test asserts both, so the fixture cannot quietly stop discriminating.

The three snapshot weightings are deliberately three different numbers.
PyPSA scales the energy balance by `snapshot_weightings.stores` and the objective
by `snapshot_weightings.objective`; every other fixture in the repository holds
both at 1.0, where confusing the two is exactly invisible.

### Prima on a 60,000-variable LP: it converges, slowly

The first large power-system LP this solver has met, and the numbers are worth
recording because they bear directly on the GPU case:

| tolerance | iterations | wall clock | relative objective error |
| --- | --- | --- | --- |
| 1e-4 | 14,848 | 12.7 s | −1.05e-04 (Optimal) |
| 1e-6 | 100,000 | 50 s | 4.06e-06 (iteration limit) |
| 1e-6 | 200,000 | 91 s | 1.29e-06 (iteration limit) |
| 1e-6 | 281,792 | 145 s | 2.66e-07 (Optimal) |

So it is a first-order method being slow on a large problem, not a stall — the
error falls monotonically and it does terminate. The default 100,000-iteration
limit is simply not enough here. The suite gates at 1e-4, which is 13 s; 145 s on
every commit is not worth the extra digits.

This is also the concrete case for the GPU backend. 281,792 iterations at two
sparse matrix-vector products each is precisely the workload `Kernels` exists to
move, and it is the first instance in this repository large enough for the
question to matter.

### What `scigrid-de` cannot gate

Its `optimize` dispatch. Adding the `standard-types` fixture rewrote 59,000 lines
of the committed `scigrid-de` golden without touching anything that network uses,
which is how this surfaced.

Six runs in fresh processes land on exactly two answers, agreeing on the
objective to 2e-8 relative and differing by up to **750 MW** at individual
generators. The cause is upstream of the solver: `find_cycles` returns 364 cycles
either way but a basis of either 2372 or 2469 nonzeros, and 2469 − 2372 = 97 is
exactly the per-snapshot difference in the LP handed to HiGHS (261298 against
263626 nonzeros over 24 snapshots). `PYTHONHASHSEED` does not pin it.

Both bases span the same cycle space and both optima cost the same, so this is a
property of the network rather than a defect in PyPSA. It does mean the objective
and the marginal prices are gates there and the dispatch frames are not — the
golden says so in a `dispatch_note` beside them. LOPF on `scigrid-de` is blocked
on storage anyway; this is recorded so it is not discovered again later, from the
other end.

### Transformer standard types are implemented but reach a wall

The conversion is there and validated. What is not implemented is what the
library's own types mostly need: every transformer type carries a non-zero
magnetising shunt, and `Transformer.model` defaults to `"t"`, under which PyPSA
converts wye–delta before building `Y`. The `standard-types` fixture sets
`model = "pi"` explicitly to isolate type expansion from that conversion.

The 110/20 kV types are worse — they are Dyn5 with `phase_shift = 150°`, and
phase shift is not modelled anywhere in this port. The fixture uses
`160 MVA 380/110 kV`, which has none. So: a transformer standard type works
today if it is a transmission-level unit declared `pi`. The T model and phase
shift are the next two pieces, and both need goldens of their own.

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

**Presolve does only the exact reductions.** Fixed variables, empty rows and
columns, and singleton rows are removed; forcing rows, dominated rows and
coefficient tightening are not. Those are where the remaining Netlib coverage
is, and they are a different kind of decision — an exact reduction cannot change
the answer, an approximate one can.

**Postsolve maps optimal duals, not Farkas rays.** The dual reconstruction is
derived for an optimal solution and is accurate enough for pricing, which is
what the power-system layer needs. It does not carry an infeasibility
certificate: a Farkas ray mapped back through it will not generally re-verify
against the original problem. `NetlibInfeasibleSuite` therefore re-checks
certificates against the reduced problem, which is the one the solver actually
proved infeasible — sound, because every reduction here is exact, so an
infeasible reduced problem implies an infeasible original.

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
