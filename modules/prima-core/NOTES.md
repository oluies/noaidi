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
now reports the figure on two JDKs on the same OS, and it comes out at
5.874e-10 on both JDK 21 and JDK 25 — agreement to the four digits `Report`
prints with `%.3e`, not a comparison of the underlying doubles; nothing
performs one. That rules out JVM sensitivity between those two versions — but
the macOS figure was taken on JDK 26, which is not in the matrix, so it does
not rule the JDK out entirely. Architecture remains the leading hypothesis, via
summation order in ojAlgo's simplex and its hardware-profile-dependent
blocking; it is not yet established.

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

Iterations of double-precision work removed, against a cold fp64 solve.
Measured on macOS/aarch64 — see '''Iteration counts are platform-specific''';
the two largest instances here move between hosts and the rest do not:

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
solve needs 35,392 for 1e-9 — 26,048 on Linux, where the ratio still says the
same thing and the pair does not. The first few digits are nearly free and the last
few cost everything. A float32 device therefore removes only the cheap prefix,
unless the caller's tolerance is loose enough that float32 can deliver the whole
answer — which at 1e-6 it does for most of the ladder.

**The row that says the float32 point is worse than starting from zero is one
draw, not a property.** At 1e-6, random-600x400 costs 2,752 iterations cold and
19,840 refining, and that −621% was carried here as a finding about reduced
precision on dense LPs. It is a finding about *that instance*. `random-600x400`
is seed 3 of a family the report now sweeps ten draws of, and it is the only one
of the ten where the hand-over costs more than a cold solve:

| tolerance | median saved | worst | best | host |
| --- | --- | --- | --- | --- |
| 1e-6 | **26%** | −621% (seed 3) | 45% | macOS/aarch64 |
| 1e-6 | **24%** | −443% (seed 3) | 56% | Linux/x86_64 |
| 1e-9 | −6% | −24% | 23% | macOS/aarch64 |
| 1e-9 | +1% | −81% | 18% | Linux/x86_64 |

Both platforms are given because they differ, and the report prints whichever
one ran it — see '''Iteration counts are platform-specific''' below. The finding
is the same on both: at 1e-6 seed 3 is the outlier and the median saving is
around a quarter of the double-precision work; at 1e-9 the hand-over is roughly
break-even across the family, which is the "float32 removes only the cheap
prefix" result above, arrived at from ten instances instead of one.

So what needed explaining was never a general regression. See '''Where seed 3's
refinement goes''' below for what happens on the one instance, and why the
obvious fix for it is worse than the problem.

The tables above are a hand-filtered view: the validation report prints the two
tolerance regimes as separate tables covering all twelve ladder instances, then
the ten-draw sweep. Run it to regenerate the underlying numbers, then merge.

### Iteration counts are platform-specific

`ValidationLadder.worstGapBound` already records that the worst oracle gap is
4.9e-10 on macOS/aarch64 and 5.9e-10 on Linux/x86_64. Iteration counts vary the
same way and by far more, and every number in this file is from the platform its
paragraph names.

Prima and PDLP, both at 1e-9. Everything up to and including `random-60x30`
gives an identical count on both hosts, for both implementations. The two
largest instances do not:

| instance | Prima mac | Prima Linux | PDLP mac | PDLP Linux |
| --- | --- | --- | --- | --- |
| random-200x120 | 6,464 | 6,784 | 4,288 | 3,968 |
| random-600x400 | 35,392 | **26,048** | 32,832 | 32,832 |

It is not a Prima quirk: PDLP moves too, in the other direction on
random-200x120, and it is a separate C++ implementation. The arithmetic is
IEEE-754 double on both hosts and the algorithm is deterministic, so what moves
is the order operations are contracted and rounded in, which perturbs the
trajectory enough to change which checkpoint a restart fires at. On instances
that converge inside a few restart periods there is no room for it and both
agree exactly.

The practical consequence is that a *ratio* between two solvers on one host is
evidence and an absolute count is a measurement of that host. That is why
`PdlpComparisonSuite` bounds the ratio rather than pinning counts.

### Where seed 3's refinement goes

Traced on macOS/aarch64; the shape holds on Linux, where the same seed costs
−443% rather than −621%. Tracing every evaluation point of both solves puts the
loss in a 200-iteration window. The refinement starts from a point whose fp64 KKT error is genuinely
good — primal residual 4.0e-3, against 8.6e+2 at the origin — and by iteration
192 it has reached a weighted error of 3.5e-3, better than the cold solve
reaches before iteration 2,300. A restart fires there on the sufficient-decay
test and re-centres on the current iterate. By iteration 384 the error is 3.3, a
thousandfold worse, and the next 19,000 iterations are spent getting back.

The restart is not incidental to that. `restartAt` chooses between two
candidates, the current iterate and the running average, and takes the better of
them — but neither is compared against the point the period began from, whose
weighted error is already in hand as `errorAtRestart`. So a restart can
re-centre on a point worse than the one it started from, and when it does, the
period's ground is lost along with its progress: `errorAtRestart` is reset
upward and nothing records that a better point was ever held.

**A third candidate is the obvious fix and it is a bad one.** Holding the
period's starting point when both candidates are worse takes seed 3 from −621%
to −7% at 1e-6, and the ten-draw median at 1e-9 from −6% to +12%. It also takes
the `infeasible` fixture from 1,088 iterations to 391,616, and its refinement
past the 500,000 limit. The reason is not subtle once seen: on an infeasible
problem the iterates diverge along a ray, and that divergence *is* the
certificate. The KKT error has to grow. A rule that refuses to restart onto a
worse point is a rule that fights the only mechanism by which infeasibility is
ever detected. Allowing one rewind per restart point keeps the infeasible
fixture near its old cost — 1,856 iterations — and gives back most of the
benefit with it: seed 3 returns to −407%.

That is why the restart rule has two candidates rather than three, and the
change is not made. What the trace does establish is that the anomaly is a
restart landing badly on one draw, not the float32 arithmetic: the point handed
over is a good one, and the refinement is at 3.5e-3 before anything goes wrong.

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
- **Multi-investment periods, and `Link.delay`.** See *The two the sweep left*
  below: both were silently mis-solved rather than merely unimplemented, which is
  why they are listed here at all.
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
asserted to leave the answer unchanged rather than assumed to, given how far a
warm start can move the iteration count on the dense instances recorded above.

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
Ramp limits are *built* in `Lopf` and refused only here, where they would have to
carry start-up and shut-down ramps against the binary status; the detection is
shared, so the two cannot drift apart again — see "Ramp limits and energy
budgets" below for the round where they had.

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

**Taps.** `tap_ratio` folds into the linear models as a plain multiplier,
exactly as PyPSA does it, so `Cycles.impedance` and the linear flow honour it.
The AC admittance is harder: an off-nominal tap makes `Y` asymmetric, and
`model = "t"` with a non-zero shunt needs a wye–delta conversion before `Y` is
built at all. Both were refused in `Admittance` for a while; both are modelled
now — see *The AC transformer model, and an assumption that was never made*.
`scigrid-de`'s 96 transformers all sit at `tap_ratio = 1` with `b = g = 0`, so
the T-model path there is inert either way.

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

The interesting part is how it escaped a codebase that refused off-nominal taps,
the T model, `Store`, `overnight_cost` and a dozen other things by name. **A
refusal needs a code site, and an attribute nothing reads has none.** Every other
gap here was found at the moment some code had to decide what to do with a value;
this one had no such moment. Searching for attributes the schema declares and the
port never mentions would have found it, and nothing else in the process would.

### PyPSA's own two models disagreed, until 1.3.0

Worth knowing before concluding either is broken. Through 1.2.4 `n.lpf()` applied
the shift and `n.optimize()` did not: its Kirchhoff row was `Σ x_l s_l = 0` with
no shift term, so the optimised flows were **identical** at 0° and 30°.

So the LOPF here was never wrong — it matched PyPSA including this omission — and
`phase-shift` pinned both halves: that the linear flow honours the shift, and
that the optimisation does not.

**PyPSA 1.3.0 closed it**, and the pin bump is where this port had to follow.
`define_kirchhoff_voltage_constraints` now builds

```
Σ_l C_lk (x_l s_l + φ_l) = 0        (φ in radians, transformers only)
```

so the shift moved from being absent to being a constant on the right-hand side,
and a port that kept the old row would have gone on returning the unshifted
dispatch against a golden that no longer holds it. That is the whole compatibility
break of the release for this repository — see *PyPSA 1.3.0* below.

The AC path refused the shift for a long time — `Y` needs `exp(jφ)` on one
off-diagonal and its conjugate on the other, which is asymmetry rather than
scaling — and the golden recorded an answer the port declined to compute. It is
now modelled; see *The AC transformer model* below for what that turned out to
cost.

## Ramp limits and energy budgets: the same shape, twice more

The sweep that found `phase_shift` — enumerate the attributes the schema declares
as input, subtract those the sources ever name — has now produced four confirmed
silent wrong answers. `active` and `phase_shift` were the first two. These are
the other two, and both were verified against PyPSA before a line was written.

**Ramp limits.** `ramp_limit_up` and `ramp_limit_down` bound how far a unit's
output may move between consecutive snapshots. They were named in this port only
inside `UnitCommitment`'s refusal, so a plain LOPF over a ramp-limited network
solved the **unconstrained** problem and reported `Optimal`. Not one pre-existing
fixture sets a ramp limit, `scigrid-de` included, so no golden could see it.

**Energy budgets.** `e_sum_max` and `e_sum_min` cap a generator's energy over the
whole horizon. They were mentioned **nowhere at all**. On the purpose-built
fixture the dropped rows under-price the answer by 23,280 — 7,320 against 30,600
— which is the largest discrepancy the sweep has turned up.

The failure mode is the same one the CO2 cap section names: *a dropped
restriction is indistinguishable from an absent one to an inequality*, so the
answer comes out below the truth reporting `Optimal`. And the reason all four
escaped is the same too — each defaults to something inert (`NaN`, `±inf`,
`True`), so nothing about an unset one is visible, and none had a code site at
which a refusal could have been written.

### Three things the formulation turned on

**The first snapshot is not like the others.** There is no `p(−1)`, so `t = 0` is
bounded against `p_init` instead. Its default is NaN and PyPSA masks the row out
entirely rather than reading it as zero — the difference between "starts wherever
it likes" and "starts shut down", which at a limit of 0.1 is 90% of the rating.
`p_init` is therefore not a separate gap: it has no effect on anything except
these rows. It is reached through `up_time_before` (default 1), and a unit
declared down gets `p_init = 0` *and* a prior status of zero, which pins `p(0)`
to exactly 0.

**Extendable units ramp against a variable.** Where the capacity is a decision,
the limit multiplies the capacity column rather than the `p_nom` in the file.
PyPSA writes the fixed case as a `p_nom` of zero plus a capacity term; following
that construction keeps the two comparable term by term.

**The budget uses a third weighting column.** `e_sum` sums `p` against
`snapshot_weightings.generators` — the column the emissions cap uses, and not the
`objective` column already in scope wherever the row gets built. Every other
fixture holds all three at 1.0, which is precisely where reading the wrong one
cannot be seen; `energy-budget` sets them apart so that it can, and the wrong
column gives 28,660 against 30,600.

### A wrong comment is worse than no comment

`UnitCommitment`'s ramp refusal swept `static.contains` over the four attributes,
under a comment asserting they are "genuinely `varying: false`" — written to
explain why the neighbouring `stand_by_cost` guard needed a per-snapshot sweep
and this one did not. Two of the four are `static or series`. So a
`generators-ramp_limit_up.csv` with no static counterpart passed the guard
untouched, reproducing one block below it the exact hole its neighbour existed to
close. Both now read through `Ramps.limited`, which resolves per snapshot.

### Both fixtures needed reshaping, and the second one twice

A flag that is set but never tight tests nothing. `ramp-limits` gives every path
its own entity — static limits, a series limit, `p_init`, `up_time_before = 0`, an
extendable unit and a Link — and each was checked by removing it and confirming
the objective moves.

`energy-budget` was harder, in a way worth keeping. At a flat price a budget
fixes *how much* a generator produces and leaves *when* entirely free, so the
first attempt matched the objective exactly and disagreed with PyPSA on every
cell of the dispatch. Varying two of the three prices was still not enough: with
`must` flat, its floor could slide between snapshots at exactly compensating
cost, and the port found the other of two genuinely tied vertices at 30,500.00
apiece. Pricing *when* `must` runs closes it — nudging any of the twelve costs by
1e-4 now moves the dispatch by exactly zero, which is the check to run rather
than assume.

## Committable in a linear program: the one that erred the other way

The fifth finding, and the first that is not a schema-sweep result — `committable`
*was* read, twice, just never where it mattered. `Expansion.reject` refused a unit
that is committable **and** extendable, and said in its comment that
`UnitCommitment` builds commitment without expansion while `Lopf` builds expansion
without commitment. `UnitCommitment` reads the flag properly. Nothing decided what
`Lopf` should do with a unit that is *merely* committable, so it built the columns
as though the flag were absent.

On the `unit-commitment` fixture that costs **18,500 against PyPSA's 17,000**,
reported `Optimal`.

### Dearer, not cheaper

Every other finding here under-priced. This one over-prices, and the asymmetry is
the point: **ignoring the status does not relax the problem, it replaces it.**

- `p_min_pu` stops being a floor that applies *while the unit is on* and becomes
  one it can never leave. `mid` is pinned at its 30 MW minimum through four
  snapshots where the commitment solution has it off. On a network whose load
  falls below the sum of the minima, a feasible problem becomes **infeasible**.
- `start_up_cost`, `shut_down_cost`, `min_up_time` and `min_down_time` are dropped
  outright, which pushes the other way.

So "the LP is a relaxation of the MILP, therefore a lower bound" — the reasoning
that would make this look safe — is simply false here. The LP is not a relaxation
of this MILP; it is a different problem.

### One refusal, not three fragments

`Lopf` now refuses every committable entity through `Commitment.reject`, and the
two narrower checks are gone: `Expansion`'s committable-and-extendable case and
`Ramps`'s committable-and-ramp-limited one. Both were correct and both were
unreachable once the general case is refused, and each described a different
fragment of the same gap. The fragment nobody had written down was the one that
returned a number.

A unit flagged committable with every commitment attribute left at its default
genuinely has the same optimum either way, and is refused too. Deciding vacuity
means getting six attributes right at once, several of them `static or series`,
and being wrong in the permissive direction returns a plausible number instead of
an error. A spurious refusal is loud and has two ways out — `UnitCommitment`, or
clearing a flag that was not doing anything. Being wrong quietly has none.

## The two the sweep left, and what "known gap" was hiding

Re-running the schema-minus-source sweep after the first four fixes produced a
long list that was mostly noise — `Expansion` builds `p_nom_max` and
`s_nom_extendable` by interpolation, so a literal search cannot see them — and
`terrain_factor`, `v_ang_min`/`v_ang_max`, `p_nom_set`, `build_year`, `lifetime`
and `Generator.weight` get **zero hits in PyPSA's own optimiser**, so they cannot
change a LOPF answer at all. `Carrier.max_growth` is real but `define_growth_limit`
returns immediately unless the network is multi-period.

Two survived, and both were confirmed against PyPSA before a line was written.

**Investment periods, which is the uncomfortable one.** Multi-investment periods
had been on the list of known gaps all along. What nobody had checked is what
happened when you gave the port one anyway: `investment_periods.csv` sat in the
reader's set of non-component files and *no code read it*, so a multi-period
network arrived at the builder indistinguishable from an ordinary one. On a
two-period network whose cheap generator has `build_year = 2040`, PyPSA pays
**17,000** — the expensive unit carries the whole of 2030, because the cheap one
does not exist yet — and this port returned **2,000**, running it ten years
before it was built, reporting `Optimal`.

So "known gap" and "silently wrong" had been the same thing. **A gap is only
honest if it is loud**, and this one had nothing to be loud at: the same
"a refusal needs a code site" problem as `phase_shift`, one level up — an entire
*file* nothing read rather than an attribute.

The reader compounded it. With `period` and `timestep` columns and no `snapshot`
column, the label came from the first column after the index, so four snapshots
read back as `2030, 2030, 2040, 2040` — two pairs of duplicates — and `timestep`
was parsed as a weighting.

**`Link.delay`.** Energy entering a link at `t` leaves at `t + delay`, and
`cyclic_delay` decides whether what is in flight at the end of the horizon wraps
or is lost. Default 0, inert unless set, no fixture setting one: the same shape
as the four the sweep found. With the only load at the first snapshot and the
import delayed by one, PyPSA pays **9,000** for local generation where this port
delivered the import instantly for **500**. Eighteen times.

### Refused, and why not implemented

Neither has a conservative reading. Ignoring periods drops the build-year
restriction, which makes the answer cheaper, while the discounting and period
weightings push the other way — the error does not even have a reliable sign.
Multi-period is a feature with its own goldens, not a fix.

`Network` gained `investmentPeriods`, read from the file that was being skipped,
and `Periods.reject` refused on it from both `Lopf` and `UnitCommitment` — a
single-bus multi-period network reaches the second entry point and not the first.
Delayed links were refused in `Lopf`, which is the only model that builds a Link
at all. Both refusals have since narrowed: `Lopf` models multi-period dispatch,
and `UnitCommitment` carries its own refusal rather than sharing that one, since
what it refuses is now a network `Lopf` accepts.

Both fixtures keep PyPSA's own answer in the goldens, so the refusals are
evidenced rather than asserted, and both refusals have a test that they are *not
gratuitous*: solving each network the way the port used to gives an answer below
PyPSA's, which is the benefit the refusal buys.

Both have since been implemented — `Link.delay` in *Delays, and the one refusal
that was cheaper to lift than to keep*, and multi-period in *Investment periods,
and a ledger entry that became a gap* below.

### The model layer could not hold a multi-period network, and said so

`investment-periods` was the first golden the model-level suites skipped. Its
snapshots are `(period, timestep)` pairs; `Network` held a flat list of labels,
so the CSV could not round-trip and the netCDF export has no `snapshots_snapshot`
dataset to read. The manifest carries a `multi_period` flag and `GoldenNetwork`,
`RoundTrip` and `NetCdfReader` skipped on it — by data, so a second such fixture
would be covered automatically, and with the reason stated in each rather than a
name in a list. That skip was the model-layer half of the same limitation
`Periods.reject` enforced at the solve layer.

All three cover it now; see below. The flag stays in the manifest as a
description of the fixture, but nothing skips on it.

## The sweep itself, made permanent

Everything in the three sections above came out of one procedure — enumerate the
attributes the schema declares as input, subtract the ones the sources ever name,
and look hard at what is left. Six confirmed silent wrong answers, the largest
under-pricing an objective by 23,280 while reporting `Optimal`.

It was a throwaway grep, run twice. Which is precisely the position
`GapRefusalSuite` was written to fix one level down: an audit that proved the
state of the tree on one afternoon and left nothing behind. Nobody is going to
remember to re-run it when PyPSA 1.3 adds a column, and *an attribute nobody has
looked at* is the shape of all six.

`SchemaSweepSuite` is that sweep as a test. It fails on three things:

- an input attribute no source names and no ruling covers;
- a ruling whose attribute the sources have started naming, which means it was
  implemented or refused and the ruling now asserts the opposite;
- a ruling naming an attribute the schema no longer declares.

### The residue is committed, with reasons

*Counted against PyPSA 1.2.4, which was pinned when this was written. The 1.3.0
bump moved every figure here and narrowed the interpolation rule; see* PyPSA
1.3.0, and a pin that stopped being free *below. The shape of the argument is
what this section is for, and that did not change.*

323 of the 422 attributes are inputs. 253 are named outright and another 24 are
built by interpolation — `Expansion` reads capacity bounds as
`s"${attribute}_max"`, so `p_nom_max` is read by code that never writes it down.
Resolving those in the sweep rather than the ledger matters: filing the
mechanism's blind spot as a human judgement is how a check starts lying.

That leaves **46**, each with a line saying why it cannot change an answer.
Nine belong to `Process`, which `Lopf.rejectUnhandled` refuses outright, so its
attributes are unreachable rather than ignored — the distinction that made
`investment_periods.csv` a bug and makes this not one. `Process.discount_rate` is
one of those nine, ruled on the group's grounds; the other **six**
`discount_rate` entries are ruled separately, read only when an annuitised
`overnight_cost` is, which `Expansion.reject` refuses on every network. The
remaining thirty-one are inert in PyPSA's own optimiser, reached under a name
that arrives in the data — `Carrier.co2_emissions` is read through
`GlobalConstraint.carrier_attribute` and appears nowhere in this port — or
descriptive.

The counts move when the port does: implementing multi-period took seventeen
entries out of the ledger and into the named set, because `Periods` now reads
`build_year` and `lifetime` rather than the refusal covering them. A count in
prose beside a check that computes it is a claim with a short shelf life, which
is the reason this paragraph names the numbers and the suite prints them.

### What it does not do, and one attribute it misses

It checks that some code *names* an attribute, not that it handles it correctly.
That is what the goldens are for, and being named is a far weaker claim.

It matches bare attribute names rather than `(component, attribute)` pairs,
because the sources refer to attributes by bare name: `table.float("x", id)`
reads a Line's reactance and nothing in the call says so. One attribute is masked
by that today. `Bus.x` is a map coordinate, and it is invisible to the sweep
because `Line.x` is a reactance the sources quote constantly — it cannot even be
ruled on, because the stale-ruling rule would fire the moment the line was added.
`ShuntImpedance` is masked wholesale for the same reason, and survives it only
because `Lopf.rejectUnhandled` refuses the component outright.

### `Link.cyclic_delay`, and a comment that overclaimed

Writing the ledger turned up one thing the sweep alone would not have. The
docstring on `rejectDelayedLinks` said `delay` and `cyclic_delay` were "both
checked", and that `cyclic_delay = false` was the default. Neither is true: only
`delay` is checked, and the default is `True`.

It is not a wrong answer. `cyclic_delay` decides what happens to energy still in
flight at the end of the horizon, nothing is ever in flight while `delay` is
zero, and every network that could observe it is refused. But the comment
asserted a check that does not exist, which is the same failure as a gap on a
list with no code site — and it survived precisely because no procedure ever
asked the question of that attribute by name.

Its ruling is now gone rather than reworded: `Delays` reads the attribute, so the
sweep's stale-ruling rule would have fired on it. That is the rule working — the
ledger entry that said "unreachable, because refused" stopped being true in the
commit that made it reachable.

## Delays, and the one refusal that was cheaper to lift than to keep

`Link.delay` was refused on the grounds that the bus balances pair `bus0` and
`bus1` inside one snapshot. That is a description of the code, not of the
difficulty: shifting the receiving term changes which *column* a balance row
references and nothing else. No new variable, no new row, no change to the row
ordering `Sclopf`'s copy depends on. It came to one new file and eleven lines in
`Lopf`.

`Delays` implements it. `Delays.reject` survives, but only for the two shapes
PyPSA's own consistency check refuses.

### Three things the transcription turned on

**The delay is elapsed time, not a snapshot count.** The declared unit is
"snapshot weighting units" and the weighting is `snapshot_weightings.generators`
— the same column the energy budgets read, and neither the `objective` one
beside it in the file nor the `stores` one the storage balances use. Writing
`tau(t)` for the time at which snapshot `t` begins, the source of `t` is the
latest `s` with `tau(s) <= tau(t) - delay`, so a delay landing between two
boundaries rounds *down* to one. PyPSA logs a warning and rounds the same way;
this follows it rather than refusing, since the contract is to agree with that
implementation.

**The arrival mask is not a zero.** Without `cyclic_delay`, the first targets
have no source at all. PyPSA still computes an index for them — it clips the
`searchsorted` result into range — and then discards it through a boolean mask.
The index it discards is `0`. An implementation that dropped the mask and kept
the index would deliver the *first* snapshot's inflow to every target before the
delay elapsed, which on `link-delay-wrap` costs 24,000 against 400.

**Efficiency is read at the arrival snapshot.** In
`define_nodal_balance_constraints` only `p` is shifted; `coeff` stays indexed by
the target. So a time-varying `efficiency` applies at the snapshot the energy
arrives in, not the one it left in.

### PyPSA disagrees with itself about `p1`, and it does not matter here

`_apply_delay_shift` shifts `-p0 * efficiency` as a *product* when the results
are written back, so `links_t.p1` uses the departure snapshot's efficiency while
the constraint used the arrival's. On `link-delay-wrap` the balance row at bus
`b` receives 100 MW and PyPSA reports `p1 = -200` for the same snapshot.

Recorded rather than smoothed over, because it decides which of the two to copy.
The constraint is where the objective and the dispatch come from, and
`LopfResult.dispatch` returns `p0` — nothing in this port reads `p1` at all. If
something ever does, this is the paragraph that says the two conventions are not
interchangeable.

### `link-delay` could not gate any of it

The fixture that justified the refusal proves the refusal and nothing more. Its
delay makes the only route to the load useless, so every number in it is
reproduced by an implementation that deletes the link outright — the objective,
the dispatch, and a link flow of zero at all four snapshots.

`link-delay-wrap` is built so that each of the three readings above is a separate
visible failure. Two links on disjoint sub-networks: `wrap` is cyclic and serves a
load at the first snapshot from the *last* one, and `lag` is not, and serves a
load at the third from the first. `generators` is weighted 2.0 against an
`objective` of 1, 1, 3, 1 — so a snapshot-counting reading reaches the wrong way
back *and* pays a different price for it, 3,400 rather than 1,400. `wrap`'s
efficiency is 0.5 at the arrival snapshot alone, so 200 MW leaves for 100 to
arrive and a departure-efficiency reading sends 100 and pays half.

Both loads are single-snapshot, which leaves the whole schedule forced rather
than merely optimal: an arrival at a bus with no load has to be zero, so every
flow the fixture does not want is pinned at zero too.

### The error had no reliable sign, which is why it was a feature

On `link-delay`, ignoring the delay was eighteen times too cheap — 500 against
9,000. On `link-delay-wrap` it is too *dear*: 2,200 against 1,400, because the
shift is what lets the energy leave in a cheaply weighted snapshot. So there was
never a conservative reading to fall back on, and "solve it as instantaneous and
note the caveat" would have been wrong in both directions on two fixtures of the
same feature.

### Two refusals kept, and why they are not gaps

`n.optimize` runs `consistency_check` before it builds, and
`check_dispatch_delays` is strict by default, so PyPSA has no answer for a
negative delay or for one at least as long as the horizon. `Delays.reject`
refuses both, which keeps the two implementations agreeing about which networks
*have* an answer — the same contract as agreeing about the answer.

PyPSA's optimiser would not refuse either on its own, which is what makes the
check worth transcribing rather than assuming: `_iter_balance_args` groups a
negative delay as immediate, and one longer than the horizon leaves every target
invalid. Silently instantaneous and silently inert, respectively.

The horizon is measured in weighting units too. On `link-delay-wrap` it is 8, not
4, so a delay of 5 solves and one of 8 is refused — backwards under a
snapshot-counting reading, and asserted in `LopfSuite` for that reason.

### The power flow deliberately does not shift, and nothing guards that

PyPSA's `lpf` and `pf` write `p{i} = -p0 * efficiency` within one snapshot and
never read `delay`; only the optimiser shifts it. So `LinearPowerFlow` and
`NewtonRaphson` go on ignoring it, and both say so at the call site. Applying it
there would agree with PyPSA's optimiser and disagree with PyPSA's power flow,
and the contract is the latter.

The first version of that comment claimed the two delay fixtures' `lpf` blocks
would fail if this changed. **They would not**, on two counts: neither fixture is
in `LinearPowerFlowSuite`'s network list, and — the decisive one — neither carries
a link `p_set`, so the injection is zero at every snapshot and shifting a zero
somewhere else changes nothing. A comment asserting a check that does not exist
is the same failure as a gap on a list with no code site, which this file already
names as a failure mode two sections up.

Guarding it needs a delayed link with a non-zero `p_set`, and that is **blocked
behind a gap found while trying to build one** — see below. Recorded as unguarded
rather than quietly left claiming otherwise.

### `<attr>_set` fixes dispatch, and this port reads none of it

Trying to give a delay fixture a link `p_set` turned the LOPF answer from 500
into 3,900. `define_fixed_operation_constraints` fixes a component's dispatch
variable to its `_set` attribute, and its docstring lists **Generator (p), Line
(s), Transformer (s), Link (p), Store (e), StorageUnit** — not just the two this
port refuses.

So `Generator.p_set`, `Link.p_set`, `Line.s_set` and `Transformer.s_set` are read
by PyPSA's optimiser and by nothing here: on the two-bus probe above PyPSA fixes
the link at 60 and pays 3,900, where this port leaves it free and pays 500. Same
shape as the six the schema sweep found, and the sweep cannot see it — `p_set` is
quoted in these sources for `Load` and for the StorageUnit refusal, so the
bare-name match reads it as handled everywhere. The documented masking limitation,
hiding a live defect rather than a harmless one.

Not fixed here. It wants its own fixture and its own golden, like every other one
of these, rather than being folded into a review-fix commit.

### A third port's delay crashed rather than being read

`delay2` and later are custom columns, not schema attributes — the same as
`efficiency2` — and `CsvReader` infers every numeric custom column as `Floats`.
`table.int` therefore threw `delay2 is Float, not an int` on an ordinary
three-port link. Loud rather than silent, but a crash where PyPSA has an answer.

Found by `DelaysSuite`, which exists because the multi-port suffix rule was
written against PyPSA's source and then exercised by nothing: both goldens are
two-port links, so `delayAttribute` was only ever driven with `bus1`. The suite
builds three-port networks through `CsvReader` and checks that `delay2` is read
under its own name, that `cyclic_delay2` defaults to wrapping when absent, and
that an unsuffixed `delay` still belongs to `bus1` alone.

## Investment periods, and a ledger entry that became a gap

Multi-period was the other half of what the schema sweep found, and the one that
stayed refused when `Link.delay` was implemented. It is modelled now: the model
layer holds a `(period, timestep)` index, and `Lopf` masks assets by build year
and weights costs by period.

### The index is two halves, kept as two fields

`Network.snapshots` holds the timestep labels and `snapshotPeriods` holds the
period each belongs to, one entry per snapshot, with the length invariant checked
in the constructor rather than assumed. That is the shape `snapshotWeightings`
already had, which is why it was chosen over a `Snapshot` case class: fifteen
call sites index snapshots positionally through `.snapshots.indices` and none of
them had to change.

The timestep half is **not unique** — `investment-periods` has timesteps
`0, 1, 0, 1` — so anything that treated `snapshots` as a key would now be wrong.
Keeping the halves in separate fields is what makes that a non-question; a joined
`"(2030, 0)"` string would invite parsing it back apart. `snapshotLabel` renders
the joined form for the one consumer that needs it, the manifest comparison,
because `str()` on a pandas MultiIndex entry is Python's tuple repr and that is
what the goldens record.

### The reader had two bugs, and the second hid behind the first

`readSnapshots` looked for a `snapshot` column and fell back to *column 1* when
there wasn't one. A multi-period file is `,period,timestep,objective,...` with no
`snapshot` column at all, so column 1 is `period`: four snapshots read back as
`2030, 2030, 2040, 2040`. And because the weighting scan took every other named
column, `timestep` was picked up as a fourth weighting — a column of `0, 1, 0, 1`
sitting alongside `objective`, `stores` and `generators`, ready to scale
something.

`investment_periods.csv` had the mirror problem: the labels were read and the
`objective` and `years` columns beside them dropped, on the stated grounds that
only a model pricing multi-period costs needs them. True while such a network was
refused; false the moment it wasn't.

### `objective` and `years` are not interchangeable

Two columns in one small file, and they do different jobs. `objective` is the
discount factor and multiplies the snapshot's own objective weighting — every
cost, and the nodal price is divided by the same product. `years` is how many
years the period stands for and scales a global constraint's emissions sum, which
is a quantity of gas rather than a cost to discount.

This repository has already been caught confusing `snapshots.csv`'s three
weightings with each other, twice. `Periods.objectiveWeight` exists so the LP
build and the price recovery cannot disagree about the first one: they were two
multiplications in two files, and a mismatch between them is invisible in the
objective and visible only in prices nobody was asserting.

### An inactive asset is pinned, not dropped

PyPSA masks an asset's variables outside `build_year <= period < build_year +
lifetime`. Here the column is bounded to `[0, 0]` instead of being removed, which
is the same restriction and keeps the column layout every other part of the model
— and `Sclopf`'s row-by-row copy — already agrees on. The bound replaces
`p_min_pu` as well as `p_max_pu`: a must-run unit that does not exist yet has to
be off, not sitting at its floor.

Storage units and stores are masked the same way, and this had to be said out
loud because they were not: `activeBounds` reached generators and branches only,
so a `StorageUnit` with `build_year = 2040` charged and discharged freely in
2030 and the objective came out *below* PyPSA's, reporting `Optimal`. PyPSA
masks all four storage variables and both store variables by `c.da.active` —
`define_operational_variables` and `define_spillage_variables` both pass the
mask — and the schema declares `build_year` and `lifetime` on seven components,
not two.

Pinning a column to zero is equivalent to PyPSA dropping a row only where the
row reads zero anyway, and the storage energy balance does not. It is a chain of
equalities across the horizon, and emitting one at every snapshot while masking
only the columns says two things PyPSA never says. At the first snapshot after a
unit *retires*, every column in the row is pinned, so it collapses to
`eff_stand · soc(t-1) = 0` — the unit is forced empty at its last active
snapshot, which is dearer or infeasible with nothing to say why. At the *start*
of a window the same row carries `state_of_charge_initial` and `inflow` on a
right-hand side whose left is all zeros.

So the rows are emitted over each asset's active snapshots, with the previous
state taken from the previous *active* one. That is
`m.add_constraints(..., mask=active)` and
`soc.where(active).ffill.roll(1).ffill` written out, and it is why the first
attempt at this — refusing the three cases where the collapse is visible — was
the wrong shape: two of the three are start-of-window, so a retiring unit went
unrefused *and* unmasked, and the third refused a network this port answers
correctly. `state_of_charge_set` moved inside the same loop, because
`define_fixed_operation_constraints` masks it by `active & ~isnull` and an
unmasked row against a pinned column reads `0 = target`.

`lifetime` defaults to infinity and a `NaN` is handled explicitly, because every
comparison against `NaN` is false and the window would close in every period —
retiring an asset the file meant to keep.

### What the fixture pins, and what it cannot

`investment-periods` reproduces PyPSA's 17,000 exactly, with `old` carrying 2030
and `new` — `build_year = 2040` — carrying 2040, and both nodal prices matching.
Its period weightings are both 1.0, which is precisely where reading them or not
cannot be told apart, so three mutations do the rest: building `new` a period
early gives the 2,000 this port used to return; retiring `old` with a lifetime of
1 makes 2030 infeasible, which is how `lifetime` is shown to be read at all; and
halving 2040's `objective` weighting gives 16,500 rather than 8,500, with the
price at that period still 5.0 rather than 2.5.

### The ledger entry that turned into a gap

`GlobalConstraint.investment_period` was **ruled safe** by `SchemaSweepSuite`, on
the grounds that a multi-period network was refused outright. Narrowing that
refusal turned the ruling into a live gap in the same change: a CO2 cap scoped to
2040 alone would have been applied to the whole horizon, with no defensible sign
to the error. It is refused now.

That is the sweep doing the job it was built for, one step further along than
before — not catching an attribute nobody had looked at, but catching a ruling
whose *justification* expired. Eighteen `build_year`/`lifetime` entries left the
ledger the same way, and `Carrier.max_growth` and `max_relative_growth` with
them.

`Process.build_year` and `Process.lifetime` had to leave too, for a different
reason: the sweep matches bare names, and `Periods` quotes both for the
components it does model. Masked exactly as `Bus.x` is by `Line.x`, and safe for
the same reason — `Lopf.rejectUnhandled` refuses any network carrying a Process.

### Delays became period-aware rather than re-refused

`Delays` documented that a multi-period network never reached it, because
`Periods.reject` ran first. That stopped being true here. PyPSA applies a delay
*within* each period — energy leaving the last snapshot of 2030 does not arrive
in the first of 2040, ten years later — so each period is its own horizon with its
own `tau`, wrap and validity mask, and the indices are offset back into the flat
array. The same walk, run once per period instead of once. `check_dispatch_delays`
takes the horizon as the **shortest** period's, which the refusal now matches.

### What is still refused, and why each is a formulation rather than a factor

Capacity expansion across periods is a different model: PyPSA gives each build
year its own asset, and *when* to build interacts with the activity window and the
discounting. Growth limits between periods only bind on that. Per-period storage
cycling closes the wrap at each period's last snapshot rather than the horizon's,
which is a different set of energy-balance rows. And unit commitment is refused on
a multi-period network specifically — `Lopf` accepts one and `UnitCommitment` does
not — because commitment chains minimum up and down times across a flat horizon,
and a unit outside its build year has no status there. Pinning its dispatch to
zero, which is what `Lopf` does, would leave the status variable free to sit at 1
and collect a start-up cost for a unit that was never built.

Two row families are built once over the whole horizon rather than once per
period, and an asset whose activity window is not the whole horizon changes rows
this model does not rebuild — which is where pinning a column stops being enough.
The **cycle basis** is computed on the whole-horizon topology, while PyPSA calls
`n.cycle_matrix(investment_period=period)` per period: a line built in 2040 has
its flow correctly pinned to zero in 2030 and its 2040 cycle row still imposed
there, collapsing to `x_A·f_A + x_B·f_B = 0` over what is really a radial path.
**Ramp rows** are masked by `c.da.active` in PyPSA and not here, so a unit would
enter the period it is built in at a ramp from zero. A partly-built asset in
either is refused; a partly-built passive branch in *no* cycle is not, because
then the pinned column is the whole of its masking.

The storage energy balance is the third family of this kind and is **not**
refused — its rows are emitted over the active snapshots instead, as described
above. That distinction is the whole of the judgement here: refuse where the
faithful version is a different model, implement where it is the same model with
its rows in the right places.

The staged line build is the canonical multi-investment case, so this is not a
corner. `investment-periods` is single-bus because it is the one fixture, not
because branches are unusual — which is exactly why the gap cases are mutations
of it that add the second bus rather than a note that nothing exercises it.

## The AC transformer model, and an assumption that was never made

Off-nominal taps, phase shift and the T model were three separate refusals in the
AC path, each with a golden recording a PyPSA answer the port declined to
compute. They are now modelled, and the interesting part is how little it took.

**The risk was assumed to be the solver.** A phase shift makes `Y` asymmetric —
`Y01` and `Y10` differ by the conjugate of `exp(jφ)`, not by a scale factor — and
the whole plan was built around finding out whether Newton-Raphson could carry
that. So it was checked *before* any admittance code was written, three ways:

- `Admittance` stores a full dense `n × n` pair of arrays and accumulates `(i,k)`
  and `(k,i)` as separate writes. Nothing keeps a triangle, nothing mirrors.
- Every consumer — the mismatch equations and all four Jacobian blocks — indexes
  `(i,k)` in the general form.
- The Jacobian was **already** asymmetric, `∂P/∂|V|` and `∂Q/∂θ` differing, which
  is why `Lu` exists and `Cholesky` is used only by the linear flow.

Then measured rather than concluded: a throwaway spike implementing PyPSA's
`calculate_Y` reproduced its bus voltage angles to **3.3e-16** on `phase-shift`
and **1.0e-17** on `transformer-levels`, first attempt, with no change to the
solver. The refusals had been guarding an assumption the code did not make.

That is worth recording as a general point. **A refusal can outlive its reason**,
and nothing about it says so — it goes on reading as a considered limitation long
after the limitation is gone. The way to find out is to spike the thing the
refusal is protecting and see what breaks, which costs an afternoon and is
cheaper than the feature it defers.

### The formulation

Per branch, with `τ = tap_ratio` on the side `tap_side` selects and 1 on the
other:

```
Y00 = (y_se + y_sh/2) / τ_hv²        Y11 = (y_se + y_sh/2) / τ_lv²
Y10 = −y_se / (τ_lv τ_hv e^{jφ})     Y01 = −y_se / (τ_lv τ_hv e^{−jφ})
```

A Line declares neither attribute, so both ratios are 1 and every expression
collapses to the plain pi model it had before.

The T model is a wye–delta conversion applied to the per-unit values *before* `Y`
is built, as PyPSA does in `apply_transformer_t_model`. With the two series
halves equal the general `summand / z_i` form collapses:

```
z' = z + y z² / 4                    y' = 4 y / (z y + 4)
```

Derived rather than transcribed, and checked against the general form in a test
rather than only through a converged solve — an error there could hide inside the
iteration. It applies only where the shunt is non-zero: `1/y` is the third leg of
the wye, so a transformer with no shunt has no T to convert and PyPSA masks on
exactly that condition.

### `tap_side` is the part that looks interchangeable and is not

`transformer-taps` has one transformer tapping the HV side and one the LV side,
because a model that applied the tap to whichever end it happened to pick would
reproduce one and not the other. It also has one combining a tap *with* a phase
shift, which is the case each feature could get right alone and still get wrong
together.

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

### Where the time actually goes: 55% sparse, 45% dense

The paragraph above reasons from a whole-solve number that SpMV "is precisely the
workload `Kernels` exists to move". That is the standard expectation for a
first-order method, and it is half right in a way that changes what to do first.

`KernelSplit` runs the real solve with a `Kernels` decorator that attributes wall
clock to each of the eight operations. On `scigrid-de` at 1e-4 — 60,552 variables,
23,688 rows, 14,848 iterations:

| operation | share | calls | per call |
| --- | --- | --- | --- |
| `spmv` | 54.5% | 29,930 | 105.5 µs |
| `axpby` | 16.4% | 133,660 | 7.1 µs |
| `squaredNorm` | 11.5% | 29,724 | 22.4 µs |
| `primalStep` | 9.8% | 14,848 | 38.3 µs |
| `dot` | 3.3% | 14,848 | 12.9 µs |
| `copy` | 2.8% | 29,839 | 5.5 µs |
| `dualStep` | 1.6% | 14,848 | 6.2 µs |
| `scale` | 0.1% | 928 | 6.7 µs |

**Sparse 54.5%, dense 45.5% — of time inside `Kernels`.** That qualifier is not
pedantry: the two shares are shares of the eight timed operations, not of the
solve, and the harness measures the difference rather than assuming it away. The
timed operations are 97% of the solve here, so the numbers are nearly the same
either way; three reviews still caught this section stating one and meaning the
other, because "nearly the same" is a measurement and not a definition.

SpMV is the largest single operation by a wide margin and is not the majority.
Nearly half the kernel time is six operations that are flat loops over contiguous
`Array[Double]`.

That reorders the acceleration work. A device backend has to move SpMV, which is
the hard kernel and the one the Cyfra spike existed to answer; the other half is
on the CPU, without a driver.

There is no SIMD in the *source* of this tree — `ScalaKernels` is scalar `while`
loops by design, being the correctness oracle. There is a great deal of it in the
generated code, which is the thing this section originally got wrong: at 3× on
the dense operations the arithmetic gives 54.5 + 45.5/3 = 69.7%, or 1.43×, and at
2× it gives 1.29×. Neither happened. The measured answer is about **1.2× per
iteration** and between **0.31× and 1.22× end to end**, because most of those
loops were already vector instructions, only the reductions were not, and the
reassociation that makes those fast can cost iterations — see *SIMD: 1.2x per
iteration everywhere, and at best a wash at four lanes* below.

==On trusting these numbers==

The instrument reads `System.nanoTime` twice per call, and the probe measures the
interval between two reads: **17.8 ns** on this machine. That interval is the
bias charged to each operation — the second read's overhead before the sample
plus the first's after it — rather than the wall-clock cost of the pair, which is
larger. Against `scigrid-de`'s cheapest operation at 5.5 µs that is 0.3%, and
0.08% of the run — the shares are safe.

They are *not* safe on a small network, and the tool prints the overhead so this
is visible rather than assumed: on `ac-dc-meshed` `axpby` runs in about 40 ns, so
nearly half of what would be attributed to it is the clock. Those runs agree with
this one at 52–53% sparse, which is reassuring and is not evidence — the
distortion falls on the operations with the most calls, which is the direction
that would flatter the dense share.

The split is stable run to run on `scigrid-de` (52.0%, 52.5%, 54.5% sparse across
three runs) and the shares are what to read; absolute times are context.

### SIMD: 1.2x per iteration everywhere, and at best a wash at four lanes

Measured with the harness and the reporter both repaired — the earlier revisions
of this section were measuring an asymmetric warm-up, then a drift estimator that
had the iteration confound folded into it.

The x86_64 rows come out of the CI job, with its counterbalancing, its same-width
references and its control-spread test. The aarch64 row does not: there is no
aarch64 runner, so it is a laptop measurement with none of that machinery behind
it, and it is marked as such in the table.

==What C2 already does==

HotSpot's SuperWord pass auto-vectorises a plain `while` loop over contiguous
arrays, so `axpby`, `scale` and `dualStep` were vector instructions before any of
this — confirmed under `-XX:-UseSuperWord`, where the reference `axpby` slows
from 7.0 to 8.6 µs. What C2 will not do is reassociate floating-point addition,
so a reduction stays scalar however simple it looks. `dot` and `squaredNorm` are
where the win is, and `primalStep` joins them because its clamp is a
data-dependent branch per element.

==The whole result, in one table==

Pooled over seven CI sweeps — nineteen same-width comparisons — plus one local
measurement on the only aarch64 machine available:

| width | iterations against the reference | **end to end** | n | basis |
| --- | --- | --- | --- | --- |
| 2, aarch64 | same | **1.11x** | 1 | local |
| 2, x86_64 | same | about **0.34x** — samples 0.31x–0.38x | 6 | CI |
| 4, x86_64 | **+25.4%** | **0.86–1.03x** (median 0.98) | 9 | CI |
| 8, x86_64 | same | **1.15–1.22x** (median 1.20) | 4 | CI |

Ranges rather than point estimates, because every point estimate this section has
published has been a single run and several have been withdrawn. The spread
within each *CI* row is wider than the differences the earlier revisions were
arguing about; the aarch64 row is one local run and has no spread to compare.
The *signs* are what is stable, and they are stable within each row except at
four lanes, where the row straddles 1.0. Across rows they are not: the same two
lanes give 1.11x on aarch64 and 0.34x on x86_64.

**And the ranges themselves are not tight — the two-lane one has now been missed
twice running, in opposite directions.** It was published as 0.32–0.36 from four
comparisons; the fifth came in at 0.38, above it, so it was widened to 0.32–0.38;
the sixth came in at 0.31, below that. Six comparisons, and the last two have
both been endpoints.

So that row is quoted as "about a third" rather than as a band that keeps
moving. A min–max over a handful of samples describes those samples; it does not
predict the next one, and continuing to widen it after each miss would be fitting
the description to the data one point at a time. Read the medians and the signs;
the endpoints are the least reliable thing here.

**No figure in the table has a correction applied.** The reporter compares the
control operations' spread against the size of the correction and withholds the
corrected figure when the spread dominates, and it withheld on every arm in the
table above. Corrections did survive on a few arms — the largest 1.028, on an
eight-lane `-all` run — and all of them are smaller than the spread between
sweeps, which is why pooling raw figures loses nothing here.

The per-iteration figures are remarkably flat away from two lanes: 1.19x to
1.23x on wall clock and 1.20x to 1.24x on time inside `Kernels`, across every
arm, width and coverage the sweeps have run. The end-to-end column is not flat,
and the whole of the four-lane gap between them is the iteration count.

==Four lanes costs 25.4% more iterations, and that is a real iteration count==

Reassociating a sum changes its rounding, and the lane count decides how the
partial sums are grouped, which moves the trajectory. Against the reference's
14,848 iterations, four lanes takes **18,624** — reproduced in all seven sweeps,
at native width and under `-XX:MaxVectorSize=32` on machines whose native width
was eight, and identical to the digit every time. It is the one figure in this
section that pooling did not have to turn into a range.

This was in doubt for two commits. The harness printed `primalStep` calls under
the heading `iterations`, and `Pdhg.step` calls it inside `while !accepted do`,
so the figure could have been line-search trials — which would have made it a
statement about the acceptance test rather than about convergence.

Four sweeps have run since the harness was repaired, and all four print both and
find them equal: 18,624 iterations and 18,624 trials, with the trial rate at
1.000 on every arm. The three before it printed one number and report the same
figure, so for those it is an inference rather than a measurement — four measured
and three inferred, against the seven the table pools.

The line search accepts essentially every step here, so the extra work is
genuinely extra iterations.

At about 1.22x per iteration against 1.254x the iterations, the arithmetic gives
1.22/1.254 = 0.973, near the row's median of 0.98. **On a four-lane machine this
backend is at best a wash and usually a small loss** — seven of the nine
comparisons land below 1.0, at native width and under `MaxVectorSize=32` alike,
and the coverage that widens all six operations behaves the same.

The two above 1.0 are both 1.03x, so nothing in the sample reaches the
per-iteration gain.

==Two lanes on x86_64 is about a third==

Against a reference at the same width, with matching iteration counts. In one of
the six comparisons the vector backend takes 31,025 ms where the scalar reference
takes 10,328; the six together run 0.31x to 0.38x, median 0.34 — about three
times slower throughout, which is the part that has never moved.

The same two lanes on aarch64 give 1.11x. Whatever the Vector API costs per
operation, x86's scalar and auto-vectorised paths absorb it and NEON's do not —
so a lane count says nothing about an outcome without the architecture beside
it.

==What the sweeps cannot settle==

**The runner's width is not stable.** The seven sweeps reported
`SPECIES_PREFERRED` of 8, 4, 8, 4, 8, 8 and 4 — four eights and three fours, in no
pattern. Any figure from this job belongs to the machine that run landed on,
which is why the width is swept explicitly rather than taken from whatever turned
up.

**The drift correction is often not usable, and the reporter now says so rather
than applying it anyway.** It compares the control operations' spread against
the size of the correction and withholds the corrected figure when the spread
dominates — which it did on every arm in the table above. An earlier revision
published a corrected number from a run it simultaneously described as too noisy
to correct, then withdrew it; this is that judgement made in code.

==Where this leaves it==

Opt-in, and the case for anything more is weak. It needs a JVM flag, it perturbs
the iterate trajectory, and across the machines measured it ranges from **0.31x
to 1.22x end to end** with no way to know which without running it. The kernels
are genuinely 1.2x faster per iteration and that is not the question a caller
asks.

`dot` and `squaredNorm` are **17.3%** of the time inside `Kernels` on the
reference arm of the sweep these figures come from — the share moves between runs
and machines, and the share table under *Where the time actually goes* gives
14.8% for the same two operations on an earlier one, which is why the run has to
be named. `spmv` at
40–55% is the ceiling that matters, and it is untouched.

==What the shares are shares of==

Every percentage here is a share of time inside `Kernels`, measured rather than
assumed: the timed operations are 96–97% of the solve, printed on every run.

The 12.7 s recorded for this configuration in the table above is still not
reproducible — about 5.4 s on aarch64, and 9.9–13.3 s across CI runners at their
native width, same 14,848 iterations. The width-limited references are excluded
from that range: `-XX:MaxVectorSize` throttles SuperWord on the scalar arm too,
so they are a different configuration.

Cold-versus-warm was the obvious explanation and is not it, since the two come
out within 1% of each other, so the discrepancy stays open.

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

The 110/20 kV types are Dyn5 with `phase_shift = 150°`. Phase shift and the T
model are both modelled now — see *The AC transformer model* — so what is left
here is narrower than it was: no golden exercises a standard type that carries
either. The fixture uses `160 MVA 380/110 kV`, which has no shift, and pins
`model = "pi"` to isolate type expansion from the conversion rather than because
the conversion is missing. The remaining piece is a golden over a Dyn5 type, not
the capability it would exercise.

## PyPSA 1.3.0, and a pin that stopped being free

The goldens are pinned, so they cannot drift; upstream can, and
`.github/workflows/pypsa-drift.yml` exists to notice. PyPSA 1.3.0 landed on
2026-08-19, a day before this bump, so the weekly cron had not yet run — the
release was found by running the drift process by hand against it.

`schema_drift.py` reported **43 changes that can move an answer**, all of them
from three upstream features, and the sweep reported **14 new input attributes**.
The attribute count went 422 → 454; the component set did not change.

### Only two networks moved, and both for the same reason

Solving all 22 golden networks under 1.3.0 before changing anything is what made
this a small change rather than a guess. Twenty of them returned the pinned
objective to 1e-6. The two that did not — `phase-shift` and `transformer-taps` —
were the two carrying a phase shift, and both came back **Infeasible**.

That is the release's one real compatibility break for this port, and it is
described under *Phase shift, and a bug with nowhere to live* above: the shift is
now a constant in the Kirchhoff row rather than absent from it. This port's row
gained the same term.

Both fixtures also had to have their shifts reduced, which is worth stating
because it is a fixture change rather than a code one. A shift forces a
circulating flow around the cycle, and past **10.044°** on `phase-shift` and
**10.007°** on `transformer-taps` that flow exceeds a transformer rating with
nowhere else to go. The old 30° and 12° are simply not solvable networks any
more.

`phase-shift` went to **9°** rather than the 10° that is also feasible, because
10° sits 0.04° from the edge and a fixture that close to infeasibility is one
solver tolerance away from failing for no reason. 9° is also a better fixture
than 30° was even setting feasibility aside: `t2` binds at its 400 MW rating, so the shift can no longer be
absorbed by re-routing alone and the **objective** moves with it — 8,524.43
against 7,800 unshifted. Every smaller shift is pure re-routing at constant cost,
which an objective-only comparison cannot see. `transformer-taps` went to **8°**,
where nothing binds and the flows still reverse; no shift moves that network's
objective before it becomes infeasible.

### The other two features are refused, not modelled

Both are inert at their defaults, which is exactly why nothing existing caught
them and why they need a refusal rather than a ruling alone.

**Maintenance scheduling.** `maintainable` turns an entity's availability into a
binary schedule — `maintenance_events` outages of `maintenance_duration`
snapshots, capped at `maintenance_pu`. Placing them is an integer decision, so it
is the same shape as commitment one attribute over, and `Commitment.reject`
refuses it on every component declaring the flag. Ignoring it errs **cheap**: the
entity stays available at every snapshot.

**Piecewise costs.** `marginal_cost`, `capital_cost` and `efficiency` may now be
piecewise-linear, which the schema records by widening their type to `static or
piecewise or series`. The breakpoints are not in that column — they are a file
beside the component — so a model that read only the column would price the
curve at a number the network does not use. Both readers refuse the file.

That refusal was wrong three times before it was right, and the three are one
mistake wearing different clothes: **it was written against what was in front of
me rather than against what PyPSA produces.** A name read out of the source, a
layer chosen without checking where the file is met, a format guarded because it
was the one I had looked at.

The first version matched a `_piecewise` suffix, taken from the sheet-name table
in `network/io.py` that maps `generators-marginal_cost_piecewise` to
`generators-marginal_cost_pw`. That table exists for Excel's 31-character sheet
limit and describes neither end of the CSV path; `_CSVExporter.save_piecewise`
writes `f"{list_name}-{attr}-pw.csv"`. The hand-written CSV in the test agreed
with the misreading and passed, which is precisely what
`GoldenNetworkSuite`'s opening paragraph says a hand-written fixture can only
ever do.

The second version had the name right and the *layer* wrong. It sat in `Lopf`,
keyed off a loaded series — but a piecewise file is indexed by breakpoint and
carries a two-row header (`name` over `attribute`), so `CsvReader` never gets far
enough to produce that series. It fails on the header or on a row count that has
no reason to match the snapshots, and both messages blame the snapshots for a
file that was never about them. The guard could not fire on any real export.

It now lives in `CsvReader`, which is what meets the file, and the fixture it is
tested against is written by `export_to_csv_folder` into `goldens/unsupported/` —
a directory for networks the reader must *turn away*, so that walking
`manifest["networks"]` does not try to load one. `piecewise_cost()` also runs
`n.optimize()` on the network before exporting it, because a badly built
breakpoint frame raises a `KeyError` about an index level while still writing a
file that looks perfectly plausible.

**And there was a third mistake, which is the second one again.** Moving the
guard out of `Lopf` cost something that site had for free: `Lopf` is downstream
of *both* readers, so one check covered both. A refusal in `CsvReader` alone
covers one of this port's two entry points, and `NetCdfReader` would have taken
PyPSA's `generators_pw_marginal_cost` — which is neither `<list>_i` nor
`<list>_t_*` — for a static column and failed on a length check blaming the
entity count for a frame indexed by breakpoint. The same misdirected diagnostic
the CSV refusal was written to remove, in the other reader. Both readers carry it
now, and `goldens/unsupported/` holds a real export in each format, because the
two spellings share nothing: `generators-marginal_cost-pw.csv` against a
`generators_pw_marginal_cost` variable over its own `breakpoint` dimension.

It holds one synthetic file besides, labelled as such in the manifest. The netCDF
guard detects on the whole `_pw_` family and reports only the value variables, so
that a renamed value variable or a partial export still refuses instead of falling
back into the length check — and `save_piecewise` always writes the value
variable, so nothing PyPSA produces reaches that branch. The alternative to a
fixture that says "no exporter writes this" was a guard nothing exercised.

The refusal is `UnsupportedNetworkFile`, not either reader's `MalformedNetwork`.
`goldens/binary/malformed` is what that type is for — a column shorter than its
index, a time unit CF does not define — and a piecewise export is none of those.
It is exactly what PyPSA meant to write; the reason it is refused is a fact about
this port. It is deliberately not a subclass, since that would keep every
existing `catch` working and let the two go on being confused.

**Optimisable phase shifts** are refused for the same class of reason: when
`phase_shift_min < phase_shift_max` the shift is a per-snapshot *variable* in the
row, not the constant this model puts there.

### Two things the release broke quietly

Neither is upstream's fault and both were found by the bump rather than by
review.

The sweep was **manufacturing** `Transformer.phase_shift_min` and
`phase_shift_max` and reporting them accounted for. `interpolated` was the
cross-product of every quoted identifier with `{_extendable, _max, _min, _mod}`,
the port quotes `"phase_shift"` for the linear flow, and the two new attributes
fell straight out of it. Its own scaladoc had recorded that this can only ever
mask, never report — invisible until the week upstream ships a name the
cross-product happens to have invented. The stems now come from
`Expansion.nominalAttribute`, which is the map that actually binds them.

`Variability.parse` tested `startsWith("static or series")`, which
`static or piecewise or series` does not satisfy. All nine widened attributes
carry `varying = true` so the fallback kept them right — but the fallback is the
weaker signal, and a piecewise attribute that did not vary by snapshot would have
been read as `Static` with its overrides silently dropped.

## The modeling layer, and the third backend

`prima-model` is names, expressions and duals; `LpSolver` was already the solver
abstraction and this does not replace it. A model compiles to an `LpProblem` and
is handed to a backend it does not name.

Four things it decides, none of them obvious:

**`Variable` and `LinearExpression` share a `Linear` trait rather than a
conversion.** The alternative, `given Conversion[Variable, LinearExpression]`,
needs `scala.language.implicitConversions` at every call site that writes a
model — a lot of import for the privilege of writing `x + y`.

**Terms are appended, not summed, until the model is compiled.** Summing on the
way in makes every `+` walk the expression built so far, so a bus balance over a
thousand generators becomes quadratic. `compile` collapses duplicates through an
insertion-ordered map, which also makes two compilations of one model
byte-identical — a hash-ordered one would make a golden-file comparison of a
built problem depend on iteration order.

**A coefficient that cancels to zero is dropped rather than stored.** A
structural zero would be equilibrated and multiplied like any other entry and
would change nothing but the cost.

**A maximisation is negated on the way in and everything that carries the
objective's sign is negated on the way out** — the value, the duals and the
reduced costs. Negating the value alone would report a price whose sign says the
opposite of what the model asked for.

### OR-Tools reports an unbounded problem as infeasible

`min -x` subject to `x >= 0` comes back `MPSOLVER_INFEASIBLE` from GLOP. Those
are opposite answers, and `SolveStatus` already sets the standard here: for
Prima, diverging without a certificate is an iteration limit and not an
infeasibility.

So `OrToolsSolver` does not pass the status through. On `INFEASIBLE` it re-solves
with the objective thrown away: a feasible region that exists means the original
was unbounded, one that does not means it really was infeasible. The cost is a
second solve on a path that has already failed, and the probe runs with the
disambiguation off, which is what stops the two from recursing.

### Writing a test whose duals are worth asserting on

The three-solver agreement fixture is a transport problem, and getting its
numbers to have a unique dual took two tries. With total supply equal to total
demand every supply row binds. With one plant's supply equal to one market's
demand a basic variable sits at zero. Either way the optimal dual is a face
rather than a point, the three solvers land on three different points of it, and
the objective still agrees — which is the same thing `network-lopf`'s nodal
prices do on the reference fixture, recorded above, and it is a property of the
problem rather than a disagreement. The fixture now has slack in the cheaper
plant and one binding row, and all three prices match to 1e-5.

### What was not done

`network-lopf` still keeps its own `(component, entity, snapshot) -> column`
map. It is the obvious first consumer of this layer and it is deliberately not
converted: the L2 model is gated on golden-file comparison against a pinned
PyPSA, so rewriting how it is built is a change whose only honest test is that
gate, and it should be made on its own rather than underneath something else.
What `Lopf.solve` and `Sclopf.solve` did take is an `LpSolver`, which is the
part that actually made them solver-dependent — and `SolverAgnosticSuite`
reaches PyPSA's objective through ojAlgo on the same model Prima solves.

## Known gaps

**Mixed precision stays opt-in, but no longer because of an unexplained
regression.** The −621% on one dense instance was the reason given, and the
ten-draw sweep shows it is that draw rather than the method: the median saving
at 1e-6 is 26% on macOS/aarch64 and 24% on Linux/x86_64. What is left is real and much smaller — at 1e-9 the hand-over is
break-even across the family, and on a dense instance a restart can land badly
enough to cost several times a cold solve. Both are properties of the instance
class rather than a defect waiting to be found. See '''The expensive part is the
tail''' and '''Where seed 3's refinement goes'''.

The step size the float32 pass settles on was the other named suspect, and it is
not the culprit: dropping it, halving it, tenthing it, and substituting the cold
solve's own final value all leave seed 3 between −363% and −972%.

**There is a GPU backend, it is correct, and it is far too slow to use.**
`Kernels` is the
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
  median of 1.4 ms thereafter.

  **What that twelvefold drop is and is not evidence for.** Each of the 25
  iterations opens a fresh `GBufferRegion` and re-uploads all five buffers,
  including `rowPtr`/`colIndices`/`values`, which a real solve would upload
  once. So the gap bundles JVM warm-up of the whole Cyfra path and first-touch
  device allocation together with any SPIR-V compilation, and on its own it does
  not attribute the cost to program building. The spike therefore also times
  building a *second* identical program after the path is warm: near zero says
  the first dispatch was warm-up, comparable to the gap says it was compilation.
  What the test establishes without qualification is that reuse works and stays
  correct across all 25 dispatches; the timing is informational, wall clock on a
  shared machine being too noisy to assert on.

  Buffer **residency** across dispatches — the matrix staying on the device
  while only `x` is rewritten, which is what `Kernels.allocate`/`upload`/
  `download` depend on — was the next thing to demonstrate, and `CyfraKernels`
  demonstrates it: one allocation holds every vector and both matrices for the
  life of a solve.
- **`limit` needs a static cap**, satisfied by the maximum row non-zero count,
  which is a plain Scala `Int` at construction time. A pathological matrix with
  one very long row makes every invocation's loop bound that long, though
  `takeWhile` still exits early.
- **The environment is not self-contained.** LWJGL's natives need explicit
  classifier dependencies, and the Vulkan loader and MoltenVK ICD come from
  Homebrew via `VK_ICD_FILENAMES`. `prima-cyfra` is therefore not aggregated
  into the root build — `sbt testFull` does not run it — since no CI runner is
  guaranteed a working Vulkan stack.

### The backend, and what it costs

`CyfraKernels` implements all eight operations and passes
`KernelContractSuite` unchanged — the first backend to test the claim that seam
was built on rather than repeat it. `CyfraSolveSuite` then runs whole solves
through it: `MixedPrecision` with the GPU as the reduced pass reaches the same
objective as the reference on `economic-dispatch`, `random-60x30` and
`random-200x120`, to the 1e-6 the caller asked for, because the refinement that
produces the answer is still fp64 on the host.

Two pieces of plumbing the spike did not need:

- **An allocation that outlives one dispatch.** Cyfra hands out an `Allocation`
  only for the duration of a callback, which is the right shape for a one-shot
  dispatch and the wrong one for a solver that allocates once and calls in a few
  hundred thousand times. `DeviceLoop` parks that callback on a thread of its
  own and feeds it through a queue. The thread is required rather than
  convenient: the command pool and descriptor-set manager come from a
  `VulkanThreadContext`, so one allocation is one thread's.
- **`writeArray` is not usable on this release.** It copies its byte buffer to
  the device and *then* fills it from the array, so it uploads whatever the
  fresh allocation happened to contain. `GBuffer(array)` does the same two steps
  in the right order, and the backend builds the byte buffer and calls `write`
  itself for the same reason.

**And it is three orders of magnitude slower than the CPU.** Microseconds per
iteration, fp32 in both cases, with the cost of starting the solve separated
out:

| instance | CPU fp64 | CPU fp32 | GPU fp32 | GPU setup |
| --- | --- | --- | --- | --- |
| economic-dispatch (21v) | 0.2 | 0.3 | **4,082** | ~0 |
| random-60x30 | 0.6 | 0.8 | **3,787** | 22 ms |
| random-200x120 | 2.2 | 4.3 | **3,679** | 41 ms |

Each figure is a slope, not a division. A single timed solve cannot separate
what an iteration costs from what starting one costs, and on the GPU the second
is a Vulkan instance, a logical device, the SPIR-V compilation of every program
and the first touch of every buffer. So each backend runs the same solve under
two iteration caps and the slope between the two points is what an iteration
costs — the intercept, reported alongside, is what it cost to get to the first.
The first version of this measurement divided the whole solve by its iteration
count, which reports a fixed cost as a per-iteration one; it happened to give
much the same answer, but only because the setup is small next to a two-second
solve, which is not something it demonstrated.

Two details of the method. Raising the caps until the CPU's difference clears
the clock does not work — both would then sit past the iteration where the solve
converges, and the two points would coincide — so the cheap backends run each
point two hundred times instead, which costs nothing on a backend that is
already microseconds. And the intercept is an extrapolation back from two
points, so where it comes out below zero the setup is simply smaller than the
run-to-run spread; `~0` says that rather than printing a negative fixed cost.

The GPU column moves by about ten per cent between runs, so read it as "about
four milliseconds" rather than as four significant figures.

The number to look at is not the ratio, it is that the GPU column barely moves
across a tenfold change in problem size. A cost that does not scale with the
work is not the work, and measuring the three kinds of operation over a
thousandfold range of vector length says where it goes:

| operation | 64 | 1,024 | 4,096 | 65,536 |
| --- | --- | --- | --- | --- |
| `copy` — a bare dispatch | 45 | 46 | 53 | 42 |
| `axpby` — dispatch plus a scalar | 251 | 251 | 220 | 225 |
| `squaredNorm` — dispatch plus a read back | 462 | 473 | 459 | 432 |

Flat, all of it, in microseconds. The arithmetic is free and the launch is
everything. A PDHG iteration is about seventeen of these, which is the 4 ms.

Three findings behind those numbers, in the order they matter:

- **A scalar costs five times a dispatch.** Every `GBuffer` Cyfra allocates is
  `Buffer.DeviceBuffer`, and there is no host-visible one to ask for, so writing
  `alpha` and `beta` means a staging buffer, a copy command buffer and a pending
  execution — 180 microseconds for eight bytes. Eight of the seventeen
  operations in an iteration carry a scalar, so this alone is a third of the
  cost. The DSL body cannot read a dispatch parameter, and the one escape from
  that, a uniform built from `Params`, handles `Int32` and nothing else on this
  release.
- **A cached program cannot be re-dispatched across a solve boundary, and why
  is not settled.** `economic-dispatch` solved twice on one `CyfraKernels` gave
  the right answer and then `NumericalError` at iteration two, with the primal
  still at the origin — silently, not as a failure. A second solve of a
  *different* shape was always fine, which is what pointed at the program cache:
  that path builds new programs. `uploadMatrix` rebuilds them now.

  What that establishes is the trigger and not the mechanism. The first guess —
  "a program dispatched against buffers allocated after its earlier dispatches
  were cleaned up" — is contradicted from inside a single solve, where
  `partialsFor` allocates its buffer on the first reduction, after every
  elementwise program has been built and dispatched, and that path is correct.
  So the guard covers the boundary the failure was reproduced at, and the
  narrower condition is open.

  Rebuilding costs less than it sounds: `VkCyfraRuntime` caches shaders on
  `SpirvProgram.shaderHash`, a digest of the SPIR-V, the entry point, the
  workgroup size and the binding tags, so an identically rebuilt program
  resolves to the same `ComputePipeline`. The spike's own control measures that
  directly — building a second identical program after the path is warm is free.
  What the twelve-solve test adds is narrower than it first claimed: it holds
  the per-solve cost flat, which rules out anything *accumulating*, and would
  not notice a constant per-solve pipeline build, because all twelve would pay
  it alike.

  None of this is a benchmark artifact: `BranchAndBound.solveWith` deliberately
  holds one set of kernels for a whole search and solves a relaxation per node,
  so every node after the first would have been wrong. What a long run *does*
  accumulate is buffers, because `Kernels` has no deallocation — a property of
  the interface, recorded in `CyfraKernels`'s own scaladoc, and the reason to
  cycle a backend rather than hold one indefinitely.
- **Batching the submissions changes nothing.** The interface invites the
  opposite conclusion — `Kernels` describes reductions as "the only
  synchronisation points on an asynchronous device", which reads as an
  instruction to record everything else and submit once. Recording 1, 4, 16 and
  64 operations before submitting gives 4,191, 4,178, 4,194 and 4,180
  microseconds per iteration. The cost is per dispatch, not per submission.
- **Batching the *dispatches* does help, by about half.** Sixteen dispatches
  chained into one `GExecution` cost 192 microseconds against 328 issued
  separately. That is a finding about the seam rather than about the device:
  `Kernels` is a call-at-a-time interface, so a backend behind it cannot use
  this. A batched seam is where a Cyfra backend would have to go — and halving
  4 ms still leaves it a thousand times the CPU.

So the honest reading is that this settles the *feasibility* question completely
and answers the value question in the negative for this release of Cyfra. What
would change it is not a bigger problem — the flat columns above say size is not
what the GPU is losing on — but a path that does not pay a command buffer and a
staging copy per operation. That is the same conclusion `HPC.md` reaches from
the other direction, and it is why the module is a spike rather than a
dependency.

What a GPU backend still cannot be validated against here is fp64, which no
accelerator in prospect offers at all, and a device timing on NVIDIA silicon.

'''The algorithmic half of the cuPDLP-C comparison needed no GPU.''' What made
cuPDLP-C worth reaching for was never that it runs on a device — it was that it
is an independent implementation of restarted PDHG, and every other backend
here is a simplex. A simplex is the right oracle for an answer and has no
opinion at all on the restart schedule, the adaptive step-size rule, or where
the termination test is applied, which are the parts of this solver that are
judgements rather than theorems and which decide whether it takes two hundred
iterations or twenty thousand.

OR-Tools ships PDLP, which is that implementation, and `prima-ortools` already
carried OR-Tools. Held to 1e-9 on both sides:

| instance | Prima | PDLP | ratio | host |
| --- | --- | --- | --- | --- |
| the six hand-written fixtures | 2–128 | 2–128 | **1.00** | both |
| economic-dispatch | 256 | 192 | 1.33 | both |
| infeasible | 1,088 | 768 | 1.42 | both |
| random-60x30 | 1,280 | 1,024 | 1.25 | both |
| random-200x120 | 6,464 | 4,288 | 1.51 | macOS |
| random-200x120 | 6,784 | 3,968 | 1.71 | Linux |
| random-600x400 | 35,392 | 32,832 | **1.08** | macOS |
| random-600x400 | 26,048 | 32,832 | **0.79** | Linux |

Identical on every instance that converges inside a few restart periods, and
inside a factor of two on the two that do not — on the largest, Prima is 8%
worse than the reference on one host and 21% *better* on the other, which is
the platform effect above rather than anything about either implementation.
`PdlpComparisonSuite` holds the ratio inside threefold either way — loose on
purpose, since the observed band is already 0.79 to 1.71 across two hosts, the
two have different presolves and different scalings, and an OR-Tools bump should
not fail a build. An order of magnitude would mean Prima's restart schedule had
moved.

Three details that had to be got right for the comparison to mean anything:

- **The tolerance must be set.** PDLP's default stops around 1e-5 where Prima's
  stops at 1e-8, and an iteration count at one tolerance says nothing about a
  count at another. `OrToolsSolver.pdlp(tolerance)` exists for that reason.
- **`termination_criteria`'s own `eps_optimal_absolute`/`eps_optimal_relative`
  are deprecated.** OR-Tools accepts them, applies them, and prints a warning to
  stderr that sbt makes easy to miss. The nested `simple_optimality_criteria`
  is the current spelling, gives an identical answer, and is silent.
- **A rejected parameter string is silently ignored.**
  `setSolverSpecificParametersAsString` returns `false` and leaves the solver on
  its defaults, so a typo would mean solving at a tolerance nobody asked for and
  reporting it as the one they did. `OrToolsSolver` checks the return.

One real difference, pinned rather than worked around: PDLP does not classify an
unbounded problem. GLOP reports `INFEASIBLE` for `min -x, x >= 0` and the
feasibility probe turns that into `DualInfeasible`; PDLP reports `NOT_SOLVED`,
and a PDLP probe cannot establish feasibility either, so the honest answer is
`NumericalError`. It does detect primal infeasibility.

The licensing question stands: Cyfra is LGPL-2.1 where the rest of this build is
Apache-2.0, which is why the backend lives in its own module — and the module is
still not aggregated into the root build, since no CI runner is guaranteed a
working Vulkan stack. `sbt primaCyfra/testOnly *` is how it is run.

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

**The MILP search is the naive one.** This entry used to read "No MILP … the
warm-start path a tree needs does not exist yet", and both halves had been false
for some time: `BranchAndBound` is 21K of it, `warmStart` defaults to `true`, and
unit commitment is built on it and gated on a PyPSA golden. A gap list that keeps
claiming a feature is missing is the same failure as one that keeps quiet about a
feature being wrong, and this one survived only because nothing re-reads a list of
things that are absent.

What is actually missing is everything that makes a tree *fast*: no cutting
planes, no pseudocost or strong branching, no best-first search, no node
presolve. The search is depth-first — chosen so a child warm-starts from its
parent, which is the whole reason a first-order method is attractive inside
branch-and-bound — branching on the most fractional variable, which is a separate
choice with a separate reason: a variable at 0.5 splits the feasible set in two
where one at 0.999 makes a child almost identical to its parent. On top of that
the pruning margin — necessary, see above, because the bound is inexact —
deliberately explores nodes an exact solver would cut.

The consequence that bites is the interaction with the entry below. A node whose
LP hits `maxIterations` is not a bound, so it cannot be pruned on and is counted
in `unprovenNodes`, and an incumbent found while any exist is reported `Feasible`
rather than `Optimal`.

**"Feasible" is weaker than "the optimum without a certificate", and the
difference matters.** Two paths lose a subtree outright rather than merely leaving
it unproven: a node whose relaxation is integral but never converged records no
incumbent *and* branches nothing, since `mostFractional` returns `None`; and a
node below the root reporting dual infeasibility drops its subtree with only an
`unproven` increment, because a spurious Farkas certificate is a real possibility
for a numerical method. So the answer returned can be genuinely suboptimal, which
is precisely what the downgrade to `Feasible` is recording — a reader who takes it
for "correct, pending proof" will trust it too far. The fix is the adaptive
iteration limit below, not the tree.

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

**No golden files from PyPSA in *this* module.** The heading used to read "No
golden files from PyPSA yet", which stopped being true once L1 and L2 arrived —
there are twenty-two golden networks and every *network* module, L1 onward, is
gated on them. Not "every module above this one": the modules above `prima-core`
in the build graph are the other Prima ones, and none of them reads
`NOAIDI_GOLDENS` — they are validated against ojAlgo and the Netlib corpus, which
are the right oracles for an LP solver and the wrong ones for a network model.
`ARCHITECTURE.md` already had that distinction right.

The scoped claim is the one that holds: this module has no power-system concepts
in it, so the gate belongs to the network layer. The `economic-dispatch` fixture
is shaped like a PyPSA LOPF and its congestion prices are asserted, but it was
constructed here rather than exported from a PyPSA run.
