package org.noaidi.prima.validation

import org.noaidi.prima.*
import org.noaidi.prima.ojalgo.OjAlgoMilp
import scala.util.Random

/** Prima's branch-and-bound against ojAlgo's mixed-integer solver.
  *
  * This matters more than the LP cross-check it mirrors. Prima's search rests on
  * an '''inexact''' bound, so its pruning rule is a judgement about how much
  * slack to leave rather than a theorem — and the failure mode it guards against
  * is silent: a discarded subtree returns a suboptimal answer labelled optimal.
  * ojAlgo's bound is exact, so a disagreement here is exactly that failure.
  *
  * Objectives are compared, not solution vectors. Several integer points can
  * share the optimal value, and which one a solver reaches is not determined —
  * the same reasoning `OracleAgreementSuite` applies to LP vertices.
  */
class MilpAgreementSuite extends munit.FunSuite:

  private val params = BnbParams(lp = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9), maxNodes = 20_000)

  /** A random MILP with a fractional relaxation.
    *
    * Bounded by construction — every variable has finite bounds and the rows are
    * `<=` with positive right-hand sides — so an unbounded or infeasible
    * instance cannot slip in and make agreement vacuous.
    */
  private def randomMilp(seed: Int, n: Int, m: Int): (LpProblem, Set[Int]) =
    val rng = new Random(seed)
    val b   = LpProblem.builder(n)
    (0 until n).foreach { j =>
      b.objectiveCoefficient(j, -(1.0 + rng.nextInt(9)))
      b.bounds(j, 0.0, 1.0 + rng.nextInt(3))
    }
    (0 until m).foreach { _ =>
      val terms = (0 until n).filter(_ => rng.nextDouble() < 0.6).map(j => (j, 1.0 + rng.nextInt(5).toDouble))
      if terms.nonEmpty then b.lessThan(terms, 3.0 + rng.nextInt(10))
    }
    // Every other variable integer, so the mixed case is exercised rather than
    // only the pure-integer one.
    (b.build()._1, (0 until n).filter(_ % 2 == 0).toSet)

  test("Prima and ojAlgo agree on every random mixed-integer instance") {
    var checked    = 0
    var fractional = 0
    (1 to 12).foreach { seed =>
      val (problem, integers) = randomMilp(seed, 6 + seed % 4, 3 + seed % 3)

      val relaxation = Pdhg.solve(problem, params.lp)
      val isFractional = relaxation.status == SolveStatus.Optimal && integers.exists { j =>
        val v = relaxation.primal(j)
        math.abs(v - math.round(v).toDouble) > 1e-4
      }
      if isFractional then fractional += 1

      val mine   = BranchAndBound.solve(problem, integers, params)
      val theirs = OjAlgoMilp.solve(problem, integers)

      if theirs.status == MilpStatus.Optimal then
        assertEquals(mine.status, MilpStatus.Optimal, s"seed $seed: $mine vs ${theirs.status}")
        assertEqualsDouble(
          mine.objectiveValue,
          theirs.objectiveValue,
          1e-5 * math.max(1.0, math.abs(theirs.objectiveValue)),
          s"seed $seed: prima=${mine.objectiveValue} ojalgo=${theirs.objectiveValue}",
        )
        checked += 1
      else if theirs.status == MilpStatus.Infeasible then
        assertEquals(mine.status, MilpStatus.Infeasible, s"seed $seed: $mine")
        checked += 1
    }
    assert(checked >= 10, s"only $checked instances were comparable; the suite is nearly vacuous")
    // If no relaxation were fractional, branch-and-bound would never branch and
    // agreement would say nothing about the search.
    assert(fractional >= 5, s"only $fractional instances had a fractional relaxation")
  }

  test("Prima never reports an integer objective better than ojAlgo's optimum") {
    // The asymmetric half, and the one that would catch a genuinely broken
    // search: claiming an objective *below* the true optimum means the reported
    // point is not feasible for the integer problem at all.
    (1 to 12).foreach { seed =>
      val (problem, integers) = randomMilp(seed, 7, 4)
      val mine   = BranchAndBound.solve(problem, integers, params)
      val theirs = OjAlgoMilp.solve(problem, integers)
      if theirs.status == MilpStatus.Optimal && mine.primal.nonEmpty then
        assert(
          mine.objectiveValue >= theirs.objectiveValue - 1e-5,
          s"seed $seed: prima claims ${mine.objectiveValue}, better than the true ${theirs.objectiveValue}",
        )
    }
  }
