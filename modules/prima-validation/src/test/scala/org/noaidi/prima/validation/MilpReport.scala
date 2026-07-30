package org.noaidi.prima.validation

import org.noaidi.prima.*
import org.noaidi.prima.ojalgo.OjAlgoMilp

/** Prima's branch-and-bound against ojAlgo's mixed-integer solver.
  *
  * The counterpart of [[Report]] for MILP. It exists for the same reason and one
  * more: Prima's search rests on an '''inexact''' bound, so its pruning rule is a
  * judgement about how much slack to leave rather than a theorem. ojAlgo's bound
  * is exact, so the objective column is the check on that judgement — and the
  * node column shows what the margin costs, since a safety margin can only make
  * the search explore more nodes than an exact solver would.
  *
  * Run with:
  * {{{
  * sbt "primaValidation/Test/runMain org.noaidi.prima.validation.MilpReport"
  * }}}
  */
object MilpReport:

  private final case class Row(
      name: String,
      size: String,
      integers: Int,
      primaStatus: MilpStatus,
      oracleStatus: MilpStatus,
      primaObjective: Double,
      oracleObjective: Double,
      nodes: Int,
      unproven: Int,
      primaMillis: Long,
      oracleMillis: Long,
      relaxation: Double,
  ):
    def relativeGap: Double =
      math.abs(primaObjective - oracleObjective) / math.max(1.0, math.abs(oracleObjective))

    /** How much of the answer integrality accounts for.
      *
      * The distance from the relaxation to the integer optimum. Near zero means
      * the instance barely needed branching, so it is weak evidence about the
      * search — worth showing rather than leaving the reader to assume.
      */
    def integralityGap: Double =
      math.abs(oracleObjective - relaxation) / math.max(1.0, math.abs(oracleObjective))

  def main(args: Array[String]): Unit =
    java.util.Locale.setDefault(java.util.Locale.ROOT)

    val lp     = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)
    val params = BnbParams(lp = lp, maxNodes = 50_000)

    val rows = MilpLadder.instances.map { instance =>
      val relaxed = Pdhg.solve(instance.problem, lp)

      val startMine = System.currentTimeMillis()
      val mine      = BranchAndBound.solve(instance.problem, instance.integers, params)
      val mineMs    = System.currentTimeMillis() - startMine

      val startTheirs = System.currentTimeMillis()
      val theirs      = OjAlgoMilp.solve(instance.problem, instance.integers)
      val theirsMs    = System.currentTimeMillis() - startTheirs

      Row(
        name = instance.name,
        size = s"${instance.problem.numVariables}v/${instance.problem.numConstraints}c/" +
          s"${instance.problem.constraintMatrix.nnz}nz",
        integers = instance.integers.size,
        primaStatus = mine.status,
        oracleStatus = theirs.status,
        primaObjective = mine.objectiveValue,
        oracleObjective = theirs.objectiveValue,
        nodes = mine.nodesExplored,
        unproven = mine.unprovenNodes,
        primaMillis = mineMs,
        oracleMillis = theirsMs,
        relaxation = relaxed.objectiveValue,
      )
    }

    println(
      f"${"instance"}%-18s ${"size"}%-16s ${"int"}%4s ${"prima"}%-9s ${"ojalgo"}%-9s " +
        f"${"prima obj"}%14s ${"ojalgo obj"}%14s ${"rel gap"}%9s ${"int gap"}%9s " +
        f"${"nodes"}%7s ${"unprv"}%6s ${"prima ms"}%9s ${"ojalgo ms"}%10s"
    )

    rows.foreach { r =>
      val comparable = r.oracleStatus == MilpStatus.Optimal && r.primaStatus == MilpStatus.Optimal
      val mineObj    = if comparable then f"${r.primaObjective}%14.6f" else f"${"-"}%14s"
      val theirsObj  = if comparable then f"${r.oracleObjective}%14.6f" else f"${"-"}%14s"
      val gap        = if comparable then f"${r.relativeGap}%9.2e" else f"${"-"}%9s"
      val intGap     = if comparable then f"${r.integralityGap}%9.2e" else f"${"-"}%9s"
      println(
        f"${r.name}%-18s ${r.size}%-16s ${r.integers}%4d ${r.primaStatus}%-9s ${r.oracleStatus}%-9s " +
          f"$mineObj $theirsObj $gap $intGap ${r.nodes}%7d ${r.unproven}%6d " +
          f"${r.primaMillis}%9d ${r.oracleMillis}%10d"
      )
    }

    val comparable = rows.filter(r =>
      r.oracleStatus == MilpStatus.Optimal && r.primaStatus == MilpStatus.Optimal
    )
    val worst = if comparable.isEmpty then 0.0 else comparable.map(_.relativeGap).max
    println(f"%nworst relative objective gap against the oracle: $worst%.3e")
    println(f"instances where Prima claimed a better objective than the oracle: " +
      comparable.count(r => r.primaObjective < r.oracleObjective - 1e-6))
    println(f"total nodes explored: ${rows.map(_.nodes).sum}, of which unproven: ${rows.map(_.unproven).sum}")

    // The report is a measurement, but a silent regression in it is worse than
    // no report, so the two claims it exists to support are asserted here too.
    if worst > 1e-5 then
      System.err.println(f"FAIL: worst objective gap $worst%.3e exceeds 1e-5")
      System.exit(1)
    if comparable.exists(r => r.primaObjective < r.oracleObjective - 1e-6) then
      System.err.println("FAIL: an objective better than the true optimum means the point is not feasible")
      System.exit(1)
