package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.ojalgo.OjAlgoSolver
import org.noaidi.prima.{Pdhg, PdhgParams, SolveStatus}

import java.nio.file.Files

/** The L2 model, solved by a backend it was not written against.
  *
  * `Lopf.solve` takes an `LpSolver` because the model it builds is a linear
  * program and nothing about it is Prima's. That is easy to assert in a type
  * signature and worth nothing until something else actually solves it: a
  * simplex and a first-order method fail in completely different ways, so
  * reaching PyPSA's objective through both is evidence the model is right
  * rather than evidence that one solver is self-consistent.
  */
class SolverAgnosticSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-agnostic-"

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  test("ojAlgo reaches PyPSA's objective on the same model Prima does") {
    assume(available, "goldens missing — run reference/generate_goldens.py")
    val expected = results("ac-dc-dispatch")("optimize")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")
    val target = expected("objective").num

    val n = network("ac-dc-dispatch")

    val byPrima  = Lopf.solve(n, PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000))
    val byOjAlgo = Lopf.solve(n, OjAlgoSolver())

    assertEquals(byPrima.status, SolveStatus.Optimal, s"${byPrima.solution}")
    assertEquals(byOjAlgo.status, SolveStatus.Optimal, s"${byOjAlgo.solution}")

    val tolerance = 1e-6 * math.max(1.0, math.abs(target))
    assertEqualsDouble(byPrima.objective, target, tolerance, "Prima against PyPSA")
    assertEqualsDouble(byOjAlgo.objective, target, tolerance, "ojAlgo against PyPSA")
  }

  test("the two agree on the dispatch itself, not only on what it costs") {
    assume(available, "goldens missing")
    val n = network("ac-dc-dispatch")

    val byPrima  = Lopf.solve(n, PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000))
    val byOjAlgo = Lopf.solve(n, OjAlgoSolver())

    // Two optima of equal cost would pass an objective-only comparison. This
    // fixture's primal is unique -- NOTES records that it is the *dual* that is
    // not -- so the dispatches have to match entity for entity.
    val generators = byPrima.network.require("Generator").ids
    val snapshots  = byPrima.network.snapshots.length
    generators.foreach { g =>
      (0 until snapshots).foreach { t =>
        assertEqualsDouble(
          byOjAlgo.dispatch("Generator", g, t),
          byPrima.dispatch("Generator", g, t),
          1e-4,
          s"$g at snapshot $t",
        )
      }
    }
  }
