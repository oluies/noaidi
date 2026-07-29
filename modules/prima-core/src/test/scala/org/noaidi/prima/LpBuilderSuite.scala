package org.noaidi.prima

class LpBuilderSuite extends munit.FunSuite:

  test("equalities are ordered before inequalities regardless of insertion order") {
    val b = LpProblem.builder(2)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.equalityConstraint(Seq(1 -> 1.0), 5.0)
    b.lessThan(Seq(0 -> 1.0), 9.0)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 7.0)

    val (problem, _) = b.build()
    assertEquals(problem.numEqualities, 2)
    assertEquals(problem.numConstraints, 4)
    // The two equalities landed in rows 0 and 1, in the order they were added.
    assertEquals(problem.rhs(0), 5.0)
    assertEquals(problem.rhs(1), 7.0)
  }

  test("a `<=` row is negated into `>=` form") {
    val b = LpProblem.builder(1)
    b.lessThan(Seq(0 -> 2.0), 8.0)
    val (problem, translation) = b.build()

    assertEquals(problem.numEqualities, 0)
    assertEquals(problem.constraintMatrix(0, 0), -2.0)
    assertEquals(problem.rhs(0), -8.0)
    assertEquals(translation.expansionOf(0), RowExpansion.Negated(0))
  }

  test("a range row becomes two rows and its dual recombines them") {
    val b = LpProblem.builder(1)
    b.constraint(Seq(0 -> 1.0), 2.0, 5.0)
    val (problem, translation) = b.build()

    assertEquals(problem.numConstraints, 2)
    assertEquals(problem.rhs(0), 2.0)
    assertEquals(problem.rhs(1), -5.0)
    assertEquals(translation.expansionOf(0), RowExpansion.Range(0, 1))

    // Only the lower side is priced: the recovered dual is that price.
    assertEquals(translation.originalDuals(IArray(3.0, 0.0)).toList, List(3.0))
    // Only the upper side is priced: the sign flips back.
    assertEquals(translation.originalDuals(IArray(0.0, 4.0)).toList, List(-4.0))
  }

  test("duals of a negated row come back with the caller's sign convention") {
    val b = LpProblem.builder(1)
    b.lessThan(Seq(0 -> 1.0), 3.0)
    val (_, translation) = b.build()
    assertEquals(translation.originalDuals(IArray(2.0)).toList, List(-2.0))
  }

  test("a row unbounded on both sides is rejected rather than silently dropped") {
    val b = LpProblem.builder(1)
    intercept[IllegalArgumentException] {
      b.constraint(Seq(0 -> 1.0), Double.NegativeInfinity, Double.PositiveInfinity)
    }
  }

  test("empty bound intervals are rejected") {
    val b = LpProblem.builder(1)
    intercept[IllegalArgumentException](b.bounds(0, 5.0, 1.0))
  }

  test("primalObjective includes the offset") {
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 2.0)
    b.bounds(0, 0.0, 10.0)
    b.objectiveOffset(100.0)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    val (problem, _) = b.build()
    assertEquals(problem.primalObjective(IArray(3.0)), 106.0)
  }

  test("an objective offset shifts the optimum by exactly that much") {
    val base = LpFixtures.equalitySplit.problem
    val shifted = LpProblem(
      objective = base.objective,
      constraintMatrix = base.constraintMatrix,
      rhs = base.rhs,
      numEqualities = base.numEqualities,
      variableLower = base.variableLower,
      variableUpper = base.variableUpper,
      objectiveOffset = -1000.0,
    )
    val solution = Pdhg.solve(shifted)
    assertEquals(solution.status, SolveStatus.Optimal)
    assertEqualsDouble(solution.objectiveValue, 24.0 - 1000.0, 1e-6)
  }
