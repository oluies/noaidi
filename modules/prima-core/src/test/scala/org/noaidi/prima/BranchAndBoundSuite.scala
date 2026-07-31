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
  private def knapsackish =
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
    val problem  = knapsackish
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
    val milp = BranchAndBound.solve(knapsackish, Set(0, 1), params)
    milp.primal.foreach(v => assertEqualsDouble(v, math.round(v).toDouble, 0.0, s"$v is not integral"))
  }

  test("relaxing integrality on one variable changes the answer") {
    // Same problem, but only `a` is integer. b is then free to sit at 1.5, and
    // the optimum is a = 3, b = 1.5 at -21 -- the relaxation value. This is what
    // distinguishes an implementation that honours the integer *set* from one
    // that rounds everything.
    val milp = BranchAndBound.solve(knapsackish, Set(0), params)
    assertEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assertEqualsDouble(milp.objectiveValue, -21.0, 1e-6, s"$milp")
    assertEqualsDouble(milp.primal(0), 3.0, 1e-9)
    assert(math.abs(milp.primal(1) - 1.5) < 1e-5, s"b should stay fractional: ${milp.primal(1)}")
  }

  test("an empty integer set short-circuits to the relaxation") {
    val milp = BranchAndBound.solve(knapsackish, Set.empty, params)
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
    val milp = BranchAndBound.solve(knapsackish, Set(0, 1), params)
    assertEquals(milp.status, MilpStatus.Optimal)
    assert(milp.bestBound <= milp.objectiveValue + 1e-9, s"bound above incumbent: $milp")
    assert(milp.gap <= 1e-6, s"gap not closed: $milp")
  }

  test("a node limit downgrades the status rather than lying") {
    // One node cannot prove optimality on a problem that needs branching. The
    // honest outcomes are Feasible or NoSolutionFound -- never Optimal.
    val milp = BranchAndBound.solve(
      knapsackish,
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
    val cold = BranchAndBound.solve(knapsackish, Set(0, 1), params.copy(warmStart = false))
    val warm = BranchAndBound.solve(knapsackish, Set(0, 1), params.copy(warmStart = true))
    assertEquals(warm.status, cold.status)
    assertEqualsDouble(warm.objectiveValue, cold.objectiveValue, 1e-9, s"cold=$cold warm=$warm")
    assertEquals(warm.primal.toSeq, cold.primal.toSeq)
  }

  test("an out-of-range integer column is rejected") {
    intercept[IllegalArgumentException](BranchAndBound.solve(knapsackish, Set(0, 7), params))
  }

  test("a NaN bound is never a licence to skip a node") {
    // The previous version of this test asserted Optimal and a finite bound on a
    // clean instance, all of which held against the pre-fix code, because no
    // safeBound is ever NaN there. The property is only decidable where a NaN can
    // actually be supplied, so the predicate is exercised directly.
    //
    // `safeBound < incumbent` is false for NaN, which would skip the node: no
    // children, no unproven increment, subtree gone.
    assert(!BranchAndBound.skipsBeforeSolving(Double.NaN, 10.0, haveIncumbent = true))
    assert(!BranchAndBound.skipsBeforeSolving(Double.NegativeInfinity, 10.0, haveIncumbent = true))
    // A genuine bound above the incumbent still prunes, so the guard has not
    // simply disabled pruning.
    assert(BranchAndBound.skipsBeforeSolving(11.0, 10.0, haveIncumbent = true))
    assert(!BranchAndBound.skipsBeforeSolving(9.0, 10.0, haveIncumbent = true))
    // And nothing is skipped before there is anything to compare against.
    assert(!BranchAndBound.skipsBeforeSolving(11.0, Double.PositiveInfinity, haveIncumbent = false))
  }

  test("a bound that cannot be computed becomes negative infinity") {
    // The other half: `math.max(NaN, eps)` propagates, so a conclusive relaxation
    // with a NaN objective or KKT gap yields a NaN margin. -infinity is the only
    // safe reading -- it says nothing is known about the subtree.
    assertEquals(BranchAndBound.safeBoundOf(conclusive = true, 5.0, 1.0), 4.0)
    assertEquals(
      BranchAndBound.safeBoundOf(conclusive = true, Double.NaN, 1.0),
      Double.NegativeInfinity,
    )
    assertEquals(
      BranchAndBound.safeBoundOf(conclusive = true, 5.0, Double.NaN),
      Double.NegativeInfinity,
    )
    // An inconclusive relaxation has no bound at all, whatever it reported.
    assertEquals(
      BranchAndBound.safeBoundOf(conclusive = false, 5.0, 1.0),
      Double.NegativeInfinity,
    )
  }

  test("the pruning margin costs nodes on an instance where it binds") {
    // A margin can only ever make the search explore more, never fewer, nodes.
    // Asserted across the ladder-style knapsack where pruning actually happens,
    // rather than on a two-variable instance where both settings agree trivially.
    val values  = Seq(41.0, 50, 49, 59, 45, 47, 12, 33, 21, 44, 51, 19, 28, 37, 40, 25)
    val weights = Seq(31.0, 27, 12, 34, 22, 19, 8, 16, 11, 24, 29, 9, 14, 20, 23, 13)
    val b       = LpProblem.builder(values.length)
    values.zipWithIndex.foreach((v, i) => b.objectiveCoefficient(i, -v))
    values.indices.foreach(i => b.bounds(i, 0.0, 1.0))
    b.lessThan(weights.zipWithIndex.map((w, i) => (i, w)), 120.0)
    val hard = b.build()._1

    def nodesAt(factor: Double): MilpSolution =
      BranchAndBound.solve(hard, values.indices.toSet, params.copy(pruningSafetyFactor = factor))

    val bare     = nodesAt(0.0)
    val margined = nodesAt(params.pruningSafetyFactor)
    val huge     = nodesAt(1e9)

    Seq(bare, margined, huge).foreach(r => assertEquals(r.status, MilpStatus.Optimal, s"$r"))
    // The margin buys soundness, not a different optimum.
    assertEqualsDouble(margined.objectiveValue, bare.objectiveValue, 1e-9, s"$margined vs $bare")
    assertEqualsDouble(huge.objectiveValue, bare.objectiveValue, 1e-9, s"$huge vs $bare")

    // A margin can only ever cost work.
    assert(margined.nodesExplored >= bare.nodesExplored, s"$margined vs $bare")

    // And this is the assertion that distinguishes the fix rather than restating
    // what the old code already did. A margin large enough to disable pruning
    // must make the search explore the tree, and that can only happen if the
    // *pre-solve* skip honours it too. The previous implementation applied the
    // margin only after solving and skipped nodes on a raw parent bound, so the
    // node count stayed pinned no matter how large the margin grew -- which is
    // exactly why the first version of this test passed against the bug.
    assert(
      huge.nodesExplored > margined.nodesExplored,
      s"a margin large enough to disable pruning explored no more nodes " +
        s"(${huge.nodesExplored} vs ${margined.nodesExplored}); the pre-solve skip is ignoring it",
    )
  }

  test("an unconverged relaxation downgrades the status and is counted") {
    // Starve the LP so no node converges. The contract is that nothing is
    // reported `Optimal` on the strength of bounds that were never established,
    // and that the count says how many.
    val starved = params.copy(lp = PdhgParams(epsAbs = 1e-14, epsRel = 1e-14, maxIterations = 2))
    val milp    = BranchAndBound.solve(knapsackish, Set(0, 1), starved)

    assertNotEquals(milp.status, MilpStatus.Optimal, s"$milp")
    assert(milp.unprovenNodes > 0, s"unproven nodes were not counted: $milp")
    assert(!milp.status.isConclusive, s"$milp")
  }

  test("a warm start built from an unusable solution is declined, not thrown") {
    // The guard is `Try(Pdhg.WarmStart(relaxation)).toOption`, and what it has to
    // do is decline rather than propagate. Asserted against the factory directly,
    // because a starved search produces finite iterates and never reaches the
    // branch -- which is why the previous test here (`nodesExplored > 0`) passed
    // against the pre-fix code and proved nothing.
    val poisoned = LpSolution(
      status = SolveStatus.NumericalError,
      primal = IArray(1.0, 2.0),
      dual = IArray(Double.NaN),
      reducedCosts = IArray(0.0, 0.0),
      objectiveValue = 1.0,
      dualObjectiveValue = 1.0,
      iterations = 1,
      restarts = 0,
      solveTimeMillis = 0L,
      kkt = KktError(0.0, 0.0, 1.0, 1.0),
    )
    intercept[IllegalArgumentException](Pdhg.WarmStart(poisoned))
    assertEquals(scala.util.Try(Pdhg.WarmStart(poisoned)).toOption, None)

    // And a sound one still produces a warm start, so the guard is not simply
    // disabling the feature.
    val good = Pdhg.solve(knapsackish, params.lp)
    assert(scala.util.Try(Pdhg.WarmStart(good)).toOption.isDefined)
  }

  test("an unbounded relaxation is reported as unbounded") {
    // A free integer column with a negative cost and no constraint to stop it.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, Double.PositiveInfinity)
    val milp = BranchAndBound.solve(b.build()._1, Set(0), params)
    assertEquals(milp.status, MilpStatus.Unbounded, s"$milp")
  }

  test("a time limit stops the search without claiming optimality") {
    // On a two-variable instance a zero limit proves nothing: the search
    // finishes inside it and `Optimal` is then the right answer -- which is what
    // the first version of this test got wrong. A sixteen-item knapsack takes
    // hundreds of nodes, so it cannot complete before the first time check.
    val values  = Seq(41.0, 50, 49, 59, 45, 47, 12, 33, 21, 44, 51, 19, 28, 37, 40, 25)
    val weights = Seq(31.0, 27, 12, 34, 22, 19, 8, 16, 11, 24, 29, 9, 14, 20, 23, 13)
    val b       = LpProblem.builder(values.length)
    values.zipWithIndex.foreach((v, i) => b.objectiveCoefficient(i, -v))
    values.indices.foreach(i => b.bounds(i, 0.0, 1.0))
    b.lessThan(weights.zipWithIndex.map((w, i) => (i, w)), 120.0)
    val hard = b.build()._1

    val full    = BranchAndBound.solve(hard, values.indices.toSet, params)
    assertEquals(full.status, MilpStatus.Optimal, s"$full")
    assert(full.nodesExplored > 50, s"the instance is too easy to test a limit: $full")

    // Gated on the unlimited run actually taking measurable time. A zero limit
    // fires only once the millisecond clock advances past `started`, so on a
    // coarse clock (~10-15 ms on Windows) or a fast enough machine the search
    // finishes inside it and Optimal is then the correct answer -- the strict
    // assertions would be asserting a race.
    val limited = BranchAndBound.solve(hard, values.indices.toSet, params.copy(timeLimitMillis = Some(0L)))
    assert(limited.nodesExplored <= full.nodesExplored, s"the limit added work: $limited")
    if full.solveTimeMillis > 5 then
      assertNotEquals(limited.status, MilpStatus.Optimal, s"$limited")
      assert(limited.nodesExplored < full.nodesExplored, s"the limit did not stop anything: $limited")
  }

  test("the reported objective is the objective of the reported point") {
    // These were computed from different things: the objective came from the
    // relaxation's fractional iterate while the vector came from snapping it.
    // Each integer coordinate moves by up to `integralityTolerance` in that
    // snap, so the two could differ by far more than the gap the result claims
    // to have closed -- and every downstream comparison would then be measuring
    // the snap rather than the answer.
    val problem = knapsackish
    val milp    = BranchAndBound.solve(problem, Set(0, 1), params)
    assertEquals(milp.status, MilpStatus.Optimal)
    assertEqualsDouble(
      milp.objectiveValue,
      problem.primalObjective(milp.primal),
      1e-12,
      s"reported objective is not c'x at the reported x: $milp",
    )
  }

  test("no answer means no point, on the short-circuit path too") {
    // `primal` is documented as empty when nothing was found, and a caller that
    // branches on `primal.nonEmpty` relies on it. The empty-integer-set path
    // used to return the relaxation's iterate regardless of status.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 1.0)
    b.equalityConstraint(Seq((0, 1.0)), 5.0) // outside the box
    val milp = BranchAndBound.solve(b.build()._1, Set.empty, params)
    assertEquals(milp.status, MilpStatus.Infeasible, s"$milp")
    assert(milp.primal.isEmpty, s"an infeasible result carried a point: $milp")
  }
