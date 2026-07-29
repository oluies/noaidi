package org.noaidi.prima
package ojalgo

class OjAlgoSolverSuite extends munit.FunSuite:

  test("solves the fixture ladder to the known optima") {
    val solver = OjAlgoSolver()
    LpFixtures.optimal.foreach { instance =>
      val solution = solver.solve(instance.problem)
      assertEquals(solution.status, SolveStatus.Optimal, s"${instance.name}: $solution")
      instance.expectedObjective.foreach { expected =>
        assertEqualsDouble(solution.objectiveValue, expected, 1e-6, instance.name)
      }
    }
  }

  test("classifies infeasible and unbounded problems") {
    val solver = OjAlgoSolver()
    assertEquals(solver.solve(LpFixtures.infeasible.problem).status, SolveStatus.PrimalInfeasible)
    assertEquals(solver.solve(LpFixtures.unbounded.problem).status, SolveStatus.DualInfeasible)
  }

  test("duals are reported as NaN rather than fabricated") {
    // ojAlgo exposes multipliers only on some solver paths and with its own sign
    // convention, so this backend does not claim them. The point of the test is
    // that the unavailability is visible: a caller doing arithmetic on these
    // gets NaN, not a plausible wrong number.
    val solution = OjAlgoSolver().solve(LpFixtures.economicDispatch.problem)
    assertEquals(solution.status, SolveStatus.Optimal)

    assert(solution.dual.forall(_.isNaN), s"duals were populated: ${solution.dual.toList}")
    assert(solution.reducedCosts.forall(_.isNaN), "reduced costs were populated")
    assert(solution.dualObjectiveValue.isNaN, "a dual objective was claimed")
    assert(solution.kkt.dualResidualNorm.isNaN, "a dual residual was claimed")
  }

  test("the primal side is fully reported") {
    val solution = OjAlgoSolver().solve(LpFixtures.economicDispatch.problem)
    assert(solution.primal.forall(_.isFinite))
    assert(solution.objectiveValue.isFinite)
    // The primal residual is computed from the primal solution alone, so it
    // remains meaningful even with no duals.
    assert(solution.kkt.primalResidualNorm.isFinite)
    assertEqualsDouble(solution.kkt.primalResidualNorm, 0.0, 1e-6)
  }

  test("options are applied to the model") {
    // `configure` is otherwise never exercised, since every other test uses
    // defaults. A tightened feasibility scale and a time limit must both leave
    // an easy problem solvable.
    val options = OjAlgoSolver.Options(feasibilityScale = Some(12), timeLimitMillis = Some(30_000L))
    val solution = new OjAlgoSolver(options).solve(LpFixtures.economicDispatch.problem)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 3700.0, 1e-6)
  }
