package org.noaidi.prima

import org.noaidi.prima.kernels.{Kernels, ScalaKernels}

/** Solving in reduced precision, then finishing in double.
  *
  * Every accelerator backend on the table is float32-only. Cyfra's DSL has no
  * double type at all, so that holds even on NVIDIA hardware where the silicon
  * could do better; Apple GPUs offer no float64 through Vulkan or Metal
  * regardless. An LP solved purely in float32 cannot report a KKT residual near
  * 1e-9, because the residual computation itself has nothing like that much
  * precision to work with.
  *
  * So the accelerator does not have to produce the final answer — only a good
  * starting point. The reduced-precision pass runs to whatever tolerance
  * float32 can support and gets close to the optimum quickly; the CPU then
  * takes over in double precision, warm-started from that point, and tightens
  * to the tolerance actually asked for.
  *
  * '''Whether that pays off depends on the problem, and it is opt-in for that
  * reason.''' On structured, sparse instances — the LOPF workload this is meant
  * for — the refinement removes most of the double-precision work: 75% on the
  * dispatch fixture at a 1e-9 tolerance and all of it at 1e-6. On dense random
  * LPs at tight tolerance the float32 point is measurably a *worse* starting
  * point than zero, and refinement can cost several times a cold solve. That is
  * unexplained. See `NOTES.md` for the measurements before enabling this on a
  * new class of problem.
  *
  * What does hold unconditionally is the answer: the reported result always
  * comes from the double-precision pass, so accuracy never depends on which
  * device did the bulk of the work. That is what makes this safe to expose even
  * where it is not yet safe to assume it is faster.
  */
object MixedPrecision:

  /** How the two passes divide the work. */
  final case class Params(
      /** Tolerance for the reduced-precision pass.
        *
        * float32 carries about seven decimal digits, and the residual is a
        * difference of similarly-sized quantities, so asking for much below
        * 1e-5 means asking the device to chase noise. Iterations spent there
        * are wasted: the refinement pass would have to redo them anyway.
        */
      reducedTolerance: Double = 1e-5,

      /** Iteration ceiling for the reduced-precision pass. Bounded separately
        * because its iterations are cheap and its progress can stall on noise.
        */
      reducedMaxIterations: Int = 20_000,

      /** Refine even when the reduced pass reported a conclusive status.
        *
        * Off by default: an infeasibility certificate that holds in float32
        * holds comfortably in double, and re-running to confirm it wastes a
        * whole solve.
        */
      alwaysRefine: Boolean = false,

      /** Share of a caller's time limit given to the reduced pass.
        *
        * Without this the two passes would each get the caller's full limit and
        * a solve could run for twice as long as asked. The refinement gets
        * whatever remains after the reduced pass actually finishes.
        */
      reducedTimeShare: Double = 0.5,
  ):
    require(reducedTolerance > 0.0, s"reducedTolerance must be positive, got $reducedTolerance")
    require(
      reducedMaxIterations > 0,
      s"reducedMaxIterations must be positive, got $reducedMaxIterations",
    )
    require(
      reducedTimeShare > 0.0 && reducedTimeShare < 1.0,
      s"reducedTimeShare must lie in (0, 1), got $reducedTimeShare",
    )

  object Params:
    val default: Params = Params()

  /** The outcome of both passes, so the cost of each is visible. */
  final case class Result(
      solution: LpSolution,
      reducedIterations: Int,
      reducedRestarts: Int,
      reducedMillis: Long,
      refinementIterations: Int,
      refinementMillis: Long,
      refined: Boolean,
  ):
    def totalIterations: Int = reducedIterations + refinementIterations

    override def toString: String =
      s"MixedPrecision(${solution.status}, obj=${solution.objectiveValue}, " +
        s"reduced=$reducedIterations iters/${reducedMillis}ms, " +
        s"refine=$refinementIterations iters/${refinementMillis}ms, refined=$refined)"

  /** Run the reduced-precision pass on `device`, then refine on the CPU.
    *
    * If `device` turns out to support float64, the reduced pass is skipped
    * entirely and this is just an ordinary solve — so a caller can route
    * everything through here without first asking what hardware it has.
    */
  def solve(
      problem: LpProblem,
      params: PdhgParams,
      device: Kernels,
      mixed: Params = Params.default,
      cancelled: () => Boolean = () => false,
  ): Result =
    if device.capabilities.supportsFloat64 then
      val solution = Pdhg.solveWith(problem, params, device, cancelled)
      Result(solution, 0, 0, 0L, solution.iterations, solution.solveTimeMillis, refined = false)
    else
      val reducedParams = params.copy(
        epsAbs = math.max(params.epsAbs, mixed.reducedTolerance),
        epsRel = math.max(params.epsRel, mixed.reducedTolerance),
        maxIterations = math.min(params.maxIterations, mixed.reducedMaxIterations),
        // A time limit means the whole solve, not each pass. Splitting it here
        // and charging the refinement for what the reduced pass actually spent
        // keeps the total inside what the caller asked for.
        timeLimitMillis =
          params.timeLimitMillis.map(limit => math.max(1L, (limit * mixed.reducedTimeShare).toLong)),
        // The reduced pass computes its certificate residuals from float32
        // matrix products, which carry around 1e-7 relative noise. Holding it to
        // the caller's 1e-8 default would mean no certificate could ever pass,
        // so infeasibility would never be detected on the device.
        infeasibilityTolerance =
          math.max(params.infeasibilityTolerance, mixed.reducedTolerance),
      )
      val reduced = Pdhg.solveWith(problem, reducedParams, device, cancelled)

      // A certificate found on the device was established at the loosened
      // tolerance above — a thousandfold relaxation by default — from matrix
      // products computed in float32. Returning that as the final answer would
      // mean reporting a problem infeasible on evidence the caller never asked
      // to be enough, with no double-precision check anywhere in the path.
      //
      // So it is re-tested here in full precision at the caller's own tolerance.
      // That costs a couple of sparse products against a whole refinement pass,
      // and if it does not hold the solve falls through to refinement rather
      // than trusting the device.
      val certificateHolds =
        reduced.status.isConclusive && reduced.status != SolveStatus.Optimal &&
          Certificates
            .classify(
              problem,
              Unsafe.raw(reduced.primal),
              Unsafe.raw(reduced.dual),
              params.infeasibilityTolerance,
            )
            .contains(reduced.status)

      val skipRefinement = !mixed.alwaysRefine && certificateHolds

      if skipRefinement then
        Result(
          reduced,
          reduced.iterations,
          reduced.restarts,
          reduced.solveTimeMillis,
          0,
          0L,
          refined = false,
        )
      else
        val cpu = ScalaKernels()
        try
          val refinementParams = params.copy(
            timeLimitMillis =
              params.timeLimitMillis.map(limit => math.max(1L, limit - reduced.solveTimeMillis))
          )
          // A float32 pass that diverges overflows to infinity, and those
          // infinities reach the reported solution. `Pdhg.WarmStart` rightly
          // refuses them, but throwing here would turn a recoverable situation
          // into an exception from a function whose contract is to return a
          // Result — and `Solver` sits behind `LpSolver`, which reports failure
          // through `SolveStatus`. A diverged device pass simply means there is
          // nothing useful to warm-start from, so the refinement runs cold.
          val usable =
            reduced.primal.forall(_.isFinite) && reduced.dual.forall(_.isFinite)
          val refined =
            Pdhg.solveWith(
              problem,
              refinementParams,
              cpu,
              cancelled,
              warmStart = Option.when(usable)(Pdhg.WarmStart(reduced)),
            )
          Result(
            solution = refined,
            reducedIterations = reduced.iterations,
            reducedRestarts = reduced.restarts,
            reducedMillis = reduced.solveTimeMillis,
            refinementIterations = refined.iterations,
            refinementMillis = refined.solveTimeMillis,
            refined = true,
          )
        finally cpu.close()

  /** A solver that runs mixed precision behind the ordinary interface, so a
    * caller that only wants an answer never has to know two passes happened.
    */
  final class Solver(
      device: () => Kernels,
      params: PdhgParams = PdhgParams.default,
      mixed: Params = Params.default,
  ) extends LpSolver:
    val name: String = "prima-pdhg-mixed"

    def solve(problem: LpProblem): LpSolution =
      val k = device()
      try MixedPrecision.solve(problem, params, k, mixed).solution
      finally k.close()

end MixedPrecision
