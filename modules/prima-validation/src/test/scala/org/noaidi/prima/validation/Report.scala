package org.noaidi.prima
package validation

import org.noaidi.prima.kernels.Float32Kernels
import org.noaidi.prima.ojalgo.OjAlgoSolver

/** Prints a side-by-side run of Prima and ojAlgo over the validation ladder.
  *
  * The suites assert; this reports. It exists so that iteration counts, restart
  * counts and timings are visible when judging whether a change to the
  * algorithm helped, which a pass/fail suite cannot show. Run with
  * `primaValidation/Test/runMain org.noaidi.prima.validation.Report`.
  */
object Report:

  private final case class Row(
      name: String,
      size: String,
      primaStatus: SolveStatus,
      oracleStatus: SolveStatus,
      primaObjective: Double,
      primaIterations: Int,
      primaRestarts: Int,
      primaMillis: Long,
      oracleObjective: Double,
      oracleMillis: Long,
  ):
    def relativeGap: Double = ValidationLadder.relativeGap(primaObjective, oracleObjective)

  def main(args: Array[String]): Unit =
    // Numbers here get pasted into notes and compared across machines, so the
    // decimal separator must not depend on where the machine happens to be.
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    println(ValidationLadder.host)

    val prima  = Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000))
    val oracle = OjAlgoSolver()

    val instances = ValidationLadder.instances

    val rows = instances.map { (name, problem) =>
      val mine   = prima.solve(problem)
      val theirs = oracle.solve(problem)
      Row(
        name = name,
        size = s"${problem.numVariables}v/${problem.numConstraints}c/${problem.constraintMatrix.nnz}nz",
        primaStatus = mine.status,
        oracleStatus = theirs.status,
        primaObjective = mine.objectiveValue,
        primaIterations = mine.iterations,
        primaRestarts = mine.restarts,
        primaMillis = mine.solveTimeMillis,
        oracleObjective = theirs.objectiveValue,
        oracleMillis = theirs.solveTimeMillis,
      )
    }

    println(
      f"${"instance"}%-20s ${"size"}%-22s ${"prima"}%-17s ${"ojalgo"}%-17s ${"prima obj"}%16s " +
        f"${"ojalgo obj"}%16s ${"rel gap"}%9s ${"iters"}%8s ${"restarts"}%9s ${"prima ms"}%9s ${"ojalgo ms"}%10s"
    )
    println("-" * 168)
    rows.foreach { r =>
      // Objective columns are meaningless for a problem with no optimum, so
      // they are dashed rather than printed as whatever the solvers last held.
      // Compared against the enum, not its rendered name, so renaming a case
      // cannot silently turn every row into dashes.
      val optimal = r.primaStatus == SolveStatus.Optimal && r.oracleStatus == SolveStatus.Optimal
      val primaObj   = if optimal then f"${r.primaObjective}%16.6f" else f"${"-"}%16s"
      val oracleObj  = if optimal then f"${r.oracleObjective}%16.6f" else f"${"-"}%16s"
      val gap        = if optimal then f"${r.relativeGap}%9.2e" else f"${"-"}%9s"
      println(
        f"${r.name}%-20s ${r.size}%-22s ${r.primaStatus}%-17s ${r.oracleStatus}%-17s $primaObj $oracleObj $gap " +
          f"${r.primaIterations}%8d ${r.primaRestarts}%9d ${r.primaMillis}%9d ${r.oracleMillis}%10d"
      )
    }

    val disagreements = rows.filter(r => r.primaStatus != r.oracleStatus)
    // Same condition the objective columns use: a gap computed against an
    // oracle that did not reach an optimum is meaningless, and this line is the
    // one CI publishes.
    val worst = rows
      .filter(r => r.primaStatus == SolveStatus.Optimal && r.oracleStatus == SolveStatus.Optimal)
      .map(_.relativeGap)
      .maxOption
      .getOrElse(0.0)
    println(f"%nworst relative objective gap against the oracle: $worst%.3e")
    // And the same number without rounding, because the rounded one has been
    // over-read. `README`, `HPC.md` and `NOTES` all compare this figure across
    // the two JDK jobs in CI, and one of them called the result "bit-identical"
    // -- which `%.3e` cannot support: four significant digits agreeing says
    // nothing about the other forty-nine bits.
    //
    // Both forms, because they answer different questions. The decimal
    // round-trips -- `%.17e` asks for more digits than a `Double` carries, and
    // Java's `Formatter` supplies the shortest round-tripping representation
    // padded with zeros rather than the true binary expansion, so it identifies
    // the value uniquely without being its exact decimal. That is enough for
    // comparing two job logs and it stays readable; the raw bits are what a
    // reader can compare without trusting the round-tripping claim at all.
    //
    // Neither is asserted on -- the comparison is across two CI jobs, which
    // nothing here can see -- so this makes the claim checkable rather than
    // checked, and the prose stays at what the rounded line supports until a run
    // has shown otherwise.
    println(ValidationLadder.exactly(worst))
    if disagreements.nonEmpty then
      println(s"STATUS DISAGREEMENTS: ${disagreements.map(_.name).mkString(", ")}")

    // Two tolerance regimes, because they give opposite answers. At 1e-9 the
    // float32 device can only deliver a starting point and the double-precision
    // tail dominates; at 1e-6 it can deliver the whole answer.
    reportMixedPrecision(instances, 1e-9)
    reportMixedPrecision(instances, 1e-6)

    // The ladder carries one dense instance per size, and a single instance
    // cannot distinguish a property of reduced precision from a property of
    // that draw. It did not: `random-600x400` is one seed of this family and
    // the only one of ten where the hand-over costs several times a cold solve.
    reportWarmStartSpread(1e-6)
    reportWarmStartSpread(1e-9)

  /** Reduced precision followed by a double-precision finish, against a cold
    * double-precision solve.
    *
    * Every accelerator backend in prospect is float32-only, so this is the
    * arrangement a GPU would actually run under. The number that matters is the
    * last column: how much of the expensive double-precision work the device
    * removes. Iterations rather than milliseconds, because the float32 pass is
    * running on a CPU here and its wall time says nothing about a GPU's.
    */
  private def reportMixedPrecision(instances: Seq[(String, LpProblem)], tolerance: Double): Unit =
    val params = PdhgParams(epsAbs = tolerance, epsRel = tolerance, maxIterations = 500_000)

    println(f"%n%nMixed precision at tolerance $tolerance%.0e (float32 pass, then float64 refinement)")
    println(
      f"${"instance"}%-20s ${"status"}%-17s ${"cold fp64"}%10s ${"fp32 pass"}%10s " +
        f"${"refine"}%8s ${"obj delta"}%11s ${"fp64 work saved"}%16s"
    )
    println("-" * 100)

    instances.foreach { (name, problem) =>
      val row = handOver(problem, params)

      val delta =
        if row.result.solution.status == SolveStatus.Optimal && row.cold.status == SolveStatus.Optimal then
          f"${math.abs(row.result.solution.objectiveValue - row.cold.objectiveValue) /
              math.max(1.0, math.abs(row.cold.objectiveValue))}%11.2e"
        else f"${"-"}%11s"

      val saved = row.saved.fold(f"${"n/a"}%16s")(v => f"$v%15.1f%%")

      println(
        f"$name%-20s ${row.result.solution.status}%-17s ${row.cold.iterations}%10d " +
          f"${row.result.reducedIterations}%10d ${row.result.refinementIterations}%8d $delta $saved"
      )
    }

  /** The same hand-over, over ten draws of one dense random shape.
    *
    * Printed per seed rather than as a summary because the distribution is the
    * finding: at 1e-6 nine of the ten save between a tenth and a half of the
    * double-precision work and one costs several times a cold solve, and a
    * median alone would hide both halves of that.
    *
    * The cold column is what the saving is measured against, and on these
    * instances it is itself unstable — the last iterate oscillates by orders of
    * magnitude between evaluation points, so which checkpoint first passes the
    * test moves a long way on small changes. A saving computed against it is a
    * ratio of two noisy numbers, which is the other reason to print ten.
    */
  private def reportWarmStartSpread(tolerance: Double): Unit =
    val params = PdhgParams(epsAbs = tolerance, epsRel = tolerance, maxIterations = 500_000)

    println(f"%n%nFloat32 hand-over over ${ValidationLadder.denseSeeds.size}%d dense random draws at tolerance $tolerance%.0e")
    println(
      f"${"instance"}%-20s ${"status"}%-17s ${"cold fp64"}%10s ${"fp32 pass"}%10s " +
        f"${"refine"}%8s ${"fp64 work saved"}%16s"
    )
    println("-" * 86)

    val rows = ValidationLadder.denseSpread.map { (name, problem) =>
      val row = handOver(problem, params)
      println(
        f"$name%-20s ${row.result.solution.status}%-17s ${row.cold.iterations}%10d " +
          f"${row.result.reducedIterations}%10d ${row.result.refinementIterations}%8d " +
          row.saved.fold(f"${"n/a"}%16s")(v => f"$v%15.1f%%")
      )
      row
    }

    // Only the rows whose saving means something. A draw where either pass ran
    // out of iterations, or where the refinement never ran because the device
    // pass was conclusive on its own, has a "saving" that is an artefact of the
    // stopping rule rather than a measurement of the hand-over -- and these
    // numbers are quoted verbatim as headline claims in README and NOTES.
    // Printing how many of the ten the summary covers is what keeps an excluded
    // draw visible instead of absorbed.
    val savings = rows.flatMap(_.saved).sorted
    if savings.isEmpty then println("  no draw produced a comparable pair")
    else
      val median = (savings(savings.size / 2) + savings((savings.size - 1) / 2)) / 2.0
      println(
        f"  median $median%.1f%%   worst ${savings.head}%.1f%%   best ${savings.last}%.1f%%" +
          f"   over ${savings.size}%d of ${rows.size}%d draws"
      )

  /** One float32 pass and one cold solve of the same problem.
    *
    * Shared by both tables rather than written twice. The guards on what counts
    * as a comparable pair live here for the same reason the ladder itself is
    * shared: the second table was copied from the first, lost those guards in
    * the copying, and then published a median over rows the first would have
    * printed as `n/a`.
    */
  private final case class HandOver(cold: LpSolution, result: MixedPrecision.Result):
    /** Double-precision work removed, or `None` where the pair says nothing.
      *
      * Three ways it says nothing: the refinement did not run, because the
      * device pass was conclusive on its own and scored a free 100%; the cold
      * solve took no iterations at all, which makes the ratio infinite or
      * `NaN`; or either solve stopped on a limit rather than an answer, so the
      * comparison is between two truncated runs.
      */
    def saved: Option[Double] =
      Option.when(
        result.refined && cold.iterations > 0 &&
          cold.status == SolveStatus.Optimal && result.solution.status == SolveStatus.Optimal
      )(100.0 * (1.0 - result.refinementIterations.toDouble / cold.iterations))

  private def handOver(problem: LpProblem, params: PdhgParams): HandOver =
    val device = Float32Kernels()
    val result =
      try MixedPrecision.solve(problem, params, device)
      finally device.close()
    HandOver(Pdhg.solve(problem, params), result)

end Report
