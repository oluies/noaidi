package org.noaidi.pf

/** Dense Cholesky factorisation and solve for a symmetric positive-definite
  * system.
  *
  * The reduced susceptance matrix of a sub-network is a weighted graph Laplacian
  * with the slack bus's row and column removed. That matrix is positive definite
  * exactly when the sub-network is connected and every susceptance is positive,
  * so a failed factorisation is not a numerical accident to be nudged past with a
  * regularisation term — it means one of those two properties does not hold, and
  * the caller can say which. That diagnostic value is the reason to use Cholesky
  * here rather than a general LU that would happily factor a singular system into
  * something that looks like an answer.
  *
  * Dense, and deliberately so: a sub-network in the reference networks has
  * single-digit buses. A country-scale network needs a sparse factorisation with a
  * fill-reducing ordering, and this is the fp64 oracle that one gets checked
  * against — the same relationship the accelerator kernels have to their
  * pure-Scala reference.
  */
object Cholesky:

  /** The system is not positive definite, so the sub-network is disconnected or
    * an impedance is non-positive.
    */
  final class NotPositiveDefinite(message: String) extends RuntimeException(message)

  /** Solve `a x = rhs` for a symmetric positive-definite `a`.
    *
    * `a` is row-major `n × n`. Only the lower triangle is read, so a caller that
    * fills one triangle need not mirror it.
    */
  def solve(n: Int, a: IArray[Double], rhs: IArray[Double]): IArray[Double] =
    require(a.length == n * n, s"matrix is ${a.length} entries, expected ${n * n}")
    require(rhs.length == n, s"rhs is ${rhs.length} entries, expected $n")
    if n == 0 then return IArray.empty

    // Lower-triangular factor, computed in place into its own buffer so `a` stays
    // untouched and a caller can reuse it across snapshots.
    val l = new Array[Double](n * n)

    var j = 0
    while j < n do
      var diagonal = a(j * n + j)
      var k        = 0
      while k < j do
        val v = l(j * n + k)
        diagonal -= v * v
        k += 1

      // `> 0` rather than `!= 0`: a zero or negative pivot both mean the matrix
      // is not positive definite, and NaN fails this test too, which is what
      // should happen when an impedance was missing upstream.
      if !(diagonal > 0.0) then
        throw new NotPositiveDefinite(
          s"pivot $j is $diagonal; the system is singular or indefinite, which for a " +
            "susceptance matrix means the sub-network is disconnected or an impedance is not positive"
        )

      val d = math.sqrt(diagonal)
      l(j * n + j) = d

      var i = j + 1
      while i < n do
        var sum = a(i * n + j)
        var m   = 0
        while m < j do
          sum -= l(i * n + m) * l(j * n + m)
          m += 1
        l(i * n + j) = sum / d
        i += 1
      j += 1

    // Forward substitution: L y = rhs.
    val y = new Array[Double](n)
    var i = 0
    while i < n do
      var sum = rhs(i)
      var k   = 0
      while k < i do
        sum -= l(i * n + k) * y(k)
        k += 1
      y(i) = sum / l(i * n + i)
      i += 1

    // Back substitution: L^T x = y.
    val x = new Array[Double](n)
    var r = n - 1
    while r >= 0 do
      var sum = y(r)
      var k   = r + 1
      while k < n do
        sum -= l(k * n + r) * x(k)
        k += 1
      x(r) = sum / l(r * n + r)
      r -= 1

    IArray.unsafeFromArray(x)
