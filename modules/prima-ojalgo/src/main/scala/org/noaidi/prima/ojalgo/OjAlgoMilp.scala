package org.noaidi.prima.ojalgo

import org.noaidi.prima.{LpProblem, MilpStatus}
import org.ojalgo.optimisation.{ExpressionsBasedModel, Optimisation}

/** ojAlgo's mixed-integer solver, as an oracle for [[org.noaidi.prima.BranchAndBound]].
  *
  * The same role `OjAlgoSolver` plays for the LP: an independent implementation
  * to disagree with. That matters more here than for the LP, because Prima's
  * branch-and-bound rests on an '''inexact''' bound and its pruning rule is
  * therefore a judgement call rather than a theorem. A cross-check against a
  * solver whose bound is exact is the only way to find out whether that
  * judgement ever discards the optimum.
  *
  * Only the objective value is compared by callers. Which optimal integer point
  * a solver lands on is not determined when several share the best value, in
  * exactly the way `OracleAgreementSuite` already documents for LP vertices.
  */
object OjAlgoMilp:

  final case class Result(status: MilpStatus, primal: IArray[Double], objectiveValue: Double)

  def solve(problem: LpProblem, integers: Set[Int]): Result =
    val model = new ExpressionsBasedModel()

    val variables = Array.tabulate(problem.numVariables) { i =>
      val v = model.newVariable(s"x$i")
      val l = problem.variableLower(i)
      val u = problem.variableUpper(i)
      if !l.isNegInfinity then v.lower(java.lang.Double.valueOf(l)): Unit
      if !u.isPosInfinity then v.upper(java.lang.Double.valueOf(u)): Unit
      v.weight(java.lang.Double.valueOf(problem.objective(i))): Unit
      if integers.contains(i) then v.integer(true): Unit
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
      if r < problem.numEqualities then expression.level(java.lang.Double.valueOf(q)): Unit
      else expression.lower(java.lang.Double.valueOf(q)): Unit
      r += 1

    val result = model.minimise()

    val primal = new Array[Double](problem.numVariables)
    var i      = 0
    while i < problem.numVariables do
      primal(i) = if result.size > i then result.doubleValue(i) else Double.NaN
      i += 1

    val status = result.getState match
      case Optimisation.State.OPTIMAL     => MilpStatus.Optimal
      case Optimisation.State.INFEASIBLE  => MilpStatus.Infeasible
      case Optimisation.State.UNBOUNDED   => MilpStatus.Unbounded
      case Optimisation.State.FEASIBLE    => MilpStatus.Feasible
      case _                              => MilpStatus.NoSolutionFound

    // ojAlgo reports the objective without the problem's constant term, which
    // `LpProblem` carries separately.
    val objective =
      if status == MilpStatus.Optimal || status == MilpStatus.Feasible then
        problem.primalObjective(IArray.unsafeFromArray(primal.clone()))
      else Double.NaN

    Result(status, IArray.unsafeFromArray(primal), objective)
