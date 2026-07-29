package org.noaidi.prima

import org.noaidi.prima.kernels.{Float32Kernels, ScalaKernels}

/** The reduced-precision-then-refine path, exercised on the CPU float32
  * backend.
  *
  * This stands in for a GPU. The arithmetic a Cyfra or Metal backend would do
  * is the same float32 arithmetic, so what is being tested here — that PDHG
  * tolerates single precision, and that a double-precision finish recovers full
  * accuracy from where it leaves off — is the part that would otherwise only be
  * discoverable after writing a Vulkan backend.
  */
class MixedPrecisionSuite extends munit.FunSuite:

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)

  private def mixed(problem: LpProblem): MixedPrecision.Result =
    val device = Float32Kernels()
    try MixedPrecision.solve(problem, params, device)
    finally device.close()

  /** `Pdhg.solveWith` does not own the backend it is given, so tests must close
    * it. A no-op for the CPU reference, but this suite is the worked example a
    * device-backend author will copy, where it leaks device memory.
    */
  private def withCpu[A](f: ScalaKernels => A): A =
    val k = ScalaKernels()
    try f(k)
    finally k.close()

  LpFixtures.optimal.foreach { instance =>
    test(s"mixed precision reaches the known optimum on ${instance.name}") {
      val result = mixed(instance.problem)
      assertEquals(result.solution.status, SolveStatus.Optimal, s"$result")
      instance.expectedObjective.foreach { expected =>
        assertEqualsDouble(
          result.solution.objectiveValue,
          expected,
          1e-6 * math.max(1.0, math.abs(expected)),
          s"$result",
        )
      }
    }
  }

  test("the refined answer meets the full double-precision tolerance") {
    // The reduced pass cannot get here on its own; this asserts the handoff
    // actually tightened the result rather than passing float32 noise through.
    val result = mixed(LpFixtures.economicDispatch.problem)
    assert(result.refined, "refinement did not run")
    val convergence = Kkt.convergence(
      result.solution.kkt,
      Kkt.norm2(LpFixtures.economicDispatch.problem.rhsRaw),
      Kkt.norm2(LpFixtures.economicDispatch.problem.objectiveRaw),
      params.epsAbs,
      params.epsRel,
    )
    assert(convergence.converged, s"refined result did not converge: ${result.solution.kkt}")
  }

  test("refinement is cheaper than a cold solve on a structured problem") {
    // The economic case for the mixed path, on the kind of problem it is meant
    // for: sparse and structured, like the LOPF it will eventually serve.
    //
    // Note this is asserted here and not for random dense instances, where it
    // is measurably false -- see the negative-result test below. Comparing
    // refinement against the reduced pass instead would measure nothing, since
    // float32 iterations are the cheap ones and there is no reason for them to
    // be fewer.
    val problem = LpFixtures.economicDispatch.problem
    val result  = mixed(problem)
    val cold    = Pdhg.solve(problem, params)

    assertEquals(result.solution.status, SolveStatus.Optimal, s"$result")
    assert(result.reducedIterations > 0, s"the reduced pass did nothing: $result")
    assert(
      result.refinementIterations < cold.iterations,
      s"warm start bought nothing: refinement took ${result.refinementIterations} " +
        s"double-precision iterations against ${cold.iterations} cold",
    )
  }

  test("mixed precision stays correct even where it is slower") {
    // A dense random LP at tight tolerance is the case where the float32 point
    // is a *worse* starting point than zero: refinement can take several times
    // the iterations of a cold solve. That is a real limitation and it is
    // recorded in NOTES.md rather than hidden.
    //
    // What must never break is the answer. This asserts the invariant that
    // actually matters -- the reported result comes from the double-precision
    // pass and is right regardless -- without asserting a speed-up that does
    // not hold here.
    // Density matches the validation ladder's random-200x120. The default 0.3
    // would be three times as dense, and all three solves here run at 1e-9;
    // exhausting the iteration budget would then surface as a bogus correctness
    // failure rather than as the budget exhaustion it is.
    val problem = LpFixtures.randomFeasible(
      seed = 3,
      numVariables = 200,
      numEqualities = 40,
      numInequalities = 80,
      density = 0.10,
    )

    val result = mixed(problem)
    val cold   = Pdhg.solve(problem, params)

    assertEquals(result.solution.status, SolveStatus.Optimal, s"mixed did not converge: $result")
    assertEquals(cold.status, SolveStatus.Optimal, s"cold solve did not converge: $cold")
    assertEqualsDouble(
      result.solution.objectiveValue,
      cold.objectiveValue,
      1e-6 * math.max(1.0, math.abs(cold.objectiveValue)),
      s"mixed=$result cold=$cold",
    )
  }

  test("mixed precision agrees with a pure double-precision solve") {
    LpFixtures.optimal.foreach { instance =>
      val viaMixed = mixed(instance.problem).solution
      val viaExact = Pdhg.solve(instance.problem, params)
      assertEqualsDouble(
        viaMixed.objectiveValue,
        viaExact.objectiveValue,
        1e-6 * math.max(1.0, math.abs(viaExact.objectiveValue)),
        s"${instance.name}: mixed=$viaMixed exact=$viaExact",
      )
    }
  }

  test("a float64 device skips the reduced pass entirely") {
    val cpu = ScalaKernels()
    try
      val result = MixedPrecision.solve(LpFixtures.productMix.problem, params, cpu)
      assertEquals(result.reducedIterations, 0)
      assert(!result.refined, "a double-precision device should not need refinement")
      // Loose relative to the KKT tolerance: converging the residual to 1e-9
      // bounds the objective error only up to the problem's conditioning.
      assertEqualsDouble(result.solution.objectiveValue, -36.0, 1e-6)
    finally cpu.close()
  }

  test("infeasibility found in float32 is reported without a second pass") {
    val result = mixed(LpFixtures.infeasible.problem)
    assertEquals(result.solution.status, SolveStatus.PrimalInfeasible, s"$result")
    assert(!result.refined, "an infeasibility certificate should not need refining")
  }

  test("unboundedness found in float32 is reported without a second pass") {
    val result = mixed(LpFixtures.unbounded.problem)
    assertEquals(result.solution.status, SolveStatus.DualInfeasible, s"$result")
    assert(!result.refined)
  }

  test("alwaysRefine forces the second pass even on a conclusive status") {
    val device = Float32Kernels()
    try
      val result = MixedPrecision.solve(
        LpFixtures.infeasible.problem,
        params,
        device,
        MixedPrecision.Params(alwaysRefine = true),
      )
      assert(result.refined, "alwaysRefine was ignored")
      assertEquals(result.solution.status, SolveStatus.PrimalInfeasible, s"$result")
    finally device.close()
  }

  test("a warm start near the optimum costs far fewer iterations than a cold one") {
    val problem = LpFixtures.economicDispatch.problem
    val cold    = Pdhg.solve(problem, params)

    val warm = withCpu { k =>
      Pdhg.solveWith(problem, params, k, warmStart = Some(Pdhg.WarmStart(cold)))
    }
    assertEquals(warm.status, SolveStatus.Optimal, s"$warm")
    assertEqualsDouble(warm.objectiveValue, cold.objectiveValue, 1e-6)
    assert(
      warm.iterations < cold.iterations,
      s"warm start did not help: cold=${cold.iterations} warm=${warm.iterations}",
    )
  }

  test("a warm start outside the feasible region is projected, not rejected") {
    // A point handed over from another precision, or from a parent subproblem,
    // has no obligation to be feasible here.
    val problem = LpFixtures.equalitySplit.problem
    val wild = Pdhg.WarmStart(
      primal = IArray(1e6, -1e6),
      dual = IArray(-5.0),
    )
    val solution = withCpu(k => Pdhg.solveWith(problem, params, k, warmStart = Some(wild)))
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 24.0, 1e-6)
  }

  test("a warm start of the wrong shape is rejected") {
    val problem = LpFixtures.equalitySplit.problem
    val wrong   = Pdhg.WarmStart(primal = IArray(1.0), dual = IArray(0.0))
    intercept[IllegalArgumentException] {
      withCpu(k => Pdhg.solveWith(problem, params, k, warmStart = Some(wrong)))
    }
  }
