package org.noaidi.prima

/** Direct tests for the Farkas certificates.
  *
  * Reaching these only through end-to-end solves leaves most of their branches
  * unvisited, and a certificate that wrongly passes turns "I could not solve
  * this" into "this has no solution" — the one failure mode a solver must not
  * have. So the directions are hand-built here rather than taken from iterates.
  */
class CertificatesSuite extends munit.FunSuite:

  private val inf  = Double.PositiveInfinity
  private val ninf = Double.NegativeInfinity

  // `x >= 1` and `-x >= 0` on a free variable: infeasible, and `y = (1, 1)`
  // is the Farkas certificate.
  private val contradiction: LpProblem =
    val b = LpProblem.builder(1)
    b.bounds(0, ninf, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    b.build()._1

  test("an exact Farkas direction certifies primal infeasibility") {
    val residual = Certificates.primalInfeasibility(contradiction, Array(1.0, 1.0))
    assert(residual.isDefined, "the direction was not even a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("scaling a certificate does not change its residual") {
    // Certificates are only defined up to a positive scale, so the normalised
    // residual must be invariant; if it were not, the tolerance would mean
    // something different for a diverging iterate than for a fresh one.
    val small = Certificates.primalInfeasibility(contradiction, Array(1.0, 1.0)).get
    val large = Certificates.primalInfeasibility(contradiction, Array(1e9, 1e9)).get
    assertEqualsDouble(large, small, 1e-12)
  }

  test("a direction with a non-positive certificate value is not a candidate") {
    // Zero proves nothing. Both of these give exactly zero: the second row of
    // `contradiction` has rhs -0.0, so weighting it alone contributes nothing.
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(0.0, 0.0)), None)
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(0.0, 1.0)), None)
    // Negating the first row's multiplier makes the value strictly negative,
    // which is the branch the zero cases above do not reach.
    assertEquals(Certificates.primalInfeasibility(contradiction, Array(-1.0, 0.0)), None)
  }

  // `x` in `[0, 5]` with `x <= -1`: infeasible, and the finite lower bound
  // absorbs the induced reduced cost completely. That isolation is the point —
  // it leaves cone violation as the only thing that can contribute residual.
  private val absorbed: LpProblem =
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 5.0)
    b.lessThan(Seq(0 -> 1.0), -1.0)
    b.lessThan(Seq(0 -> 1.0), 100.0)
    b.build()._1

  test("a certificate whose reduced cost is fully absorbed has zero residual") {
    // Only the binding row is weighted, and its multiplier is in the cone.
    val residual = Certificates.primalInfeasibility(absorbed, Array(2.0, 0.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("a dual multiplier outside the cone counts against the certificate") {
    // Same problem, but the second row now carries a negative multiplier, which
    // is not a feasible dual direction. Nothing else contributes residual here,
    // so the expected value pins the cone term exactly: deleting the cone
    // accumulation would make this zero.
    val y        = Array(2.0, -1.0)
    val residual = Certificates.primalInfeasibility(absorbed, y)
    assert(residual.isDefined, "the direction was not a candidate")
    // Cone violation of magnitude 1 against ||y|| = sqrt(5). Normalised by the
    // direction, so the redundant row's rhs of 100 does not enter -- see the
    // scale-invariance test below.
    assertEqualsDouble(residual.get, 1.0 / math.sqrt(5.0), 1e-12)
  }

  test("a reduced cost absorbed by a finite bound does not count as residual") {
    // `x` in `[0, 5]` with `x >= 7`: infeasible, and the certificate's induced
    // reduced cost is taken up by the finite upper bound rather than left over.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 5.0)
    b.greaterThan(Seq(0 -> 1.0), 7.0)
    val problem = b.build()._1

    val residual = Certificates.primalInfeasibility(problem, Array(1.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("the same reduced cost against an infinite bound is residual") {
    // Identical row, but the variable is now unbounded above, so nothing can
    // absorb the reduced cost and the direction proves nothing.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 7.0)
    val problem = b.build()._1

    val residual = Certificates.primalInfeasibility(problem, Array(1.0))
    assert(residual.forall(_ > 1e-6), s"unabsorbed reduced cost was not penalised: $residual")
  }

  // `min -x` with `x >= 0` unbounded above: the direction `d = 1` is a
  // recession direction that strictly decreases the objective.
  private val ray: LpProblem =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    b.build()._1

  test("a recession direction certifies unboundedness") {
    val residual = Certificates.dualInfeasibility(ray, Array(1.0))
    assert(residual.isDefined, "the direction was not a candidate")
    assertEqualsDouble(residual.get, 0.0, 1e-12)
  }

  test("a direction that does not improve the objective is not a candidate") {
    assertEquals(Certificates.dualInfeasibility(ray, Array(0.0)), None)
    assertEquals(Certificates.dualInfeasibility(ray, Array(-1.0)), None)
  }

  test("a direction through a finite bound is not a recession direction") {
    // Same objective, but `x` is now capped, so moving along `d` leaves the box.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, 10.0)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    val bounded = b.build()._1

    val residual = Certificates.dualInfeasibility(bounded, Array(1.0))
    assert(residual.forall(_ > 1e-6), s"finite bound was not penalised: $residual")
  }

  test("a direction that disturbs an equality row is not a recession direction") {
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.bounds(1, 0.0, inf)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 5.0)
    val problem = b.build()._1

    // Increasing only `x0` breaks the equality; increasing `x0` while decreasing
    // `x1` preserves it but leaves `x1`'s lower bound.
    assert(Certificates.dualInfeasibility(problem, Array(1.0, 0.0)).forall(_ > 1e-6))
    assert(Certificates.dualInfeasibility(problem, Array(1.0, -1.0)).forall(_ > 1e-6))
  }

  test("classify prefers primal infeasibility when both directions certify") {
    // Infeasible constraints and an objective unbounded along a free variable.
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(1, -1.0)
    b.bounds(0, ninf, inf)
    b.bounds(1, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    val problem = b.build()._1

    val status = Certificates.classify(problem, Array(0.0, 1.0), Array(1.0, 1.0), 1e-8)
    assertEquals(status, Some(SolveStatus.PrimalInfeasible))
  }

  // -- normalisation: a large right-hand side must not buy a certificate ------
  //
  // These are the regression tests for a real false positive. Normalising the
  // shortfall by the certificate's own value let an unrelated large coefficient
  // dilute a genuine violation until it slipped under the tolerance, so a
  // *feasible* problem was reported infeasible. Both directions below are wrong
  // by a full unit; only the normalisation decides whether that is visible.

  test("a cone violation is not diluted by a large right-hand side") {
    // Feasible: x in [0, 5] with the redundant row x <= 1e10.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, 5.0)
    b.lessThan(Seq(0 -> 1.0), 1e10)
    val feasible = b.build()._1

    // y = -1 violates the dual cone outright. Normalised by the certificate
    // value this read as 1e-10 and passed at the default tolerance.
    val residual = Certificates.primalInfeasibility(feasible, Array(-1.0))
    assert(
      residual.forall(_ > 1e-3),
      s"a feasible problem produced a passing infeasibility certificate: $residual",
    )
    assertEquals(Certificates.classify(feasible, Array(0.0), Array(-1.0), 1e-8), None)
  }

  test("an unabsorbed reduced cost is not diluted by a large right-hand side") {
    // Feasible: x in [0, inf) with the row x >= 1e10, satisfied at x = 1e10.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 1e10)
    val feasible = b.build()._1

    // y = 1 is in the cone but leaves a reduced cost of 1 that no finite bound
    // can absorb. Against the value of 1e10 that read as 1e-10.
    val residual = Certificates.primalInfeasibility(feasible, Array(1.0))
    assert(
      residual.forall(_ > 1e-3),
      s"a feasible problem produced a passing infeasibility certificate: $residual",
    )
  }

  test("a recession violation is not diluted by a large cost coefficient") {
    // Bounded: every variable has a finite box, so the objective cannot run
    // away in any direction.
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -1e10)
    b.bounds(0, 0.0, 1.0)
    b.bounds(1, 0.0, 1.0)
    b.greaterThan(Seq(0 -> 1.0, 1 -> 1.0), 0.0)
    val bounded = b.build()._1

    // d = (1, 1) walks straight through both upper bounds. Normalised by
    // |c'd| = 1e10 the violation read as 1.4e-10.
    val residual = Certificates.dualInfeasibility(bounded, Array(1.0, 1.0))
    assert(
      residual.forall(_ > 1e-3),
      s"a bounded problem produced a passing unboundedness certificate: $residual",
    )
    assertEquals(Certificates.classify(bounded, Array(1.0, 1.0), Array(0.0), 1e-8), None)
  }

  test("a reduced-cost violation is not diluted by a small matrix coefficient") {
    // Feasible: x in [0, inf) with the row 1e-10*x >= 1, satisfied at x = 1e10.
    //
    // This is the hole that normalising by ||y|| alone left open. The residual
    // is a component of K'y and so carries the matrix's units; dividing by
    // ||y|| leaves them in, and shrinking a coefficient shrinks the reported
    // shortfall just as inflating a right-hand side once did.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1e-10), 1.0)
    val feasible = b.build()._1

    val residual = Certificates.primalInfeasibility(feasible, Array(1.0))
    assert(
      residual.forall(_ > 1e-3),
      s"a feasible problem produced a passing infeasibility certificate: $residual",
    )
    assertEquals(Certificates.classify(feasible, Array(0.0), Array(1.0), 1e-8), None)
  }

  test("a recession violation is not diluted by a small matrix coefficient") {
    // Bounded: x in [0, inf) with c = -1 and the row -1e-9*x >= 0, which forces
    // x = 0. The mirror of the case above, on the Kd term of the dual test.
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.lessThan(Seq(0 -> 1e-9), 0.0)
    val bounded = b.build()._1

    val residual = Certificates.dualInfeasibility(bounded, Array(1.0))
    assert(
      residual.forall(_ > 1e-3),
      s"a bounded problem produced a passing unboundedness certificate: $residual",
    )
  }

  test("the shortfall is invariant under a change of units") {
    // Multiplying K and q by the same factor is a pure change of units. If the
    // measure moved with it, the same LP would get opposite verdicts depending
    // on whether it was written in MW or W.
    def shortfall(units: Double): Double =
      val b = LpProblem.builder(1)
      b.bounds(0, 0.0, inf)
      b.greaterThan(Seq(0 -> units), units * 7.0)
      Certificates.primalInfeasibility(b.build()._1, Array(1.0)).get

    val base = shortfall(1.0)
    Seq(1e-8, 1e-4, 1.0, 1e4, 1e8).foreach { t =>
      assertEqualsDouble(shortfall(t), base, 1e-9, s"shortfall moved at scale $t")
    }
  }

  test("the reported shortfall does not depend on the problem's scale") {
    // The same wrong direction against the same structure, with one redundant
    // row's rhs varied over ten orders of magnitude. A measure that can be
    // defeated by inflating the data would fall as the rhs grows.
    def shortfall(redundantRhs: Double): Double =
      val b = LpProblem.builder(1)
      b.bounds(0, 0.0, 5.0)
      b.lessThan(Seq(0 -> 1.0), -1.0)
      b.lessThan(Seq(0 -> 1.0), redundantRhs)
      Certificates.primalInfeasibility(b.build()._1, Array(2.0, -1.0)).get

    val small = shortfall(100.0)
    val large = shortfall(1e10)
    assertEqualsDouble(large, small, 1e-12, "shortfall moved when only the data scale changed")
    // Scale-invariant in the direction, as a certificate must be.
    assertEqualsDouble(shortfall(100.0), 1.0 / math.sqrt(5.0), 1e-12)
  }

  test("classify reports nothing for an ordinary feasible point") {
    val problem  = LpFixtures.economicDispatch.problem
    val solution = Pdhg.solve(problem)
    val status = Certificates.classify(
      problem,
      Unsafe.raw(solution.primal),
      Unsafe.raw(solution.dual),
      1e-8,
    )
    assertEquals(status, None)
  }

  test("an unrelated large coefficient cannot dilute a primal violation") {
    // The hole a *global* matrix norm left open. Normalising the reduced-cost
    // term by `spectralNormBound` fixed the units and not the dilution: that
    // bound is built from the largest row and column sums, so a coefficient on
    // a row and a variable the direction never touches shrank every reported
    // shortfall by its size.
    //
    // Here the second row's coefficient is the unrelated one. The problem is
    // feasible at `x0 = 1, x1 = 1e-10` at every scale, and `y = (1, 0)` is not a
    // certificate of anything -- its reduced cost on `x0` is entirely
    // unabsorbed, `x0` having no finite upper bound.
    def shortfall(unrelated: Double): Double =
      val b = LpProblem.builder(2)
      b.bounds(0, 0.0, inf)
      b.bounds(1, 0.0, inf)
      b.greaterThan(Seq(0 -> 1.0), 1.0)
      b.greaterThan(Seq(1 -> unrelated), 1.0)
      Certificates.primalInfeasibility(b.build()._1, Array(1.0, 0.0)).get

    // Fully unabsorbed, so the shortfall is the largest it can be.
    assertEqualsDouble(shortfall(1.0), 1.0, 1e-12)
    Seq(1e4, 1e10, 1e14).foreach { big =>
      assertEqualsDouble(shortfall(big), 1.0, 1e-12,
        s"a coefficient of $big elsewhere in K moved the shortfall")
    }

    // And the verdict, which is what a caller sees: at 1e10 the global-norm
    // version reported 1e-10 and `classify` called a feasible problem
    // infeasible.
    val b = LpProblem.builder(2)
    b.bounds(0, 0.0, inf)
    b.bounds(1, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.greaterThan(Seq(1 -> 1e10), 1.0)
    val problem = b.build()._1
    assertEquals(
      Certificates.classify(problem, Array(0.0, 0.0), Array(1.0, 0.0), 1e-8),
      None,
      "a feasible problem was reported infeasible",
    )
  }

  test("an unrelated large coefficient cannot dilute a row violation") {
    // The dual mirror. `x0 <= 0` with `x0 >= 0` pins `x0` to zero, so
    // minimising `-x0` is bounded; the direction `d = (1, 0)` decreases the
    // objective but walks straight out of the first row, which is exactly the
    // violation the measure has to report. The second row's coefficient is
    // again unrelated to `d`.
    def shortfall(unrelated: Double): Double =
      val b = LpProblem.builder(2)
      b.bounds(0, 0.0, inf)
      b.bounds(1, 0.0, inf)
      b.objectiveCoefficient(0, -1.0)
      b.lessThan(Seq(0 -> 1.0), 0.0)
      b.lessThan(Seq(1 -> unrelated), 1.0)
      Certificates.dualInfeasibility(b.build()._1, Array(1.0, 0.0)).get

    assertEqualsDouble(shortfall(1.0), 1.0, 1e-12)
    Seq(1e4, 1e10, 1e14).foreach { big =>
      assertEqualsDouble(shortfall(big), 1.0, 1e-12,
        s"a coefficient of $big elsewhere in K moved the shortfall")
    }

    val b = LpProblem.builder(2)
    b.bounds(0, 0.0, inf)
    b.bounds(1, 0.0, inf)
    b.objectiveCoefficient(0, -1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    b.lessThan(Seq(1 -> 1e10), 1.0)
    val problem = b.build()._1
    assertEquals(
      Certificates.classify(problem, Array(1.0, 0.0), Array(0.0, 0.0), 1e-8),
      None,
      "a bounded problem was reported unbounded",
    )
  }

  test("an extreme coefficient does not overflow the per-column norm") {
    // The hazard the per-component normalisation introduced, and the reason it
    // is scaled. Accumulating `v*v` for the column norm overflows to Infinity
    // above ~1.3e154 and underflows to 0.0 below ~1.5e-154, and both erase the
    // residual: an infinite norm divides it to zero, and a zero norm takes the
    // "structurally empty column" branch, which assumes the residual is zero
    // because the column is. Either way the dilution hole reopens, on data no
    // API here rejects -- `LpProblem.constraint` puts no bound on magnitude.
    //
    // `1e200 x0 >= 1` is feasible at `x0 = 1e-200`, and `y = 1` is not a
    // certificate of anything: its reduced cost is entirely unabsorbed.
    def shortfall(coefficient: Double): Double =
      val b = LpProblem.builder(1)
      b.bounds(0, 0.0, inf)
      b.greaterThan(Seq(0 -> coefficient), 1.0)
      Certificates.primalInfeasibility(b.build()._1, Array(1.0)).get

    Seq(1e-200, 1e-160, 1.0, 1e160, 1e200, 1e300).foreach { c =>
      assertEqualsDouble(shortfall(c), 1.0, 1e-12,
        s"a coefficient of $c collapsed the shortfall")
    }
  }

  test("an extreme coefficient does not overflow the per-row norm") {
    // The mirror of the column sweep, on the dual side. `rowNorms` got the same
    // scaling fix and feeds a different consumer -- `scaledRow` in
    // `dualInfeasibility` -- with the same two collapse modes, so without this
    // the `rowNorms` half of that change could be reverted with the suite still
    // green.
    //
    // `min -x0` subject to `c x0 <= 0` and `x0 >= 0` pins x0 to zero, so the
    // objective is bounded; `d = 1` decreases it and walks straight out of the
    // row, which is the violation that has to survive at every scale.
    def shortfall(coefficient: Double): Double =
      val b = LpProblem.builder(1)
      b.bounds(0, 0.0, inf)
      b.objectiveCoefficient(0, -1.0)
      b.lessThan(Seq(0 -> coefficient), 0.0)
      Certificates.dualInfeasibility(b.build()._1, Array(1.0)).get

    Seq(1e-200, 1e-160, 1.0, 1e160, 1e200, 1e300).foreach { c =>
      assertEqualsDouble(shortfall(c), 1.0, 1e-12,
        s"a coefficient of $c collapsed the row shortfall")
    }
  }

  test("a norm too large to represent is not treated as a certificate") {
    // Scaling keeps the *accumulator* in range; the norm itself can still
    // exceed Double.MaxValue. Four entries of 1e308 in one column give 2e308,
    // and dividing a residual by Infinity yields exactly 0.0 -- a shortfall of
    // zero, which reads as an exact certificate. So a non-finite norm is
    // propagated instead, and the direction is reported as no candidate.
    //
    // The problem is feasible at `x0 = 1e-308`, and `y` picks out one row.
    val b = LpProblem.builder(1)
    b.bounds(0, 0.0, inf)
    (0 until 4).foreach(_ => b.greaterThan(Seq(0 -> 1e308), 1.0))
    val problem = b.build()._1

    assert(
      !problem.constraintMatrix.columnNorms(0).isFinite,
      "the fixture no longer overflows the column norm, so this tests nothing",
    )
    assertEquals(
      Certificates.primalInfeasibility(problem, Array(1.0, 0.0, 0.0, 0.0)),
      None,
      "an unrepresentable norm was read as an exact certificate",
    )
    assertEquals(
      Certificates.classify(problem, Array(0.0), Array(1.0, 0.0, 0.0, 0.0), 1e-8),
      None,
      "a feasible problem was reported infeasible",
    )
  }
