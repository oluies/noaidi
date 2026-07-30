package org.noaidi.network

import java.nio.file.{Files, Path, Paths}

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

  test("a chain of passive branches forms one island") {
    assume(available, "goldens missing")
    // Path compression makes this the interesting shape for the union-find, and
    // a series chain is what a radial distribution feeder looks like.
    val n     = network("ac-dc-meshed")
    val acIsl = Topology.subNetworks(n).filter(_.carrier == "AC")
    // Three AC islands, one of which is a single isolated-by-passive-branches
    // bus reached only over a link.
    assertEquals(acIsl.size, 3)
    assert(acIsl.exists(_.size == 1), s"expected a singleton AC island, got ${acIsl.map(_.size)}")
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

  test("isolated buses are reported") {
    assume(available, "goldens missing")
    // Neither reference network has one, which is the expected result and worth
    // asserting so the check is known to run rather than assumed.
    List("ac-dc-meshed", "storage-hvdc").foreach { name =>
      assertEquals(Topology.isolatedBuses(network(name)), IndexedSeq.empty, name)
    }
  }

  test("an empty network has no sub-networks rather than failing") {
    assume(available, "goldens missing")
    assertEquals(Topology.subNetworks(Network.empty("empty", schema)), IndexedSeq.empty)
  }
