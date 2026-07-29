package org.noaidi.prima
package ojalgo

import org.ojalgo.optimisation.{ExpressionsBasedModel, Optimisation}
// `type` is a Scala keyword, so ojAlgo's package of that name needs quoting.
import org.ojalgo.`type`.context.NumberContext

/** ojAlgo behind the common solver interface.
  *
  * ojAlgo is a mature pure-JVM simplex and interior-point implementation, which
  * makes it two useful things at once: the CPU fallback for when no accelerator
  * is present, and the oracle Prima's own answers are checked against. A
  * first-order method and a simplex method fail in completely different ways,
  * so agreement between them is real evidence rather than a shared bug.
  */
final class OjAlgoSolver(options: OjAlgoSolver.Options = OjAlgoSolver.Options()) extends LpSolver:

  val name: String = "ojalgo"

  def solve(problem: LpProblem): LpSolution =
    val started = System.nanoTime()
    val model   = new ExpressionsBasedModel()

    val variables = Array.tabulate(problem.numVariables) { i =>
      val v = model.newVariable(s"x$i")
      val l = problem.variableLower(i)
      val u = problem.variableUpper(i)
      if !l.isNegInfinity then v.lower(java.lang.Double.valueOf(l)): Unit
      if !u.isPosInfinity then v.upper(java.lang.Double.valueOf(u)): Unit
      v.weight(java.lang.Double.valueOf(problem.objective(i))): Unit
      v
    }

    val matrix = problem.constraintMatrix
    var r      = 0
    while r < problem.numConstraints do
      val expression = model.newExpression(s"c$r")
      var p          = matrix.rowPtr(r)
      val end        = matrix.rowPtr(r + 1)
      while p < end do
        expression.set(variables(matrix.colIndices(p)), java.lang.Double.valueOf(matrix.values(p))): Unit
        p += 1
      val q = problem.rhs(r)
      // Equalities come first by construction; everything after is `Kx >= q`.
      if r < problem.numEqualities then expression.level(java.lang.Double.valueOf(q)): Unit
      else expression.lower(java.lang.Double.valueOf(q)): Unit
      r += 1

    options.configure(model)

    val result  = model.minimise()
    val elapsed = (System.nanoTime() - started) / 1000000L

    val primal = new Array[Double](problem.numVariables)
    var i      = 0
    while i < problem.numVariables do
      primal(i) = result.doubleValue(i)
      i += 1

    val status = mapState(result.getState)

    // ojAlgo exposes multipliers only on some of its solver paths, and where it
    // does they follow its own `<=` orientation rather than the `>=` standard
    // form used here. Rather than substitute zeros — which would flow into the
    // dual objective, the duality gap and the reduced costs and come back as
    // plausible-looking numbers that are simply wrong — the dual side is
    // reported as NaN whenever it cannot be established. A caller reading
    // `dual` or `dualityGap` from this backend then gets an unmistakable answer
    // instead of a quiet one.
    val duals   = Array.fill(problem.numConstraints)(Double.NaN)
    val reduced = Array.fill(problem.numVariables)(Double.NaN)

    val error =
      if status == SolveStatus.Optimal then
        KktError(
          primalResidualNorm = Kkt.primalResidualNorm(problem, primal),
          dualResidualNorm = Double.NaN,
          primalObjective = problem.primalObjective(Unsafe.wrap(primal.clone())),
          dualObjective = Double.NaN,
        )
      else KktError(Double.NaN, Double.NaN, result.getValue, Double.NaN)

    LpSolution(
      status = status,
      primal = Unsafe.wrap(primal),
      dual = Unsafe.wrap(duals),
      reducedCosts = Unsafe.wrap(reduced),
      objectiveValue =
        if status == SolveStatus.Optimal then error.primalObjective else result.getValue,
      dualObjectiveValue = Double.NaN,
      iterations = 0,
      restarts = 0,
      solveTimeMillis = elapsed,
      kkt = error,
    )

  private def mapState(state: Optimisation.State): SolveStatus =
    if state == Optimisation.State.OPTIMAL || state == Optimisation.State.DISTINCT then
      SolveStatus.Optimal
    else if state == Optimisation.State.INFEASIBLE then SolveStatus.PrimalInfeasible
    else if state == Optimisation.State.UNBOUNDED then SolveStatus.DualInfeasible
    else SolveStatus.NumericalError

end OjAlgoSolver

object OjAlgoSolver:

  /** Solver options worth exposing. ojAlgo's defaults are sensible; this exists
    * so validation runs can tighten them past the point where the oracle's own
    * tolerance would be confused with a disagreement.
    */
  final case class Options(
      /** Decimal places ojAlgo treats as significant when judging feasibility.
        * Expressed as a scale rather than a tolerance because that is what its
        * `NumberContext` takes.
        */
      feasibilityScale: Option[Int] = None,
      timeLimitMillis: Option[Long] = None,
  ):
    private[ojalgo] def configure(model: ExpressionsBasedModel): Unit =
      feasibilityScale.foreach { scale =>
        model.options.feasibility = NumberContext.ofScale(scale)
      }
      timeLimitMillis.foreach { limit =>
        model.options.time_abort = limit
        model.options.time_suffice = limit
      }

  def apply(): OjAlgoSolver = new OjAlgoSolver()
