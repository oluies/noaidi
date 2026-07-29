package org.noaidi.prima
package kernels

/** A CPU backend that computes in single precision.
  *
  * This exists to make the reduced-precision path testable without a GPU.
  * Every accelerator backend worth having is float32-only: Cyfra's DSL offers
  * no double type at all, and Apple GPUs expose none through either Vulkan or
  * Metal regardless. So the question "does restarted PDHG still converge when
  * the iteration is float32, and how much does a double-precision finish
  * recover?" has to be answerable before writing a line of Vulkan.
  *
  * Answering it on a GPU would confound three variables at once — the
  * algorithm's precision sensitivity, the correctness of a hand-written SPIR-V
  * kernel, and the driver stack. This isolates the first. It runs the same
  * arithmetic the reference does, rounding to float32 after every operation, so
  * a disagreement with [[ScalaKernels]] is a precision effect and nothing else.
  *
  * It is deliberately not fast. Rounding through `toFloat` on every operation
  * makes it slower than the double-precision reference, which is fine: its
  * output is a fidelity measurement, not a benchmark.
  *
  * Reductions accumulate in float32 as well. That is the pessimistic choice —
  * a real GPU reduces in tree order, which is typically more accurate than
  * float32 sequential accumulation, not less — so results here bound what
  * hardware will do rather than flatter it.
  */
final class Float32Kernels extends Kernels:

  type Vec = Array[Double]
  type Mat = SparseMatrix

  val capabilities: KernelCapabilities =
    KernelCapabilities(name = "scala-float32", device = "cpu", supportsFloat64 = false)

  /** Round to the nearest representable float32, the operation every arithmetic
    * result here passes through.
    */
  private inline def r(v: Double): Double = v.toFloat.toDouble

  def allocate(n: Int): Array[Double] = new Array[Double](n)

  def upload(data: Array[Double]): Array[Double] =
    val out = new Array[Double](data.length)
    var i   = 0
    while i < data.length do
      // Infinite bounds must survive the narrowing; `Double.MaxValue` would
      // overflow to infinity anyway, but an explicit pass-through keeps the
      // intent clear and avoids depending on that.
      out(i) = if data(i).isInfinite then data(i) else r(data(i))
      i += 1
    out

  def download(v: Array[Double], into: Array[Double]): Unit =
    System.arraycopy(v, 0, into, 0, v.length)

  def uploadMatrix(m: SparseMatrix): SparseMatrix =
    val values  = m.valuesRaw
    val rounded = new Array[Double](values.length)
    var i       = 0
    while i < values.length do
      rounded(i) = r(values(i))
      i += 1
    SparseMatrix.fromCsrUnsafe(m.rows, m.cols, m.rowPtrRaw, m.colIndicesRaw, rounded)

  def length(v: Array[Double]): Int = v.length

  def close(): Unit = ()

  def spmv(a: SparseMatrix, x: Array[Double], out: Array[Double]): Unit =
    var row = 0
    while row < a.rows do
      var acc = 0.0f
      var p   = a.rowPtrRaw(row)
      val e   = a.rowPtrRaw(row + 1)
      while p < e do
        acc = (acc + (a.valuesRaw(p).toFloat * x(a.colIndicesRaw(p)).toFloat)).toFloat
        p += 1
      out(row) = acc.toDouble
      row += 1

  def axpby(alpha: Double, x: Array[Double], beta: Double, y: Array[Double], out: Array[Double]): Unit =
    val a = alpha.toFloat
    val b = beta.toFloat
    var i = 0
    while i < out.length do
      out(i) = ((a * x(i).toFloat) + (b * y(i).toFloat)).toDouble
      i += 1

  def scale(alpha: Double, x: Array[Double], out: Array[Double]): Unit =
    val a = alpha.toFloat
    var i = 0
    while i < out.length do
      out(i) = (a * x(i).toFloat).toDouble
      i += 1

  def copy(src: Array[Double], dst: Array[Double]): Unit =
    System.arraycopy(src, 0, dst, 0, src.length)

  def dot(x: Array[Double], y: Array[Double]): Double =
    var s = 0.0f
    var i = 0
    while i < x.length do
      s = (s + (x(i).toFloat * y(i).toFloat)).toFloat
      i += 1
    s.toDouble

  def squaredNorm(x: Array[Double]): Double =
    var s = 0.0f
    var i = 0
    while i < x.length do
      val v = x(i).toFloat
      s = (s + (v * v)).toFloat
      i += 1
    s.toDouble

  def primalStep(
      x: Array[Double],
      ktY: Array[Double],
      cost: Array[Double],
      lower: Array[Double],
      upper: Array[Double],
      tau: Double,
      out: Array[Double],
  ): Unit =
    val t = tau.toFloat
    var i = 0
    while i < out.length do
      val step = (x(i).toFloat - (t * (cost(i).toFloat - ktY(i).toFloat))).toDouble
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
    val s = sigma.toFloat
    val n = out.length
    var i = 0
    while i < numEqualities do
      out(i) = (y(i).toFloat + (s * (rhs(i).toFloat - kxBar(i).toFloat))).toDouble
      i += 1
    while i < n do
      val step = (y(i).toFloat + (s * (rhs(i).toFloat - kxBar(i).toFloat))).toDouble
      out(i) = if step > 0.0 then step else 0.0
      i += 1

end Float32Kernels

object Float32Kernels:
  def apply(): Float32Kernels = new Float32Kernels
