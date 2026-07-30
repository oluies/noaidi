package org.noaidi.network

import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.ListMap

/** Sub-network decomposition, against PyPSA's own.
  *
  * The rule being reproduced is not obvious and is easy to get plausibly wrong:
  * sub-networks are the connected components over '''passive''' branches only.
  * Include links and the reference AC/DC network collapses from four islands
  * into one, which still looks like a network and would make power flow
  * singular rather than merely slow.
  *
  * The expected decomposition comes from `manifest.json`, written by calling
  * `determine_network_topology()` on the pinned install — so it is PyPSA's
  * answer, not my reading of the rule.
  */
class TopologySuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))
  private lazy val manifest: ujson.Value =
    ujson.read(Files.readString(goldens.resolve("manifest.json")))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  test("component roles come from PyPSA's category, not a hardcoded list") {
    assume(available, "goldens missing")
    assertEquals(Role.of(schema("Line")), Role.PassiveBranch)
    assertEquals(Role.of(schema("Transformer")), Role.PassiveBranch)
    assertEquals(Role.of(schema("Link")), Role.ControllableBranch)
    // Process is a second controllable branch and is classified correctly
    // without being named anywhere in the code — which is the point of reading
    // the category.
    assertEquals(Role.of(schema("Process")), Role.ControllableBranch)
    assertEquals(Role.of(schema("Generator")), Role.Attached)
    assertEquals(Role.of(schema("Load")), Role.Attached)
    assertEquals(Role.of(schema("ShuntImpedance")), Role.Attached)
    assertEquals(Role.of(schema("Bus")), Role.Bus)
    assertEquals(Role.of(schema("Carrier")), Role.None)
    assertEquals(Role.of(schema("LineType")), Role.None)
  }

  List("ac-dc-meshed", "storage-hvdc").foreach { name =>
    test(s"$name decomposes into the sub-networks PyPSA found") {
      assume(available, "goldens missing")
      val expected = manifest("networks")(name)("sub_networks").arr.map { sn =>
        (sn("carrier").str, sn("buses").arr.map(_.str).toIndexedSeq.sorted)
      }.toIndexedSeq

      val actual = Topology.subNetworks(network(name)).map(sn => (sn.carrier, sn.buses))

      assertEquals(actual.size, expected.size, s"$name: sub-network count")
      // Compared as sets of islands: PyPSA numbers them in its own order and
      // that ordering is not a property worth reproducing.
      assertEquals(
        actual.toSet,
        expected.toSet,
        s"$name: sub-network decomposition",
      )

      // The scaladoc promises a deterministic order by smallest bus name; assert
      // it rather than leaving it to hash iteration order to honour by luck.
      val heads = actual.map(_._2.head)
      assertEquals(heads, heads.sorted, s"$name: sub-networks are not in a stable order")
    }
  }

  test("links do not merge sub-networks") {
    assume(available, "goldens missing")
    val n = network("ac-dc-meshed")

    // The AC/DC network is joined by links; if those counted, everything would
    // be one island. This is the assertion that the passive-only rule is
    // actually being applied rather than coincidentally producing the right
    // answer.
    assert(Topology.controllableBranches(n).nonEmpty, "the fixture has no links")
    assertEquals(Topology.subNetworks(n).size, 4)

    val carriers = Topology.subNetworks(n).map(_.carrier).toSet
    assertEquals(carriers, Set("AC", "DC"), "AC and DC should stay separate")
  }

  /** A network built in code, for shapes the reference fixtures do not contain. */
  private def synthetic(
      buses: IndexedSeq[String],
      lines: IndexedSeq[(String, String)],
      carriers: IndexedSeq[String] = IndexedSeq.empty,
  ): Network =
    val busCarriers = if carriers.nonEmpty then carriers else buses.map(_ => "AC")
    val busTable = ComponentTable(
      schema("Bus"),
      buses,
      ListMap("carrier" -> Column.Strings(IArray.from(busCarriers))),
      ListMap.empty,
    )
    val lineTable = ComponentTable(
      schema("Line"),
      lines.indices.map(i => s"l$i"),
      ListMap(
        "bus0" -> Column.Strings(IArray.from(lines.map(_._1))),
        "bus1" -> Column.Strings(IArray.from(lines.map(_._2))),
      ),
      ListMap.empty,
    )
    Network.empty("synthetic", schema).withTable(busTable).withTable(lineTable)

  test("a series chain forms one island") {
    assume(available, "goldens missing")
    // Neither reference network contains a chain -- the AC side is a triangle,
    // a pair and a singleton -- so this is built in code. A radial feeder is the
    // shape that exercises the union-find's path compression, which the previous
    // version of this test claimed to cover and did not.
    val chain = synthetic(
      IndexedSeq("a", "b", "c", "d", "e"),
      IndexedSeq("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "e"),
    )
    val islands = Topology.subNetworks(chain)
    assertEquals(islands.size, 1, s"a chain should be one island, got ${islands.map(_.buses)}")
    assertEquals(islands.head.buses, IndexedSeq("a", "b", "c", "d", "e"))
  }

  test("the AC side splits into three islands, one reached only over a link") {
    assume(available, "goldens missing")
    val acIsl = Topology.subNetworks(network("ac-dc-meshed")).filter(_.carrier == "AC")
    assertEquals(acIsl.size, 3)
    assert(acIsl.exists(_.size == 1), s"expected a singleton AC island, got ${acIsl.map(_.size)}")
  }

  test("a multi-port branch connects all of its ports") {
    assume(available, "goldens missing")
    // PyPSA links take two *or more* buses, with the extra ports arriving as
    // custom bus2/bus3 columns. Reading only the declared bus0/bus1 pair drops
    // those endpoints, and the visible symptom is a connected bus reported
    // isolated.
    val buses = ComponentTable(
      schema("Bus"),
      IndexedSeq("a", "b", "c"),
      ListMap("carrier" -> Column.Strings(IArray("AC", "AC", "AC"))),
      ListMap.empty,
    )
    val threePort = ComponentTable(
      schema("Link"),
      IndexedSeq("k"),
      ListMap(
        "bus0" -> Column.Strings(IArray("a")),
        "bus1" -> Column.Strings(IArray("b")),
        "bus2" -> Column.Strings(IArray("c")),
      ),
      ListMap.empty,
    )
    val n = Network.empty("multiport", schema).withTable(buses).withTable(threePort)

    assertEquals(Topology.portsOf(threePort), IndexedSeq("bus0", "bus1", "bus2"))
    // Every pair, so connectivity over the link is transitive.
    assertEquals(Topology.controllableBranches(n).size, 3)
    // And the third port is not reported isolated.
    assertEquals(Topology.isolatedBuses(n), IndexedSeq.empty)
  }

  test("every bus belongs to exactly one sub-network") {
    assume(available, "goldens missing")
    List("ac-dc-meshed", "storage-hvdc").foreach { name =>
      val n    = network(name)
      val all  = n.require("Bus").ids.toSet
      val seen = Topology.subNetworks(n).flatMap(_.buses)
      assertEquals(seen.size, seen.distinct.size, s"$name: a bus appears twice")
      assertEquals(seen.toSet, all, s"$name: partition does not cover the buses")
    }
  }

  test("attached components are found by their bus") {
    assume(available, "goldens missing")
    val n = network("ac-dc-meshed")
    val atManchester = Topology.attachedTo(n, "Manchester")
    assert(atManchester.nonEmpty, "Manchester should carry generators and load")
    // Every hit must genuinely reference that bus.
    atManchester.foreach { (component, id) =>
      assertEquals(n.require(component).string("bus", id), "Manchester")
    }
    // Branches are not "attached" — they have bus0/bus1, not bus.
    assert(!atManchester.exists((c, _) => c == "Line" || c == "Link"))
  }

  test("an isolated bus is reported") {
    assume(available, "goldens missing")
    // The positive case. Asserting only that the goldens have none would pass
    // for `def isolatedBuses(n) = IndexedSeq.empty`, so the branch that actually
    // surfaces the infeasibility would never have run.
    val orphaned = synthetic(
      IndexedSeq("connected0", "connected1", "nowhere"),
      IndexedSeq("connected0" -> "connected1"),
    )
    assertEquals(Topology.isolatedBuses(orphaned), IndexedSeq("nowhere"))
  }

  test("neither reference network has an isolated bus") {
    assume(available, "goldens missing")
    List("ac-dc-meshed", "storage-hvdc").foreach { name =>
      assertEquals(Topology.isolatedBuses(network(name)), IndexedSeq.empty, name)
    }
  }

  test("a branch referencing an unknown bus is rejected") {
    assume(available, "goldens missing")
    // Silently skipping it would split an island and yield a plausible
    // decomposition with the wrong slack count -- no error, wrong answer.
    val broken = synthetic(IndexedSeq("a", "b"), IndexedSeq("a" -> "typo"))
    val failure = intercept[CsvReader.MalformedNetwork](Topology.subNetworks(broken))
    assert(failure.getMessage.contains("typo"), failure.getMessage)
  }

  test("an island whose buses disagree about carrier is flagged") {
    assume(available, "goldens missing")
    // PyPSA warns here rather than failing, and labels the island with the first
    // bus's carrier. Matching that, but surfacing the fact instead of hiding it
    // behind a single label.
    val mixed = synthetic(
      IndexedSeq("a", "b"),
      IndexedSeq("a" -> "b"),
      carriers = IndexedSeq("AC", "DC"),
    )
    val island = Topology.subNetworks(mixed).head
    assert(island.mixedCarriers, "a mixed-carrier island was not flagged")
    assertEquals(island.carrier, "AC", "the label should be the first bus's carrier")

    // And a well-formed island is not flagged.
    assert(Topology.subNetworks(network("ac-dc-meshed")).forall(!_.mixedCarriers))
  }

  test("an empty network has no sub-networks rather than failing") {
    assume(available, "goldens missing")
    assertEquals(Topology.subNetworks(Network.empty("empty", schema)), IndexedSeq.empty)
  }
