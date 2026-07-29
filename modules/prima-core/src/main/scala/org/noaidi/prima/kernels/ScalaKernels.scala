package org.noaidi.prima
package kernels

/** The reference implementation: pure Scala, single threaded, IEEE double
  * throughout.
  *
  * This is the correctness oracle every other backend is measured against, so
  * it favours being obviously right over being fast. It still uses primitive
  * arrays and `while` loops rather than collection combinators, because boxed
  * higher-order iteration in the inner loop would make the oracle slow enough
  * to stop being usable on the larger validation instances.
  *
  * Reductions accumulate in a plain `Double` in index order. That is
  * deterministic, which matters more here than the extra accuracy a compensated
  * sum would buy: a GPU backend reduces in tree order and will differ from any
  * host ordering anyway, so agreement is asserted within a tolerance rather
  * than exactly.
  */
final class ScalaKernels extends Kernels:

  type Vec = Array[Double]
  type Mat = SparseMatrix

  val capabilities: KernelCapabilities =
    KernelCapabilities(name = "scala-reference", device = "cpu", supportsFloat64 = true)

  def allocate(n: Int): Array[Double] = new Array[Double](n)

  def upload(data: Array[Double]): Array[Double] = data.clone()

  def download(v: Array[Double], into: Array[Double]): Unit =
    System.arraycopy(v, 0, into, 0, v.length)

  def uploadMatrix(m: SparseMatrix): SparseMatrix = m

  def length(v: Array[Double]): Int = v.length

  def close(): Unit = ()

  def spmv(a: SparseMatrix, x: Array[Double], out: Array[Double]): Unit =
    a.multiplyInto(x, out)

  def axpby(alpha: Double, x: Array[Double], beta: Double, y: Array[Double], out: Array[Double]): Unit =
    var i = 0
    val n = out.length
    while i < n do
      out(i) = alpha * x(i) + beta * y(i)
      i += 1

  def scale(alpha: Double, x: Array[Double], out: Array[Double]): Unit =
    var i = 0
    val n = out.length
    while i < n do
      out(i) = alpha * x(i)
      i += 1

  def copy(src: Array[Double], dst: Array[Double]): Unit =
    System.arraycopy(src, 0, dst, 0, src.length)

  def dot(x: Array[Double], y: Array[Double]): Double =
    var s = 0.0
    var i = 0
    val n = x.length
    while i < n do
      s += x(i) * y(i)
      i += 1
    s

  def squaredNorm(x: Array[Double]): Double =
    var s = 0.0
    var i = 0
    val n = x.length
    while i < n do
      s += x(i) * x(i)
      i += 1
    s

  def primalStep(
      x: Array[Double],
      ktY: Array[Double],
      cost: Array[Double],
      lower: Array[Double],
      upper: Array[Double],
      tau: Double,
      out: Array[Double],
  ): Unit =
    var i = 0
    val n = out.length
    while i < n do
      val step = x(i) - tau * (cost(i) - ktY(i))
      // Clamping written as two comparisons rather than min/max so that an
      // infinite bound passes the candidate through untouched even when the
      // candidate is itself infinite.
      out(i) =
        if step < lower(i) then lower(i)
        else if step > upper(i) then upper(i)
        else step
      i += 1

  def dualStep(
      y: Array[Double],
      kxBar: Array[Double],
      rhs: Array[Double],
      sigma: Double,
      numEqualities: Int,
      out: Array[Double],
  ): Unit =
    val n = out.length
    var i = 0
    // Equality rows: the dual is free, so no projection.
    while i < numEqualities do
      out(i) = y(i) + sigma * (rhs(i) - kxBar(i))
      i += 1
    // Inequality rows (`K x >= q`): the dual is non-negative.
    while i < n do
      val step = y(i) + sigma * (rhs(i) - kxBar(i))
      out(i) = if step > 0.0 then step else 0.0
      i += 1

end ScalaKernels

object ScalaKernels:
  def apply(): ScalaKernels = new ScalaKernels
