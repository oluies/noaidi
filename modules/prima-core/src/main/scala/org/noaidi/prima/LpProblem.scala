package org.noaidi.prima

import scala.collection.mutable

/** A linear program in the standard form the first-order method operates on:
  *
  * {{{
  *   minimise    c' x + offset
  *   subject to  K x  = q   (first `numEqualities` rows)
  *               K x >= q   (remaining rows)
  *               l <= x <= u
  * }}}
  *
  * Equality rows come first so that the dual cone is a simple suffix condition
  * — `y` is free on the head and non-negative on the tail — which makes the
  * dual projection a contiguous clamp and therefore trivial to run on a GPU.
  *
  * Bounds may be infinite on either side. This is the interface the
  * solver-agnostic modeling layer compiles down to, so it deliberately carries
  * no notion of variable names, row names or model structure.
  */
final class LpProblem private (
    val objective: IArray[Double],
    val objectiveOffset: Double,
    val constraintMatrix: SparseMatrix,
    val rhs: IArray[Double],
    val numEqualities: Int,
    val variableLower: IArray[Double],
    val variableUpper: IArray[Double],
):

  def numVariables: Int   = objective.length
  def numConstraints: Int = rhs.length
  def numInequalities: Int = numConstraints - numEqualities

  private[prima] val objectiveRaw: Array[Double] = Unsafe.raw(objective)
  private[prima] val rhsRaw: Array[Double]       = Unsafe.raw(rhs)
  private[prima] val lowerRaw: Array[Double]     = Unsafe.raw(variableLower)
  private[prima] val upperRaw: Array[Double]     = Unsafe.raw(variableUpper)

  /** `c' x + offset` for a candidate primal point. */
  def primalObjective(x: IArray[Double]): Double =
    require(x.length == numVariables, s"expected $numVariables variables, got ${x.length}")
    var s = objectiveOffset
    var i = 0
    val v = Unsafe.raw(x)
    while i < numVariables do
      s += objectiveRaw(i) * v(i)
      i += 1
    s

  override def toString: String =
    s"LpProblem(vars=$numVariables, eq=$numEqualities, ineq=$numInequalities, nnz=${constraintMatrix.nnz})"

end LpProblem

object LpProblem:

  /** Direct construction in standard form. Prefer [[builder]] for anything
    * written by hand.
    */
  def apply(
      objective: IArray[Double],
      constraintMatrix: SparseMatrix,
      rhs: IArray[Double],
      numEqualities: Int,
      variableLower: IArray[Double],
      variableUpper: IArray[Double],
      objectiveOffset: Double = 0.0,
  ): LpProblem =
    val n = objective.length
    require(constraintMatrix.cols == n, s"matrix has ${constraintMatrix.cols} columns, expected $n")
    require(
      constraintMatrix.rows == rhs.length,
      s"matrix has ${constraintMatrix.rows} rows but rhs has ${rhs.length} entries",
    )
    require(
      numEqualities >= 0 && numEqualities <= rhs.length,
      s"numEqualities $numEqualities out of range [0, ${rhs.length}]",
    )
    require(variableLower.length == n, s"lower bounds have ${variableLower.length} entries, expected $n")
    require(variableUpper.length == n, s"upper bounds have ${variableUpper.length} entries, expected $n")
    var i = 0
    while i < n do
      require(
        variableLower(i) <= variableUpper(i),
        s"variable $i has empty bound interval [${variableLower(i)}, ${variableUpper(i)}]",
      )
      require(objective(i).isFinite, s"objective coefficient $i is not finite: ${objective(i)}")
      i += 1
    var r = 0
    while r < rhs.length do
      require(rhs(r).isFinite, s"right-hand side $r is not finite: ${rhs(r)}")
      r += 1

    new LpProblem(
      objective,
      objectiveOffset,
      constraintMatrix,
      rhs,
      numEqualities,
      variableLower,
      variableUpper,
    )

  def builder(initialVariables: Int): LpBuilder = new LpBuilder(initialVariables)

end LpProblem

/** How an original two-sided row was expanded into standard-form rows.
  *
  * A range constraint `lo <= a'x <= hi` with two distinct finite sides needs two
  * `>=` rows, so its dual is recovered as `y(positive) - y(negative)`. Keeping
  * the mapping lets callers get duals back in their own row numbering, which is
  * what nodal prices need.
  */
enum RowExpansion:
  /** A single standard-form row, used as-is. */
  case Direct(row: Int)

  /** The row was negated to turn `a'x <= hi` into `-a'x >= -hi`. */
  case Negated(row: Int)

  /** A range row split into `a'x >= lo` and `-a'x >= -hi`. */
  case Range(lowerRow: Int, upperRow: Int)

/** Translation from a user-facing model back to standard form and back again. */
final class RowTranslation(private val expansions: IndexedSeq[RowExpansion]):
  def numOriginalRows: Int = expansions.length

  def expansionOf(originalRow: Int): RowExpansion = expansions(originalRow)

  /** Map standard-form duals back onto the original rows, restoring the sign
    * convention of the row as the caller wrote it.
    */
  def originalDuals(standardDuals: IArray[Double]): IArray[Double] =
    val out = new Array[Double](expansions.length)
    var i   = 0
    while i < expansions.length do
      out(i) = expansions(i) match
        case RowExpansion.Direct(r)      => standardDuals(r)
        case RowExpansion.Negated(r)     => -standardDuals(r)
        case RowExpansion.Range(lo, hi)  => standardDuals(lo) - standardDuals(hi)
      i += 1
    Unsafe.wrap(out)

/** Incremental construction of an LP from two-sided row bounds.
  *
  * Rows are given as `lower <= a'x <= upper` with either side allowed to be
  * infinite, which is how power-system models are naturally written; the
  * builder does the conversion into equalities-then-inequalities form and hands
  * back the mapping needed to interpret the duals.
  */
final class LpBuilder(initialVariables: Int):
  require(initialVariables >= 0, s"numVariables must be non-negative, got $initialVariables")

  // Growable rather than fixed at construction, so a caller can add a column after it
  // has started adding rows.
  //
  // Fixed arrays made the column count part of the constructor's contract, and a model
  // that discovers a variable late -- a piecewise segment, a tier of a bid ladder -- then
  // has to allocate every column before the builder exists and emit its rows afterwards,
  // in two passes with the ordering rule between them unstated. The first family to need
  // that got it wrong twice: once by declaring after the builder (an out-of-range index),
  // once by emitting its equality after the inequalities (which silently breaks the
  // row-index identity `Sclopf` depends on). Both were the split's doing rather than the
  // model's.
  // Primitive arrays with a length and manual doubling, not `ArrayBuffer[Double]`.
  //
  // `ArrayBuffer[Double]` is not specialised, so every coefficient and bound becomes a
  // boxed `java.lang.Double` -- three per column at construction, another on every
  // `objectiveCoefficient` write, then all of them unboxed again by `toArray` in
  // `build()`. At 60,552 columns for `scigrid-de` and far more on a year-long network
  // that is tens of megabytes of transient garbage and an indirection per element, on
  // the one path this module optimises on purpose: `Unsafe.wrap` hands arrays over
  // without copying, and both constraint families take a `collection.Map` to avoid a
  // per-build copy of the column index.
  private var objective = new Array[Double](math.max(initialVariables, 16))
  private var lower     = Array.fill(math.max(initialVariables, 16))(Double.NegativeInfinity)
  private var upper     = Array.fill(math.max(initialVariables, 16))(Double.PositiveInfinity)
  private var columns   = initialVariables
  private var offset    = 0.0

  /** How many columns the problem has so far. */
  def numVariables: Int = columns

  /** Double the three column arrays when the next column would not fit.
    *
    * The fresh tail is left at whatever `copyOf` zeroes it to, and deliberately so. A
    * first version refilled it with the unbounded defaults, on the reasoning that a
    * column nobody has bounded must not read as `[0, 0]` -- but no column can ever be
    * read from that tail: [[addVariable]] writes all three of its values before
    * incrementing `columns`, and `checkVariable` refuses any index at or past `columns`,
    * so the only entries `build` trims to are ones that were written explicitly.
    *
    * That refill was unreachable, and a mutation removing it failed no test -- which is
    * how it was found, rather than by reading it back.
    */
  private def grow(): Unit =
    if columns == objective.length then
      val size = objective.length * 2
      objective = java.util.Arrays.copyOf(objective, size)
      lower     = java.util.Arrays.copyOf(lower, size)
      upper     = java.util.Arrays.copyOf(upper, size)

  /** Add a column, and return its index.
    *
    * The bounds and the objective coefficient go on at once, because a column added
    * halfway through a build has no other moment where all three are known together --
    * and because [[objectiveCoefficient]] sets rather than adds, so a later caller
    * reaching for it would overwrite whatever this column was declared with.
    */
  def addVariable(lo: Double, hi: Double, cost: Double): Int =
    require(lo <= hi, s"new variable has empty bound interval [$lo, $hi]")
    grow()
    val index = columns
    objective(index) = cost
    lower(index)     = lo
    upper(index)     = hi
    columns += 1
    index

  private final case class Row(coefficients: Seq[(Int, Double)], lo: Double, hi: Double)
  private val rows = mutable.ArrayBuffer.empty[Row]

  private def checkVariable(variable: Int): Unit =
    require(
      variable >= 0 && variable < columns,
      s"variable index $variable out of range [0, $columns)",
    )

  def objectiveCoefficient(variable: Int, value: Double): this.type =
    checkVariable(variable)
    objective(variable) = value
    this

  def objectiveOffset(value: Double): this.type =
    offset = value
    this

  def bounds(variable: Int, lo: Double, hi: Double): this.type =
    checkVariable(variable)
    require(lo <= hi, s"variable $variable has empty bound interval [$lo, $hi]")
    lower(variable) = lo
    upper(variable) = hi
    this

  /** `lo <= a'x <= hi`. Use infinities for one-sided rows and `lo == hi` for an
    * equality.
    */
  def constraint(coefficients: Seq[(Int, Double)], lo: Double, hi: Double): this.type =
    require(lo <= hi, s"constraint has empty range [$lo, $hi]")
    require(
      !(lo.isNegInfinity && hi.isPosInfinity),
      "constraint is unbounded on both sides and would have no effect",
    )
    coefficients.foreach { (v, _) => checkVariable(v) }
    rows += Row(coefficients, lo, hi)
    this

  def equalityConstraint(coefficients: Seq[(Int, Double)], value: Double): this.type =
    constraint(coefficients, value, value)

  def greaterThan(coefficients: Seq[(Int, Double)], lo: Double): this.type =
    constraint(coefficients, lo, Double.PositiveInfinity)

  def lessThan(coefficients: Seq[(Int, Double)], hi: Double): this.type =
    constraint(coefficients, Double.NegativeInfinity, hi)

  def build(): (LpProblem, RowTranslation) =
    // Equalities first, then inequalities, so the dual cone is a suffix.
    val equalityRows   = rows.zipWithIndex.filter { (r, _) => r.lo == r.hi }
    val inequalityRows = rows.zipWithIndex.filter { (r, _) => r.lo != r.hi }

    val entries    = mutable.ArrayBuffer.empty[(Int, Int, Double)]
    val rhs        = mutable.ArrayBuffer.empty[Double]
    val expansions = new Array[RowExpansion](rows.length)

    def emit(coefficients: Seq[(Int, Double)], sign: Double, value: Double): Int =
      val r = rhs.length
      coefficients.foreach { (v, a) => entries += ((r, v, sign * a)) }
      rhs += sign * value
      r

    equalityRows.foreach { (row, originalIndex) =>
      expansions(originalIndex) = RowExpansion.Direct(emit(row.coefficients, 1.0, row.lo))
    }
    val numEqualities = rhs.length

    inequalityRows.foreach { (row, originalIndex) =>
      val hasLo = !row.lo.isNegInfinity
      val hasHi = !row.hi.isPosInfinity
      expansions(originalIndex) =
        if hasLo && hasHi then
          RowExpansion.Range(emit(row.coefficients, 1.0, row.lo), emit(row.coefficients, -1.0, row.hi))
        else if hasLo then RowExpansion.Direct(emit(row.coefficients, 1.0, row.lo))
        else RowExpansion.Negated(emit(row.coefficients, -1.0, row.hi))
    }

    val problem = LpProblem(
      // Trimmed to `columns` rather than handed over whole: the arrays carry spare
      // capacity from `grow`, and its tail is not part of the problem.
      objective = Unsafe.wrap(java.util.Arrays.copyOf(objective, columns)),
      constraintMatrix = SparseMatrix.fromTriplets(rhs.length, columns, entries),
      rhs = Unsafe.wrap(rhs.toArray),
      numEqualities = numEqualities,
      variableLower = Unsafe.wrap(java.util.Arrays.copyOf(lower, columns)),
      variableUpper = Unsafe.wrap(java.util.Arrays.copyOf(upper, columns)),
      objectiveOffset = offset,
    )
    (problem, RowTranslation(expansions.toIndexedSeq))

end LpBuilder
