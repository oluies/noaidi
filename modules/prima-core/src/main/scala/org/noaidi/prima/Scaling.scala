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
      constraintMatrix = matrix,
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
