package org.noaidi.pf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema, Topology}

/** Outage factors against PyPSA's own BODF.
  *
  * `sclopf-triangle` cannot do this job. A single cycle has one alternative
  * path, so the whole of an outaged branch's flow reappears on every survivor
  * and every factor is ±1 by topology, whatever the impedances — an
  * implementation that simply returned ±1 would match it, and would also match
  * the SCLOPF objective it feeds.
  *
  * `lodf-mesh` is two loops sharing an edge, where the split depends on the
  * impedances and PyPSA's factors include −0.5455, −0.6429 and +0.3571. Those
  * are what this checks.
  */
class LodfSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def bodf(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))("bodf")

  test("outage factors match PyPSA's BODF on a meshed network") {
    assume(available, "goldens missing — run reference/generate_goldens.py")
    val expected = bodf("lodf-mesh")
    assert(!expected.obj.contains("error"), s"golden BODF failed: ${expected.obj.get("error")}")

    val n = network("lodf-mesh")
    val subs = Topology.subNetworks(n)
    assertEquals(subs.length, 1, "the fixture should be one island")
    val lodf = Lodf.of(n, subs.head, None)

    val block    = expected.obj.values.head
    val branches = block("branches").arr.map(b => (b(0).str, b(1).str)).toIndexedSeq
    val matrix   = block("bodf").arr.map(_.arr.map(_.num).toIndexedSeq).toIndexedSeq

    var offDiagonal = 0
    branches.zipWithIndex.foreach { (affected, i) =>
      branches.zipWithIndex.foreach { (outage, j) =>
        assertEqualsDouble(
          lodf.factor(affected, outage),
          matrix(i)(j),
          1e-9,
          s"factor on ${affected._2} from outage of ${outage._2}",
        )
        // Count the entries that are neither the -1 diagonal nor a full
        // redistribution, since those are the ones a stub returning +-1 would
        // get wrong.
        if i != j && math.abs(math.abs(matrix(i)(j)) - 1.0) > 1e-6 then offDiagonal += 1
      }
    }
    assert(
      offDiagonal >= 6,
      s"only $offDiagonal factors differ from +-1; the fixture cannot distinguish a stub",
    )
  }

  test("a single cycle gives factors of magnitude one, and so proves little") {
    assume(available, "goldens missing")
    // Recorded because I previously used exactly this shape as evidence that
    // outage factors stay bounded near a bridge. On one cycle that is a
    // topological identity -- there is a single alternative path, so all of the
    // flow moves to it -- and the impedances cannot change it. The measurement
    // could not have come out any other way, which is why the meshed fixture
    // above exists.
    val n    = network("sclopf-triangle")
    val lodf = Lodf.of(n, Topology.subNetworks(n).head, None)
    Seq(("AB", "BC"), ("AB", "AC"), ("BC", "AC")).foreach { (a, o) =>
      assertEqualsDouble(math.abs(lodf.factor(("Line", a), ("Line", o))), 1.0, 1e-9)
    }
  }
