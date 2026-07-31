package org.noaidi.pf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Role, Schema, Topology}

/** Linear power flow against PyPSA's `n.lpf()`.
  *
  * These goldens were captured well before this module existed, so nothing here
  * was fitted to them — which is the whole value of having generated them first.
  * All three networks are checked, and `storage-hvdc` matters in particular: it is
  * the one fixture LOPF cannot touch, because its storage units couple snapshots.
  * LPF takes dispatch as given, so storage is no obstacle and the network becomes
  * usable evidence rather than a rejection test.
  *
  * Angles, line flows and the slack generator's dispatch are all compared. The
  * last of those is the one that catches a slack-selection error: choosing the
  * wrong slack bus shifts every angle by a constant and leaves the flows correct,
  * so an angle-only comparison would call it a scaling bug and a flow-only
  * comparison would not notice at all.
  */
class LinearPowerFlowSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  // `ac-dc-co2` is deliberately absent: it differs from `ac-dc-dispatch` only in
  // generator marginal costs and a CO2 cap, neither of which is an input to a
  // power flow, so its LPF golden is identical and would add a duplicate run
  // rather than coverage.
  // `scigrid-de` is deliberately absent, and it is the network this module most
  // wants: 585 buses, 852 lines and 96 transformers against the nine-bus
  // fixtures everything else runs on. It cannot be read yet. All 852 of its
  // lines carry `x = 0` and a *line type*, so the impedance comes from
  // `LineType` + `length` + `num_parallel`, and PyPSA omits the standard type
  // library from its export because its own reader repopulates it. Line-type
  // expansion is the next piece; the transformer conversion this commit adds is
  // validated on `transformer-levels` instead, which isolates it.
  private val networks =
    List("ac-dc-meshed", "ac-dc-dispatch", "storage-hvdc", "transformer-levels")

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def lpf(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))("lpf")

  private def manifest: ujson.Value =
    ujson.read(Files.readString(goldens.resolve("manifest.json")))

  /** A golden frame cell, by row and entity name.
    *
    * The row index is checked against the golden's own `index` rather than
    * trusted. The two artefacts spell timestamps differently -- `2015-01-01
    * 00:00:00` in snapshots.csv against `2015-01-01T00:00:00` in the JSON -- so
    * positional indexing is right today and silently compares the wrong rows the
    * moment either side reorders.
    */
  private def frameValue(frame: ujson.Value, row: Int, column: String, snapshot: String): Double =
    // A snapshot label need not be a timestamp: `transformer-levels` indexes by
    // integers, which arrive as JSON numbers rather than strings.
    val stamp = (frame("index")(row) match
      case ujson.Str(v) => v
      case other        => other.toString
    ).replace('T', ' ')
    assertEquals(stamp, snapshot.replace('T', ' '), s"golden row $row is not the expected snapshot")
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index) match
      case ujson.Num(v)                             => v
      case o: ujson.Obj if o.value.contains("$nan") => Double.NaN
      case other                                    => fail(s"unexpected golden value $other")

  networks.foreach { name =>
    test(s"voltage angles match PyPSA on $name") {
      assume(available, "goldens missing — run reference/generate_goldens.py")
      val expected = lpf(name)
      assert(!expected.obj.contains("error"), s"golden lpf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = LinearPowerFlow.solve(n)
      val frame  = expected("bus_v_ang")

      // Absolute, in radians: these are O(1e-3), so a relative tolerance on a
      // slack bus sitting at exactly zero would be meaningless.
      n.snapshots.indices.foreach { t =>
        n.require("Bus").ids.foreach { bus =>
          assertEqualsDouble(
            result.voltageAngle(bus, t),
            frameValue(frame, t, bus, n.snapshots(t)),
            1e-9,
            s"$name snapshot $t, bus $bus",
          )
        }
      }
    }

    test(s"line flows match PyPSA on $name") {
      assume(available, "goldens missing")
      val expected = lpf(name)
      assert(!expected.obj.contains("error"), s"golden lpf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = LinearPowerFlow.solve(n)
      val frame  = expected("line_p0")

      n.snapshots.indices.foreach { t =>
        n.require("Line").ids.foreach { line =>
          assertEqualsDouble(
            result.flow("Line", line, t),
            frameValue(frame, t, line, n.snapshots(t)),
            1e-6,
            s"$name snapshot $t, line $line",
          )
        }
      }
    }

    test(s"the slack picked matches PyPSA on $name") {
      assume(available, "goldens missing")
      // Straight from the manifest, which records PyPSA's own slack_bus per
      // sub-network. Worth asserting separately from the angles because it is the
      // one convention here that documentation does not state and that a wrong
      // guess hides behind a constant offset.
      val n        = network(name)
      val expected = manifest("networks")(name)("sub_networks").arr
      val actual   = Slack.all(n, Topology.subNetworks(n))

      assertEquals(actual.length, expected.length, s"$name sub-network count")

      // Matched on bus membership rather than position: the two sides sort their
      // islands independently, and pairing by index would be an accidental pass.
      expected.foreach { sub =>
        val buses = sub("buses").arr.map(_.str).toIndexedSeq
        val mine  = actual.find(_.subNetwork.buses == buses)
        assert(mine.isDefined, s"$name has no sub-network ${buses.mkString("{", ", ", "}")}")
        assertEquals(mine.get.bus, sub("slack_bus").str, s"$name slack for $buses")
      }
    }

    test(s"generator dispatch matches PyPSA on $name") {
      assume(available, "goldens missing")
      // The slack generator carries its whole island's net demand, so this is
      // where a slack-attribution error surfaces as a number rather than an
      // offset. On ac-dc-meshed, Manchester Wind's 1308.81 is exactly the sum of
      // the London, Norwich and Manchester loads.
      val expected = lpf(name)
      assert(!expected.obj.contains("error"), s"golden lpf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = LinearPowerFlow.solve(n)
      val frame  = expected("generator_p")

      n.snapshots.indices.foreach { t =>
        n.require("Generator").ids.foreach { g =>
          assertEqualsDouble(
            result.dispatch("Generator", g, t),
            frameValue(frame, t, g, n.snapshots(t)),
            1e-6,
            s"$name snapshot $t, generator $g",
          )
        }
      }
    }

    test(s"every bus balances on $name") {
      assume(available, "goldens missing")
      // Independent of the goldens: whatever the angles are, the power leaving a
      // bus along its branches must equal the power injected at it. This is the
      // check that would catch a susceptance used in one place and not another,
      // which a comparison against PyPSA could only report as a diffuse mismatch.
      val n      = network(name)
      val result = LinearPowerFlow.solve(n)
      // Every passive branch, not only lines. A transformer carries flow the same
      // way, so summing lines alone made the balance look violated the moment a
      // network had one -- the test's own blind spot, not the model's.
      val branches = n.tables.values.filter(t => Role.of(t.spec) == Role.PassiveBranch).toIndexedSeq

      n.snapshots.indices.foreach { t =>
        n.require("Bus").ids.foreach { bus =>
          var outflow = 0.0
          branches.foreach { table =>
            table.ids.foreach { b =>
              if table.string("bus0", b) == bus then outflow += result.flow(table.spec.name, b, t)
              if table.string("bus1", b) == bus then outflow -= result.flow(table.spec.name, b, t)
            }
          }
          assertEqualsDouble(outflow, result.busPower(bus, t), 1e-6, s"$name snapshot $t, bus $bus")
        }
      }
    }
  }

  test("total injection is zero within every sub-network") {
    assume(available, "goldens missing")
    // A lossless linear flow moves power around, it does not create it. Asserted
    // per island rather than network-wide, because a network-wide sum would cancel
    // an error that pushed power from one island into another — exactly what a
    // mishandled link would do.
    val n      = network("ac-dc-meshed")
    val result = LinearPowerFlow.solve(n)

    Topology.subNetworks(n).foreach { sub =>
      n.snapshots.indices.foreach { t =>
        val total = sub.buses.map(result.busPower(_, t)).sum
        assertEqualsDouble(total, 0.0, 1e-6, s"sub-network ${sub.buses} at snapshot $t")
      }
    }
  }

  test("an island with no generator is reported rather than hidden") {
    assume(available, "goldens missing")
    // ac-dc-meshed's DC island has only converters attached, and their p_set is
    // unset. So it is balanced and solvable, but there is no generator to
    // attribute the slack power to — a legal state PyPSA merely warns about. The
    // result exposes it instead of quietly assigning the power somewhere.
    val n      = network("ac-dc-meshed")
    val result = LinearPowerFlow.solve(n)
    val dc     = result.slacks.filter(_.subNetwork.carrier == "DC")

    assertEquals(dc.length, 1)
    assertEquals(dc.head.generator, None)
    assertEquals(dc.head.bus, "Norwich DC")
    // And it really is balanced, which is why this is reportable rather than fatal.
    n.snapshots.indices.foreach { t =>
      assertEqualsDouble(result.busPower(dc.head.bus, t), 0.0, 1e-9, s"snapshot $t")
    }
  }

  test("a DC island takes its susceptance from resistance, numerically") {
    assume(available, "goldens missing")
    // The AC/DC split is pinned only *negatively* by the goldens, which is worth
    // being explicit about: every DC island in them has zero injection at every
    // bus, so `B theta = 0` and theta = 0 for any positive susceptance whatsoever.
    // Choosing reactance there fails only because the DC lines carry x = 0 and the
    // guard throws. Nothing checks the magnitude.
    //
    // So: a two-bus DC island with an actual load. v_nom = 2 and r = 0.5 give
    // susceptance v_nom^2/r = 8, and a 4 MW load at B puts theta_B at -0.5 rad
    // with 4 MW flowing A -> B. Reading `x` instead would divide by zero; reading
    // r without the v_nom^2 scale would give theta_B = -2.
    val dir = Files.createTempDirectory("noaidi-pf-dc-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nA,2.0,DC\nB,2.0,DC\n")
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nl,A,B,0.0,0.5,100.0\n")
    Files.writeString(dir.resolve("generators.csv"), "name,bus,control,carrier\ng,A,PQ,wind\n")
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nd,B,4.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    val n      = CsvReader.read(dir, schema, "pf-dc")
    val result = LinearPowerFlow.solve(n)

    assertEqualsDouble(result.voltageAngle("A", 0), 0.0, 1e-12, "slack angle")
    assertEqualsDouble(result.voltageAngle("B", 0), -0.5, 1e-12, "v_nom^2/r susceptance")
    assertEqualsDouble(result.flow("Line", "l", 0), 4.0, 1e-9)
    // The slack generator serves the load, and reports it as a positive dispatch.
    assertEqualsDouble(result.dispatch("Generator", "g", 0), 4.0, 1e-9)
  }

  test("a load reports PyPSA's sign, not its injection") {
    assume(available, "goldens missing")
    // `sign` is -1 for a Load, and it belongs to the injection arithmetic alone.
    // Folding it into the recorded dispatch made a 1000 MW load report -1000
    // where PyPSA's `loads_t.p` says +1000 -- under an accessor documented as
    // returning MW, and disagreeing with the sibling accessor in Lopf. Nothing
    // caught it because the golden comparison only ever reads generators, all of
    // which carry sign = +1.
    val n      = network("ac-dc-meshed")
    val result = LinearPowerFlow.solve(n)
    val loads  = n.require("Load")

    n.snapshots.indices.foreach { t =>
      loads.ids.foreach { l =>
        val demand = loads.valueAt("p_set", l, t)
        assertEqualsDouble(result.dispatch("Load", l, t), demand, 1e-9, s"snapshot $t, load $l")
        assert(demand > 0.0, s"fixture load $l is zero, so the sign cannot be observed")
      }
    }
  }

  test("a one-port attached to a nonexistent bus is rejected") {
    assume(available, "goldens missing")
    // Branch endpoints were validated and one-ports were not, so a Load with a
    // stale bus matched nothing in `injections` and simply vanished: the island's
    // demand dropped and the angles came out those of a different network. The
    // sibling LOPF module already rejected this, which made the same broken input
    // loud through one entry point and silently wrong through the other.
    val broken  = mutate("ac-dc-meshed", "loads.csv", _.replace(",Manchester,", ",Mancester,"))
    val failure = intercept[LinearPowerFlow.UnsupportedNetwork](LinearPowerFlow.solve(broken))
    assert(failure.getMessage.contains("Mancester"), failure.getMessage)
  }

  test("a bus with no v_nom is refused rather than rescaled") {
    assume(available, "goldens missing")
    // v_nom^2 does not cancel, so a branch whose base is missing ends up scaled
    // differently from every other branch in its island and every angle in that
    // island is wrong. Previously this fell back to a different per-unit base
    // without a word.
    val broken = mutate("ac-dc-meshed", "buses.csv", _.replace(",380.0,", ",0.0,"))
    val failure = intercept[LinearPowerFlow.UnsupportedNetwork](LinearPowerFlow.solve(broken))
    assert(failure.getMessage.contains("v_nom"), failure.getMessage)
  }

  /** A copy of a golden network with one file rewritten, read back through the
    * real parse path.
    */
  private def mutate(name: String, file: String, edit: String => String): Network =
    val dir = Files.createTempDirectory("noaidi-pf-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve(name)
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    val target = dir.resolve(file)
    val before = Files.readString(target)
    val after  = edit(before)
    assertNotEquals(after, before, s"the edit to $file changed nothing")
    Files.writeString(target, after)
    CsvReader.read(dir, schema, name)

  test("a zero impedance in the relevant attribute is refused") {
    assume(available, "goldens missing")
    // ac-dc-meshed's AC lines carry r = 0 and its DC lines carry x = 0 — PyPSA
    // warns about both on import. So picking the wrong attribute per carrier does
    // not degrade the answer, it divides by zero, and this asserts the refusal is
    // explicit rather than an Infinity propagating into the angles.
    val n = network("ac-dc-meshed")
    val lines = n.require("Line")
    val acLine = lines.ids.find(l => lines.float("x", l) > 0.0 && lines.float("r", l) == 0.0)
    assert(acLine.isDefined, "fixture no longer has an AC line with r = 0")

    val failure = intercept[LinearPowerFlow.UnsupportedNetwork] {
      // Reuse the module's own guard by asking for a DC reading of an AC branch.
      LinearPowerFlow.solve(swapCarrier(n))
    }
    assert(failure.getMessage.contains("no susceptance"), failure.getMessage)
  }

  /** The same network with every bus relabelled DC.
    *
    * That makes the AC lines' `r = 0` the attribute the flow reads, which is the
    * condition the guard exists for. Built by rewriting `buses.csv` and reading it
    * back, so the fixture travels the real parse path.
    */
  private def swapCarrier(n: Network): Network =
    val dir    = Files.createTempDirectory("noaidi-pf-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("ac-dc-meshed")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    val buses = dir.resolve("buses.csv")
    val text  = Files.readString(buses)
    val edited = text.linesIterator.zipWithIndex
      .map { (line, i) => if i == 0 then line else line.replace(",AC,", ",DC,") }
      .mkString("\n") + "\n"
    assertNotEquals(edited, text, "no AC bus was relabelled; the fixture is not exercised")
    Files.writeString(buses, edited)
    CsvReader.read(dir, schema, "ac-dc-meshed")

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }

  test("a transformer is referred to its own rating, not to voltage") {
    assume(available, "goldens missing")
    // The conversion transformers were refused for. A transformer's per-unit base
    // is `s_nom`, a line's is `v_nom^2`, and using the line formula for a
    // transformer gives a plausible answer rather than a failure -- which is why
    // it was refused rather than approximated. Asserted directly against the
    // numbers as well as through the golden comparison above, so the reason is
    // visible and not buried in a sweep.
    val n = network("transformer-levels")
    val expected = lpf("transformer-levels")("transformer_p0")

    n.snapshots.indices.foreach { t =>
      n.require("Transformer").ids.foreach { id =>
        assertEqualsDouble(
          result(n).flow("Transformer", id, t),
          frameValue(expected, t, id, n.snapshots(t)),
          1e-6,
          s"snapshot $t, transformer $id",
        )
      }
    }

    // And the two bases really do differ here, so the comparison is not passing
    // because they happen to coincide.
    val transformers = n.require("Transformer")
    val lines        = n.require("Line")
    val tByRating    = transformers.float("s_nom", "t1") / transformers.float("x", "t1")
    val vNom         = n.require("Bus").float("v_nom", "hv1")
    val tByVoltage   = vNom * vNom / transformers.float("x", "t1")
    assert(
      math.abs(tByRating - tByVoltage) / tByVoltage > 0.5,
      s"the fixture no longer distinguishes the two per-unit bases ($tByRating vs $tByVoltage)",
    )
    assert(lines.size > 0)
  }

  private def result(n: Network): LpfResult = LinearPowerFlow.solve(n)
