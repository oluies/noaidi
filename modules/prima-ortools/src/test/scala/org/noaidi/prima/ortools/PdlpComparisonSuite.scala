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
  private val ladder: Seq[(String, LpProblem)] =
    LpFixtures.conclusive.map(i => i.name -> i.problem) ++ Seq(
      "random-60x30"   -> LpFixtures.randomFeasible(1, 60, 10, 20, 0.25),
      "random-200x120" -> LpFixtures.randomFeasible(2, 200, 40, 80, 0.10),
      "random-600x400" -> LpFixtures.randomFeasible(3, 600, 120, 280, 0.04),
    )

  /** `unbounded` is compared separately: PDLP does not classify it. */
  private val comparable = ladder.filterNot(_._1 == "unbounded")

  test("two implementations of the same algorithm take the same order of iterations") {
    println(f"%n${"instance"}%-20s ${"prima"}%9s ${"pdlp"}%9s ${"ratio"}%7s  status")
    val ratios = comparable.map { (name, problem) =>
      val mine   = prima.solve(problem)
      val theirs = pdlp.solve(problem)

      assertEquals(theirs.status, mine.status, s"$name: the two disagreed on the status")
      assert(theirs.iterations > 0, s"$name: PDLP reported no iterations, so there is nothing to compare")

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
    assertEquals(pdlp.solve(unbounded.problem).status, SolveStatus.NumericalError)

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
    intercept[IllegalArgumentException](bad.solve(problem))
  }
