package org.noaidi.pf

class CholeskySuite extends munit.FunSuite:

  private def solve(n: Int, a: Seq[Double], b: Seq[Double]): IArray[Double] =
    Cholesky.solve(n, IArray.from(a), IArray.from(b))

  test("solves a system with a known answer") {
    // A 3x3 SPD system whose solution is exactly (1, 2, 3), chosen so the
    // assertion does not depend on the factorisation's own arithmetic.
    val a = Seq(
      4.0, 1.0, 0.0,
      1.0, 3.0, 1.0,
      0.0, 1.0, 2.0,
    )
    val x = solve(3, a, Seq(6.0, 10.0, 8.0))
    assertEqualsDouble(x(0), 1.0, 1e-12)
    assertEqualsDouble(x(1), 2.0, 1e-12)
    assertEqualsDouble(x(2), 3.0, 1e-12)
  }

  test("reads only the lower triangle") {
    // The susceptance assembly fills both triangles, but a caller that fills one
    // must get the same answer -- otherwise the documented contract is wrong and
    // a future sparse implementation would be built against a false premise.
    val full  = Seq(4.0, 1.0, 1.0, 3.0)
    val lower = Seq(4.0, Double.NaN, 1.0, 3.0)
    val rhs   = Seq(6.0, 7.0)
    val a     = solve(2, full, rhs)
    val b     = solve(2, lower, rhs)
    assertEqualsDouble(b(0), a(0), 1e-12)
    assertEqualsDouble(b(1), a(1), 1e-12)
    assert(a(0).isFinite && a(1).isFinite)
  }

  test("a singular system is refused, not approximated") {
    // The Laplacian of a two-bus network with *both* rows kept -- exactly what
    // forgetting to strike out the slack produces. It is singular, and an LU would
    // return a large finite vector that looks like an answer.
    val a = Seq(1.0, -1.0, -1.0, 1.0)
    intercept[Cholesky.NotPositiveDefinite](solve(2, a, Seq(1.0, -1.0)))
  }

  test("an indefinite system is refused") {
    // A negative susceptance would arise from a negative reactance, which is
    // physically meaningless but arithmetically quiet.
    val a = Seq(-4.0, 1.0, 1.0, 3.0)
    intercept[Cholesky.NotPositiveDefinite](solve(2, a, Seq(1.0, 1.0)))
  }

  test("a NaN pivot is refused rather than propagated") {
    // A missing impedance upstream becomes NaN on the diagonal. `!(x > 0)` catches
    // it where `x <= 0` would not, and the alternative is a result vector of NaN
    // that every downstream tolerance check silently passes.
    val a = Seq(Double.NaN, 0.0, 0.0, 1.0)
    intercept[Cholesky.NotPositiveDefinite](solve(2, a, Seq(1.0, 1.0)))
  }

  test("an empty system solves to an empty vector") {
    // A single-bus sub-network has no free buses at all -- ac-dc-meshed's Norway
    // island is exactly this, so it is a real case rather than a boundary curio.
    assertEquals(solve(0, Seq.empty, Seq.empty).length, 0)
  }

  test("a size mismatch is caught at the boundary") {
    intercept[IllegalArgumentException](solve(2, Seq(1.0, 0.0, 0.0), Seq(1.0, 1.0)))
    intercept[IllegalArgumentException](solve(2, Seq(1.0, 0.0, 0.0, 1.0), Seq(1.0)))
  }

  test("the input matrix is not modified") {
    // The doc promises a caller can reuse the matrix across snapshots, and an
    // in-place factorisation is the natural way to write this by accident.
    val a   = IArray(4.0, 1.0, 1.0, 3.0)
    val _   = Cholesky.solve(2, a, IArray(6.0, 7.0))
    assertEquals(a.toSeq, Seq(4.0, 1.0, 1.0, 3.0))
  }
