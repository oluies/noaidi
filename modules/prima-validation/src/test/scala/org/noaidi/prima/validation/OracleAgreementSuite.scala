package org.noaidi.prima
package validation

import org.noaidi.prima.ojalgo.OjAlgoSolver

/** Prima against ojAlgo.
  *
  * A first-order method and a simplex method share no code and fail in
  * different ways, so where they agree the answer is very likely right. The
  * comparison is on objective value rather than on the solution vector: several
  * of these instances are degenerate and have a face of optimal solutions, and
  * two solvers landing on different vertices of that face is correct behaviour,
  * not a discrepancy.
  */
class OracleAgreementSuite extends munit.FunSuite:

  private val prima  = Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000))
  private val oracle = OjAlgoSolver()

  private def relativeTolerance(reference: Double): Double =
    1e-6 * math.max(1.0, math.abs(reference))

  LpFixtures.conclusive.foreach { instance =>
    test(s"prima and ojalgo agree on ${instance.name}") {
      val mine  = prima.solve(instance.problem)
      val theirs = oracle.solve(instance.problem)

      assertEquals(mine.status, instance.expectedStatus, s"prima: $mine")
      assertEquals(theirs.status, instance.expectedStatus, s"ojalgo: $theirs")

      if instance.expectedStatus == SolveStatus.Optimal then
        assertEqualsDouble(
          mine.objectiveValue,
          theirs.objectiveValue,
          relativeTolerance(theirs.objectiveValue),
          s"prima=${mine.objectiveValue} ojalgo=${theirs.objectiveValue}",
        )
    }
  }

  (1L to 12L).foreach { seed =>
    test(s"prima and ojalgo agree on random sparse instance $seed") {
      val problem = LpFixtures.randomFeasible(
        seed = seed,
        numVariables = 30 + (seed.toInt * 3),
        numEqualities = 6,
        numInequalities = 10,
        density = 0.25,
      )
      val mine   = prima.solve(problem)
      val theirs = oracle.solve(problem)

      assertEquals(theirs.status, SolveStatus.Optimal, s"oracle failed on seed $seed: $theirs")
      assertEquals(mine.status, SolveStatus.Optimal, s"prima failed on seed $seed: $mine")
      assertEqualsDouble(
        mine.objectiveValue,
        theirs.objectiveValue,
        relativeTolerance(theirs.objectiveValue),
        s"seed $seed: prima=${mine.objectiveValue} ojalgo=${theirs.objectiveValue}",
      )
    }
  }

  test("the worst objective gap across the ladder stays within its documented bound") {
    // The per-instance assertions above run at 1e-6, three orders of magnitude
    // looser than the figure both documents publish, and the CI report only
    // prints. This is what actually holds that number.
    //
    // Ladder, formula and bound all come from ValidationLadder, which Report
    // also uses. Sharing them is what makes "the assertion enforces the
    // published number" structurally true rather than a claim in a comment.
    val gaps = ValidationLadder.entries.flatMap { entry =>
      val mine   = prima.solve(entry.problem)
      val theirs = oracle.solve(entry.problem)
      // Asserted against the *expected* status, not filtered on the observed
      // one. Filtering would let an instance that regressed to IterationLimit
      // drop out of the comparison instead of failing it.
      assertEquals(mine.status, entry.expected, s"${entry.name}: prima: $mine")
      assertEquals(theirs.status, entry.expected, s"${entry.name}: ojalgo: $theirs")
      Option.when(entry.expected == SolveStatus.Optimal) {
        (entry.name, ValidationLadder.relativeGap(mine.objectiveValue, theirs.objectiveValue))
      }
    }

    assert(gaps.nonEmpty, "no instance produced a comparable optimum")
    val (worstName, worst) = gaps.maxBy(_._2)
    assert(
      worst <= ValidationLadder.worstGapBound,
      f"worst relative objective gap $worst%.3e on $worstName exceeds " +
        f"the documented bound of ${ValidationLadder.worstGapBound}%.0e",
    )
  }

  test("prima's solutions are primal feasible on every random instance") {
    (1L to 12L).foreach { seed =>
      val problem  = LpFixtures.randomFeasible(seed, 30, 6, 10, 0.25)
      val solution = prima.solve(problem)
      val kx       = problem.constraintMatrix.multiply(solution.primal)

      for r <- 0 until problem.numConstraints do
        val slack = kx(r) - problem.rhs(r)
        if r < problem.numEqualities then
          assert(math.abs(slack) <= 1e-6, s"seed $seed row $r: equality off by $slack")
        else assert(slack >= -1e-6, s"seed $seed row $r: inequality violated by ${-slack}")
    }
  }

  test("prima's duals satisfy complementary slackness") {
    // ojAlgo's multipliers are not reliable across its solver paths, so the
    // duals are validated intrinsically instead: an inactive inequality must
    // carry a zero multiplier, and a reduced cost may only be non-zero where the
    // variable sits on the bound that cost pushes it against.
    LpFixtures.optimal.foreach { instance =>
      val p        = instance.problem
      val solution = prima.solve(p)
      val kx       = p.constraintMatrix.multiply(solution.primal)

      for r <- p.numEqualities until p.numConstraints do
        val slack = kx(r) - p.rhs(r)
        if slack > 1e-5 then
          assertEqualsDouble(
            solution.dual(r),
            0.0,
            1e-5,
            s"${instance.name}: row $r is slack by $slack yet priced at ${solution.dual(r)}",
          )
        assert(solution.dual(r) >= -1e-6, s"${instance.name}: row $r has a negative multiplier")

      for i <- 0 until p.numVariables do
        val reduced = solution.reducedCosts(i)
        val atLower = math.abs(solution.primal(i) - p.variableLower(i)) <= 1e-5
        val atUpper = math.abs(solution.primal(i) - p.variableUpper(i)) <= 1e-5
        if reduced > 1e-5 then
          assert(atLower, s"${instance.name}: variable $i has reduced cost $reduced off its lower bound")
        if reduced < -1e-5 then
          assert(atUpper, s"${instance.name}: variable $i has reduced cost $reduced off its upper bound")
    }
  }
