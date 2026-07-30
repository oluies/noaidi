package org.noaidi.pf

class LuSuite extends munit.FunSuite:

  private def solve(n: Int, a: Seq[Double], b: Seq[Double]): IArray[Double] =
    Lu.solve(n, IArray.from(a), IArray.from(b))

  test("solves an unsymmetric system with a known answer") {
    // Unsymmetric on purpose: this exists because the Newton Jacobian is, so a
    // test that happened to be symmetric would not distinguish it from Cholesky.
    val a = Seq(
      2.0, 1.0, -1.0,
      -3.0, -1.0, 2.0,
      -2.0, 1.0, 2.0,
    )
    val x = solve(3, a, Seq(8.0, -11.0, -3.0))
    assertEqualsDouble(x(0), 2.0, 1e-12)
    assertEqualsDouble(x(1), 3.0, 1e-12)
    assertEqualsDouble(x(2), -1.0, 1e-12)
  }

  test("pivots when the natural diagonal entry is zero") {
    // Not a contrived case: at a flat start dP/d|V| vanishes for a lossless
    // branch, so a zero on the diagonal is the *expected* first Newton iteration.
    // Without partial pivoting this divides by zero and returns NaN.
    val a = Seq(0.0, 1.0, 1.0, 0.0)
    val x = solve(2, a, Seq(3.0, 5.0))
    assertEqualsDouble(x(0), 5.0, 1e-12)
    assertEqualsDouble(x(1), 3.0, 1e-12)
  }

  test("pivots for accuracy, not just for zeros") {
    // A tiny-but-non-zero pivot is the classic case where no-pivoting loses most
    // of the mantissa while returning a plausible-looking vector.
    val eps = 1e-18
    val a   = Seq(eps, 1.0, 1.0, 1.0)
    val x   = solve(2, a, Seq(1.0, 2.0))
    // Exact answer tends to (1, 1) as eps -> 0.
    assertEqualsDouble(x(0), 1.0, 1e-9)
    assertEqualsDouble(x(1), 1.0, 1e-9)
  }

  test("a singular matrix is refused") {
    val a = Seq(1.0, 2.0, 2.0, 4.0)
    intercept[Lu.Singular](solve(2, a, Seq(1.0, 2.0)))
  }

  test("the input matrix and rhs are not modified") {
    val a   = IArray(2.0, 1.0, 1.0, 3.0)
    val rhs = IArray(5.0, 10.0)
    val _   = Lu.solve(2, a, rhs)
    assertEquals(a.toSeq, Seq(2.0, 1.0, 1.0, 3.0))
    assertEquals(rhs.toSeq, Seq(5.0, 10.0))
  }

  test("an empty system solves to an empty vector") {
    assertEquals(solve(0, Seq.empty, Seq.empty).length, 0)
  }

  test("a size mismatch is caught at the boundary") {
    intercept[IllegalArgumentException](solve(2, Seq(1.0, 0.0, 0.0), Seq(1.0, 1.0)))
    intercept[IllegalArgumentException](solve(2, Seq(1.0, 0.0, 0.0, 1.0), Seq(1.0)))
  }
