package org.noaidi.prima.validation

import org.noaidi.prima.*
import scala.util.Random

/** Mixed-integer instances for cross-solver comparison.
  *
  * Every instance here has a '''fractional relaxation''', and that is a
  * requirement rather than a coincidence. A MILP whose LP relaxation is already
  * integral is solved correctly by returning the relaxation and doing nothing,
  * so a ladder of those would benchmark and validate nothing about the search.
  * `MilpAgreementSuite` asserts the property holds.
  */
object MilpLadder:

  final case class Instance(name: String, problem: LpProblem, integers: Set[Int])

  /** maximise 5a + 4b subject to 6a + 4b <= 24, a + 2b <= 6.
    *
    * The relaxation sits at a = 3, b = 1.5 for -21; the integer optimum is
    * a = 4, b = 0 for -20. Small enough to check by hand, which is why it opens
    * the ladder.
    */
  private def fractionalPair: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -5.0)
    b.objectiveCoefficient(1, -4.0)
    b.bounds(0, 0.0, 10.0)
    b.bounds(1, 0.0, 10.0)
    b.lessThan(Seq((0, 6.0), (1, 4.0)), 24.0)
    b.lessThan(Seq((0, 1.0), (1, 2.0)), 6.0)
    Instance("fractional-pair", b.build()._1, Set(0, 1))

  /** 0/1 knapsack: maximise value subject to a weight budget. */
  private def knapsack(name: String, values: Seq[Double], weights: Seq[Double], capacity: Double): Instance =
    val n = values.length
    val b = LpProblem.builder(n)
    values.zipWithIndex.foreach((v, i) => b.objectiveCoefficient(i, -v))
    (0 until n).foreach(i => b.bounds(i, 0.0, 1.0))
    b.lessThan(weights.zipWithIndex.map((w, i) => (i, w)), capacity)
    Instance(name, b.build()._1, (0 until n).toSet)

  /** Set cover: choose the fewest sets covering every element.
    *
    * A different shape from the knapsacks — the constraints are `>=` and the
    * relaxation is famously fractional — so the ladder is not four variations on
    * one structure.
    */
  private def setCover(name: String, sets: Seq[Set[Int]], elements: Int): Instance =
    val b = LpProblem.builder(sets.length)
    sets.indices.foreach { j =>
      b.objectiveCoefficient(j, 1.0)
      b.bounds(j, 0.0, 1.0)
    }
    (0 until elements).foreach { e =>
      val covering = sets.indices.filter(j => sets(j).contains(e)).map(j => (j, 1.0))
      // An uncovered element used to produce no row at all, so a typo in a set
      // literal silently yielded an easier problem -- fewer constraints, possibly
      // an integral relaxation -- rather than an error.
      require(covering.nonEmpty, s"$name: element $e is covered by no set")
      b.greaterThan(covering, 1.0)
    }
    Instance(name, b.build()._1, sets.indices.toSet)

  /** A mixed instance: half the columns integer, half continuous.
    *
    * The pure-integer case is the easy one to get right; a mixed problem is
    * where an implementation that rounds everything, or that branches on
    * continuous columns, comes apart.
    */
  def randomMixed(seed: Int, n: Int, m: Int): Instance =
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
    Instance(s"random-mixed-$seed", b.build()._1, (0 until n).filter(_ % 2 == 0).toSet)

  /** The ladder.
    *
    * Seeds are chosen, not arbitrary: `randomMixed(2)` and several neighbours
    * produce an integral relaxation and so solve in one node without branching.
    * `MilpAgreementSuite` asserts that every instance here is fractional, so a
    * future edit cannot quietly reintroduce one that tests nothing.
    */
  /** The ladder.
    *
    * Seeds are chosen, not arbitrary: `randomMixed(2)` and several neighbours
    * produce an integral relaxation and so solve in one node without branching.
    * `MilpAgreementSuite` asserts that every instance here is fractional, so a
    * future edit cannot quietly reintroduce one that tests nothing.
    */
  /** The settings both the report and the agreement suite use.
    *
    * Shared so the node counts and gaps pinned in NOTES describe the same
    * configuration CI's suite verifies. They were previously different --
    * 50,000 nodes and 200,000 LP iterations in the report against 20,000 and the
    * 100,000 default in the suite.
    */
  val params: BnbParams =
    BnbParams(lp = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000), maxNodes = 50_000)

  val instances: Seq[Instance] = Seq(
    fractionalPair,
    knapsack("knapsack-8", Seq(10, 13, 18, 31, 7, 15, 22, 9), Seq(3, 4, 5, 9, 2, 5, 7, 3), 17.0),
    knapsack(
      "knapsack-16",
      Seq(41, 50, 49, 59, 45, 47, 12, 33, 21, 44, 51, 19, 28, 37, 40, 25),
      Seq(31, 27, 12, 34, 22, 19, 8, 16, 11, 24, 29, 9, 14, 20, 23, 13),
      120.0,
    ),
    // The odd-cycle cover, which is the standard example of a fractional set
    // cover: every set covers two of three elements, so the relaxation takes
    // half of each for 1.5 while any integer cover needs 2. The previous
    // ten-element instance had an integrality gap of exactly zero and solved in
    // one node -- it agreed with the oracle without ever branching, which says
    // nothing about the search.
    setCover("set-cover-triangle", Seq(Set(0, 1), Set(1, 2), Set(0, 2)), 3),
    // Every 2-subset of {0..4}: the edge set of K5, not the Petersen graph,
    // which this was briefly and wrongly called. The relaxation takes half of
    // each edge for 2.5 against an integer optimum of 3.
    setCover(
      "set-cover-k5-edges",
      Seq(Set(0, 1), Set(1, 2), Set(2, 3), Set(3, 4), Set(4, 0),
          Set(0, 2), Set(1, 3), Set(2, 4), Set(3, 0), Set(4, 1)),
      5,
    ),
    randomMixed(1, 8, 4),
    randomMixed(9, 10, 5),
    randomMixed(3, 12, 6),
    randomMixed(4, 14, 7),
  )
