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
    def relativeGap: Double =
      val scale = math.max(1.0, math.abs(oracleObjective))
      math.abs(primaObjective - oracleObjective) / scale

  def main(args: Array[String]): Unit =
    // Numbers here get pasted into notes and compared across machines, so the
    // decimal separator must not depend on where the machine happens to be.
    java.util.Locale.setDefault(java.util.Locale.ROOT)

    val prima  = Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000))
    val oracle = OjAlgoSolver()

    val instances =
      LpFixtures.conclusive.map(i => (i.name, i.problem)) ++
        Seq(
          ("random-60x30", LpFixtures.randomFeasible(1, 60, 10, 20, 0.25)),
          ("random-200x120", LpFixtures.randomFeasible(2, 200, 40, 80, 0.10)),
          ("random-600x400", LpFixtures.randomFeasible(3, 600, 120, 280, 0.04)),
        )

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
    if disagreements.nonEmpty then
      println(s"STATUS DISAGREEMENTS: ${disagreements.map(_.name).mkString(", ")}")

    // Two tolerance regimes, because they give opposite answers. At 1e-9 the
    // float32 device can only deliver a starting point and the double-precision
    // tail dominates; at 1e-6 it can deliver the whole answer.
    reportMixedPrecision(instances, 1e-9)
    reportMixedPrecision(instances, 1e-6)

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
      val device = Float32Kernels()
      val result =
        try MixedPrecision.solve(problem, params, device)
        finally device.close()
      val cold = Pdhg.solve(problem, params)

      val delta =
        if result.solution.status == SolveStatus.Optimal && cold.status == SolveStatus.Optimal then
          f"${math.abs(result.solution.objectiveValue - cold.objectiveValue) /
              math.max(1.0, math.abs(cold.objectiveValue))}%11.2e"
        else f"${"-"}%11s"

      val saved =
        if result.refined && cold.iterations > 0 then
          f"${100.0 * (1.0 - result.refinementIterations.toDouble / cold.iterations)}%15.1f%%"
        else f"${"n/a"}%16s"

      println(
        f"$name%-20s ${result.solution.status}%-17s ${cold.iterations}%10d " +
          f"${result.reducedIterations}%10d ${result.refinementIterations}%8d $delta $saved"
      )
    }

end Report
