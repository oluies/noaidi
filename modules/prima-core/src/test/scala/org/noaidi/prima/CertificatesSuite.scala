package org.noaidi.prima

/** Direct tests for the Farkas certificates.
  *
  * Reaching these only through end-to-end solves leaves most of their branches
  * unvisited, and a certificate that wrongly passes turns "I could not solve
  * this" into "this has no solution" — the one failure mode a solver must not
  * have. So the directions are hand-built here rather than taken from iterates.
  */
class CertificatesSuite extends munit.FunSuite:

  private val inf  = Double.PositiveInfinity
  private val ninf = Double.NegativeInfinity

  // `x >= 1` and `-x >= 0` on a free variable: infeasible, and `y = (1, 1)`
  // is the Farkas certificate.
  private val contradiction: LpProblem =
    val b = LpProblem.builder(1)
    b.bounds(0, ninf, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    b.build()._1

  test("an exact Farkas direction certifies primal infeasibility") {
    val residual = Certificates.primalInfeasibility(contradiction, Array(1.0, 1.0))
    assert(residual.isDefined, "the direction was not even a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("scaling a certificate does not change its residual") {
    // Certificates are only defined up to a positive scale, so the normalised
    // residual must be invariant; if it were not, the tolerance would mean
    // something different for a diverging iterate than for a fresh one.
    val small = Certificates.primalInfeasibility(contradiction, Array(1.0, 1.0)).get
    val large = Certificates.primalInfeasibility(contradiction, Array(1e9, 1e9)).get
    assertEqualsDouble(large, small, 1e-12)
  }

  test("a direction with a non-positive certificate value is not a candidate") {
    // Zero proves nothing. Both of these give exactly zero: the second row of
    // `contradiction` has rhs -0.0, so weighting it alone contributes nothing.
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(0.0, 0.0)), None)
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(0.0, 1.0)), None)
    // Negating the first row's multiplier makes the value strictly negative,
    // which is the branch the zero cases above do not reach.
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(-1.0, 0.0)), None)
  }

  // `x` in `[0, 5]` with `x <= -1`: infeasible, and the finite lower bound
  // absorbs the induced reduced cost completely. That isolation is the point —
  // it leaves cone violation as the only thing that can contribute residual.
  private val absorbed: LpProblem =
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 5.0)
    b.lessThan(Seq(0 -> 1.0), -1.0)
    b.lessThan(Seq(0 -> 1.0), 100.0)
    b.build()._1

  test("a certificate whose reduced cost is fully absorbed has zero residual") {
    // Only the binding row is weighted, and its multiplier is in the cone.
    val residual = Certificates.primalInfeasibility(absorbed, Array(2.0, 0.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("a dual multiplier outside the cone counts against the certificate") {
    // Same problem, but the second row now carries a negative multiplier, which
    // is not a feasible dual direction. Nothing else contributes residual here,
    // so the expected value pins the cone term exactly: deleting the cone
    // accumulation would make this zero.
    val y        = Array(2.0, -1.0)
    val residual = Certificates.primalInfeasibility(absorbed, y)
    assert(residual.isDefined, "the direction was not a candidate")
    // Certificate value is q'y = 1*2 + (-100)*(-1) = 102; the only residual is
    // the cone violation of magnitude 1.
    assertEqualsDouble(residual.get, 1.0 / 102.0, 1e-12)
  }

  test("a reduced cost absorbed by a finite bound does not count as residual") {
    // `x` in `[0, 5]` with `x >= 7`: infeasible, and the certificate's induced
    // reduced cost is taken up by the finite upper bound rather than left over.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 5.0)
    b.greaterThan(Seq(0 -> 1.0), 7.0)
    val problem = b.build()._1

    val residual = Certificates.primalInfeasibility(problem, Array(1.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("the same reduced cost against an infinite bound is residual") {
    // Identical row, but the variable is now unbounded above, so nothing can
    // absorb the reduced cost and the direction proves nothing.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 7.0)
    val problem = b.build()._1

    val residual = Certificates.primalInfeasibility(problem, Array(1.0))
    assert(residual.forall(_ > 1e-6), s"unabsorbed reduced cost was not penalised: $residual")
  }

  // `min -x` with `x >= 0` unbounded above: the direction `d = 1` is a
  // recession direction that strictly decreases the objective.
  private val ray: LpProblem =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    b.build()._1

  test("a recession direction certifies unboundedness") {
    val residual = Certificates.dualInfeasibility(ray, Array(1.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("a direction that does not improve the objective is not a candidate") {
    assertEquals(Certificates.dualInfeasibility(ray, Array(0.0)), None)
    assertEquals(Certificates.dualInfeasibility(ray, Array(-1.0)), None)
  }

  test("a direction through a finite bound is not a recession direction") {
    // Same objective, but `x` is now capped, so moving along `d` leaves the box.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, 10.0)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    val bounded = b.build()._1

    val residual = Certificates.dualInfeasibility(bounded, Array(1.0))
    assert(residual.forall(_ > 1e-6), s"finite bound was not penalised: $residual")
  }

  test("a direction that disturbs an equality row is not a recession direction") {
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.bounds(1, 0.0, inf)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 5.0)
    val problem = b.build()._1

    // Increasing only `x0` breaks the equality; increasing `x0` while decreasing
    // `x1` preserves it but leaves `x1`'s lower bound.
    assert(Certificates.dualInfeasibility(problem, Array(1.0, 0.0)).forall(_ > 1e-6))
    assert(Certificates.dualInfeasibility(problem, Array(1.0, -1.0)).forall(_ > 1e-6))
  }

  test("classify prefers primal infeasibility when both directions certify") {
    // Infeasible constraints and an objective unbounded along a free variable.
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(1, -1.0)
    b.bounds(0, ninf, inf)
    b.bounds(1, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    val problem = b.build()._1

    val status = Certificates.classify(problem, Array(0.0, 1.0), Array(1.0, 1.0), 1e-8)
    assertEquals(status, Some(SolveStatus.PrimalInfeasible))
  }

  test("classify reports nothing for an ordinary feasible point") {
    val problem  = LpFixtures.economicDispatch.problem
    val solution = Pdhg.solve(problem)
    val status = Certificates.classify(
      problem,
      Unsafe.raw(solution.primal),
      Unsafe.raw(solution.dual),
      1e-8,
    )
    assertEquals(status, None)
  }
