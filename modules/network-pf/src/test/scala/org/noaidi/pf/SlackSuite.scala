package org.noaidi.pf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema, Topology}

/** Slack selection, on networks built to separate the three rules.
  *
  * The goldens cannot do this. `ac-dc-dispatch` marks Manchester Wind and
  * Frankfurt Wind as `control = Slack`, but both are '''already''' their island's
  * first generator in file order — so rule 1 and rule 2 pick the same bus and
  * deleting the declared-slack lookup entirely would break no test. Each rule is
  * therefore exercised here on a network where it disagrees with the others.
  */
class SlackSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        // Closed explicitly: `Files.walk` holds an open directory handle, as the
        // two other `network-pf` suites already account for.
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }

  /** A two-bus AC island, with generators listed in the given order.
    *
    * Bus order is deliberately `B,A` so that file order and sorted order differ:
    * a rule that reaches for the first bus gets different answers depending on
    * which it means, and only file order is right.
    */
  private def network(generators: Seq[(String, String, String)]): Network =
    val dir = Files.createTempDirectory("noaidi-slack-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nB,1.0,AC\nA,1.0,AC\n")
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nl,A,B,1.0,0.0,100.0\n")
    Files.writeString(
      dir.resolve("generators.csv"),
      "name,bus,control,carrier\n" + generators.map((n, b, c) => s"$n,$b,$c,wind\n").mkString,
    )
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    CsvReader.read(dir, schema, "slack-fixture")

  private def choose(n: Network): Slack.Choice =
    val subs = Topology.subNetworks(n)
    assertEquals(subs.length, 1, "fixture should be one island")
    Slack.of(n, subs.head)

  test("a declared slack wins over the first generator") {
    assume(available, "goldens missing")
    // The distinguishing case: the declared slack is listed *second*, so rule 2
    // would pick the other one. This is what no golden can test.
    val choice = choose(network(Seq(("first", "A", "PQ"), ("declared", "B", "Slack"))))
    assertEquals(choice.bus, "B")
    assertEquals(choice.generator, Some("declared"))
  }

  test("with no declared slack the first generator in file order wins") {
    assume(available, "goldens missing")
    // Both PQ, so rule 2 applies — and the first generator sits on bus A while
    // the *file's* first bus is B, so a rule that reached for the first bus
    // instead would answer B.
    val choice = choose(network(Seq(("first", "A", "PQ"), ("second", "B", "PQ"))))
    assertEquals(choice.bus, "A")
    assertEquals(choice.generator, Some("first"))
  }

  test("the first declared slack wins when several are declared") {
    assume(available, "goldens missing")
    // PyPSA takes one; the point is that the choice is deterministic rather than
    // dependent on map iteration order.
    val choice = choose(network(Seq(("one", "A", "Slack"), ("two", "B", "Slack"))))
    assertEquals(choice.bus, "A")
    assertEquals(choice.generator, Some("one"))
  }

  test("an island with no generator falls back to its first bus in file order") {
    assume(available, "goldens missing")
    // buses.csv lists B before A, so file order says B and sorted order says A.
    // The reference network settles which is right: PyPSA's DC island reports
    // Norwich DC, which is first in the file and third alphabetically.
    val dir = Files.createTempDirectory("noaidi-slack-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nB,1.0,AC\nA,1.0,AC\n")
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nl,A,B,1.0,0.0,100.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    val n = CsvReader.read(dir, schema, "slack-no-gen")

    val choice = choose(n)
    assertEquals(choice.bus, "B")
    assertEquals(choice.generator, None)
    // And the sorted order really does disagree, so the test is not passing by
    // the two happening to coincide.
    assertEquals(Topology.subNetworks(n).head.buses.head, "A")
  }
