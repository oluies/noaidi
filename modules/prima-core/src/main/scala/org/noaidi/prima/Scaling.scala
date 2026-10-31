package org.noaidi.prima

/** Diagonal preconditioning settings.
  *
  * First-order methods converge at a rate governed by the condition of the
  * constraint matrix, and power-system LPs are badly conditioned out of the box
  * — line susceptances and generator capacities differ by orders of magnitude.
  * Equilibrating the matrix first is not an optimisation, it is the difference
  * between converging in thousands of iterations and not converging at all.
  */
final case class ScalingParams(
    ruizIterations: Int = 10,
    applyPockChambolle: Boolean = true,
    pockChambolleAlpha: Double = 1.0,
):
  require(ruizIterations >= 0, s"ruizIterations must be non-negative, got $ruizIterations")
  require(
    pockChambolleAlpha >= 0.0 && pockChambolleAlpha <= 2.0,
    s"pockChambolleAlpha must lie in [0, 2], got $pockChambolleAlpha",
  )

object ScalingParams:
  val default: ScalingParams = ScalingParams()
  val none: ScalingParams    = ScalingParams(ruizIterations = 0, applyPockChambolle = false)

/** A rescaled problem plus the diagonals needed to undo the rescaling.
  *
  * With `K~ = Dr K Dc`, the solved variables relate to the original ones by
  * `x = Dc x~` and `y = Dr y~`, which is what [[unscalePrimal]] and
  * [[unscaleDual]] apply.
  */
final class ScaledProblem private[prima] (
    val problem: LpProblem,
    private[prima] val rowScale: Array[Double],
    private[prima] val colScale: Array[Double],
):

  def unscalePrimal(x: Array[Double]): Array[Double] =
    val out = new Array[Double](x.length)
    var i   = 0
    while i < x.length do
      out(i) = x(i) * colScale(i)
      i += 1
    out

  def unscaleDual(y: Array[Double]): Array[Double] =
    val out = new Array[Double](y.length)
    var i   = 0
    while i < y.length do
      out(i) = y(i) * rowScale(i)
      i += 1
    out

  /** Map a starting point in original coordinates into scaled coordinates. */
  def scalePrimal(x: Array[Double]): Array[Double] =
    val out = new Array[Double](x.length)
    var i   = 0
    while i < x.length do
      out(i) = x(i) / colScale(i)
      i += 1
    out

/** An equilibration, separated from the problem it was derived from.
  *
  * Holds the scaled matrix and the accumulated row and column factors -- the
  * part of [[Scaling]] that reads only the constraint matrix. Applying it to a
  * problem that shares that matrix produces the same [[ScaledProblem]] the
  * one-shot [[Scaling.apply]] would, for one pass over `n + m` values instead of
  * several over the nonzeros.
  *
  * `source` is kept and checked by reference. A plan is only valid for the
  * matrix it was built from, and the failure if it were not is silent: the
  * factors would equilibrate nothing in particular, the solve would converge to
  * the wrong problem's geometry, and the answer would come back labelled
  * `Optimal`. Reference equality is the right test rather than a conservative
  * one, because the caller this exists for -- a branch-and-bound node -- passes
  * the very same instance by construction, and anything that does not is a
  * mistake rather than a different spelling.
  */
final class ScalingPlan private[prima] (
    private[prima] val source: SparseMatrix,
    private[prima] val matrix: SparseMatrix,
    private[prima] val rowScale: Array[Double],
    private[prima] val colScale: Array[Double],
):

  /** This equilibration, applied to a problem over the same matrix. */
  private[prima] def applyTo(problem: LpProblem): ScaledProblem =
    require(
      problem.constraintMatrix eq source,
      "a ScalingPlan was applied to a problem with a different constraint matrix; " +
        "the factors describe the matrix it was built from and mean nothing for another",
    )
    Scaling.scaledProblemOf(this, problem)

object Scaling:

  /** Equilibrate a problem with Ruiz iterations followed by an optional
    * Pock-Chambolle pass, exactly as PDLP does.
    *
    * Ruiz repeatedly divides each row and column by the square root of its
    * infinity norm, driving every row and column norm towards one.
    * Pock-Chambolle then applies the diagonal scaling that the PDHG convergence
    * proof asks for, which fixes the step-size condition without needing an
    * estimate of the spectral norm.
    *
    * Empty rows and columns are left at scale one; there is nothing to
    * equilibrate and dividing by their zero norm would produce infinities.
    */
  def apply(problem: LpProblem, params: ScalingParams): ScaledProblem =
    plan(problem, params).applyTo(problem)

  /** The half of a scaling that depends only on the constraint matrix.
    *
    * Everything below the equilibration loop -- the cost, the right-hand side
    * and the box -- is one pass over `n + m` values. Everything inside it is
    * several passes over the nonzeros, and two whole matrices per iteration: a
    * `transpose` to reach the columns and a `scaledBy` to apply the factors.
    * The split is there because the second half is identical for any two
    * problems sharing a matrix, and the first is not.
    *
    * Which is exactly the shape of a branch-and-bound search. Every node is the
    * root's matrix with two different bound vectors, so a search of 423 nodes
    * equilibrated the same matrix 423 times -- 86% of the solver's allocation,
    * measured, and 4% to 25% of a node's solve depending on how fast that node
    * converges. [[BranchAndBound]] now computes this once and hands it to every
    * node.
    *
    * Note that the reuse extends past the matrix itself: `transpose` is a lazy
    * val, so a plan that is reused hands back the same instance and its
    * transpose is computed once rather than per node.
    */
  def plan(problem: LpProblem, params: ScalingParams): ScalingPlan =
    val m = problem.numConstraints
    val n = problem.numVariables

    val cumulativeRow = Array.fill(m)(1.0)
    val cumulativeCol = Array.fill(n)(1.0)
    var matrix        = problem.constraintMatrix

    val rowFactor = new Array[Double](m)
    val colFactor = new Array[Double](n)

    var iteration = 0
    while iteration < params.ruizIterations do
      var r = 0
      while r < m do
        val norm = matrix.rowMaxAbs(r)
        rowFactor(r) = if norm > 0.0 then 1.0 / math.sqrt(norm) else 1.0
        r += 1

      val t = matrix.transpose
      var c = 0
      while c < n do
        val norm = t.rowMaxAbs(c)
        colFactor(c) = if norm > 0.0 then 1.0 / math.sqrt(norm) else 1.0
        c += 1

      matrix = matrix.scaledBy(rowFactor, colFactor)
      accumulate(cumulativeRow, rowFactor)
      accumulate(cumulativeCol, colFactor)
      iteration += 1

    if params.applyPockChambolle then
      val alpha = params.pockChambolleAlpha
      var r     = 0
      while r < m do
        val s = matrix.rowAbsPowSum(r, 2.0 - alpha)
        rowFactor(r) = if s > 0.0 then 1.0 / math.sqrt(s) else 1.0
        r += 1

      val t = matrix.transpose
      var c = 0
      while c < n do
        val s = t.rowAbsPowSum(c, alpha)
        colFactor(c) = if s > 0.0 then 1.0 / math.sqrt(s) else 1.0
        c += 1

      matrix = matrix.scaledBy(rowFactor, colFactor)
      accumulate(cumulativeRow, rowFactor)
      accumulate(cumulativeCol, colFactor)

    new ScalingPlan(problem.constraintMatrix, matrix, cumulativeRow, cumulativeCol)

  private[prima] def scaledProblemOf(
      plan: ScalingPlan,
      problem: LpProblem,
  ): ScaledProblem =
    val n             = problem.numVariables
    val m             = problem.numConstraints
    val cumulativeRow = plan.rowScale
    val cumulativeCol = plan.colScale

    // c~ = Dc c, q~ = Dr q, and the box scales inversely with the variables.
    val cost  = new Array[Double](n)
    val lower = new Array[Double](n)
    val upper = new Array[Double](n)
    var i     = 0
    while i < n do
      val d = cumulativeCol(i)
      cost(i) = problem.objectiveRaw(i) * d
      // `d` is strictly positive, so infinities pass through with their sign.
      lower(i) = problem.lowerRaw(i) / d
      upper(i) = problem.upperRaw(i) / d
      i += 1

    val rhs = new Array[Double](m)
    var r   = 0
    while r < m do
      rhs(r) = problem.rhsRaw(r) * cumulativeRow(r)
      r += 1

    val scaled = LpProblem(
      objective = Unsafe.wrap(cost),
      constraintMatrix = plan.matrix,
      rhs = Unsafe.wrap(rhs),
      numEqualities = problem.numEqualities,
      variableLower = Unsafe.wrap(lower),
      variableUpper = Unsafe.wrap(upper),
      objectiveOffset = problem.objectiveOffset,
    )
    new ScaledProblem(scaled, cumulativeRow, cumulativeCol)

  private def accumulate(cumulative: Array[Double], factor: Array[Double]): Unit =
    var i = 0
    while i < cumulative.length do
      cumulative(i) *= factor(i)
      i += 1

end Scaling
