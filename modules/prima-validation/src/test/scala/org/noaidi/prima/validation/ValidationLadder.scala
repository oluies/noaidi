package org.noaidi.prima
package validation

/** The instance ladder and the gap measure, defined once.
  *
  * `Report` publishes a worst-gap figure that `OracleAgreementSuite` is supposed
  * to enforce. That only holds if both use the same instances and the same
  * formula — and previously both were copy-pasted, so adding an instance to the
  * report or changing the scale factor in one place would have decoupled the
  * enforced bound from the published number without any signal. That is exactly
  * the failure the enforcement was added to prevent, so the shared definition
  * lives here and both call it.
  */
object ValidationLadder:

  /** Named instances, in the order the report prints them.
    *
    * `conclusive` rather than `optimal`, so the infeasible and unbounded
    * fixtures appear in the report; consumers that need only the optimal ones
    * filter on status rather than on a second list.
    */
  final case class Entry(name: String, problem: LpProblem, expected: SolveStatus)

  /** Each instance carries the status it is expected to reach.
    *
    * Without it, a gap test can only filter on the *observed* status — and an
    * instance that regressed to `IterationLimit` would then vanish from the
    * comparison rather than fail it, leaving the published figure quietly
    * measured over fewer instances. The random ones have no other coverage at
    * these sizes, so that would go unnoticed.
    */
  val entries: Seq[Entry] =
    LpFixtures.conclusive.map(i => Entry(i.name, i.problem, i.expectedStatus)) ++
      Seq(
        Entry("random-60x30", LpFixtures.randomFeasible(1, 60, 10, 20, 0.25), SolveStatus.Optimal),
        Entry("random-200x120", LpFixtures.randomFeasible(2, 200, 40, 80, 0.10), SolveStatus.Optimal),
        Entry("random-600x400", LpFixtures.randomFeasible(3, 600, 120, 280, 0.04), SolveStatus.Optimal),
      )

  val instances: Seq[(String, LpProblem)] = entries.map(e => (e.name, e.problem))

  /** Relative objective disagreement, scaled so an optimum near zero does not
    * make the ratio meaningless.
    */
  def relativeGap(mine: Double, oracle: Double): Double =
    math.abs(mine - oracle) / math.max(1.0, math.abs(oracle))

  /** The bound `OracleAgreementSuite` enforces and both documents quote.
    *
    * Set above the observed worst gap — 4.9e-10 on macOS/aarch64, 5.9e-10 on
    * Linux/x86_64 — with enough headroom to absorb that platform variation while
    * still catching an order-of-magnitude regression.
    */
  val worstGapBound: Double = 1e-8
