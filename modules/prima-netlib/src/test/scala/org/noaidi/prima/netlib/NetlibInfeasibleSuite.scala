package org.noaidi.prima
package netlib

import org.noaidi.prima.mps.MpsReader

/** Netlib's infeasible collection.
  *
  * This is the suite the whole MPS effort was for. Prima's certificate logic
  * has been wrong twice, both times reporting a feasible problem as infeasible,
  * and both times the bug was found by review rather than by a test. These 29
  * instances are the other half of that question: problems built to be
  * infeasible, where the failure mode is reporting a spurious optimum.
  *
  * The assertion is deliberately asymmetric. Reporting `Optimal` on any of them
  * is a hard failure — it means the solver invented a feasible point. Failing to
  * prove infeasibility within the iteration budget is not: PDHG has no presolve
  * here, and some of these need one before a certificate emerges. That is
  * recorded rather than asserted.
  */
class NetlibInfeasibleSuite extends munit.FunSuite:

  private val params = PdhgParams(
    epsAbs = 1e-8,
    epsRel = 1e-8,
    maxIterations = 20_000,
    timeLimitMillis = Some(20_000L),
  )

  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val results = scala.collection.mutable.LinkedHashMap.empty[String, SolveStatus]

  NetlibCorpus.infeasibleNames.foreach { name =>
    test(s"netlib/infeasible/$name is never reported optimal") {
      assume(NetlibCorpus.available, "Netlib corpus unavailable (offline?) — skipping")
      val path = NetlibCorpus.infeasible(name)
      assume(path.isDefined, s"$name could not be fetched")

      val instance = MpsReader.fromFile(path.get)
      assume(!instance.isMixedInteger, s"$name has integer columns; the LP relaxation is not the instance")

      val solution = Pdhg.solve(instance.problem, params)
      results(name) = solution.status

      // The hard assertion. A first-order method that has not proven anything
      // should say so; claiming an optimum on an infeasible problem means it
      // produced a primal point that does not exist.
      assertNotEquals(
        solution.status,
        SolveStatus.Optimal,
        s"$name is infeasible but was reported optimal with objective ${solution.objectiveValue}",
      )

      // And when it does claim infeasibility, the claim must survive an
      // independent re-check of the certificate at the same tolerance.
      if solution.status == SolveStatus.PrimalInfeasible then
        val verdict = Certificates.classify(
          instance.problem,
          Unsafe.raw(solution.primal),
          Unsafe.raw(solution.dual),
          params.infeasibilityTolerance,
        )
        assertEquals(
          verdict,
          Some(SolveStatus.PrimalInfeasible),
          s"$name was reported infeasible but its certificate does not re-verify",
        )
    }
  }

  override def afterAll(): Unit =
    if results.nonEmpty then
      val proven = results.count(_._2 == SolveStatus.PrimalInfeasible)
      val byStatus = results.groupBy(_._2).map((s, m) => s"$s=${m.size}").toSeq.sorted.mkString(", ")
      println(s"netlib infeasible: $proven/${results.size} proven infeasible  ($byStatus)")
