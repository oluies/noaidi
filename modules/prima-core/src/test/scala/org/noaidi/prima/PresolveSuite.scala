package org.noaidi.prima

/** Presolve, and above all postsolve.
  *
  * A reduction that removes the wrong thing usually produces an infeasible or
  * obviously wrong answer, which is easy to notice. A postsolve that maps back
  * incorrectly produces a well-formed solution to the original problem that is
  * simply not the optimum — and it will pass any test that only checks the
  * reduced problem. So the standard applied throughout is: solving with
  * presolve must give the same objective, primal and dual as solving without.
  */
class PresolveSuite extends munit.FunSuite:

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 100_000)

  /** Solve twice — reduced then restored, against direct — and require them to
    * agree on everything a caller can observe.
    */
  private def agrees(problem: LpProblem, hint: String): Unit =
    val direct = Pdhg.solve(problem, params)
    val result = Presolve(problem)

    if result.provenInfeasible then
      assertEquals(direct.status, SolveStatus.PrimalInfeasible, s"$hint: presolve claimed infeasible")
    else
      val restored = result.restore(Pdhg.solve(result.reduced, params))
      assertEquals(restored.status, direct.status, s"$hint: status differs")

      if direct.status == SolveStatus.Optimal then
        assertEqualsDouble(
          restored.objectiveValue,
          direct.objectiveValue,
          1e-6 * math.max(1.0, math.abs(direct.objectiveValue)),
          s"$hint: objective differs",
        )
        // The restored point must satisfy the *original* constraints, not the
        // reduced ones — that is what postsolve is for.
        val kx = problem.constraintMatrix.multiply(restored.primal)
        for r <- 0 until problem.numConstraints do
          if r < problem.numEqualities then
            assertEqualsDouble(kx(r), problem.rhs(r), 1e-6, s"$hint: equality row $r")
          else assert(kx(r) >= problem.rhs(r) - 1e-6, s"$hint: inequality row $r violated")
        for i <- 0 until problem.numVariables do
          assert(
            restored.primal(i) >= problem.variableLower(i) - 1e-6 &&
              restored.primal(i) <= problem.variableUpper(i) + 1e-6,
            s"$hint: variable $i outside its original bounds",
          )

  LpFixtures.optimal.foreach { instance =>
    test(s"presolve preserves the answer on ${instance.name}") {
      agrees(instance.problem, instance.name)
    }
  }

  test("presolve preserves the answer on random sparse instances") {
    (1L to 8L).foreach { seed =>
      agrees(LpFixtures.randomFeasible(seed, 40, 8, 14, 0.25), s"random seed $seed")
    }
  }

  test("a variable pinned by its own bounds is removed and restored") {
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 1.0)
    b.objectiveCoefficient(1, 5.0)
    b.bounds(0, 0.0, 10.0)
    b.bounds(1, 3.0, 3.0) // fixed
    b.greaterThan(Seq(0 -> 1.0, 1 -> 1.0), 4.0)
    val problem = b.build()._1

    val result = Presolve(problem)
    assertEquals(result.stats.fixedColumns, 1)
    // Everything else follows: with y fixed the row becomes a singleton on x,
    // which folds into x's bounds and leaves x in no row at all. Counting exact
    // survivors would be asserting the cascade's route rather than its result.
    assertEquals(result.reduced.numVariables, 0, s"${result.stats}")

    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEquals(restored.status, SolveStatus.Optimal, s"$restored")
    assertEqualsDouble(restored.primal(1), 3.0, 1e-9, "the fixed variable lost its value")
    // x >= 1 once y = 3 is substituted, so the optimum is 1 + 15.
    assertEqualsDouble(restored.objectiveValue, 16.0, 1e-6)
  }

  test("a singleton row becomes a bound and its dual is recovered") {
    // min x subject to 2x >= 6 -- the row alone determines the answer, so its
    // price is the whole marginal cost and postsolve must reconstruct it.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 100.0)
    b.greaterThan(Seq(0 -> 2.0), 6.0)
    val problem = b.build()._1

    val result = Presolve(problem)
    assertEquals(result.stats.singletonRows, 1)
    assertEquals(result.reduced.numConstraints, 0)

    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEqualsDouble(restored.primal(0), 3.0, 1e-6)
    assertEqualsDouble(restored.objectiveValue, 3.0, 1e-6)
    // Relaxing the row by one unit lowers the cost by 1/2.
    assertEqualsDouble(restored.dual(0), 0.5, 1e-5, "singleton row dual not recovered")
  }

  test("an empty row that holds is dropped and priced at zero") {
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 2.0, 2.0) // fixing this empties the row below
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    val problem = b.build()._1

    val result = Presolve(problem)
    assert(!result.provenInfeasible)
    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEqualsDouble(restored.objectiveValue, 2.0, 1e-6)
    assertEqualsDouble(restored.dual(0), 0.0, 1e-9)
  }

  test("an empty row that cannot hold proves infeasibility outright") {
    // Fixing x at 0 leaves the row demanding 0 >= 5.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 0.0)
    b.greaterThan(Seq(0 -> 1.0), 5.0)
    val problem = b.build()._1

    val result = Presolve(problem)
    assert(result.provenInfeasible, "presolve should have proven this infeasible without iterating")
  }

  test("contradictory bounds from a singleton row prove infeasibility") {
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 1.0)
    b.greaterThan(Seq(0 -> 1.0), 5.0) // forces x >= 5 against x <= 1
    val problem = b.build()._1
    assert(Presolve(problem).provenInfeasible)
  }

  test("a variable in no row is set from its cost alone") {
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 1.0)
    b.objectiveCoefficient(1, -2.0) // wants to be as large as possible
    b.bounds(0, 0.0, 10.0)
    b.bounds(1, 0.0, 4.0)
    b.greaterThan(Seq(0 -> 1.0), 3.0) // only mentions x
    val problem = b.build()._1

    val result = Presolve(problem)
    // At least y, and in practice x too once its singleton row is folded away.
    assert(result.stats.emptyColumns >= 1, s"${result.stats}")
    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEqualsDouble(restored.primal(1), 4.0, 1e-9, "the free variable went the wrong way")
    assertEqualsDouble(restored.objectiveValue, 3.0 - 8.0, 1e-6)
  }

  test("reductions cascade across passes") {
    // Fixing z empties a row, which turns another into a singleton, which fixes
    // a variable. One pass would catch none of the later steps.
    val b = LpProblem.builder(3)
    b.objectiveCoefficient(0, 1.0)
    b.objectiveCoefficient(1, 1.0)
    b.objectiveCoefficient(2, 1.0)
    b.bounds(0, 0.0, 10.0)
    b.bounds(1, 0.0, 10.0)
    b.bounds(2, 5.0, 5.0)
    b.equalityConstraint(Seq(2 -> 1.0), 5.0)
    b.equalityConstraint(Seq(0 -> 1.0, 2 -> 1.0), 9.0)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 6.0)
    val problem = b.build()._1

    val result = Presolve(problem)
    assertEquals(result.reduced.numVariables, 0, s"expected everything to reduce away: ${result.stats}")
    val restored = result.restore(Pdhg.solve(result.reduced, params))
    // z = 5, x = 4, y = 2.
    assertEqualsDouble(restored.primal(2), 5.0, 1e-9)
    assertEqualsDouble(restored.primal(0), 4.0, 1e-6)
    assertEqualsDouble(restored.primal(1), 2.0, 1e-6)
    assertEqualsDouble(restored.objectiveValue, 11.0, 1e-6)
  }

  test("a problem with nothing to reduce is passed through unchanged") {
    val problem = LpFixtures.economicDispatch.problem
    val result  = Presolve(problem)
    assert(result.stats.isEmpty, s"unexpected reductions: ${result.stats}")
    assertEquals(result.reduced.numVariables, problem.numVariables)
    assertEquals(result.reduced.numConstraints, problem.numConstraints)
  }
