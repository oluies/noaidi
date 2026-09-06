package org.noaidi.prima
package ortools

/** Prima against the only other implementation of what Prima does.
  *
  * Every other backend in this build is a simplex. A simplex is the right
  * oracle for an *answer* — it fails in a completely different way, so
  * agreement is real evidence — and it says nothing at all about the parts of
  * this solver that are judgements rather than theorems: the restart schedule,
  * the adaptive step-size rule, and where the termination test is applied.
  * Those are what decide whether Prima takes two hundred iterations or twenty
  * thousand, and no simplex has an opinion on them.
  *
  * PDLP does. It is Google's restarted primal-dual hybrid gradient, from the
  * paper Prima follows, written independently in C++. Held to the same
  * tolerance on the same instances, its iteration count is the closest thing to
  * a reference this method has.
  *
  * '''This is the comparison the roadmap wanted cuPDLP-C for, minus the GPU.'''
  * cuPDLP-C would add a device timing and an fp64-on-hardware check; it would
  * not add anything to the question of whether the algorithm is implemented
  * right, because it is the same algorithm and PDLP is already it.
  */
class PdlpComparisonSuite extends munit.FunSuite:

  private val tolerance = 1e-9

  private val prima = Pdhg.Solver(PdhgParams(epsAbs = tolerance, epsRel = tolerance, maxIterations = 500_000))
  private val pdlp  = OrToolsSolver.pdlp(tolerance)

  /** The conclusive fixtures plus three random instances of growing size.
    *
    * The random ones carry the comparison: the hand-written fixtures converge
    * in a handful of restart periods, where any two implementations of anything
    * would agree, and `random-600x400` needs tens of thousands of iterations,
    * where a difference in the restart schedule has room to show.
    */
  private val ladder: Seq[LpFixtures.Instance] =
    LpFixtures.conclusive ++ Seq(
      LpFixtures.Instance("random-60x30", LpFixtures.randomFeasible(1, 60, 10, 20, 0.25), None),
      LpFixtures.Instance("random-200x120", LpFixtures.randomFeasible(2, 200, 40, 80, 0.10), None),
      LpFixtures.Instance("random-600x400", LpFixtures.randomFeasible(3, 600, 120, 280, 0.04), None),
    )

  /** `unbounded` is compared separately: PDLP does not classify it. */
  private val comparable = ladder.filterNot(_.name == "unbounded")

  test("two implementations of the same algorithm take the same order of iterations") {
    println(f"%n${"instance"}%-20s ${"prima"}%9s ${"pdlp"}%9s ${"ratio"}%7s  status")
    val ratios = comparable.map { instance =>
      val name   = instance.name
      val mine   = prima.solve(instance.problem)
      val theirs = pdlp.solve(instance.problem)

      assertEquals(theirs.status, mine.status, s"$name: the two disagreed on the status")
      assert(theirs.iterations > 0, s"$name: PDLP reported no iterations, so there is nothing to compare")

      // The answers first, then the counts. A ratio of iteration counts means
      // nothing unless the two are solving the same problem to the same place,
      // and a PDLP that reached a different optimum -- or the same one at a
      // materially different accuracy -- would land somewhere inside a band
      // deliberately widened to threefold and pass every instance.
      if mine.status == SolveStatus.Optimal then
        val scale = math.max(1.0, math.abs(mine.objectiveValue))
        assertEqualsDouble(theirs.objectiveValue, mine.objectiveValue, 1e-6 * scale, s"$name: objective")
        instance.expectedObjective.foreach { expected =>
          assertEqualsDouble(theirs.objectiveValue, expected, 1e-6 * scale, s"$name: against the known optimum")
        }

      val ratio = mine.iterations.toDouble / theirs.iterations
      println(f"$name%-20s ${mine.iterations}%9d ${theirs.iterations}%9d $ratio%7.2f  ${mine.status}")
      name -> ratio
    }

    // Threefold either way, and deliberately loose. The observed spread is
    // already 0.79 to 1.71 across two hosts -- iteration counts on the largest
    // dense instances are platform-specific, for both implementations, which
    // NOTES records under "Iteration counts are platform-specific" -- and on
    // top of that the two have different presolves and different scalings, so
    // pinning the ratio would fail on an OR-Tools bump that changed neither
    // algorithm. What it catches is the thing worth catching: a change to
    // Prima's restart schedule or step-size rule that costs it an order of
    // magnitude against a reference that did not move.
    ratios.foreach { (name, ratio) =>
      assert(
        ratio > 1.0 / 3.0 && ratio < 3.0,
        f"$name: Prima took $ratio%.2f times PDLP's iterations, which is outside the band the " +
          "restart schedule and step-size rule have held to",
      )
    }
  }

  test("PDLP does not classify an unbounded problem, and says so") {
    // GLOP reports `INFEASIBLE` for `min -x, x >= 0` and the feasibility probe
    // turns that into `DualInfeasible`. PDLP reports `NOT_SOLVED`, and the
    // probe -- being PDLP too -- cannot establish feasibility either, so the
    // answer is `NumericalError`: no conclusion, stated as none.
    //
    // Pinned rather than worked around. It is a real difference between the two
    // backends, and a caller choosing PDLP for its iteration behaviour should
    // know it gives up infeasibility classification in one direction.
    val unbounded = LpFixtures.conclusive.find(_.name == "unbounded").get
    assertEquals(prima.solve(unbounded.problem).status, SolveStatus.DualInfeasible)
    assertEquals(OrToolsSolver().solve(unbounded.problem).status, SolveStatus.DualInfeasible)

    // Asserted as "never wrong", not as the exact status. `NumericalError` is
    // what it gives today, and an OR-Tools bump that got PDLP to classify this
    // -- an improvement, needing no change here -- would fail a build for a
    // reason unrelated to Prima. That is the standard the ratio band above
    // sets, and it applies to a status just as much as to a count.
    val theirs = pdlp.solve(unbounded.problem).status
    assert(
      theirs == SolveStatus.NumericalError || theirs == SolveStatus.DualInfeasible,
      s"PDLP reported $theirs for an unbounded problem, which is neither no-conclusion nor the right one",
    )

    // The other direction it does get: a primal-infeasible problem is detected.
    val infeasible = LpFixtures.conclusive.find(_.name == "infeasible").get
    assertEquals(pdlp.solve(infeasible.problem).status, SolveStatus.PrimalInfeasible)
  }

  test("the tolerance has to be set, or the comparison measures two different questions") {
    // PDLP's default termination is far looser than Prima's, so a
    // default-configured PDLP reaches a different place and its iteration count
    // is not comparable to anything. This is why `OrToolsSolver.pdlp` exists
    // rather than `OrToolsSolver(Options(backend = "PDLP"))`.
    val problem  = LpFixtures.conclusive.find(_.name == "economic-dispatch").get.problem
    val default  = OrToolsSolver(OrToolsSolver.Options(backend = "PDLP")).solve(problem)
    val tightened = pdlp.solve(problem)

    assertEquals(default.status, SolveStatus.Optimal)
    assertEquals(tightened.status, SolveStatus.Optimal)
    assert(
      default.kkt.primalResidualNorm > 100.0 * tightened.kkt.primalResidualNorm,
      f"default PDLP stopped at ${default.kkt.primalResidualNorm}%.2e and the tightened one at " +
        f"${tightened.kkt.primalResidualNorm}%.2e, so the default is no longer the loose one",
    )
    assert(default.iterations < tightened.iterations, "the looser tolerance should cost fewer iterations")
  }

  test("a parameter string OR-Tools rejects is refused rather than ignored") {
    // `setSolverSpecificParametersAsString` returns false and leaves the solver
    // on its defaults. Ignoring that return would mean solving at a tolerance
    // nobody asked for and reporting it as the one they did -- which is exactly
    // how the deprecated spelling of these fields would have gone unnoticed.
    val problem = LpFixtures.conclusive.find(_.name == "economic-dispatch").get.problem
    val bad = OrToolsSolver(
      OrToolsSolver.Options(backend = "PDLP", parameters = Some("no_such_field { x: 1 }"))
    )
    // The message is checked, not just the type: `solve` throws the same
    // exception from `require(solver != null, ...)`, so on a build where PDLP
    // is missing from the linked natives this would pass for precisely the
    // wrong reason -- the parameter check never having run.
    val thrown = intercept[IllegalArgumentException](bad.solve(problem))
    assert(
      thrown.getMessage.contains("rejected the parameters"),
      s"threw for the wrong reason: ${thrown.getMessage}",
    )
  }

  test("a limit-terminated PDLP reports that it solved nothing") {
    // Which status PDLP gives when it runs out of iterations is the premise two
    // comments in `OrToolsSolver` rest on, and one of them asserted the wrong
    // one. It is `NOT_SOLVED`, which with no time limit set maps to
    // `NumericalError` -- so a divergent reference fails this suite's status
    // assertion rather than being mistaken for a feasible point.
    //
    // That is the premise only. The conclusion it supports -- that an exhausted
    // probe cannot report `Feasible` -- is pinned by the next test, which has to
    // call `feasibilityOf` directly; see there for why no end-to-end fixture
    // reaches it.
    val problem = ladder.find(_.name == "random-600x400").get.problem
    val starved = OrToolsSolver.pdlp(tolerance, iterationLimit = 4).solve(problem)

    assertEquals(starved.status, SolveStatus.NumericalError)
    // Measured rather than assumed: PDLP reports exactly the limit at 1, 2, 3,
    // 4, 5, 8, 16, 63, 64, 65 and 100 on this instance and this pinned
    // OR-Tools, so it does not round up to the termination-check frequency.
    // If a future bump changes that, this failing is the correct outcome --
    // the premise two comments in `OrToolsSolver` rest on would have moved.
    assert(starved.iterations <= 4, s"asked for at most 4 iterations, got ${starved.iterations}")

    // And the same solver, given room, still reaches the answer -- so the
    // status above is the limit talking and not the instance.
    assertEquals(pdlp.solve(problem).status, SolveStatus.Optimal)
  }

  test("an exhausted feasibility probe establishes nothing, rather than feasibility") {
    // Called directly, because no solve can reach it in this state. `feasibilityOf`
    // runs only when the outer solve returns `INFEASIBLE`, and the probe solves a
    // strict sub-problem -- the same rows with the objective thrown away -- under
    // the same inherited `iteration_limit`. So the probe always converges first:
    // measured on the `infeasible` fixture, 704 iterations against the outer
    // solve's 768. Any limit generous enough for the outer solve to reach
    // `INFEASIBLE` has already let the probe finish, and any tighter one starves
    // the outer solve to `NOT_SOLVED` so the probe is never built. There is no
    // window, which is why this is asserted against the method rather than
    // through `solve` -- the same reason `mapStatus` is `private[ortools]`.
    //
    // What it pins is the step the comment in `feasibilityOf` turns on: an
    // exhausted probe must land on `Unknown`, so the caller is told nothing was
    // established. Were it to answer `Feasible`, `mapStatus` would publish a
    // conclusive `DualInfeasible` derived from a non-converged iterate.
    val problem = ladder.find(_.name == "random-60x30").get.problem

    assertEquals(
      OrToolsSolver.pdlp(tolerance, iterationLimit = 4).feasibilityOf(problem),
      OrToolsSolver.Feasibility.Unknown,
      "a probe that ran out of iterations claimed to have established something",
    )

    // The control: the same probe, given room, does establish feasibility -- so
    // the `Unknown` above is the limit talking and not the problem.
    assertEquals(
      OrToolsSolver.pdlp(tolerance).feasibilityOf(problem),
      OrToolsSolver.Feasibility.Feasible,
    )
  }

  test("the probe drops the caller's time limit, and would be foolable if it did not") {
    // `feasibilityOf` guards itself with `options.copy(disambiguate = false,
    // timeLimitMillis = None)`, and its comment leans on both halves. The first
    // is pinned by the `infeasible` fixture -- drop `disambiguate = false` and
    // the probe recurses. The second was pinned by nothing: every other test
    // here builds its solver through `OrToolsSolver.pdlp`, which never sets a
    // time limit, so deleting `timeLimitMillis = None` left the whole suite
    // green at sixteen of sixteen.
    //
    // It matters because of what the caller's budget does to an exhausted
    // probe. `NOT_SOLVED` maps to `TimeLimit` when a limit was set and reached,
    // `TimeLimit` is read as proof of a feasible point, and `mapStatus` then
    // publishes a conclusive `DualInfeasible` derived from an iterate that
    // never converged -- the outcome the comment two tests above says cannot
    // happen. So this asks for the probe with a budget it must ignore.
    //
    // The instance is the large one deliberately: `timeLimitReached` is
    // `solveMillis >= limit`, so a solve that finishes inside a millisecond
    // would leave the mutation invisible for a second reason and prove nothing.
    val problem = ladder.find(_.name == "random-600x400").get.problem
    val timed = OrToolsSolver(
      OrToolsSolver.Options(
        backend = "PDLP",
        timeLimitMillis = Some(1),
        parameters = Some(OrToolsSolver.pdlpParameters(tolerance, iterationLimit = 4)),
      )
    )

    assertEquals(
      timed.feasibilityOf(problem),
      OrToolsSolver.Feasibility.Unknown,
      "the probe honoured the caller's time limit and read its own exhaustion as feasibility",
    )
  }
