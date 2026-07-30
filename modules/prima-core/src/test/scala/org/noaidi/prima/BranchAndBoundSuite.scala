package org.noaidi.prima

/** Branch-and-bound, against optima worked out by hand.
  *
  * Every fixture here is one where the '''relaxation is fractional''', so the
  * integer answer differs from the LP answer. That is deliberate: a MILP whose
  * relaxation happens to be integral is solved correctly by doing nothing at
  * all, and a suite of those would pass against a stub that returned the
  * relaxation.
  */
class BranchAndBoundSuite extends munit.FunSuite:

  private val params = BnbParams(lp = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9))

  /** maximise 5a + 4b  subject to  6a + 4b <= 24,  a + 2b <= 6,  a,b >= 0 */
  private def knapsackish(integral: Boolean) =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -5.0)
    b.objectiveCoefficient(1, -4.0)
    b.bounds(0, 0.0, 10.0)
    b.bounds(1, 0.0, 10.0)
    b.lessThan(Seq((0, 6.0), (1, 4.0)), 24.0)
    b.lessThan(Seq((0, 1.0), (1, 2.0)), 6.0)
    b.build()._1

  test("an integer optimum differs from the fractional relaxation") {
    // The LP optimum is a = 3, b = 1.5 with objective -21. Neither coordinate
    // survives integrality: the best integer point is a = 4, b = 0 at -20.
    // A stub returning the relaxation reports -21 and a fractional b, so this
    // fails without a working search.
    val problem  = knapsackish(true)
    val relaxed  = Pdhg.solve(problem, params.lp)
    assertEquals(relaxed.status, SolveStatus.Optimal)
    assertEqualsDouble(relaxed.objectiveValue, -21.0, 1e-6, "relaxation")
    assert(math.abs(relaxed.primal(1) - 1.5) < 1e-5, s"relaxation should be fractional: ${relaxed.primal(1)}")

    val milp = BranchAndBound.solve(problem, Set(0, 1), params)
    assertEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assertEqualsDouble(milp.objectiveValue, -20.0, 1e-6, s"$milp")
    assertEqualsDouble(milp.primal(0), 4.0, 1e-9)
    assertEqualsDouble(milp.primal(1), 0.0, 1e-9)
  }

  test("integer coordinates come back as whole numbers") {
    val milp = BranchAndBound.solve(knapsackish(true), Set(0, 1), params)
    milp.primal.foreach(v => assertEqualsDouble(v, math.round(v).toDouble, 0.0, s"$v is not integral"))
  }

  test("relaxing integrality on one variable changes the answer") {
    // Same problem, but only `a` is integer. b is then free to sit at 1.5, and
    // the optimum is a = 3, b = 1.5 at -21 -- the relaxation value. This is what
    // distinguishes an implementation that honours the integer *set* from one
    // that rounds everything.
    val milp = BranchAndBound.solve(knapsackish(true), Set(0), params)
    assertEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assertEqualsDouble(milp.objectiveValue, -21.0, 1e-6, s"$milp")
    assertEqualsDouble(milp.primal(0), 3.0, 1e-9)
    assert(math.abs(milp.primal(1) - 1.5) < 1e-5, s"b should stay fractional: ${milp.primal(1)}")
  }

  test("an empty integer set short-circuits to the relaxation") {
    val milp = BranchAndBound.solve(knapsackish(true), Set.empty, params)
    assertEquals(milp.status, MilpStatus.Optimal)
    assertEqualsDouble(milp.objectiveValue, -21.0, 1e-6)
    assertEquals(milp.nodesExplored, 1)
  }

  test("a problem with no integer point is proven infeasible") {
    // 2x = 1 with x integer. The relaxation is feasible at x = 0.5, so this can
    // only be settled by branching: x <= 0 and x >= 1 are both infeasible.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 1.0)
    b.equalityConstraint(Seq((0, 2.0)), 1.0)
    val problem = b.build()._1

    assertEquals(Pdhg.solve(problem, params.lp).status, SolveStatus.Optimal, "relaxation is feasible")
    val milp = BranchAndBound.solve(problem, Set(0), params)
    assertEquals(milp.status, MilpStatus.Infeasible, s"$milp")
  }

  test("binary variables are integers with unit bounds") {
    // minimise -(3x + 2y + 4z) subject to x + y + z <= 2, all binary.
    // Best two of {3, 2, 4} is z and x, so -7.
    val b = LpProblem.builder(3)
    Seq(-3.0, -2.0, -4.0).zipWithIndex.foreach((c, i) => b.objectiveCoefficient(i, c))
    (0 until 3).foreach(i => b.bounds(i, 0.0, 1.0))
    b.lessThan(Seq((0, 1.0), (1, 1.0), (2, 1.0)), 2.0)
    val milp = BranchAndBound.solve(b.build()._1, Set(0, 1, 2), params)

    assertEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assertEqualsDouble(milp.objectiveValue, -7.0, 1e-6, s"$milp")
    assertEqualsDouble(milp.primal(0), 1.0, 1e-9)
    assertEqualsDouble(milp.primal(1), 0.0, 1e-9)
    assertEqualsDouble(milp.primal(2), 1.0, 1e-9)
  }

  test("the reported bound brackets the optimum") {
    // The contract behind MilpStatus.Optimal: bestBound and objectiveValue agree
    // to the gap tolerance, and the bound never exceeds the achieved objective.
    val milp = BranchAndBound.solve(knapsackish(true), Set(0, 1), params)
    assertEquals(milp.status, MilpStatus.Optimal)
    assert(milp.bestBound <= milp.objectiveValue + 1e-9, s"bound above incumbent: $milp")
    assert(milp.gap <= 1e-6, s"gap not closed: $milp")
  }

  test("a node limit downgrades the status rather than lying") {
    // One node cannot prove optimality on a problem that needs branching. The
    // honest outcomes are Feasible or NoSolutionFound -- never Optimal.
    val milp = BranchAndBound.solve(
      knapsackish(true),
      Set(0, 1),
      params.copy(maxNodes = 1),
    )
    assertNotEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assert(!milp.status.isConclusive, s"$milp")
  }

  test("warm starting does not change the answer") {
    // It is a performance device, so agreeing with the cold search is the whole
    // requirement. NOTES records an unexplained warm-start regression on dense
    // LPs, which is why this is asserted rather than assumed.
    val cold = BranchAndBound.solve(knapsackish(true), Set(0, 1), params.copy(warmStart = false))
    val warm = BranchAndBound.solve(knapsackish(true), Set(0, 1), params.copy(warmStart = true))
    assertEquals(warm.status, cold.status)
    assertEqualsDouble(warm.objectiveValue, cold.objectiveValue, 1e-9, s"cold=$cold warm=$warm")
    assertEquals(warm.primal.toSeq, cold.primal.toSeq)
  }

  test("an out-of-range integer column is rejected") {
    intercept[IllegalArgumentException](BranchAndBound.solve(knapsackish(true), Set(0, 7), params))
  }
