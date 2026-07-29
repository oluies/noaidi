package org.noaidi.prima
package validation

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
      primaStatus: String,
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
        primaStatus = mine.status.toString,
        primaObjective = mine.objectiveValue,
        primaIterations = mine.iterations,
        primaRestarts = mine.restarts,
        primaMillis = mine.solveTimeMillis,
        oracleObjective = theirs.objectiveValue,
        oracleMillis = theirs.solveTimeMillis,
      )
    }

    println(
      f"${"instance"}%-20s ${"size"}%-22s ${"status"}%-17s ${"prima obj"}%16s " +
        f"${"ojalgo obj"}%16s ${"rel gap"}%9s ${"iters"}%8s ${"restarts"}%9s ${"prima ms"}%9s ${"ojalgo ms"}%10s"
    )
    println("-" * 150)
    rows.foreach { r =>
      println(
        f"${r.name}%-20s ${r.size}%-22s ${r.primaStatus}%-17s ${r.primaObjective}%16.6f " +
          f"${r.oracleObjective}%16.6f ${r.relativeGap}%9.2e ${r.primaIterations}%8d " +
          f"${r.primaRestarts}%9d ${r.primaMillis}%9d ${r.oracleMillis}%10d"
      )
    }

    val worst = rows.filter(_.primaStatus == "Optimal").map(_.relativeGap).maxOption.getOrElse(0.0)
    println(f"%nworst relative objective gap against the oracle: $worst%.3e")

end Report
