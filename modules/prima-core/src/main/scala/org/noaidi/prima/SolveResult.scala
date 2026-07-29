package org.noaidi.prima

/** Outcome of a solve.
  *
  * `PrimalInfeasible` and `DualInfeasible` are reported only on the strength of
  * a certificate that passed [[Certificates]]; running out of iterations while
  * diverging is `IterationLimit`, not infeasibility.
  */
enum SolveStatus:
  case Optimal
  case PrimalInfeasible
  case DualInfeasible
  case IterationLimit
  case TimeLimit
  case NumericalError

  /** The caller asked the solve to stop before it reached any conclusion. */
  case Interrupted

  def isSuccess: Boolean = this == Optimal

  /** True when the solver reached a definitive answer, optimal or not. */
  def isConclusive: Boolean = this match
    case Optimal | PrimalInfeasible | DualInfeasible               => true
    case IterationLimit | TimeLimit | NumericalError | Interrupted => false

/** A primal-dual solution together with the evidence for its status.
  *
  * The vectors are always populated, even for a non-optimal status, so that a
  * caller can inspect where the method got to; `status` decides whether they
  * mean anything.
  */
final case class LpSolution(
    status: SolveStatus,
    primal: IArray[Double],
    dual: IArray[Double],
    reducedCosts: IArray[Double],
    objectiveValue: Double,
    dualObjectiveValue: Double,
    iterations: Int,
    restarts: Int,
    solveTimeMillis: Long,
    kkt: KktError,
    /** Step size the adaptive rule had settled on, in the equilibrated space,
      * or `NaN` if this solver has no such notion.
      *
      * Reported so it can be handed to a warm-started solve. Both passes over a
      * problem derive the same scaling, so the value transfers between them —
      * and it represents real work, since the rule takes thousands of
      * iterations to converge on a large instance.
      *
      * `NaN` rather than a plausible default is deliberate. Any backend that is
      * not PDHG — a simplex solver, say — leaves this unset, and a default of
      * `1.0` would be indistinguishable from a rule that genuinely converged
      * there. A warm start built from such a solution would then override the
      * opening step size derived from the spectral norm bound with a fabricated
      * one, and start the method outside the region where it converges.
      */
    finalStepSize: Double = Double.NaN,

    /** Primal weight the restart schedule had settled on, likewise in the
      * equilibrated space, `NaN` when unset, and likewise worth carrying across
      * a warm start.
      */
    finalPrimalWeight: Double = Double.NaN,
):
  def dualityGap: Double = kkt.absoluteGap

  override def toString: String =
    f"LpSolution($status, obj=$objectiveValue%.10g, gap=${kkt.absoluteGap}%.3e, " +
      f"pRes=${kkt.primalResidualNorm}%.3e, dRes=${kkt.dualResidualNorm}%.3e, " +
      s"iters=$iterations, restarts=$restarts, ${solveTimeMillis}ms)"

/** One solver, whichever it is.
  *
  * Everything the modeling layer needs to know about a backend. Prima, ojAlgo,
  * OR-Tools, HiGHS and the commercial solvers all sit behind this, so a model
  * never names the solver that will run it.
  */
trait LpSolver:
  def name: String
  def solve(problem: LpProblem): LpSolution
