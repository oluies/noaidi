package org.noaidi.pf

/** Dense LU factorisation with partial pivoting.
  *
  * Newton-Raphson's Jacobian is '''not''' symmetric — the off-diagonal blocks
  * ∂P/∂|V| and ∂Q/∂θ differ — so [[Cholesky]] does not apply and this is a
  * separate routine rather than a generalisation of it. It also cannot carry
  * Cholesky's diagnostic: a singular Jacobian means the iteration has walked
  * somewhere the linearisation says nothing, which is a statement about the
  * current iterate rather than about the network.
  *
  * Partial pivoting is not optional here. A flat start puts every voltage angle
  * at zero, where ∂P/∂|V| vanishes for a lossless branch, so zero pivots on the
  * diagonal are the expected first iteration rather than a pathological case.
  *
  * Dense, like [[Cholesky]], and for the same reason: it is the reference a
  * sparse factorisation gets checked against.
  */
object Lu:

  /** The matrix is singular to working precision. */
  final class Singular(message: String) extends RuntimeException(message)

  /** Solve `a x = rhs`, with `a` row-major `n × n`. `a` is not modified. */
  def solve(n: Int, a: IArray[Double], rhs: IArray[Double]): IArray[Double] =
    require(a.length == n * n, s"matrix is ${a.length} entries, expected ${n * n}")
    require(rhs.length == n, s"rhs is ${rhs.length} entries, expected $n")
    if n == 0 then return IArray.empty

    val m = new Array[Double](n * n)
    System.arraycopy(a.toArray, 0, m, 0, n * n)
    val x = new Array[Double](n)
    System.arraycopy(rhs.toArray, 0, x, 0, n)

    // Row permutation, applied to the right-hand side as it is discovered rather
    // than accumulated and applied at the end.
    var k = 0
    while k < n do
      // Partial pivot: the largest magnitude in the column at or below the
      // diagonal.
      var pivotRow = k
      var best     = math.abs(m(k * n + k))
      var i        = k + 1
      while i < n do
        val candidate = math.abs(m(i * n + k))
        if candidate > best then
          best = candidate
          pivotRow = i
        i += 1

      if !(best > 0.0) then
        throw new Singular(s"column $k has no non-zero pivot; the matrix is singular")

      if pivotRow != k then
        var c = 0
        while c < n do
          val swap = m(k * n + c)
          m(k * n + c) = m(pivotRow * n + c)
          m(pivotRow * n + c) = swap
          c += 1
        val swapRhs = x(k)
        x(k) = x(pivotRow)
        x(pivotRow) = swapRhs

      val pivot = m(k * n + k)
      var r     = k + 1
      while r < n do
        val factor = m(r * n + k) / pivot
        if factor != 0.0 then
          var c = k
          while c < n do
            m(r * n + c) -= factor * m(k * n + c)
            c += 1
          x(r) -= factor * x(k)
        r += 1
      k += 1

    // Back substitution.
    var r = n - 1
    while r >= 0 do
      var sum = x(r)
      var c   = r + 1
      while c < n do
        sum -= m(r * n + c) * x(c)
        c += 1
      x(r) = sum / m(r * n + r)
      r -= 1

    IArray.unsafeFromArray(x)
