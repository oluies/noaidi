package org.noaidi.prima

/** Optimality measures for a primal-dual pair.
  *
  * Residual norms are absolute; turning them into a stopping decision needs the
  * problem's own scale, which is what [[Kkt.evaluate]] carries alongside them.
  */
final case class KktError(
    primalResidualNorm: Double,
    dualResidualNorm: Double,
    primalObjective: Double,
    dualObjective: Double,
):
  def dualityGap: Double = primalObjective - dualObjective

  def absoluteGap: Double = math.abs(dualityGap)

  def isFinite: Boolean =
    primalResidualNorm.isFinite && dualResidualNorm.isFinite &&
      primalObjective.isFinite && dualObjective.isFinite

  /** A single scalar summarising how far the pair is from optimal, weighted by
    * the primal weight so that it is comparable across restarts.
    *
    * This drives restart decisions only — never termination — so the precise
    * weighting is a heuristic, not part of the correctness argument.
    */
  def weighted(primalWeight: Double): Double =
    math.sqrt(
      primalWeight * primalResidualNorm * primalResidualNorm +
        (dualResidualNorm * dualResidualNorm) / primalWeight +
        dualityGap * dualityGap
    )

/** Whether a pair meets the requested tolerances, and by how much. */
final case class Convergence(
    error: KktError,
    primalTolerance: Double,
    dualTolerance: Double,
    gapTolerance: Double,
):
  def primalFeasible: Boolean = error.primalResidualNorm <= primalTolerance
  def dualFeasible: Boolean   = error.dualResidualNorm <= dualTolerance
  def gapClosed: Boolean      = error.absoluteGap <= gapTolerance

  def converged: Boolean = error.isFinite && primalFeasible && dualFeasible && gapClosed

  /** Worst of the three criteria as a ratio of its tolerance; `<= 1` means
    * converged. Useful for logging progress that is comparable across problems.
    */
  def relativeError: Double =
    math.max(
      error.primalResidualNorm / primalTolerance,
      math.max(error.dualResidualNorm / dualTolerance, error.absoluteGap / gapTolerance),
    )

object Kkt:

  /** Evaluate a primal-dual pair.
    *
    * `x` is assumed to already lie inside the variable box and `y` inside the
    * dual cone — both hold by construction, since every iterate comes out of a
    * projection — so bound and cone violations are not re-checked here.
    *
    * `kx` and `ktY` may be supplied when the caller already has them, which the
    * solver does; passing `null` recomputes them.
    */
  def evaluate(
      problem: LpProblem,
      x: Array[Double],
      y: Array[Double],
      kx: Array[Double] | Null = null,
      ktY: Array[Double] | Null = null,
  ): KktError =
    val m       = problem.numConstraints
    val n       = problem.numVariables
    val q       = problem.rhsRaw
    val c       = problem.objectiveRaw
    val lower   = problem.lowerRaw
    val upper   = problem.upperRaw

    val ax =
      if kx != null then kx
      else
        val buf = new Array[Double](m)
        problem.constraintMatrix.multiplyInto(x, buf)
        buf

    val primalSq = primalResidualSquared(problem, ax)

    var primalObj = problem.objectiveOffset
    var i         = 0
    while i < n do
      primalObj += c(i) * x(i)
      i += 1

    val aty =
      if ktY != null then ktY
      else
        val buf = new Array[Double](n)
        problem.constraintMatrix.transpose.multiplyInto(y, buf)
        buf

    // Dual objective is `q'y` plus, for each variable, the bound its reduced
    // cost pushes it against. A reduced cost pushing towards an infinite bound
    // cannot be absorbed and becomes dual residual instead.
    var dualObj = problem.objectiveOffset
    i = 0
    while i < m do
      dualObj += q(i) * y(i)
      i += 1

    var dualSq = 0.0
    i = 0
    while i < n do
      val reduced = c(i) - aty(i)
      if reduced > 0.0 then
        val l = lower(i)
        if l.isNegInfinity then dualSq += reduced * reduced else dualObj += l * reduced
      else if reduced < 0.0 then
        val u = upper(i)
        if u.isPosInfinity then dualSq += reduced * reduced else dualObj += u * reduced
      i += 1

    KktError(math.sqrt(primalSq), math.sqrt(dualSq), primalObj, dualObj)

  /** Apply relative tolerances in the style of PDLP: each criterion is allowed
    * an absolute slack plus a share of the corresponding data norm.
    */
  def convergence(
      error: KktError,
      rhsNorm: Double,
      objectiveNorm: Double,
      epsAbs: Double,
      epsRel: Double,
  ): Convergence =
    Convergence(
      error = error,
      primalTolerance = epsAbs + epsRel * rhsNorm,
      dualTolerance = epsAbs + epsRel * objectiveNorm,
      gapTolerance =
        epsAbs + epsRel * (math.abs(error.primalObjective) + math.abs(error.dualObjective)),
    )

  /** Primal residual norm alone, for a solver that produces a primal solution
    * but no trustworthy duals.
    *
    * The alternative — evaluating with a placeholder dual — would return a
    * fully-populated [[KktError]] whose dual fields are arithmetic on numbers
    * that mean nothing.
    */
  def primalResidualNorm(problem: LpProblem, x: Array[Double]): Double =
    val ax = new Array[Double](problem.numConstraints)
    problem.constraintMatrix.multiplyInto(x, ax)
    math.sqrt(primalResidualSquared(problem, ax))

  /** The single definition of primal infeasibility: equalities must hold
    * exactly, and `Kx >= q` rows contribute only their shortfall.
    *
    * Shared by [[evaluate]] and [[primalResidualNorm]] so the two cannot drift
    * apart — otherwise a residual reported by one backend would stop meaning
    * the same thing as one reported by another.
    */
  private def primalResidualSquared(problem: LpProblem, ax: Array[Double]): Double =
    val m   = problem.numConstraints
    val nEq = problem.numEqualities
    val q   = problem.rhsRaw

    var sum = 0.0
    var i   = 0
    while i < nEq do
      val r = ax(i) - q(i)
      sum += r * r
      i += 1
    while i < m do
      val shortfall = q(i) - ax(i)
      if shortfall > 0.0 then sum += shortfall * shortfall
      i += 1
    sum

  private[prima] def norm2(a: Array[Double]): Double =
    var s = 0.0
    var i = 0
    while i < a.length do
      s += a(i) * a(i)
      i += 1
    math.sqrt(s)

end Kkt
