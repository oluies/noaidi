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
  * prove infeasibility within the iteration budget is not: presolve settles some
  * of these outright, and the rest need the approximate reductions before a
  * certificate emerges. That is recorded rather than asserted.
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
  private val provenByPresolve = scala.collection.mutable.Set.empty[String]

  NetlibCorpus.infeasibleNames.foreach { name =>
    test(s"netlib/infeasible/$name is never reported optimal") {
      assume(NetlibCorpus.available, "Netlib corpus unavailable (offline?) — skipping")
      val path = NetlibCorpus.infeasible(name)
      assume(path.isDefined, s"$name could not be fetched")

      val instance = MpsReader.fromFile(path.get)
      assume(!instance.isMixedInteger, s"$name has integer columns; the LP relaxation is not the instance")

      // Presolve can settle infeasibility outright, without iterating at all:
      // an empty row that cannot hold, or bounds driven into contradiction, is
      // a proof rather than a numerical judgement.
      val presolved = Presolve(instance.problem)
      val onReduced = Option.when(presolved.provenStatus.isEmpty)(Pdhg.solve(presolved.reduced, params))

      // When presolve settles it, nothing is solved at all — which is the claim
      // being measured. Previously this ran a full 20k-iteration solve of the
      // original purely to obtain an LpSolution and then overwrote its status,
      // which both contradicted the claim and made the assertion below vacuous
      // for exactly those instances.
      val status = presolved.provenStatus.getOrElse(onReduced.get.status)
      results(name) = status
      if presolved.provenInfeasible then provenByPresolve += name

      // The hard assertion. A first-order method that has not proven anything
      // should say so; claiming an optimum on an infeasible problem means it
      // produced a primal point that does not exist.
      assertNotEquals(
        status,
        SolveStatus.Optimal,
        s"$name is infeasible but was reported optimal",
      )

      // And when the solver claims infeasibility, the claim must survive an
      // independent re-check of the certificate at the same tolerance.
      //
      // Checked against the *reduced* problem, which is the one the solver
      // actually proved infeasible. Postsolve maps optimal duals back, and does
      // so accurately enough for pricing, but an infeasibility certificate is a
      // Farkas ray rather than an optimal dual and the same mapping does not
      // carry it. Infeasibility of the reduced problem implies infeasibility of
      // the original regardless, since every reduction here is exact.
      onReduced.filter(_.status == SolveStatus.PrimalInfeasible).foreach { reduced =>
        val verdict = Certificates.classify(
          presolved.reduced,
          Unsafe.raw(reduced.primal),
          Unsafe.raw(reduced.dual),
          params.infeasibilityTolerance,
        )
        assertEquals(
          verdict,
          Some(SolveStatus.PrimalInfeasible),
          s"$name was reported infeasible but its certificate does not re-verify",
        )
      }
    }
  }

  override def afterAll(): Unit =
    if results.nonEmpty then
      val proven = results.count(_._2 == SolveStatus.PrimalInfeasible)
      val byStatus = results.groupBy(_._2).map((s, m) => s"$s=${m.size}").toSeq.sorted.mkString(", ")
      println(
        s"netlib infeasible: $proven/${results.size} proven infeasible " +
          s"(${provenByPresolve.size} by presolve alone)  ($byStatus)"
      )
