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

  /** Every golden network.
    *
    * `ac-dc-dispatch` and `ac-dc-co2` were missing here, and they are the only two
    * carrying solved output series and output columns -- exactly the shape the
    * reader and writer had never been exercised on.
    */
  private val goldenNetworks =
    List("ac-dc-meshed", "ac-dc-dispatch", "ac-dc-co2", "ac-pf-pv", "unit-commitment", "storage-hvdc")

  goldenNetworks.foreach { name =>
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

  /** Write a network to disk and read it back through CsvReader.
    *
    * Hand-building a ComponentTable bypasses the reader, which is where column
    * typing happens — so a defect in inference is invisible to a synthetic
    * fixture. These cases go through the real path.
    */
  private val scratch = scala.collection.mutable.ArrayBuffer.empty[Path]

  private def viaReader(files: Map[String, String]): Network =
    val dir = Files.createTempDirectory("noaidi-topo-")
    scratch += dir
    files.foreach { (name, body) =>
      Files.writeString(dir.resolve(name), body)
    }
    // Only the schema comes from the goldens; the network itself is written here.
    CsvReader.read(dir, schema, "fixture")

  override def afterAll(): Unit =
    scratch.foreach { dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
    }

  test("a multi-port link with numeric bus names reads correctly") {
    assume(available, "goldens missing")
    // storage-hvdc names its buses 0 to 5, so a bus2 column there holds values
    // that all parse as numbers. Inferring Floats would break comparison against
    // the bus table and render "0" back as "0.0" -- and a hand-built fixture
    // using Column.Strings could never show it.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\n0,380.0,AC\n1,380.0,AC\n2,380.0,AC\n",
      "links.csv"     -> "name,bus0,bus1,bus2\nk,0,1,2\n",
    ))

    val links = n.require("Link")
    assertEquals(Topology.portsOf(links), IndexedSeq("bus0", "bus1", "bus2"))
    assertEquals(links.string("bus2", "k"), "2", "the port was not read as an identifier")
    // No dangling references: every port names a real bus.
    assertEquals(Topology.danglingReferences(n), IndexedSeq.empty)
    assertEquals(Topology.isolatedBuses(n), IndexedSeq.empty)
  }

  test("a blank endpoint is dangling, not an absent edge") {
    assume(available, "goldens missing")
    // bus0/bus1 have no meaningful default, so an empty cell is a missing
    // reference. Dropping it silently splits the island exactly as a wrong name
    // would -- which is the failure this module is supposed to make impossible.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\na,380.0,AC\nb,380.0,AC\n",
      "lines.csv"     -> "name,bus0,bus1\nl0,a,\n",
    ))
    val dangling = Topology.danglingReferences(n)
    assertEquals(dangling.size, 1, s"expected one dangling endpoint, got $dangling")
    assertEquals(dangling.head._3, "bus1")
    intercept[CsvReader.MalformedNetwork](Topology.subNetworks(n))
  }

  test("a branch table missing an endpoint column is rejected") {
    assume(available, "goldens missing")
    // Without bus1 the branch contributes no edges and portsOf would simply
    // return a short list, so nothing would complain.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\na,380.0,AC\nb,380.0,AC\n",
      "lines.csv"     -> "name,bus0\nl0,a\n",
    ))
    val failure = intercept[CsvReader.MalformedNetwork](Topology.subNetworks(n))
    assert(failure.getMessage.contains("bus1"), failure.getMessage)
  }

  test("a stale link reference does not block decomposition") {
    assume(available, "goldens missing")
    // subNetworks reads passive branches only, so a bad reference in links.csv
    // cannot affect its answer and must not prevent it being computed.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\na,380.0,AC\nb,380.0,AC\n",
      "lines.csv"     -> "name,bus0,bus1\nl0,a,b\n",
      "links.csv"     -> "name,bus0,bus1\nk,a,gone\n",
    ))
    // Reported by the full check...
    assert(Topology.danglingReferences(n).exists((_, _, _, bus) => bus == "gone"))
    // ...but the decomposition still works.
    assertEquals(Topology.subNetworks(n).map(_.buses), IndexedSeq(IndexedSeq("a", "b")))
  }

  test("the carrier label agrees with the sorted bus list") {
    assume(available, "goldens missing")
    // The label must come from the same ordering the `buses` field uses. Reading
    // it in file order would let a mixed island report a carrier belonging to
    // none of buses.head -- here file order is b,a and sorted order is a,b.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\nb,380.0,DC\na,380.0,AC\n",
      "lines.csv"     -> "name,bus0,bus1\nl0,a,b\n",
    ))
    val island = Topology.subNetworks(n).head
    assertEquals(island.buses, IndexedSeq("a", "b"))
    assertEquals(island.carrier, "AC", "the label should match buses.head, not file order")
    assert(island.mixedCarriers)
  }

  test("an unused extra port is not a dangling reference") {
    assume(available, "goldens missing")
    // PyPSA's default for bus2 is the empty string, and it omits all-default
    // columns on export -- so a bus2 column appears as soon as one link uses it,
    // and every link that does not carries a blank. Treating those as dangling
    // would reject a valid multi-port network, which is the shape port
    // enumeration exists for.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\na,380.0,AC\nb,380.0,AC\nc,380.0,AC\n",
      "links.csv"     -> "name,bus0,bus1,bus2\nthree,a,b,c\ntwo,a,b,\n",
    ))

    // The three-port link connects all three; the two-port one leaves bus2 blank
    // and that is not an error.
    assertEquals(Topology.danglingReferences(n), IndexedSeq.empty)
    assertEquals(Topology.isolatedBuses(n), IndexedSeq.empty)
  }

  test("a links table missing bus1 does not block decomposition") {
    assume(available, "goldens missing")
    // subNetworks reads passive branches only, so it must not even enumerate the
    // ports of a controllable table -- filtering the resulting tuples rather than
    // the tables would still throw here.
    val n = viaReader(Map(
      "snapshots.csv" -> ",snapshot\n0,2015-01-01 00:00:00\n",
      "buses.csv"     -> "name,v_nom,carrier\na,380.0,AC\nb,380.0,AC\n",
      "lines.csv"     -> "name,bus0,bus1\nl0,a,b\n",
      "links.csv"     -> "name,bus0\nk,a\n",
    ))
    assertEquals(Topology.subNetworks(n).map(_.buses), IndexedSeq(IndexedSeq("a", "b")))
  }

  test("every bus belongs to exactly one sub-network") {
    assume(available, "goldens missing")
    goldenNetworks.foreach { name =>
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

  test("no reference network with branches has an isolated bus") {
    assume(available, "goldens missing")
    // Restricted to networks that have branches at all, and the restriction is
    // the point rather than an escape hatch. `unit-commitment` is a single bus
    // with no lines -- generation and load meet at one node -- so that bus is
    // isolated by definition, and it is perfectly well formed. What
    // `isolatedBuses` exists to catch is a bus left out of a network that does
    // have a graph, where the infeasibility is a mistake rather than the shape.
    // Both kinds of branch, because `isolatedBuses` considers both. A network
    // wired only with links has a real graph and would be checked by it, yet a
    // passive-only filter would drop it from this assertion.
    val withBranches = goldenNetworks.filter { n =>
      val g = network(n)
      (Topology.passiveBranches(g) ++ Topology.controllableBranches(g)).nonEmpty
    }
    assert(withBranches.size >= 5, s"only ${withBranches.size} golden networks have branches")

    withBranches.foreach { name =>
      assertEquals(Topology.isolatedBuses(network(name)), IndexedSeq.empty, name)
    }

    // And the excluded one really is the degenerate shape, not a network whose
    // branches failed to load.
    val single = network("unit-commitment")
    assertEquals(single.require("Bus").size, 1)
    assertEquals(Topology.passiveBranches(single), IndexedSeq.empty)
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
