package org.noaidi.prima

class ScalingSuite extends munit.FunSuite:

  private val params = ScalingParams.default

  test("scaling equilibrates row and column infinity norms towards one") {
    // Rows and columns differ by six orders of magnitude, which is the regime
    // power-system data actually lives in.
    val b = LpProblem.builder(3)
    b.bounds(0, 0.0, 1.0)
    b.bounds(1, 0.0, 1.0)
    b.bounds(2, 0.0, 1.0)
    b.equalityConstraint(Seq(0 -> 1e6, 1 -> 2e6), 1e6)
    b.equalityConstraint(Seq(1 -> 1e-3, 2 -> 3e-3), 1e-3)
    val (problem, _) = b.build()

    val scaled = Scaling(problem, params)
    val matrix = scaled.problem.constraintMatrix

    for r <- 0 until matrix.rows do
      val norm = matrix.rowMaxAbs(r)
      assert(norm > 0.05 && norm < 20.0, s"row $r still has norm $norm after scaling")

    val t = matrix.transpose
    for c <- 0 until t.rows do
      val norm = t.rowMaxAbs(c)
      assert(norm > 0.05 && norm < 20.0, s"column $c still has norm $norm after scaling")
  }

  test("unscaling recovers the original coordinates exactly") {
    val problem = LpFixtures.economicDispatch.problem
    val scaled  = Scaling(problem, params)
    val point   = Array.tabulate(problem.numVariables)(i => 1.0 + i * 0.5)

    val roundTripped = scaled.unscalePrimal(scaled.scalePrimal(point))
    point.indices.foreach { i =>
      assertEqualsDouble(roundTripped(i), point(i), 1e-12, s"variable $i")
    }
  }

  test("a point feasible in original coordinates stays feasible when scaled") {
    val problem  = LpFixtures.economicDispatch.problem
    val scaled   = Scaling(problem, params)
    val optimum  = LpFixtures.economicDispatch.expectedPrimal.get.toArray
    val inScaled = scaled.scalePrimal(optimum)

    val kx = new Array[Double](problem.numConstraints)
    scaled.problem.constraintMatrix.multiplyInto(inScaled, kx)

    for r <- 0 until scaled.problem.numConstraints do
      assertEqualsDouble(kx(r), scaled.problem.rhs(r), 1e-8, s"scaled equality row $r")

    for i <- 0 until problem.numVariables do
      assert(
        inScaled(i) >= scaled.problem.variableLower(i) - 1e-9 &&
          inScaled(i) <= scaled.problem.variableUpper(i) + 1e-9,
        s"variable $i left its box under scaling",
      )
  }

  test("the objective value is invariant under scaling") {
    val problem = LpFixtures.economicDispatch.problem
    val scaled  = Scaling(problem, params)
    val optimum = LpFixtures.economicDispatch.expectedPrimal.get.toArray

    val original = problem.primalObjective(Unsafe.wrap(optimum.clone()))
    val inScaled = scaled.problem.primalObjective(Unsafe.wrap(scaled.scalePrimal(optimum)))
    assertEqualsDouble(inScaled, original, 1e-8)
  }

  test("infinite bounds survive scaling with their sign") {
    val b = LpProblem.builder(2)
    b.bounds(0, Double.NegativeInfinity, Double.PositiveInfinity)
    b.bounds(1, 0.0, Double.PositiveInfinity)
    b.equalityConstraint(Seq(0 -> 3.0, 1 -> 4.0), 12.0)
    val (problem, _) = b.build()

    val scaled = Scaling(problem, params).problem
    assert(scaled.variableLower(0).isNegInfinity)
    assert(scaled.variableUpper(0).isPosInfinity)
    assertEquals(scaled.variableLower(1), 0.0)
    assert(scaled.variableUpper(1).isPosInfinity)
  }

  test("empty rows and columns are left alone instead of producing infinities") {
    val b = LpProblem.builder(2)
    b.bounds(0, 0.0, 1.0)
    b.bounds(1, 0.0, 1.0) // never appears in a constraint
    b.equalityConstraint(Seq(0 -> 2.0), 1.0)
    val (problem, _) = b.build()

    val scaled = Scaling(problem, params)
    assert(scaled.problem.constraintMatrix.values.forall(v => v.isFinite && v != 0.0))
    assert(scaled.colScale.forall(s => s.isFinite && s > 0.0))
    assert(scaled.rowScale.forall(s => s.isFinite && s > 0.0))
  }

  test("disabling scaling leaves the problem untouched") {
    val problem = LpFixtures.productMix.problem
    val scaled  = Scaling(problem, ScalingParams.none)
    assertEquals(scaled.problem.constraintMatrix.values.toList, problem.constraintMatrix.values.toList)
    assertEquals(scaled.problem.rhs.toList, problem.rhs.toList)
    assert(scaled.rowScale.forall(_ == 1.0))
    assert(scaled.colScale.forall(_ == 1.0))
  }

  test("a plan applied to its own problem is the one-shot scaling, bit for bit") {
    // `apply` is defined as `plan(...).applyTo(...)`, and the point of splitting
    // it was that branch-and-bound could hold the plan across nodes. That is only
    // sound if the split changes nothing, so this compares the two paths on the
    // raw bits rather than within a tolerance: the arithmetic is meant to be the
    // same operations in the same order, not merely a close answer.
    val problem = LpFixtures.productMix.problem
    val params  = ScalingParams.default

    val oneShot = Scaling(problem, params)
    val viaPlan = Scaling.plan(problem, params).applyTo(problem)

    def bits(xs: Seq[Double]) = xs.map(java.lang.Double.doubleToRawLongBits)
    assertEquals(bits(viaPlan.rowScale.toSeq), bits(oneShot.rowScale.toSeq))
    assertEquals(bits(viaPlan.colScale.toSeq), bits(oneShot.colScale.toSeq))
    assertEquals(
      bits(viaPlan.problem.constraintMatrix.values.toSeq),
      bits(oneShot.problem.constraintMatrix.values.toSeq))
    assertEquals(bits(viaPlan.problem.rhs.toSeq), bits(oneShot.problem.rhs.toSeq))
    assertEquals(bits(viaPlan.problem.objective.toSeq), bits(oneShot.problem.objective.toSeq))
    assertEquals(bits(viaPlan.problem.variableLower.toSeq), bits(oneShot.problem.variableLower.toSeq))
    assertEquals(bits(viaPlan.problem.variableUpper.toSeq), bits(oneShot.problem.variableUpper.toSeq))
  }

  test("a plan reuses the scaled matrix rather than rebuilding it") {
    // What makes holding a plan worth anything. The scaled matrix and its
    // transpose are the expensive products -- several passes over the nonzeros,
    // and `transpose` is a lazy val, so handing back the same instance is what
    // keeps it from being recomputed for every node of a search.
    val problem = LpFixtures.productMix.problem
    val plan    = Scaling.plan(problem, ScalingParams.default)

    val first  = plan.applyTo(problem)
    val second = plan.applyTo(problem)
    assert(first.problem.constraintMatrix eq second.problem.constraintMatrix)
    assert(first.problem.constraintMatrix.transpose eq second.problem.constraintMatrix.transpose)

    // The parts that do depend on the problem are rebuilt, because a node's
    // bounds are the thing that differs between two problems sharing a plan.
    assert(
      !(first.problem.variableLower.asInstanceOf[AnyRef] eq
        second.problem.variableLower.asInstanceOf[AnyRef]),
      "the scaled bounds were shared between two applications of one plan")
  }

  test("a plan refuses a problem over a different matrix") {
    // The failure it prevents is silent: factors that equilibrate some other
    // matrix would still produce a scaled problem, the solve would converge to
    // the wrong geometry, and the answer would come back labelled Optimal.
    val plan  = Scaling.plan(LpFixtures.productMix.problem, ScalingParams.default)
    val other = LpFixtures.randomFeasible(1, 20, 6, 10, 0.3)

    val failure = intercept[IllegalArgumentException](plan.applyTo(other))
    assert(failure.getMessage.contains("different constraint matrix"), failure.getMessage)
  }
