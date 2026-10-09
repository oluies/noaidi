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
      relaxationStatus: SolveStatus,
      primaPrimal: IArray[Double],
  ):
    def relativeGap: Double =
      math.abs(primaObjective - oracleObjective) / math.max(1.0, math.abs(oracleObjective))

    /** How much of the answer integrality accounts for.
      *
      * The distance from the relaxation to the integer optimum. Near zero means
      * the instance barely needed branching, so it is weak evidence about the
      * search — worth showing rather than leaving the reader to assume.
      *
      * `None` unless the relaxation actually solved: this column is what
      * certifies that an instance exercises the search, so a value taken from
      * whatever an unconverged solve last held would be worse than no column.
      */
    def integralityGap: Option[Double] =
      if relaxationStatus != SolveStatus.Optimal then None
      else Some(math.abs(oracleObjective - relaxation) / math.max(1.0, math.abs(oracleObjective)))

  /** The table header.
    *
    * CI's job summary extracts the table by matching `^instance `, so this
    * line's leading token is load-bearing beyond the human reader.
    */
  private val header =
    f"${"instance"}%-18s ${"size"}%-16s ${"int"}%4s ${"prima"}%-9s ${"ojalgo"}%-9s " +
      f"${"prima obj"}%14s ${"ojalgo obj"}%14s ${"rel gap"}%9s ${"int gap"}%9s " +
      f"${"nodes"}%7s ${"unprv"}%6s ${"prima ms"}%9s ${"ojalgo ms"}%10s"

  /** One instance's line, kept next to [[header]] so the columns and their
    * widths cannot drift apart now that the two are printed from different
    * places.
    *
    * Every numeric column is blanked unless both solvers reached `Optimal`: a
    * gap against an objective one side never established is not a measurement,
    * and printing it would read as agreement.
    */
  private def render(r: Row): String =
    val comparable = r.oracleStatus == MilpStatus.Optimal && r.primaStatus == MilpStatus.Optimal
    val mineObj    = if comparable then f"${r.primaObjective}%14.6f" else f"${"-"}%14s"
    val theirsObj  = if comparable then f"${r.oracleObjective}%14.6f" else f"${"-"}%14s"
    val gap        = if comparable then f"${r.relativeGap}%9.2e" else f"${"-"}%9s"
    val intGap     = r.integralityGap match
      case Some(g) if comparable => f"$g%9.2e"
      case _                     => f"${"-"}%9s"
    f"${r.name}%-18s ${r.size}%-16s ${r.integers}%4d ${r.primaStatus}%-9s ${r.oracleStatus}%-9s " +
      f"$mineObj $theirsObj $gap $intGap ${r.nodes}%7d ${r.unproven}%6d " +
      f"${r.primaMillis}%9d ${r.oracleMillis}%10d"

  def main(args: Array[String]): Unit =
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    println(ValidationLadder.host)

    val params = MilpLadder.params
    val lp     = params.lp

    // The header goes out before the first solve, and each row as its instance
    // finishes, rather than all nine lines after the last one.
    //
    // `rows` used to be built in full before anything was printed, so a search
    // that ran past CI's ten-minute `timeout` left a log containing the step's
    // own kill message and not one row -- the report existed only in a value
    // that never got printed. Which instance had diverged was then a guess. The
    // order of the output is unchanged, so the job summary still finds its
    // table by the `^instance ` header.
    println(header)

    val rows = MilpLadder.instances.map { instance =>
      // On stderr, so it stays out of `milp-report.txt` and the job summary
      // while still landing in the log. `BnbParams.timeLimitMillis` should now
      // end a degenerate search with `Feasible` and a FAIL line rather than let
      // it reach the step's `timeout`, but this marker costs nothing and covers
      // the case that limit cannot: it is checked between nodes, so a single
      // node's LP running to its 200,000-iteration cap overruns it uninterrupted.
      System.err.println(s"solving ${instance.name}")
      val relaxed = Pdhg.solve(instance.problem, lp)

      val startMine = System.currentTimeMillis()
      val mine      = BranchAndBound.solve(instance.problem, instance.integers, params)
      val mineMs    = System.currentTimeMillis() - startMine

      val startTheirs = System.currentTimeMillis()
      val theirs      = OjAlgoMilp.solve(instance.problem, instance.integers)
      val theirsMs    = System.currentTimeMillis() - startTheirs

      val row = Row(
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
        relaxationStatus = relaxed.status,
        primaPrimal = mine.primal,
      )
      println(render(row))
      row
    }

    val comparable = rows.filter(r =>
      r.oracleStatus == MilpStatus.Optimal && r.primaStatus == MilpStatus.Optimal
    )
    val worst = if comparable.isEmpty then 0.0 else comparable.map(_.relativeGap).max
    println(f"%nworst relative objective gap against the oracle: $worst%.3e")
    // Unrounded too, for the reason `Report` gives at length: this figure gets
    // compared across CI's two JDK jobs, and four significant digits agreeing
    // does not make two doubles equal. Shared with `Report` rather than copied,
    // because the precision and layout are the part a reader diffs.
    println(ValidationLadder.exactly(worst))
    println(f"instances where Prima claimed a better objective than the oracle: " +
      comparable.count(r => r.primaObjective < r.oracleObjective - 1e-6))
    println(f"total nodes explored: ${rows.map(_.nodes).sum}, of which unproven: ${rows.map(_.unproven).sum}")

    // The gate has to fail *because* Prima regressed, not go quiet.
    //
    // Rows only entered `comparable` when both solvers reported Optimal, so a
    // regression that turned instances into Feasible or NoSolutionFound removed
    // them from the check rather than failing it -- and in the limit where every
    // instance degraded, `comparable` was empty, `worst` was 0.0 and the step
    // exited 0 having compared nothing. Every status is now asserted directly.
    var failed = false
    def fail(message: String): Unit =
      System.err.println(s"FAIL: $message")
      failed = true

    rows.filter(_.oracleStatus != MilpStatus.Optimal).foreach { r =>
      fail(s"${r.name}: the oracle did not solve it (${r.oracleStatus}), so nothing was compared")
    }
    rows.filter(_.primaStatus != MilpStatus.Optimal).foreach { r =>
      fail(s"${r.name}: Prima returned ${r.primaStatus} rather than Optimal")
    }
    rows.filter(_.unproven > 0).foreach { r =>
      fail(s"${r.name}: ${r.unproven} node(s) ended without a provable bound")
    }
    // `primaObjective` is `MilpSolution.objectiveValue`, which is the objective
    // of the returned point -- but only because BranchAndBound now computes it
    // from the snapped vector. It used to come from the relaxation's fractional
    // iterate instead, which meant this whole report was partly measuring the
    // snap displacement rather than search quality. Checked here so the two
    // cannot drift apart again unnoticed.
    rows.zip(MilpLadder.instances).foreach { (r, instance) =>
      // Against the primal from the solve already performed above, not a second
      // one. Re-solving doubled the step's wall time, and comparing against a
      // different run would surface any nondeterminism here as a spurious
      // failure rather than at its cause.
      if r.primaStatus == MilpStatus.Optimal then
        val achieved = instance.problem.primalObjective(r.primaPrimal)
        if math.abs(achieved - r.primaObjective) > 1e-9 * math.max(1.0, math.abs(achieved)) then
          fail(s"${r.name}: reported objective ${r.primaObjective} is not c'x = $achieved")
    }
    rows.filter(_.integralityGap.isEmpty).foreach { r =>
      fail(s"${r.name}: the LP relaxation did not solve, so the instance cannot be certified")
    }
    if worst > 1e-5 then fail(f"worst objective gap $worst%.3e exceeds 1e-5")
    if comparable.exists(r => r.primaObjective < r.oracleObjective - 1e-6) then
      fail("an objective better than the true optimum means the point is not feasible")

    if failed then System.exit(1)
