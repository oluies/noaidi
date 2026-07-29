package org.noaidi.prima

import scala.collection.mutable
import scala.util.Random

/** LP instances with known answers, in rough order of difficulty.
  *
  * The optima here were derived by hand or by inspection of the merit order,
  * not by running a solver, so they are an independent check rather than a
  * record of what some implementation happened to produce.
  */
object LpFixtures:

  final case class Instance(
      name: String,
      problem: LpProblem,
      expectedObjective: Option[Double],
      expectedPrimal: Option[Seq[Double]] = None,
      expectedStatus: SolveStatus = SolveStatus.Optimal,
  )

  private def inf  = Double.PositiveInfinity
  private def ninf = Double.NegativeInfinity

  /** `min x` subject to `x >= 3`, `x` in `[0, 10]`. Optimum 3. */
  val tinyLowerBound: Instance =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 10.0)
    b.greaterThan(Seq(0 -> 1.0), 3.0)
    Instance("tiny-lower-bound", b.build()._1, Some(3.0), Some(Seq(3.0)))

  /** The textbook product mix, as a minimisation.
    *
    * `max 3a + 5b` subject to `a <= 4`, `2b <= 12`, `3a + 2b <= 18`, both
    * non-negative. The optimum is `(2, 6)` with value 36, so minimising the
    * negated objective gives -36.
    */
  val productMix: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, -3.0)
    b.objectiveCoefficient(1, -5.0)
    b.bounds(0, 0.0, inf)
    b.bounds(1, 0.0, inf)
    b.lessThan(Seq(0 -> 1.0), 4.0)
    b.lessThan(Seq(1 -> 2.0), 12.0)
    b.lessThan(Seq(0 -> 3.0, 1 -> 2.0), 18.0)
    Instance("product-mix", b.build()._1, Some(-36.0), Some(Seq(2.0, 6.0)))

  /** `min 2x + 3y` subject to `x + y = 10`, `x` in `[0,6]`, `y` in `[0,10]`.
    * The cheaper variable saturates its bound: `x = 6`, `y = 4`, value 24.
    */
  val equalitySplit: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 2.0)
    b.objectiveCoefficient(1, 3.0)
    b.bounds(0, 0.0, 6.0)
    b.bounds(1, 0.0, 10.0)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 10.0)
    Instance("equality-split", b.build()._1, Some(24.0), Some(Seq(6.0, 4.0)))

  /** A degenerate vertex: the inequality and the equality are both tight at the
    * optimum and the optimal basis is not unique. `min x + y` subject to
    * `x + y >= 2`, `x - y = 0`, both in `[0, 5]`. Optimum `(1, 1)`, value 2.
    */
  val degenerate: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 1.0)
    b.objectiveCoefficient(1, 1.0)
    b.bounds(0, 0.0, 5.0)
    b.bounds(1, 0.0, 5.0)
    b.greaterThan(Seq(0 -> 1.0, 1 -> 1.0), 2.0)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> -1.0), 0.0)
    Instance("degenerate", b.build()._1, Some(2.0), Some(Seq(1.0, 1.0)))

  /** Variables that are free in sign, which exercises the dual residual path
    * where a reduced cost has no finite bound to be absorbed by.
    * `min x - y` subject to `x + y = 0`, both in `[-1, 1]`. Optimum `(-1, 1)`.
    */
  val freeSign: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 1.0)
    b.objectiveCoefficient(1, -1.0)
    b.bounds(0, -1.0, 1.0)
    b.bounds(1, -1.0, 1.0)
    b.equalityConstraint(Seq(0 -> 1.0, 1 -> 1.0), 0.0)
    Instance("free-sign", b.build()._1, Some(-2.0), Some(Seq(-1.0, 1.0)))

  /** A range constraint, which the builder must split into two rows.
    * `min x` subject to `2 <= x + y <= 5`, `y = 0` fixed by its bounds,
    * `x` in `[-10, 10]`. Optimum `x = 2`.
    */
  val rangeRow: Instance =
    val b = LpProblem.builder(2)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, -10.0, 10.0)
    b.bounds(1, 0.0, 0.0)
    b.constraint(Seq(0 -> 1.0, 1 -> 1.0), 2.0, 5.0)
    Instance("range-row", b.build()._1, Some(2.0), Some(Seq(2.0, 0.0)))

  /** `x >= 1` and `x <= 0` on a free variable: no feasible point exists. */
  val infeasible: Instance =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, ninf, inf)
    b.greaterThan(Seq(0 -> 1.0), 1.0)
    b.lessThan(Seq(0 -> 1.0), 0.0)
    Instance("infeasible", b.build()._1, None, None, SolveStatus.PrimalInfeasible)

  /** `min -x` with `x >= 0` and no upper bound: unbounded below. */
  val unbounded: Instance =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, -1.0)
    b.bounds(0, 0.0, inf)
    b.greaterThan(Seq(0 -> 1.0), 0.0)
    Instance("unbounded", b.build()._1, None, None, SolveStatus.DualInfeasible)

  /** Economic dispatch over a two-bus network and two snapshots — the shape
    * PyPSA's linear optimal power flow reduces to, in miniature.
    *
    * Two buses joined by one line of 30 MW capacity. Generators: `g1` at bus A
    * (10/MWh, 120 MW), `g2` at bus A (30/MWh, 100 MW), `g3` at bus B
    * (20/MWh, 90 MW). Loads are (100, 50) at the first snapshot and (80, 70) at
    * the second.
    *
    * Snapshot 1 dispatches on merit order alone: `g1` at 120, `g3` at 30, with
    * 20 MW flowing A to B, costing 1800. Snapshot 2 needs 40 MW across the line
    * but the limit is 30, so the line saturates and 10 MW of demand at B must be
    * met by the more expensive `g3` instead of `g1`: `g1` 110, `g3` 40, costing
    * 1900. Total 3700, with the line's dual pricing the congestion.
    *
    * Variables are ordered `[g1, g2, g3, flow]` per snapshot.
    */
  val economicDispatch: Instance =
    val snapshots = 2
    val loadsA    = Array(100.0, 80.0)
    val loadsB    = Array(50.0, 70.0)
    val costs     = Array(10.0, 30.0, 20.0)
    val caps      = Array(120.0, 100.0, 90.0)
    val lineLimit = 30.0

    val b = LpProblem.builder(4 * snapshots)
    for t <- 0 until snapshots do
      val base = 4 * t
      for g <- 0 until 3 do
        b.objectiveCoefficient(base + g, costs(g))
        b.bounds(base + g, 0.0, caps(g))
      b.bounds(base + 3, -lineLimit, lineLimit)
      // Bus A: local generation less the export equals local load.
      b.equalityConstraint(Seq(base -> 1.0, (base + 1) -> 1.0, (base + 3) -> -1.0), loadsA(t))
      // Bus B: local generation plus the import equals local load.
      b.equalityConstraint(Seq((base + 2) -> 1.0, (base + 3) -> 1.0), loadsB(t))

    Instance(
      "economic-dispatch",
      b.build()._1,
      Some(3700.0),
      Some(Seq(120.0, 0.0, 30.0, 20.0, 110.0, 0.0, 40.0, 30.0)),
    )

  /** Everything with a finite optimum, for suites that sweep the ladder. */
  val optimal: Seq[Instance] =
    Seq(tinyLowerBound, productMix, equalitySplit, degenerate, freeSign, rangeRow, economicDispatch)

  val conclusive: Seq[Instance] = optimal ++ Seq(infeasible, unbounded)

  /** A random sparse LP that is feasible and bounded by construction.
    *
    * Every variable gets a finite box, which bounds the objective; the
    * right-hand sides are taken from a point inside that box, which guarantees a
    * feasible point exists. Without both, a random instance is overwhelmingly
    * likely to be infeasible and would test nothing.
    */
  def randomFeasible(
      seed: Long,
      numVariables: Int,
      numEqualities: Int,
      numInequalities: Int,
      density: Double = 0.3,
  ): LpProblem =
    val rng     = new Random(seed)
    val builder = LpProblem.builder(numVariables)

    val witness = new Array[Double](numVariables)
    for i <- 0 until numVariables do
      val lo = -10.0 + rng.nextDouble() * 5.0
      val hi = lo + 1.0 + rng.nextDouble() * 20.0
      builder.bounds(i, lo, hi)
      witness(i) = lo + rng.nextDouble() * (hi - lo)
      builder.objectiveCoefficient(i, rng.nextGaussian() * 5.0)

    def randomRow(): Seq[(Int, Double)] =
      val row = mutable.ArrayBuffer.empty[(Int, Double)]
      for j <- 0 until numVariables do
        if rng.nextDouble() < density then row += ((j, rng.nextGaussian() * 2.0))
      // A row with no entries constrains nothing and would be dropped, so force
      // at least one coefficient.
      if row.isEmpty then row += ((rng.nextInt(numVariables), 1.0))
      row.toSeq

    def valueAt(row: Seq[(Int, Double)]): Double = row.map((j, a) => a * witness(j)).sum

    for _ <- 0 until numEqualities do
      val row = randomRow()
      builder.equalityConstraint(row, valueAt(row))

    for _ <- 0 until numInequalities do
      val row = randomRow()
      // Slack below the witness point keeps it feasible while leaving the row
      // able to bind elsewhere.
      builder.greaterThan(row, valueAt(row) - rng.nextDouble() * 5.0)

    builder.build()._1

end LpFixtures
