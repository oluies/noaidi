package org.noaidi.prima

class PdhgSuite extends munit.FunSuite:

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)

  // Tolerance for comparing against a hand-derived optimum. Looser than the
  // solver's own convergence tolerance, because that one bounds the KKT
  // residual rather than the distance to the optimum.
  private val tol = 1e-6

  LpFixtures.optimal.foreach { instance =>
    test(s"solves ${instance.name} to the known optimum") {
      val solution = Pdhg.solve(instance.problem, params)
      assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
      instance.expectedObjective.foreach { expected =>
        assertEqualsDouble(solution.objectiveValue, expected, tol * math.max(1.0, math.abs(expected)))
      }
      instance.expectedPrimal.foreach { expected =>
        expected.zipWithIndex.foreach { (value, i) =>
          assertEqualsDouble(solution.primal(i), value, 1e-5, s"variable $i of $solution")
        }
      }
    }
  }

  test("detects primal infeasibility with a certificate") {
    val solution = Pdhg.solve(LpFixtures.infeasible.problem, params)
    assertEquals(solution.status, SolveStatus.PrimalInfeasible, s"$solution")
  }

  test("detects an unbounded objective with a certificate") {
    val solution = Pdhg.solve(LpFixtures.unbounded.problem, params)
    assertEquals(solution.status, SolveStatus.DualInfeasible, s"$solution")
  }

  test("primal and dual objectives agree at optimality") {
    LpFixtures.optimal.foreach { instance =>
      val solution = Pdhg.solve(instance.problem, params)
      assertEqualsDouble(
        solution.objectiveValue,
        solution.dualObjectiveValue,
        1e-5 * math.max(1.0, math.abs(solution.objectiveValue)),
        s"${instance.name}: strong duality violated by $solution",
      )
    }
  }

  test("the reported solution actually satisfies the constraints") {
    LpFixtures.optimal.foreach { instance =>
      val p        = instance.problem
      val solution = Pdhg.solve(instance.problem, params)
      val kx       = p.constraintMatrix.multiply(solution.primal)

      for r <- 0 until p.numConstraints do
        if r < p.numEqualities then
          assertEqualsDouble(kx(r), p.rhs(r), 1e-6, s"${instance.name}: equality row $r")
        else assert(kx(r) >= p.rhs(r) - 1e-6, s"${instance.name}: inequality row $r violated")

      for i <- 0 until p.numVariables do
        assert(
          solution.primal(i) >= p.variableLower(i) - 1e-9 &&
            solution.primal(i) <= p.variableUpper(i) + 1e-9,
          s"${instance.name}: variable $i outside its bounds",
        )
    }
  }

  test("congestion shows up in the line's shadow price") {
    // In the dispatch fixture the second snapshot saturates the line, so the two
    // buses must price differently there and identically where it does not bind.
    val solution = Pdhg.solve(LpFixtures.economicDispatch.problem, params)
    assertEquals(solution.status, SolveStatus.Optimal)

    // Rows are the four equalities in builder order: A/B for snapshot 1, then 2.
    val priceA1 = solution.dual(0)
    val priceB1 = solution.dual(1)
    val priceA2 = solution.dual(2)
    val priceB2 = solution.dual(3)

    assertEqualsDouble(priceA1, priceB1, 1e-4, "uncongested snapshot should price alike")
    assert(
      math.abs(priceA2 - priceB2) > 1.0,
      f"congested snapshot should price apart, got $priceA2%.4f and $priceB2%.4f",
    )
    // Marginal unit at bus A in the congested snapshot is g1 at 10/MWh, and at
    // bus B it is g3 at 20/MWh.
    assertEqualsDouble(priceA2, 10.0, 1e-3)
    assertEqualsDouble(priceB2, 20.0, 1e-3)
  }

  test("scaling does not change the answer") {
    val unscaled = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, scaling = ScalingParams.none)
    LpFixtures.optimal.foreach { instance =>
      val withScaling    = Pdhg.solve(instance.problem, params)
      val withoutScaling = Pdhg.solve(instance.problem, unscaled)
      assertEquals(withoutScaling.status, SolveStatus.Optimal, s"${instance.name}: $withoutScaling")
      assertEqualsDouble(
        withScaling.objectiveValue,
        withoutScaling.objectiveValue,
        1e-5 * math.max(1.0, math.abs(withScaling.objectiveValue)),
        instance.name,
      )
    }
  }

  test("stops at the iteration limit without claiming optimality") {
    val capped   = PdhgParams(epsAbs = 1e-14, epsRel = 1e-14, maxIterations = 3)
    val solution = Pdhg.solve(LpFixtures.economicDispatch.problem, capped)
    assertEquals(solution.status, SolveStatus.IterationLimit)
    assertEquals(solution.iterations, 3)
  }

  test("an already-optimal trivial problem terminates immediately") {
    // No constraints and a zero objective: the starting point is optimal.
    val b = LpProblem.builder(2)
    b.bounds(0, 0.0, 1.0)
    b.bounds(1, 0.0, 1.0)
    val solution = Pdhg.solve(b.build()._1, params)
    assertEquals(solution.status, SolveStatus.Optimal)
    assertEquals(solution.iterations, 0)
  }

  test("stops at the time limit without claiming optimality") {
    // Tolerances that cannot be met, so only the clock can end this.
    val timed = PdhgParams(
      epsAbs = 1e-16,
      epsRel = 1e-16,
      maxIterations = 50_000_000,
      timeLimitMillis = Some(50L),
    )
    val problem =
      LpFixtures.randomFeasible(seed = 5, numVariables = 200, numEqualities = 40, numInequalities = 80)

    val started  = System.nanoTime()
    val solution = Pdhg.solve(problem, timed)
    val elapsed  = (System.nanoTime() - started) / 1000000L

    assertEquals(solution.status, SolveStatus.TimeLimit, s"$solution")
    assert(solution.iterations < 50_000_000, "the iteration limit ended this, not the clock")
    // The limit is only checked at evaluation points, so allow generous slack;
    // the assertion is that it stopped early, not that it stopped punctually.
    assert(elapsed < 20000, s"time limit overshot badly: ${elapsed}ms")
  }

  test("parameters that would silently break convergence are rejected") {
    // Zero would fire the artificial restart at every checkpoint, discarding the
    // running average before it can ever be the better candidate.
    intercept[IllegalArgumentException](PdhgParams(restartArtificialFraction = 0.0))
    intercept[IllegalArgumentException](PdhgParams(restartArtificialFraction = 1.5))
    // Negative would make every certificate test fail, disabling detection.
    intercept[IllegalArgumentException](PdhgParams(infeasibilityTolerance = -1e-9))
  }

  test("the initial step size respects the PDHG stability condition") {
    // A tall thin column is the case where the induced infinity norm is not a
    // bound on the spectral norm: here ||K||_inf is 1 but ||K||_2 is sqrt(m).
    val m      = 100
    val column = SparseMatrix.fromTriplets(m, 1, (0 until m).map(r => (r, 0, 1.0)))
    assertEqualsDouble(column.normInf, 1.0, 1e-12)
    assert(
      column.spectralNormBound >= math.sqrt(m.toDouble) - 1e-9,
      s"bound ${column.spectralNormBound} is below the true spectral norm ${math.sqrt(m.toDouble)}",
    )

    // And the solver built on it still lands on the right answer.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 10.0)
    for _ <- 0 until m do b.greaterThan(Seq(0 -> 1.0), 3.0)
    val solution = Pdhg.solve(b.build()._1, params)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 3.0, 1e-6)
  }

  test("restarts happen on a problem that needs them") {
    val problem  = LpFixtures.randomFeasible(seed = 7, numVariables = 40, numEqualities = 8, numInequalities = 12)
    val solution = Pdhg.solve(problem, params)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assert(solution.restarts > 0, "expected at least one restart on a non-trivial instance")
  }
