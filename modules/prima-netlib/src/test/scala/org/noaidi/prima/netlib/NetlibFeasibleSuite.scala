package org.noaidi.prima
package netlib

import org.noaidi.prima.mps.MpsReader

/** Netlib's feasible instances against their published optima.
  *
  * The reference values were computed by Gurobi at 1e-8 and ship with the
  * corpus. They are the first oracle in this project independent of ojAlgo —
  * until now every cross-check ran against a solver that is part of this build,
  * so a shared misunderstanding of the model would have gone unnoticed.
  *
  * Two different things are asserted, and the distinction is the point:
  *
  *   - '''Whenever Prima claims an optimum, it must be the right one.''' This
  *     holds for every instance, always. A wrong answer is a defect.
  *   - '''A named subset must reach an optimum at all.''' This is a regression
  *     gate, not a statement about the algorithm. Even with presolve, PDHG does
  *     not converge on all of Netlib within a sane budget, and asserting that it
  *     should would produce a permanently red build that everyone learns to
  *     ignore.
  *
  * Instances outside the subset are reported, not asserted. Presolve grew the
  * subset by two; the approximate reductions should grow it further, and the
  * report is how that gets noticed.
  */
class NetlibFeasibleSuite extends munit.FunSuite:

  private val params = PdhgParams(
    epsAbs = 1e-8,
    epsRel = 1e-8,
    maxIterations = 50_000,
    timeLimitMillis = Some(30_000L),
  )

  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")

  // Set before any test runs: the per-instance notes are formatted in the test
  // bodies, so doing this in afterAll would be too late to affect them. These
  // numbers get pasted into notes and compared across machines, so the decimal
  // separator must not depend on where the machine happens to be.
  java.util.Locale.setDefault(java.util.Locale.ROOT)

  /** Instances that currently converge, locked in so a regression is a failure
    * rather than a quietly shorter report.
    */
  private val mustConverge: Set[String] = Set(
    "afiro", "sc50a", "sc50b", "kb2", "adlittle", "blend", "sc105", "stocfor1",
    "israel", "beaconfd", "brandy", "e226", "sc205", "scorpion",
    // Reached only once presolve landed: both need heavy singleton-row
    // elimination (34 and 47 rows) before the iteration converges.
    "scagr7", "bandm",
  )

  private val outcomes = scala.collection.mutable.LinkedHashMap.empty[String, (SolveStatus, String)]

  NetlibCorpus.feasibleNames.foreach { name =>
    test(s"netlib/feasible/$name") {
      assume(NetlibCorpus.available, "Netlib corpus unavailable (offline?) — skipping")
      val path = NetlibCorpus.feasible(name)
      assume(path.isDefined, s"$name could not be fetched")

      val instance = MpsReader.fromFile(path.get)
      assume(!instance.isMixedInteger, s"$name has integer columns")

      // Once the corpus is reachable the references must be too. Letting them
      // degrade to empty would silently delete every optimum comparison -- the
      // one assertion this module exists for -- while the suite stayed green.
      assert(
        NetlibCorpus.referenceObjectives.nonEmpty,
        "corpus is available but reference objectives are missing or unparseable",
      )
      // Every name in the ladder is expected to have a published optimum, so a
      // missing one is a defect rather than a reason to skip the comparison.
      // Asserting only that the map is non-empty catches total loss but not the
      // loss of a single entry, which reads identically at the call site.
      val reference = NetlibCorpus.referenceObjectives.get(name)
      assert(reference.isDefined, s"$name has no published optimum in the reference CSV")

      // Presolve then solve then restore, which is how a caller would use it.
      // The measured claim is that this converges on instances the raw solver
      // cannot reach, so the suite has to exercise the same path.
      val presolved = Presolve(instance.problem)
      // A known-feasible instance must never be *proven* infeasible. Falling
      // back to solving the original would paper over exactly that error.
      assert(!presolved.provenInfeasible, s"$name is feasible but presolve proved it infeasible")
      val solution = presolved.provenStatus match
        case Some(proven) => fail(s"$name is feasible but presolve proved $proven")
        case None         => presolved.restore(Pdhg.solve(presolved.reduced, params))

      val note = reference match
        case Some(expected) if solution.status == SolveStatus.Optimal =>
          val gap = math.abs(solution.objectiveValue - expected) / math.max(1.0, math.abs(expected))
          f"gap=$gap%.2e"
        case Some(_) => "-"
        case None    => "no reference"
      outcomes(name) = (solution.status, s"$note  [${presolved.stats}]")

      // Correctness, asserted for every instance: an optimum that disagrees
      // with the published value is wrong regardless of which instances happen
      // to converge today.
      reference.foreach { expected =>
        if solution.status == SolveStatus.Optimal then
          // Tight enough to hold the published figure honest. NOTES and README
          // advertise 2.2e-08; a 1e-5 bound would let that drift three orders of
          // magnitude with the suite still green.
          val tolerance = 1e-7 * math.max(1.0, math.abs(expected))
          assertEqualsDouble(
            solution.objectiveValue,
            expected,
            tolerance,
            s"$name: reported ${solution.objectiveValue} against published $expected",
          )
      }

      // An infeasibility claim on a known-feasible instance is the serious
      // error, and exactly the one the certificate bugs produced.
      assertNotEquals(
        solution.status,
        SolveStatus.PrimalInfeasible,
        s"$name is feasible with optimum ${reference.getOrElse("?")} but was reported infeasible",
      )

      // Coverage, asserted only where it currently holds.
      if mustConverge.contains(name) then
        assertEquals(solution.status, SolveStatus.Optimal, s"$name regressed: $solution")
    }
  }

  override def afterAll(): Unit =
    if outcomes.nonEmpty then
      val converged = outcomes.count(_._2._1 == SolveStatus.Optimal)
      println(f"%nnetlib feasible: $converged/${outcomes.size} solved to optimality")
      outcomes.foreach { case (name, (status, note)) =>
        println(f"  $name%-12s $status%-16s $note")
      }
