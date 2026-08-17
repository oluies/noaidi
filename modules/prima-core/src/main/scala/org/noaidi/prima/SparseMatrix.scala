package org.noaidi.prima

import scala.collection.mutable

/** A sparse matrix in compressed sparse row form.
  *
  * Column indices within a row are stored in ascending order and duplicate
  * entries are summed at construction, so the representation is canonical: two
  * matrices with the same mathematical content have identical arrays.
  *
  * The backing arrays are primitive and are never mutated after construction.
  * They are exposed to the rest of `org.noaidi.prima` directly so that kernels
  * can run over them without copying; public callers only ever see `IArray`.
  */
final class SparseMatrix private (
    val rows: Int,
    val cols: Int,
    private[prima] val rowPtrRaw: Array[Int],
    private[prima] val colIndicesRaw: Array[Int],
    private[prima] val valuesRaw: Array[Double],
):

  /** Number of stored (structurally non-zero) entries. */
  def nnz: Int = rowPtrRaw(rows)

  def rowPtr: IArray[Int]     = Unsafe.wrapInts(rowPtrRaw)
  def colIndices: IArray[Int] = Unsafe.wrapInts(colIndicesRaw)
  def values: IArray[Double]  = Unsafe.wrap(valuesRaw)

  /** The transpose, in CSR form.
    *
    * PDHG needs `K x` and `K' y` on every iteration. Rather than paying for a
    * gather-scatter transpose product, we materialise the transpose once and
    * run the same row-major kernel over both.
    */
  lazy val transpose: SparseMatrix =
    val n       = nnz
    val tRowPtr = new Array[Int](cols + 1)
    var k       = 0
    while k < n do
      tRowPtr(colIndicesRaw(k) + 1) += 1
      k += 1
    var c = 0
    while c < cols do
      tRowPtr(c + 1) += tRowPtr(c)
      c += 1

    val cursor = java.util.Arrays.copyOf(tRowPtr, cols)
    val tCols  = new Array[Int](n)
    val tVals  = new Array[Double](n)
    var r      = 0
    while r < rows do
      var p = rowPtrRaw(r)
      val e = rowPtrRaw(r + 1)
      while p < e do
        val col  = colIndicesRaw(p)
        val dest = cursor(col)
        tCols(dest) = r
        tVals(dest) = valuesRaw(p)
        cursor(col) = dest + 1
        p += 1
      r += 1

    new SparseMatrix(cols, rows, tRowPtr, tCols, tVals)

  /** Value at `(row, col)`, or 0.0 if not stored. Binary search; for tests and
    * diagnostics, not for hot paths.
    */
  def apply(row: Int, col: Int): Double =
    require(row >= 0 && row < rows, s"row $row out of range [0, $rows)")
    require(col >= 0 && col < cols, s"col $col out of range [0, $cols)")
    val idx = java.util.Arrays.binarySearch(colIndicesRaw, rowPtrRaw(row), rowPtrRaw(row + 1), col)
    if idx >= 0 then valuesRaw(idx) else 0.0

  /** `out := this * x`. Allocation-free; `out` must have length `rows`. */
  private[prima] def multiplyInto(x: Array[Double], out: Array[Double]): Unit =
    var r = 0
    while r < rows do
      var acc = 0.0
      var p   = rowPtrRaw(r)
      val e   = rowPtrRaw(r + 1)
      while p < e do
        acc += valuesRaw(p) * x(colIndicesRaw(p))
        p += 1
      out(r) = acc
      r += 1

  def multiply(x: IArray[Double]): IArray[Double] =
    require(x.length == cols, s"expected a vector of length $cols, got ${x.length}")
    val out = new Array[Double](rows)
    multiplyInto(Unsafe.raw(x), out)
    Unsafe.wrap(out)

  /** Largest absolute value in row `r`, or 0.0 for an empty row. */
  private[prima] def rowMaxAbs(r: Int): Double =
    var m = 0.0
    var p = rowPtrRaw(r)
    val e = rowPtrRaw(r + 1)
    while p < e do
      val a = math.abs(valuesRaw(p))
      if a > m then m = a
      p += 1
    m

  /** Sum of `|a_rj|^alpha` over row `r`. Used by Pock-Chambolle scaling. */
  private[prima] def rowAbsPowSum(r: Int, alpha: Double): Double =
    var s = 0.0
    var p = rowPtrRaw(r)
    val e = rowPtrRaw(r + 1)
    while p < e do
      s += math.pow(math.abs(valuesRaw(p)), alpha)
      p += 1
    s

  /** `diag(rowScale) * this * diag(colScale)`, preserving the sparsity pattern. */
  private[prima] def scaledBy(rowScale: Array[Double], colScale: Array[Double]): SparseMatrix =
    val n   = nnz
    val out = new Array[Double](n)
    var r   = 0
    while r < rows do
      val rs = rowScale(r)
      var p  = rowPtrRaw(r)
      val e  = rowPtrRaw(r + 1)
      while p < e do
        out(p) = valuesRaw(p) * rs * colScale(colIndicesRaw(p))
        p += 1
      r += 1
    new SparseMatrix(rows, cols, rowPtrRaw, colIndicesRaw, out)

  /** Largest absolute entry in the whole matrix; 0.0 if empty. */
  def maxAbs: Double =
    var m = 0.0
    var k = 0
    while k < nnz do
      val a = math.abs(valuesRaw(k))
      if a > m then m = a
      k += 1
    m

  /** Euclidean norm of each row.
    *
    * Per row rather than over the whole matrix, because that is what makes a
    * residual on one row dimensionless without dragging in coefficients from
    * rows it has nothing to do with. `|(Kd)_i| <= ||K_i.||_2 * ||d||_2` by
    * Cauchy-Schwarz, so dividing by this and by `||d||` gives a ratio in
    * `[0, 1]` that no other row can move. [[Certificates]] documents what went
    * wrong when a global norm was used instead.
    *
    * A structurally empty row gives 0.0, and the matching `(Kd)_i` is then
    * exactly zero, so callers skip the division rather than producing a NaN.
    */
  private[prima] lazy val rowNorms: Array[Double] =
    val out = new Array[Double](rows)
    var r   = 0
    while r < rows do
      // Scaled by the row's largest entry before squaring. Accumulating `v*v`
      // directly overflows to Infinity above ~1.3e154 and underflows to 0.0
      // below ~1.5e-154, and both erase the residual the norm divides: an
      // infinite norm sends it to zero, a zero norm takes the "empty column"
      // branch in `Certificates` and drops it. That would resurrect the exact
      // dilution this normalisation exists to prevent -- `1e200 x0 >= 1` is
      // feasible at `x0 = 1e-200` and would be reported infeasible -- and
      // nothing in `LpProblem.constraint` bounds coefficient magnitude. The
      // global `spectralNormBound` this replaced had no such hazard, being
      // built from absolute sums.
      val mx = rowMaxAbs(r)
      if mx > 0.0 then
        var s = 0.0
        var p = rowPtrRaw(r)
        val e = rowPtrRaw(r + 1)
        while p < e do
          val t = valuesRaw(p) / mx
          s += t * t
          p += 1
        out(r) = mx * math.sqrt(s)
      else out(r) = 0.0
      r += 1
    out

  /** Euclidean norm of each column, the counterpart of [[rowNorms]] for `K'y`.
    *
    * Accumulated straight off the CSR arrays rather than as `transpose.rowNorms`,
    * so reading it does not *force* the transpose. That matters for callers who
    * would not otherwise build one -- [[Certificates]] used standalone, or a
    * solve with `ScalingParams.none`.
    *
    * It does '''not''' save anything on the default solve path, and an earlier
    * version of this comment claimed it did. `Pdhg.Solve` hands the same
    * `problem` to `Scaling` and to `Certificates.classify`, and `Scaling`
    * evaluates `matrix.transpose` on its first Ruiz iteration; `Kkt` and
    * `Presolve` reach for it too. Being a `lazy val`, it is already materialised
    * and retained by then. Cheaper than a second traversal either way: `cols`
    * doubles, one O(nnz) pass.
    *
    * Scaled like [[rowNorms]], in two passes: the column maxima first, then the
    * scaled squares.
    */
  private[prima] lazy val columnNorms: Array[Double] =
    val maxima = new Array[Double](cols)
    var k      = 0
    while k < nnz do
      val a = math.abs(valuesRaw(k))
      val c = colIndicesRaw(k)
      if a > maxima(c) then maxima(c) = a
      k += 1

    val acc = new Array[Double](cols)
    k = 0
    while k < nnz do
      val c  = colIndicesRaw(k)
      val mx = maxima(c)
      if mx > 0.0 then
        val t = valuesRaw(k) / mx
        acc(c) += t * t
      k += 1

    var c = 0
    while c < cols do
      acc(c) = if maxima(c) > 0.0 then maxima(c) * math.sqrt(acc(c)) else 0.0
      c += 1
    acc

  /** Induced 1-norm, i.e. the largest absolute column sum.
    *
    * Cached because the arrays are never mutated after construction and the
    * step-size rule reads this on every restart. [[transpose]] sets the
    * precedent.
    */
  lazy val norm1: Double = transpose.normInf

  /** Induced infinity norm, i.e. the largest absolute row sum. */
  lazy val normInf: Double =
    var m = 0.0
    var r = 0
    while r < rows do
      var s = 0.0
      var p = rowPtrRaw(r)
      val e = rowPtrRaw(r + 1)
      while p < e do
        s += math.abs(valuesRaw(p))
        p += 1
      if s > m then m = s
      r += 1
    m

  /** Frobenius norm. */
  def normFrobenius: Double =
    var s = 0.0
    var k = 0
    while k < nnz do
      s += valuesRaw(k) * valuesRaw(k)
      k += 1
    math.sqrt(s)

  /** An upper bound on the spectral norm, from Holder's inequality:
    * `||K||_2 <= sqrt(||K||_1 * ||K||_inf)`.
    *
    * PDHG is stable only while `eta * ||K||_2 <= 1`, and this is the cheapest
    * quantity that bounds `||K||_2` from above. Neither induced norm alone
    * does: for an `m x 1` column of ones, `||K||_inf` is 1 while `||K||_2` is
    * `sqrt(m)`, so using it would start every solve outside the stability
    * condition by a factor of `sqrt(m)`.
    *
    * The bound is tight for that column, and loose by at most `sqrt(min(m,n))`
    * in general — an overestimate costs a conservative first step, which the
    * adaptive rule grows away within a few iterations, whereas an underestimate
    * would start the method outside the region where it converges at all.
    */
  lazy val spectralNormBound: Double = math.sqrt(norm1 * normInf)

  def toDense: Array[Array[Double]] =
    val out = Array.ofDim[Double](rows, cols)
    var r   = 0
    while r < rows do
      var p = rowPtrRaw(r)
      val e = rowPtrRaw(r + 1)
      while p < e do
        out(r)(colIndicesRaw(p)) = valuesRaw(p)
        p += 1
      r += 1
    out

  override def toString: String = s"SparseMatrix($rows x $cols, nnz=$nnz)"

end SparseMatrix

object SparseMatrix:

  /** Build from `(row, column, value)` triplets. Duplicates are summed and
    * exact zeros are dropped, so the result is canonical.
    */
  def fromTriplets(rows: Int, cols: Int, entries: IterableOnce[(Int, Int, Double)]): SparseMatrix =
    require(rows >= 0 && cols >= 0, s"matrix dimensions must be non-negative, got $rows x $cols")
    val raw = entries.iterator.toArray
    raw.foreach { case (r, c, v) =>
      require(r >= 0 && r < rows, s"row index $r out of range [0, $rows)")
      require(c >= 0 && c < cols, s"column index $c out of range [0, $cols)")
      // Infinities are rejected as well as NaN: an infinite entry survives
      // construction but turns a scaling factor into zero, which then shows up
      // as a NaN variable bound far from the offending input.
      require(v.isFinite, s"entry at ($r, $c) is not finite: $v")
    }

    java.util.Arrays.sort(
      raw.asInstanceOf[Array[AnyRef]],
      (a: AnyRef, b: AnyRef) =>
        val x = a.asInstanceOf[(Int, Int, Double)]
        val y = b.asInstanceOf[(Int, Int, Double)]
        if x._1 != y._1 then Integer.compare(x._1, y._1) else Integer.compare(x._2, y._2),
    )

    // Merge duplicates in place, dropping entries that cancel to exactly zero.
    val mergedRow = new Array[Int](raw.length)
    val mergedCol = new Array[Int](raw.length)
    val mergedVal = new Array[Double](raw.length)
    var m         = 0
    var i         = 0
    while i < raw.length do
      val (r, c, v0) = raw(i)
      var v          = v0
      var j          = i + 1
      while j < raw.length && raw(j)._1 == r && raw(j)._2 == c do
        v += raw(j)._3
        j += 1
      if v != 0.0 then
        mergedRow(m) = r
        mergedCol(m) = c
        mergedVal(m) = v
        m += 1
      i = j

    val rowPtr = new Array[Int](rows + 1)
    var k      = 0
    while k < m do
      rowPtr(mergedRow(k) + 1) += 1
      k += 1
    var r = 0
    while r < rows do
      rowPtr(r + 1) += rowPtr(r)
      r += 1

    new SparseMatrix(
      rows,
      cols,
      rowPtr,
      java.util.Arrays.copyOf(mergedCol, m),
      java.util.Arrays.copyOf(mergedVal, m),
    )

  /** Build from dense rows. Convenient for tests and small hand-written models. */
  def fromDense(data: Seq[Seq[Double]], cols: Int): SparseMatrix =
    val entries = mutable.ArrayBuffer.empty[(Int, Int, Double)]
    data.zipWithIndex.foreach { (row, r) =>
      require(row.length == cols, s"row $r has ${row.length} entries, expected $cols")
      row.zipWithIndex.foreach { (v, c) => if v != 0.0 then entries += ((r, c, v)) }
    }
    fromTriplets(data.length, cols, entries)

  /** Wrap CSR arrays directly, trusting the caller for the invariants.
    *
    * For rebuilding a matrix whose sparsity pattern is already known correct
    * and only whose values have changed — narrowing them to a device's
    * precision, for instance. The arrays must not be mutated afterwards.
    */
  private[prima] def fromCsrUnsafe(
      rows: Int,
      cols: Int,
      rowPtr: Array[Int],
      colIndices: Array[Int],
      values: Array[Double],
  ): SparseMatrix =
    new SparseMatrix(rows, cols, rowPtr, colIndices, values)

  def zeros(rows: Int, cols: Int): SparseMatrix =
    new SparseMatrix(rows, cols, new Array[Int](rows + 1), Array.emptyIntArray, Array.emptyDoubleArray)

  def identity(n: Int): SparseMatrix =
    fromTriplets(n, n, (0 until n).map(i => (i, i, 1.0)))

  /** Stack matrices vertically. All must have the same number of columns. */
  def vstack(blocks: Seq[SparseMatrix]): SparseMatrix =
    require(blocks.nonEmpty, "vstack needs at least one block")
    val cols = blocks.head.cols
    blocks.foreach(b => require(b.cols == cols, s"block has ${b.cols} columns, expected $cols"))

    val totalRows = blocks.map(_.rows).sum
    val totalNnz  = blocks.map(_.nnz).sum
    val rowPtr    = new Array[Int](totalRows + 1)
    val colIdx    = new Array[Int](totalNnz)
    val vals      = new Array[Double](totalNnz)

    var rowBase = 0
    var nnzBase = 0
    blocks.foreach { b =>
      var r = 0
      while r < b.rows do
        rowPtr(rowBase + r + 1) = rowPtr(rowBase + r) + (b.rowPtrRaw(r + 1) - b.rowPtrRaw(r))
        r += 1
      System.arraycopy(b.colIndicesRaw, 0, colIdx, nnzBase, b.nnz)
      System.arraycopy(b.valuesRaw, 0, vals, nnzBase, b.nnz)
      rowBase += b.rows
      nnzBase += b.nnz
    }
    new SparseMatrix(totalRows, cols, rowPtr, colIdx, vals)

end SparseMatrix
