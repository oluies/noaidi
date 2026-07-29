package org.noaidi.prima

import org.noaidi.prima.kernels.{Kernels, ScalaKernels}

/** Restarted primal-dual hybrid gradient, following PDLP.
  *
  * The method solves the saddle-point problem
  *
  * {{{
  *   min over x in [l,u]   max over y in Y   c'x - y'Kx + q'y
  * }}}
  *
  * by alternating a projected gradient step in each variable, with the primal
  * extrapolated. Per iteration it does two sparse matrix-vector products and a
  * handful of vector operations, and no factorisation at all — which is exactly
  * why it ports to a GPU where a simplex or interior-point method does not.
  *
  * Three things turn that bare iteration into something that converges at a
  * useful rate, and all three are here:
  *
  *   - '''preconditioning''', so the constraint matrix is equilibrated before
  *     the first step (see [[Scaling]]);
  *   - '''an adaptive step size''', which infers the local curvature from the
  *     step just taken instead of relying on a global spectral norm bound;
  *   - '''restarts''', which periodically discard momentum and re-centre on
  *     whichever of the current or averaged iterate is closer to optimal. This
  *     is what lifts the method from sublinear to linear convergence.
  *
  * All numerics go through a [[Kernels]] instance, so the same loop runs on the
  * CPU reference and on an accelerator without change.
  */
object Pdhg:

  /** Solve with the pure-Scala reference kernels. */
  def solve(problem: LpProblem, params: PdhgParams = PdhgParams.default): LpSolution =
    val kernels = ScalaKernels()
    try solveWith(problem, params, kernels)
    finally kernels.close()

  /** Solve using a supplied kernel backend. The backend is not closed here;
    * the caller owns its lifetime.
    *
    * `cancelled` is polled at evaluation points, which makes cancellation
    * cooperative and bounded by [[PdhgParams.evaluationFrequency]] iterations.
    * A tight numeric loop cannot be interrupted from outside, so this is the
    * only honest way to stop one early.
    */
  def solveWith(
      problem: LpProblem,
      params: PdhgParams,
      kernels: Kernels,
      cancelled: () => Boolean = () => false,
  ): LpSolution =
    new Solve(problem, params, kernels, cancelled).run()

  /** A solver bound to a set of parameters, for use behind [[LpSolver]]. */
  final class Solver(params: PdhgParams = PdhgParams.default) extends LpSolver:
    val name: String = "prima-pdhg"
    def solve(problem: LpProblem): LpSolution = Pdhg.solve(problem, params)

  /** Above this many consecutive rejections the step-size search is treated as
    * stuck and the trial is taken anyway. The rule normally accepts within one
    * or two attempts; the bound only exists so a pathological instance cannot
    * spin here forever.
    */
  private val MaxStepTrials = 60

  /** One solve. Mutable throughout, and deliberately not shared: the state here
    * is the working memory of the iteration, held in one place so the loop
    * itself reads as the algorithm rather than as buffer management.
    */
  private final class Solve(
      problem: LpProblem,
      params: PdhgParams,
      k: Kernels,
      cancelled: () => Boolean,
  ):

    private val n   = problem.numVariables
    private val m   = problem.numConstraints
    private val nEq = problem.numEqualities

    private val scaled   = Scaling(problem, params.scaling)
    private val sp       = scaled.problem
    private val rowScale = scaled.rowScale
    private val colScale = scaled.colScale

    // Termination is judged on the original problem, so its data norms are the
    // ones the relative tolerances are measured against.
    private val rhsNorm       = Kkt.norm2(problem.rhsRaw)
    private val objectiveNorm = Kkt.norm2(problem.objectiveRaw)

    private val matrix          = k.uploadMatrix(sp.constraintMatrix)
    private val matrixTranspose = k.uploadMatrix(sp.constraintMatrix.transpose)

    private val cost  = k.upload(sp.objectiveRaw)
    private val rhs   = k.upload(sp.rhsRaw)
    private val lower = k.upload(sp.lowerRaw)
    private val upper = k.upload(sp.upperRaw)

    // Iterates and their matrix products.
    private val x     = k.allocate(n)
    private val y     = k.allocate(m)
    private val kx    = k.allocate(m)
    private val ktY   = k.allocate(n)
    private val xNext = k.allocate(n)
    private val yNext = k.allocate(m)
    private val xBar  = k.allocate(n)
    private val kxBar = k.allocate(m)
    private val dx    = k.allocate(n)
    private val dy    = k.allocate(m)
    private val kdx   = k.allocate(m)

    // Step-size-weighted running sums over the current restart period. The
    // matrix products are averaged alongside the iterates so that evaluating
    // the average costs no extra sparse products.
    private val sumX   = k.allocate(n)
    private val sumY   = k.allocate(m)
    private val sumKx  = k.allocate(m)
    private val sumKtY = k.allocate(n)
    private val avgX   = k.allocate(n)
    private val avgY   = k.allocate(m)
    private val avgKx  = k.allocate(m)
    private val avgKtY = k.allocate(n)

    // The iterate the current restart period began from; the primal weight is
    // re-balanced from how far each block travelled across a whole period.
    private val xRestart = k.allocate(n)
    private val yRestart = k.allocate(m)

    // Kept zero, purely so the running sums can be cleared without arithmetic
    // that would turn an overflowed sum into a NaN.
    private val zeroN = k.allocate(n)
    private val zeroM = k.allocate(m)

    // Host mirrors, in original (unscaled) coordinates.
    private val hostX   = new Array[Double](n)
    private val hostY   = new Array[Double](m)
    private val hostKx  = new Array[Double](m)
    private val hostKtY = new Array[Double](n)

    private var stepSize     = 0.0
    private var primalWeight = 1.0
    private var weightSum    = 0.0

    private var iteration      = 0
    private var restarts       = 0
    private var lastRestartIt  = 0
    private var errorAtRestart = Double.PositiveInfinity
    private var lastCandidate  = Double.PositiveInfinity

    private val startNanos = System.nanoTime()

    def run(): LpSolution =
      initialise()

      // The starting point is a legitimate answer for a trivially feasible
      // problem, so it is tested before any work is done.
      val initial = evaluate(x, y, kx, ktY)
      if converged(initial) then finish(SolveStatus.Optimal, initial)
      else
        errorAtRestart = initial.weighted(primalWeight)

        var outcome: Option[(SolveStatus, KktError)] = None
        while outcome.isEmpty do
          val movement = step()
          iteration += 1

          if movement == 0.0 then
            // Neither variable moved: the iteration is at a fixed point, which
            // for PDHG is a saddle point of the Lagrangian. Whether that is
            // optimal is decided by the usual test — on a freshly computed `Kx`,
            // since judging a genuine optimum against a residual that has been
            // drifting since the last checkpoint would report it as a numerical
            // failure.
            k.spmv(matrix, x, kx)
            val error = evaluate(x, y, kx, ktY)
            outcome = Some(
              (if converged(error) then SolveStatus.Optimal else SolveStatus.NumericalError, error)
            )
          else if iteration % params.evaluationFrequency == 0 then
            outcome = checkpoint()
            // The wall clock and the cancellation flag are read here rather than
            // every iteration: on a small problem `System.nanoTime` alone costs
            // a noticeable share of an iteration.
            if outcome.isEmpty then
              if cancelled() then outcome = Some((SolveStatus.Interrupted, evaluate(x, y, kx, ktY)))
              else if timedOut then outcome = Some((SolveStatus.TimeLimit, evaluate(x, y, kx, ktY)))

          if outcome.isEmpty && iteration >= params.maxIterations then
            outcome = Some((SolveStatus.IterationLimit, evaluate(x, y, kx, ktY)))

        val (status, error) = outcome.get
        finish(status, error)

    // -- setup ---------------------------------------------------------------

    private def initialise(): Unit =
      // PDHG is stable only while `eta * ||K||_2 <= 1`, so the first step is
      // taken from an upper bound on the spectral norm. The adaptive rule grows
      // it from there; starting too large would put the opening iterations
      // outside the region where the method converges.
      val bound = sp.constraintMatrix.spectralNormBound
      stepSize = if bound > 0.0 then 1.0 / bound else 1.0

      // Balance the two blocks by the scale of the data each one sees.
      val cNorm = Kkt.norm2(sp.objectiveRaw)
      val qNorm = Kkt.norm2(sp.rhsRaw)
      primalWeight = if cNorm > 0.0 && qNorm > 0.0 then cNorm / qNorm else 1.0

      // x starts at zero projected into the box, since zero itself need not be
      // feasible for the bounds; y starts at zero, which is always in the cone.
      val start = new Array[Double](n)
      var i     = 0
      while i < n do
        val l = sp.lowerRaw(i)
        val u = sp.upperRaw(i)
        start(i) = if 0.0 < l then l else if 0.0 > u then u else 0.0
        i += 1
      k.copy(k.upload(start), x)

      k.spmv(matrix, x, kx)
      k.spmv(matrixTranspose, y, ktY)
      k.copy(x, xRestart)
      k.copy(y, yRestart)
      resetAverages()

    private def resetAverages(): Unit =
      weightSum = 0.0
      k.copy(zeroN, sumX)
      k.copy(zeroN, sumKtY)
      k.copy(zeroM, sumY)
      k.copy(zeroM, sumKx)

    // -- the iteration -------------------------------------------------------

    /** One accepted PDHG iteration with an adaptive step size.
      *
      * Returns the movement of the accepted step, which is zero only at a fixed
      * point.
      *
      * The step size is chosen by the Malitsky-Pock style rule PDLP uses: take a
      * trial step, measure the interaction `|dy' K dx|` against the movement in
      * the weighted norm, and accept only if the step size used was no larger
      * than that ratio permits. A rejected trial costs one sparse product, not
      * two, since `K'y` is unchanged while `y` has not moved.
      */
    private def step(): Double =
      var movement = 0.0
      var accepted = false
      var trials   = 0

      while !accepted do
        trials += 1
        val tau   = stepSize / primalWeight
        val sigma = stepSize * primalWeight

        k.primalStep(x, ktY, cost, lower, upper, tau, xNext)
        // Extrapolate the primal before the dual sees it. This is what makes
        // PDHG converge without a proximal term on the coupling.
        k.axpby(2.0, xNext, -1.0, x, xBar)
        k.spmv(matrix, xBar, kxBar)
        k.dualStep(y, kxBar, rhs, sigma, nEq, yNext)

        k.axpby(1.0, xNext, -1.0, x, dx)
        k.axpby(1.0, yNext, -1.0, y, dy)
        // K dx falls out of the extrapolation: K xBar = 2 K xNext - K x, so
        // K dx = (K xBar - K x) / 2. No extra sparse product needed.
        k.axpby(0.5, kxBar, -0.5, kx, kdx)

        val dxSq = k.squaredNorm(dx)
        val dySq = k.squaredNorm(dy)
        movement = 0.5 * (primalWeight * dxSq + dySq / primalWeight)

        if movement == 0.0 then accepted = true
        else
          val interaction = math.abs(k.dot(dy, kdx))
          val limit = if interaction > 0.0 then movement / interaction else Double.PositiveInfinity

          // Two competing adjustments: converge towards the largest step the
          // measured curvature allows, but never grow faster than the second
          // factor, so a single lucky iteration cannot blow the step size up.
          val t          = (iteration + 1).toDouble
          val nextByRule = (1.0 - math.pow(t, -0.3)) * limit
          val nextByCap  = (1.0 + math.pow(t, -0.6)) * stepSize
          val proposed   = math.min(nextByRule, nextByCap)

          if stepSize <= limit || trials >= MaxStepTrials then
            // K xNext = (K xBar + K x) / 2, again from the extrapolation.
            k.axpby(0.5, kxBar, 0.5, kx, kx)
            k.copy(xNext, x)
            k.copy(yNext, y)
            k.spmv(matrixTranspose, y, ktY)
            accumulate(stepSize)
            accepted = true

          if proposed.isFinite && proposed > 0.0 then stepSize = proposed

      movement

    private def accumulate(weight: Double): Unit =
      weightSum += weight
      k.axpby(weight, x, 1.0, sumX, sumX)
      k.axpby(weight, y, 1.0, sumY, sumY)
      k.axpby(weight, kx, 1.0, sumKx, sumKx)
      k.axpby(weight, ktY, 1.0, sumKtY, sumKtY)

    // -- evaluation, restarts and termination --------------------------------

    /** Test both candidate iterates for convergence and for infeasibility, then
      * decide whether to restart. `None` means keep going.
      */
    private def checkpoint(): Option[(SolveStatus, KktError)] =
      // Recompute K x from scratch rather than trusting the incremental update,
      // which has been accumulating rounding error since the last checkpoint.
      k.spmv(matrix, x, kx)

      val current = evaluate(x, y, kx, ktY)
      if converged(current) then Some((SolveStatus.Optimal, current))
      else
        val status = classify()
        if status.isDefined then Some((status.get, current))
        else
          val currentError = current.weighted(primalWeight)
          val average      = if weightSum > 0.0 then Some(evaluateAverage()) else None

          if average.exists(converged) then Some((SolveStatus.Optimal, average.get))
          else
            val averageError = average.map(_.weighted(primalWeight)).getOrElse(Double.PositiveInfinity)
            val useAverage   = averageError < currentError
            val candidate    = math.min(currentError, averageError)

            if shouldRestart(candidate) then restartAt(useAverage) else lastCandidate = candidate
            None

    private def evaluateAverage(): KktError =
      val inv = 1.0 / weightSum
      k.scale(inv, sumX, avgX)
      k.scale(inv, sumY, avgY)
      k.scale(inv, sumKx, avgKx)
      k.scale(inv, sumKtY, avgKtY)
      evaluate(avgX, avgY, avgKx, avgKtY)

    /** Evaluate a scaled iterate against the '''original''' problem.
      *
      * Tolerances have to mean something in the user's units, not in the
      * equilibrated ones, so everything is mapped back first. That costs no
      * sparse products: because the scaling is diagonal, the original matrix
      * products are the scaled ones divided elementwise by the same diagonals.
      */
    private def evaluate(xv: k.Vec, yv: k.Vec, kxv: k.Vec, ktYv: k.Vec): KktError =
      k.download(xv, hostX)
      k.download(yv, hostY)
      k.download(kxv, hostKx)
      k.download(ktYv, hostKtY)
      unscaleInPlace()
      Kkt.evaluate(problem, hostX, hostY, hostKx, hostKtY)

    private def unscaleInPlace(): Unit =
      var i = 0
      while i < n do
        hostX(i) *= colScale(i)
        hostKtY(i) /= colScale(i)
        i += 1
      var r = 0
      while r < m do
        hostY(r) *= rowScale(r)
        hostKx(r) /= rowScale(r)
        r += 1

    /** Certificates are tested on the host mirror, which [[evaluate]] has just
      * filled with the unscaled iterate and its matrix products.
      */
    private def classify(): Option[SolveStatus] =
      Certificates.classify(problem, hostX, hostY, params.infeasibilityTolerance, hostKx, hostKtY)

    private def converged(error: KktError): Boolean =
      Kkt.convergence(error, rhsNorm, objectiveNorm, params.epsAbs, params.epsRel).converged

    private def shouldRestart(candidate: Double): Boolean =
      val sufficient = candidate <= params.restartSufficientReduction * errorAtRestart
      val necessary =
        candidate <= params.restartNecessaryReduction * errorAtRestart && candidate > lastCandidate
      val artificial =
        (iteration - lastRestartIt).toDouble >= params.restartArtificialFraction * iteration.toDouble
      sufficient || necessary || artificial

    /** Re-centre on the better of the two iterates and re-balance the primal
      * weight from how far each block travelled over the period just ended.
      *
      * If the primal moved much further than the dual, the primal steps were
      * too large relative to the dual ones; the weight update corrects that,
      * geometrically smoothed so one anomalous period cannot swing it.
      */
    private def restartAt(useAverage: Boolean): Unit =
      if useAverage then
        k.copy(avgX, x)
        k.copy(avgY, y)
        k.copy(avgKx, kx)
        k.copy(avgKtY, ktY)

      k.axpby(1.0, x, -1.0, xRestart, dx)
      k.axpby(1.0, y, -1.0, yRestart, dy)
      val primalMoved = math.sqrt(k.squaredNorm(dx))
      val dualMoved   = math.sqrt(k.squaredNorm(dy))

      if primalMoved > 0.0 && dualMoved > 0.0 then
        val theta = params.primalWeightSmoothing
        val next =
          math.exp(theta * math.log(dualMoved / primalMoved) + (1.0 - theta) * math.log(primalWeight))
        if next.isFinite && next > 0.0 then primalWeight = next

      k.copy(x, xRestart)
      k.copy(y, yRestart)
      resetAverages()
      restarts += 1
      lastRestartIt = iteration
      errorAtRestart = evaluate(x, y, kx, ktY).weighted(primalWeight)
      lastCandidate = Double.PositiveInfinity

    private def timedOut: Boolean = params.timeLimitMillis.exists(elapsedMillis >= _)

    private def elapsedMillis: Long = (System.nanoTime() - startNanos) / 1000000L

    // -- result --------------------------------------------------------------

    private def finish(status: SolveStatus, error: KktError): LpSolution =
      // `hostX`/`hostY` already hold the unscaled iterate this status refers to,
      // because every terminal path runs through `evaluate` last.
      val reduced = new Array[Double](n)
      var i       = 0
      while i < n do
        reduced(i) = problem.objectiveRaw(i) - hostKtY(i)
        i += 1

      LpSolution(
        status = status,
        primal = Unsafe.wrap(hostX.clone()),
        dual = Unsafe.wrap(hostY.clone()),
        reducedCosts = Unsafe.wrap(reduced),
        objectiveValue = error.primalObjective,
        dualObjectiveValue = error.dualObjective,
        iterations = iteration,
        restarts = restarts,
        solveTimeMillis = elapsedMillis,
        kkt = error,
      )

  end Solve

end Pdhg
