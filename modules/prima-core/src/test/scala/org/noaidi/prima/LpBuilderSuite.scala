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

  test("every builder method rejects an out-of-range variable the same way") {
    val b = LpProblem.builder(2)
    intercept[IllegalArgumentException](b.objectiveCoefficient(2, 1.0))
    intercept[IllegalArgumentException](b.bounds(-1, 0.0, 1.0))
    intercept[IllegalArgumentException](b.greaterThan(Seq(5 -> 1.0), 0.0))
  }

  test("non-finite objective coefficients are rejected") {
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, Double.PositiveInfinity)
    b.bounds(0, 0.0, 1.0)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    intercept[IllegalArgumentException](b.build())
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

  test("a column added after a row widens the matrix and leaves earlier rows alone") {
    // `addVariable` is the whole point of the builder's columns being growable, and its
    // only exercise was through a suite that begins every case with `assume(fixtures)` --
    // so without the NordPSA reference data on disk it had no coverage at all.
    //
    // The interesting part is not that the column exists. It is that a row emitted
    // *before* it must end up with no entry in it, because a row is stored as the
    // coefficients it was given and nothing back-fills a zero.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 10.0)
    b.objectiveCoefficient(0, 1.0)
    b.greaterThan(Seq(0 -> 1.0), 2.0)

    val added = b.addVariable(0.0, 5.0, -2.0)
    assertEquals(added, 1, "the new column should take the next index")
    assertEquals(b.numVariables, 2)
    b.lessThan(Seq(added -> 1.0), 4.0)

    val (problem, _) = b.build()
    assertEquals(problem.numVariables, 2)
    assertEquals(problem.constraintMatrix.cols, 2, "the matrix did not widen")
    assertEqualsDouble(problem.variableLower(added), 0.0, 1e-12)
    assertEqualsDouble(problem.variableUpper(added), 5.0, 1e-12)
    assertEqualsDouble(problem.objective(added), -2.0, 1e-12, "the declared cost was lost")

    // The first row, emitted before the column existed, must not have acquired an entry
    // in it.
    val firstRowEntries =
      (0 until problem.constraintMatrix.cols).filter(c => problem.constraintMatrix(0, c) != 0.0)
    assert(
      !firstRowEntries.contains(added),
      s"a row emitted before the column has an entry in it: columns $firstRowEntries",
    )
  }

  test("growing past the initial capacity keeps every column's bounds") {
    // Walks well past the initial capacity so the arrays double more than once, and
    // checks every column still carries what it was declared with -- the property a
    // botched `copyOf` or an off-by-one in the length would break.
    //
    // Not a test of the fresh tail's contents: nothing can read it. `addVariable` writes
    // all three values before incrementing the count, and `checkVariable` refuses any
    // index at or past it.
    val b = LpProblem.builder(0)
    val indices = (0 until 40).map(i => b.addVariable(-i.toDouble, i.toDouble, i.toDouble))
    assertEquals(b.numVariables, 40)
    // One row, so the problem is well-formed; the assertion is about the bounds.
    b.lessThan(Seq(indices.last -> 1.0), 100.0)
    val (problem, _) = b.build()
    assertEquals(problem.numVariables, 40)
    indices.foreach { i =>
      assertEqualsDouble(problem.variableLower(i), -i.toDouble, 1e-12, s"lower of column $i")
      assertEqualsDouble(problem.variableUpper(i), i.toDouble, 1e-12, s"upper of column $i")
      assertEqualsDouble(problem.objective(i), i.toDouble, 1e-12, s"cost of column $i")
    }
  }

  test("appending to a builder whose capacity equals its declared count keeps the head") {
    // The shape production uses, and the one neither other case covered. `Lopf` builds
    // with `LpProblem.builder(bounds.length)` -- ~336 declared columns -- and
    // `TerminalValue` then appends, so `columns == objective.length` already holds at
    // construction and the very first `addVariable` has to grow from a capacity that is
    // neither zero nor a power of two. `builder(0)` grows from the 16-element floor and
    // `builder(1)` never grows at all, so a `grow` that mis-sized or copied only part of
    // the head would have passed both.
    //
    // Most of the declared columns are left unbounded on purpose: that is what a column
    // `Lopf` declares but never bounds looks like, and a grow that rewrote the head
    // rather than copying it would turn them into `[0, 0]` -- a pinned column, which
    // changes the problem silently rather than failing.
    val declared = 20
    val b = LpProblem.builder(declared)
    assertEquals(b.numVariables, declared)
    b.bounds(0, -1.0, 1.0)
    b.objectiveCoefficient(0, 7.0)
    b.greaterThan(Seq(0 -> 1.0), -0.5)

    val appended = b.addVariable(2.0, 3.0, -4.0)
    assertEquals(appended, declared, "the appended column should follow the declared ones")
    b.lessThan(Seq(appended -> 1.0), 3.0)

    val (problem, _) = b.build()
    assertEquals(problem.numVariables, declared + 1)
    // The bounded head column kept what it was given.
    assertEqualsDouble(problem.variableLower(0), -1.0, 1e-12)
    assertEqualsDouble(problem.variableUpper(0), 1.0, 1e-12)
    assertEqualsDouble(problem.objective(0), 7.0, 1e-12)
    // The untouched head columns are still unbounded, not pinned at zero.
    (1 until declared).foreach { j =>
      assert(problem.variableLower(j).isNegInfinity, s"column $j lost its unbounded lower")
      assert(problem.variableUpper(j).isPosInfinity, s"column $j lost its unbounded upper")
      assertEqualsDouble(problem.objective(j), 0.0, 1e-12, s"column $j gained a cost")
    }
    // And the appended column carries its own.
    assertEqualsDouble(problem.variableLower(appended), 2.0, 1e-12)
    assertEqualsDouble(problem.variableUpper(appended), 3.0, 1e-12)
    assertEqualsDouble(problem.objective(appended), -4.0, 1e-12)
  }

