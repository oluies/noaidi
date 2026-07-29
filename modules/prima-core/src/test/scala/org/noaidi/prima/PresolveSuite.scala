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

    result.provenStatus match
      case Some(proven) =>
        assertEquals(proven, direct.status, s"$hint: presolve proved a different status")
        // A proof means there is nothing to solve, and both entry points must
        // refuse rather than hand back something that looks usable.
        intercept[IllegalStateException](result.reduced)
        intercept[IllegalStateException](result.restore(direct))
      case None =>
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

          // The dual side, which is where postsolve actually goes wrong. Duals
          // are not unique on degenerate instances so they are not compared
          // elementwise against the direct solve; what is asserted is that the
          // restored dual is a valid dual of the ORIGINAL problem.
          for r <- problem.numEqualities until problem.numConstraints do
            assert(
              restored.dual(r) >= -1e-6,
              s"$hint: row $r has dual ${restored.dual(r)}, outside the cone for a >= row",
            )

          // Reduced costs must still be c - K'y against the original data.
          val ktY = problem.constraintMatrix.transpose.multiply(restored.dual)
          for i <- 0 until problem.numVariables do
            assertEqualsDouble(
              restored.reducedCosts(i),
              problem.objective(i) - ktY(i),
              1e-6,
              s"$hint: reduced cost $i is not c - K'y",
            )

          // And the pair must actually be optimal for the original, not merely
          // well-formed. This is the assertion that would have caught a slack row
          // being priced.
          val convergence = Kkt.convergence(
            restored.kkt,
            Kkt.norm2(problem.rhsRaw),
            Kkt.norm2(problem.objectiveRaw),
            1e-6,
            1e-6,
          )
          assert(
            convergence.converged,
            s"$hint: restored pair is not optimal for the original: ${restored.kkt}",
          )

  // `conclusive`, not `optimal`: the infeasible and unbounded fixtures are
  // exactly the ones presolve can silently mishandle, by proving nothing or by
  // deleting the column the unboundedness lived on.
  LpFixtures.conclusive.foreach { instance =>
    test(s"presolve preserves the answer on ${instance.name}") {
      agrees(instance.problem, instance.name)
    }
  }

  test("an unbounded objective survives presolve") {
    // min -x with x >= 0 and no upper bound: the row folds into a bound, x is
    // left in no row, and its cost drives it to infinity. Removing the column
    // without recording that would leave an empty problem reporting Optimal.
    val result = Presolve(LpFixtures.unbounded.problem)
    assert(result.unboundedIfFeasible, s"the improving direction was lost: ${result.stats}")
    // Not a proof on its own -- the remaining rows must also be feasible.
    assertEquals(result.provenStatus, None)
    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEquals(restored.status, SolveStatus.DualInfeasible, s"$restored")
  }

  test("an improving direction on an infeasible problem is not unboundedness") {
    // `min -x` with x free of every row, alongside two rows that contradict each
    // other. x gives an improving direction to infinity, but the problem has no
    // feasible point at all, so the answer is infeasible -- not unbounded.
    // Treating the direction as a proof reports the wrong conclusive status.
    val b = LpProblem.builder(3)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, Double.PositiveInfinity)
    b.bounds(1, 0.0, Double.PositiveInfinity)
    b.bounds(2, 0.0, Double.PositiveInfinity)
    b.greaterThan(Seq(1 -> 1.0, 2 -> 1.0), 10.0)
    b.lessThan(Seq(1 -> 1.0, 2 -> 1.0), 1.0)
    val problem = b.build()._1

    val direct = Pdhg.solve(problem, params)
    assertEquals(direct.status, SolveStatus.PrimalInfeasible, s"$direct")

    val result = Presolve(problem)
    assert(result.unboundedIfFeasible, "the improving direction should still be noticed")
    assertEquals(result.provenStatus, None, "an improving direction is not a proof by itself")

    val restored = result.restore(Pdhg.solve(result.reduced, params))
    assertEquals(
      restored.status,
      SolveStatus.PrimalInfeasible,
      s"an infeasible problem was reported as unbounded: $restored",
    )
  }

  test("a slack singleton row is priced at zero, not from the reduced cost") {
    // `rangeRow` splits into two singleton rows on the same column once y is
    // fixed. Only the binding one may carry a price; pricing both gives the
    // second a negative dual on a `>=` row.
    val problem  = LpFixtures.rangeRow.problem
    val result   = Presolve(problem)
    val restored = result.restore(Pdhg.solve(result.reduced, params))

    assertEqualsDouble(restored.objectiveValue, 2.0, 1e-6)
    for r <- problem.numEqualities until problem.numConstraints do
      assert(restored.dual(r) >= -1e-9, s"row $r priced at ${restored.dual(r)}")
    assertEqualsDouble(restored.kkt.absoluteGap, 0.0, 1e-5, s"duality gap: ${restored.kkt}")
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
