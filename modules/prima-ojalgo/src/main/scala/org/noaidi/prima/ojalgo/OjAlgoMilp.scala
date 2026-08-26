package org.noaidi.prima.ojalgo

import org.noaidi.prima.{LpProblem, MilpStatus}
import org.ojalgo.optimisation.{ExpressionsBasedModel, Optimisation}
import org.ojalgo.optimisation.integer.IntegerStrategy
import org.ojalgo.`type`.context.NumberContext

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

    // The oracle has to be exact and deterministic, and by default it is
    // neither. ojAlgo's integer solver stops on `time_suffice` once it holds a
    // solution it considers good enough, and reports that incumbent with state
    // OPTIMAL -- so under load it can return a *worse* objective than Prima and
    // still claim optimality. That was not hypothetical: the same ladder run
    // twice gave a worst gap of 7.3e-10 and then 1.7e-2, with the report
    // flagging Prima for having "claimed a better objective than the oracle"
    // when what had actually happened was the oracle giving up early.
    //
    // These instances are small enough that an exact search costs milliseconds,
    // so the time limits are pushed out of reach and the MIP gap tightened to
    // well below the 1e-5 the comparison asserts. An oracle that is sometimes
    // right is worse than no oracle, because a disagreement stops meaning
    // anything.
    model.options.time_abort = Long.MaxValue
    model.options.time_suffice = Long.MaxValue

    // Single-threaded with a tight gap tolerance. Parallelism is the other half
    // of the non-determinism: a multi-threaded branch-and-bound races to an
    // incumbent, so which node closes the search -- and therefore what it holds
    // when a time or gap check fires -- varies between runs on the same input.
    //
    // The worker count sits on the options rather than on the strategy: 57.1.1
    // dropped `ConfigurableStrategy.withParallelism` and had `IntegerSolver`
    // read `options.getParallelism()` instead, feeding it to the strategy's
    // `getWorkerPriorities(int)`. One priority comparator is one worker, so
    // this is the same single thread the old call asked for.
    model.options.parallelism(1): Unit
    model.options.integer(
      IntegerStrategy.newConfigurable()
        .withGapTolerance(NumberContext.ofScale(12))
    ): Unit

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
      // DISTINCT means a proven *unique* optimum, which small integer models
      // reach often. Omitting it mapped those to NoSolutionFound, and the
      // agreement suite then skipped the seed instead of failing -- an oracle
      // quietly weakening itself. `OjAlgoSolver.mapState` already treats it as
      // optimal, so this now matches.
      case Optimisation.State.OPTIMAL | Optimisation.State.DISTINCT => MilpStatus.Optimal
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
