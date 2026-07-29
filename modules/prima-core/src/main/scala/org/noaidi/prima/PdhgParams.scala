package org.noaidi.prima

/** Tuning for the restarted PDHG method.
  *
  * Defaults follow PDLP. The restart and step-size constants in particular are
  * not free parameters to taste — they come from the paper's experiments and
  * changing them mostly makes things worse.
  */
final case class PdhgParams(
    /** Absolute slack allowed on each termination criterion. */
    epsAbs: Double = 1e-8,

    /** Slack proportional to the problem's own data norms. */
    epsRel: Double = 1e-8,

    maxIterations: Int = 100_000,

    timeLimitMillis: Option[Long] = None,

    /** How often to leave the inner loop to test convergence and restarts.
      *
      * Every evaluation costs a device-to-host copy, so testing each iteration
      * would dominate the run on a GPU; testing too rarely wastes iterations
      * past the point of convergence.
      */
    evaluationFrequency: Int = 64,

    scaling: ScalingParams = ScalingParams.default,

    /** How close a Farkas direction must come to being exact before the solver
      * is willing to declare infeasibility on its strength.
      */
    infeasibilityTolerance: Double = 1e-8,

    /** Restart as soon as the KKT error has fallen this far since the last
      * restart, without waiting for anything else.
      */
    restartSufficientReduction: Double = 0.2,

    /** Restart on this much reduction, but only once progress has stalled. */
    restartNecessaryReduction: Double = 0.8,

    /** Restart unconditionally once the current period has run this long
      * relative to the whole solve, which bounds how much work a period can
      * waste when the error measure is uninformative.
      */
    restartArtificialFraction: Double = 0.36,

    /** Weight on the new estimate when re-balancing primal against dual
      * progress at a restart. `0` freezes the primal weight, `1` discards
      * history.
      */
    primalWeightSmoothing: Double = 0.5,
):
  require(epsAbs >= 0.0, s"epsAbs must be non-negative, got $epsAbs")
  require(epsRel >= 0.0, s"epsRel must be non-negative, got $epsRel")
  require(maxIterations > 0, s"maxIterations must be positive, got $maxIterations")
  require(evaluationFrequency > 0, s"evaluationFrequency must be positive, got $evaluationFrequency")
  require(
    primalWeightSmoothing >= 0.0 && primalWeightSmoothing <= 1.0,
    s"primalWeightSmoothing must lie in [0, 1], got $primalWeightSmoothing",
  )
  require(
    restartSufficientReduction > 0.0 && restartSufficientReduction <= restartNecessaryReduction,
    "restartSufficientReduction must be positive and no greater than restartNecessaryReduction",
  )

object PdhgParams:
  val default: PdhgParams = PdhgParams()

  /** Loose tolerances, for a first pass or for a subproblem inside a
    * branch-and-bound tree where an exact bound is not yet worth paying for.
    */
  val loose: PdhgParams = PdhgParams(epsAbs = 1e-6, epsRel = 1e-6)
