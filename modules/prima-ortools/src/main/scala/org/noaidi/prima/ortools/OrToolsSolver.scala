package org.noaidi.prima
package ortools

import com.google.ortools.Loader
import com.google.ortools.linearsolver.{MPConstraint, MPSolver, MPVariable}

/** OR-Tools behind the common solver interface.
  *
  * The third backend, and the one that makes [[LpSolver]] worth having: ojAlgo
  * is a pure-JVM simplex, Prima is a first-order method, and GLOP is a
  * production revised simplex with its own presolve and its own scaling. Three
  * implementations that share no code is what turns agreement between them into
  * evidence.
  *
  * '''Duals are reported.''' GLOP supplies them for a linear program, so unlike
  * `OjAlgoSolver` this backend does not have to return `NaN` — which matters
  * for the modeling layer, where a model's prices are usually the reason it was
  * written.
  */
final class OrToolsSolver(options: OrToolsSolver.Options = OrToolsSolver.Options()) extends LpSolver:

  val name: String = s"ortools-${options.backend.toLowerCase}"

  /** Is the problem feasible at all, with the objective thrown away?
    *
    * Asked only when OR-Tools has said `INFEASIBLE`, because it says that for
    * an unbounded problem too: `min -x` subject to `x >= 0` comes back
    * infeasible rather than unbounded. Those are opposite answers, and this
    * interface reports `PrimalInfeasible` only on evidence -- the same standard
    * `SolveStatus` sets for Prima, where diverging without a certificate is an
    * iteration limit and not an infeasibility.
    *
    * Dropping the objective decides it. A feasible region that exists means the
    * original was unbounded; one that does not means it really was infeasible.
    * The cost is a second solve, paid only on a path that has already failed.
    *
    * '''Three answers, not two.''' A probe that returns `NOT_SOLVED` or
    * `ABNORMAL` has not proved anything, and collapsing that into "infeasible"
    * would publish a conclusive status on no evidence -- exactly what this
    * method exists to stop the caller doing. `Unknown` becomes a
    * `NumericalError`, which is what a solver that could not decide should say.
    */
  private def feasibilityOf(problem: LpProblem): OrToolsSolver.Feasibility =
    val stripped = LpProblem(
      objective = IArray.fill(problem.numVariables)(0.0),
      constraintMatrix = problem.constraintMatrix,
      rhs = problem.rhs,
      numEqualities = problem.numEqualities,
      variableLower = problem.variableLower,
      variableUpper = problem.variableUpper,
    )
    // The probe gets no time limit of its own to inherit. Sharing the caller's
    // would let a budget already spent on the real solve decide a question of
    // feasibility by exhaustion, and report the answer as if it were proved.
    val probe = new OrToolsSolver(options.copy(disambiguate = false, timeLimitMillis = None))
    probe.solve(stripped).status match
      // `TimeLimit` is proof, not an inconclusive answer: it comes from
      // `MPSolver.ResultStatus.FEASIBLE`, which means the solver is holding a
      // feasible point, and a feasible point is the whole question this probe
      // asks.
      //
      // The other producer of `TimeLimit` cannot fire here, and the reason is
      // narrower than "the probe carries no time limit". `options.copy` keeps
      // `parameters`, so a PDLP probe *does* inherit `iteration_limit` -- but a
      // limit-terminated PDLP returns `NOT_SOLVED`, and `NOT_SOLVED` reaches
      // `TimeLimit` only when a time limit was set and reached, which the probe
      // never does. So an exhausted probe lands on `Unknown` and the caller is
      // told nothing was established, rather than being handed a conclusive
      // `DualInfeasible` derived from a non-converged iterate.
      case SolveStatus.Optimal | SolveStatus.TimeLimit => OrToolsSolver.Feasibility.Feasible
      case SolveStatus.PrimalInfeasible                => OrToolsSolver.Feasibility.Infeasible
      case _                                           => OrToolsSolver.Feasibility.Unknown

  def solve(problem: LpProblem): LpSolution =
    OrToolsSolver.load()
    val started = System.nanoTime()

    val solver = MPSolver.createSolver(options.backend)
    require(solver != null, s"OR-Tools has no solver named ${options.backend}")
    // `createSolver` returns a SWIG proxy over a native model. Left to GC it is
    // reclaimed only when the JVM feels heap pressure, which a few hundred
    // bytes of proxy never generates -- so a scenario sweep or a
    // branch-and-bound loop would accumulate native models indefinitely.
    // `OjAlgoSolver` has no such obligation, so the pattern is not transferable
    // from the sibling backend.
    try solveWith(problem, solver, started)
    finally solver.delete()

  private def solveWith(problem: LpProblem, solver: MPSolver, started: Long): LpSolution =
    val variables: Array[MPVariable] = Array.tabulate(problem.numVariables) { i =>
      // OR-Tools takes infinities directly, and its own infinity is the one to
      // hand it: `MPSolver.infinity()` is not required to be `Double.Infinity`
      // and a mismatch is read as a finite bound of absurd size rather than as
      // no bound.
      val l = if problem.variableLower(i).isNegInfinity then -MPSolver.infinity() else problem.variableLower(i)
      val u = if problem.variableUpper(i).isPosInfinity then MPSolver.infinity() else problem.variableUpper(i)
      solver.makeNumVar(l, u, s"x$i")
    }

    val matrix = problem.constraintMatrix
    val rows: Array[MPConstraint] = Array.tabulate(problem.numConstraints) { r =>
      val q = problem.rhs(r)
      // Equalities come first by construction; everything after is `Kx >= q`.
      val row =
        if r < problem.numEqualities then solver.makeConstraint(q, q, s"c$r")
        else solver.makeConstraint(q, MPSolver.infinity(), s"c$r")
      var p   = matrix.rowPtr(r)
      val end = matrix.rowPtr(r + 1)
      while p < end do
        row.setCoefficient(variables(matrix.colIndices(p)), matrix.values(p))
        p += 1
      row
    }

    val objective = solver.objective()
    var i         = 0
    while i < problem.numVariables do
      objective.setCoefficient(variables(i), problem.objective(i))
      i += 1
    objective.setMinimization()

    options.parameters.foreach { text =>
      require(
        solver.setSolverSpecificParametersAsString(text),
        s"OR-Tools rejected the parameters for ${options.backend}: $text",
      )
    }

    options.timeLimitMillis.foreach(limit => solver.setTimeLimit(limit))

    // Two clocks. `started` is taken before the model is built and is what the
    // caller is told the solve cost; `solveStarted` is what `setTimeLimit`
    // actually bounds. Testing the limit against the first would report a
    // `NOT_SOLVED` as a time limit because *model construction* was slow on a
    // large problem, which is the conclusive-sounding-status-on-no-evidence
    // this predicate exists to prevent, in a quieter form.
    val solveStarted = System.nanoTime()
    val resultStatus = solver.solve()
    val solveMillis  = (System.nanoTime() - solveStarted) / 1000000L
    val elapsed      = (System.nanoTime() - started) / 1000000L
    val status =
      OrToolsSolver.mapStatus(
        resultStatus,
        () =>
          if options.disambiguate then feasibilityOf(problem)
          else OrToolsSolver.Feasibility.Infeasible,
        // Whether the limit was *reached*, not whether one was configured. A
        // `NOT_SOLVED` from any other cause -- a model the backend rejects, an
        // immediate abort -- would otherwise be reported as a time limit
        // whenever one merely happened to be set, which is the same
        // conclusive-sounding status on no evidence this method exists to stop.
        options.timeLimitMillis.exists(solveMillis >= _),
      )

    val primal = Array.tabulate(problem.numVariables)(j => variables(j).solutionValue())

    // Duals and reduced costs are only defined where a solution exists. Asking
    // for them after an infeasible or aborted solve is an error inside
    // OR-Tools, not a number, so this reports the same `NaN` the interface
    // already uses for a backend that cannot vouch for a dual.
    val hasSolution =
      resultStatus == MPSolver.ResultStatus.OPTIMAL || resultStatus == MPSolver.ResultStatus.FEASIBLE
    val duals =
      if hasSolution then Array.tabulate(problem.numConstraints)(r => rows(r).dualValue())
      else Array.fill(problem.numConstraints)(Double.NaN)
    val reduced =
      if hasSolution then Array.tabulate(problem.numVariables)(j => variables(j).reducedCost())
      else Array.fill(problem.numVariables)(Double.NaN)

    val objectiveValue =
      if hasSolution then objective.value() + problem.objectiveOffset else Double.NaN

    // The KKT error is computed here rather than taken from the solver, which
    // does not report one: a simplex answer is exact in its own basis and the
    // residual a first-order method reports has no counterpart. Computing it
    // against the original problem is what makes the two comparable at all.
    val kkt =
      if hasSolution then Kkt.evaluate(problem, primal.clone(), duals.clone())
      else KktError(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

    LpSolution(
      status = status,
      primal = IArray.unsafeFromArray(primal),
      dual = IArray.unsafeFromArray(duals),
      reducedCosts = IArray.unsafeFromArray(reduced),
      objectiveValue = objectiveValue,
      dualObjectiveValue = if hasSolution then kkt.dualObjective else Double.NaN,
      iterations = solver.iterations().toInt,
      restarts = 0,
      solveTimeMillis = elapsed,
      kkt = kkt,
      // Left unset, as the interface asks: neither has any meaning for a
      // simplex, and a plausible default would be indistinguishable from an
      // adaptive rule that genuinely converged there.
      finalStepSize = Double.NaN,
      finalPrimalWeight = Double.NaN,
    )

object OrToolsSolver:

  /** Which OR-Tools backend to ask for, and how long to let it run.
    *
    * `GLOP` is Google's own revised simplex and the only linear backend the
    * distribution always carries. `PDLP` is also present and is the same
    * algorithm Prima implements, which makes it the more interesting
    * comparison and the less useful oracle — two first-order methods share the
    * failure modes that make a cross-check worth running.
    */
  final case class Options(
      backend: String = "GLOP",
      timeLimitMillis: Option[Long] = None,
      /** Re-solve without the objective when OR-Tools reports infeasibility, to
        * tell a genuinely infeasible problem from an unbounded one it has
        * called infeasible. Off for the probe solve itself, which is what stops
        * the two from recursing.
        */
      disambiguate: Boolean = true,
      /** Backend-specific parameters, in OR-Tools' own text-proto format.
        *
        * Generic on purpose: each backend takes a different message, and a
        * typed `tolerance` field here would be PDLP's proto leaking into an
        * interface that also has to serve GLOP and SCIP. [[pdlp]] builds the
        * one string this port actually needs.
        *
        * A string OR-Tools rejects is '''refused rather than ignored''' —
        * `setSolverSpecificParametersAsString` returns `false` and leaves the
        * solver on its defaults, so a typo would otherwise mean solving at a
        * tolerance nobody asked for and reporting it as the one they did.
        */
      parameters: Option[String] = None,
  ):
    require(backend.nonEmpty, "backend must be named")
    require(timeLimitMillis.forall(_ > 0), "a time limit must be positive")

  /** PDLP, held to a stated tolerance.
    *
    * PDLP is Google's own restarted primal-dual hybrid gradient — the same
    * algorithm [[org.noaidi.prima.Pdhg]] implements, from the same paper. That
    * makes it the least useful oracle here and by far the most interesting
    * comparison: two independent implementations agreeing on an *objective*
    * says only that both can solve an LP, but agreeing on how many iterations
    * it takes to reach a tolerance is evidence about the restart schedule, the
    * step-size rule and the termination test, which is the part of this solver
    * that is a judgement rather than a theorem.
    *
    * The tolerance has to be set or the comparison is meaningless: PDLP's
    * default stops around 1e-5, where Prima's stops at 1e-8, and an iteration
    * count at one tolerance says nothing about a count at another.
    *
    * '''The spelling matters.''' `termination_criteria` carries
    * `eps_optimal_absolute` and `eps_optimal_relative` directly, and both are
    * deprecated — OR-Tools accepts them, applies them, and prints a warning to
    * stderr that is easy to miss under sbt. The nested
    * `simple_optimality_criteria` is the current form, gives an identical
    * answer, and is silent.
    */
  def pdlp(tolerance: Double, iterationLimit: Int = 500_000): OrToolsSolver =
    require(tolerance > 0.0, s"tolerance must be positive, got $tolerance")
    require(iterationLimit > 0, s"iterationLimit must be positive, got $iterationLimit")
    OrToolsSolver(
      Options(
        backend = "PDLP",
        // Bounded, and not as a formality. PDLP's `TerminationCriteria` leaves
        // `iteration_limit` unset by default, so a reference solver held to
        // 1e-9 -- aggressive for a first-order method -- would run without any
        // cap at all. The asymmetry is what makes it matter: Prima's side of
        // the comparison carries `maxIterations`, so a divergent Prima fails a
        // status assertion, while a divergent PDLP would hang the job until the
        // CI timeout with nothing to read.
        //
        // On hitting the limit PDLP returns `NOT_SOLVED`, not a feasible point
        // it has not proved optimal -- measured, and pinned by
        // `PdlpComparisonSuite`, because an earlier version of this comment
        // asserted the opposite and the difference decides whether the
        // feasibility probe below can be fooled. With no time limit set that
        // maps to `NumericalError`, so the comparison fails on the status with
        // both counts in hand.
        parameters = Some(
          s"termination_criteria { iteration_limit: $iterationLimit " +
            s"simple_optimality_criteria { eps_optimal_absolute: $tolerance eps_optimal_relative: $tolerance } }"
        ),
      )
    )

  /** OR-Tools' natives load once per JVM, and loading twice is not an error but
    * is not free either.
    */
  private var loaded = false

  private[ortools] def load(): Unit = synchronized {
    if !loaded then
      Loader.loadNativeLibraries()
      loaded = true
  }

  /** What a probe solve established, which is not always one of two things. */
  private[ortools] enum Feasibility:
    case Feasible, Infeasible, Unknown

  /** The whole status table, visible to a test.
    *
    * `private[ortools]` rather than `private`: no fixture drives GLOP to
    * `ABNORMAL`, `NOT_SOLVED` or `FEASIBLE`, so the only way to hold this
    * mapping to anything is to call it.
    */
  private[ortools] def mapStatus(
      status: MPSolver.ResultStatus,
      feasible: () => Feasibility,
      timeLimitReached: Boolean,
  ): SolveStatus = status match
    case MPSolver.ResultStatus.OPTIMAL    => SolveStatus.Optimal
    case MPSolver.ResultStatus.INFEASIBLE =>
      feasible() match
        case Feasibility.Feasible   => SolveStatus.DualInfeasible
        case Feasibility.Infeasible => SolveStatus.PrimalInfeasible
        case Feasibility.Unknown    => SolveStatus.NumericalError
    case MPSolver.ResultStatus.UNBOUNDED => SolveStatus.DualInfeasible
    // `FEASIBLE` means the solver stopped with a point it has not proved
    // optimal, which is what a time limit looks like from here.
    case MPSolver.ResultStatus.FEASIBLE => SolveStatus.TimeLimit
    case MPSolver.ResultStatus.ABNORMAL => SolveStatus.NumericalError
    // Not `Interrupted`, whose own definition is "the caller asked the solve to
    // stop before it reached any conclusion". Nothing here supports caller
    // interruption, and a time-limited GLOP abort with no feasible point is the
    // likely producer -- so a solve that ran out of budget would be reported as
    // a cancellation nobody requested.
    case MPSolver.ResultStatus.NOT_SOLVED =>
      if timeLimitReached then SolveStatus.TimeLimit else SolveStatus.NumericalError
    case _ => SolveStatus.NumericalError
