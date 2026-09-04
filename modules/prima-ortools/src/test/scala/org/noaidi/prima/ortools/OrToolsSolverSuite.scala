package org.noaidi.prima
package ortools

class OrToolsSolverSuite extends munit.FunSuite:

  private val ortools = OrToolsSolver()
  private val prima   = Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000))

  test("every conclusive fixture reaches the status it should") {
    LpFixtures.conclusive.foreach { instance =>
      val solution = ortools.solve(instance.problem)
      assertEquals(solution.status, instance.expectedStatus, instance.name)
    }
  }

  test("optimal objectives agree with Prima") {
    LpFixtures.conclusive.filter(_.expectedStatus == SolveStatus.Optimal).foreach { instance =>
      val theirs = ortools.solve(instance.problem)
      val mine   = prima.solve(instance.problem)
      val gap =
        math.abs(theirs.objectiveValue - mine.objectiveValue) / math.max(1.0, math.abs(mine.objectiveValue))
      assert(gap < 1e-6, s"${instance.name}: objectives disagreed by $gap")
    }
  }

  test("the objective offset is carried") {
    // OR-Tools is told the coefficients and not the constant, so the offset has
    // to be added back here. A backend that dropped it would agree with every
    // fixture that has none, which is all of them until an expansion model
    // shows up.
    val builder = LpProblem.builder(1)
    builder.bounds(0, 2.0, 10.0)
    builder.objectiveCoefficient(0, 3.0)
    builder.objectiveOffset(100.0)
    builder.greaterThan(Seq((0, 1.0)), 2.0)
    val (problem, _) = builder.build()

    assertEqualsDouble(ortools.solve(problem).objectiveValue, 106.0, 1e-9)
  }

  test("duals are reported rather than refused") {
    // The difference from `OjAlgoSolver`, and the reason this backend is worth
    // having behind the modeling layer: prices come back.
    val instance = LpFixtures.conclusive.find(_.name == "economic-dispatch").get
    val solution = ortools.solve(instance.problem)
    assertEquals(solution.status, SolveStatus.Optimal)
    assert(solution.dual.forall(_.isFinite), "OR-Tools reported a dual that is not finite")
    assert(solution.dual.exists(_ != 0.0), "every dual was zero, which no binding model has")
  }

  test("duals carry the same sign convention as Prima's") {
    // Both are standard form `Kx >= q`, so the multiplier is the derivative of
    // the objective with respect to `q` in both -- but that is a claim about
    // two independent implementations and is exactly the kind of thing that is
    // off by a sign until someone checks.
    val instance = LpFixtures.conclusive.find(_.name == "economic-dispatch").get
    val theirs   = ortools.solve(instance.problem)
    val mine     = prima.solve(instance.problem)
    theirs.dual.indices.foreach { r =>
      assertEqualsDouble(theirs.dual(r), mine.dual(r), 1e-5, s"row $r")
    }
  }

  test("reduced costs carry the same sign convention too") {
    // A separate convention from the duals, read from a separate OR-Tools API
    // (`MPVariable.reducedCost`) against Prima's `c - K'y`, and therefore just
    // as capable of being off by a sign. It is not a detail on this fixture:
    // `economic-dispatch` puts the line limit on a *variable bound* rather than
    // on a row, so the congestion price its docstring advertises lives entirely
    // in a reduced cost and nothing else here would notice it flipping.
    val instance = LpFixtures.conclusive.find(_.name == "economic-dispatch").get
    val theirs   = ortools.solve(instance.problem)
    val mine     = prima.solve(instance.problem)
    assert(theirs.reducedCosts.exists(c => math.abs(c) > 1e-6), "every reduced cost was zero")
    theirs.reducedCosts.indices.foreach { j =>
      assertEqualsDouble(theirs.reducedCosts(j), mine.reducedCosts(j), 1e-5, s"variable $j")
    }
  }

  test("an infeasible problem yields NaN rather than a number OR-Tools does not have") {
    val instance = LpFixtures.conclusive.find(_.expectedStatus == SolveStatus.PrimalInfeasible).get
    val solution = ortools.solve(instance.problem)
    assertEquals(solution.status, SolveStatus.PrimalInfeasible)
    assert(solution.dual.forall(_.isNaN), "duals should be NaN where no solution exists")
  }
