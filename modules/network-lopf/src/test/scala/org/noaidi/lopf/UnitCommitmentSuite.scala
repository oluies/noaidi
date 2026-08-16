package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema}
import org.noaidi.prima.{BnbParams, MilpStatus, PdhgParams}

/** Unit commitment against PyPSA's own mixed-integer solve.
  *
  * The fixture is purpose-built and its constraints were checked to bind before
  * anything was written against it: `mid` is off at snapshots 3-5 so the status
  * frame is not all-ones, and its `min_down_time` changes the optimum by 300
  * (16700 to 17000). Both matter — an all-ones schedule would be reproduced by
  * an implementation that never switches anything off, and a non-binding
  * constraint is one this suite could not tell apart from its absence.
  */
class UnitCommitmentSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))("optimize")

  private val params = BnbParams(
    lp = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000),
    maxNodes = 20_000,
  )

  private def frameValue(frame: ujson.Value, row: Int, column: String): Double =
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index) match
      case ujson.Num(v)                             => v
      case o: ujson.Obj if o.value.contains("$nan") => Double.NaN
      case other                                    => fail(s"unexpected golden value $other")

  test("the objective matches PyPSA's") {
    assume(available, "goldens missing — run reference/generate_goldens.py")
    val expected = results("unit-commitment")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")

    val result = UnitCommitment.solve(network("unit-commitment"), params)
    assertEquals(result.status, MilpStatus.Optimal, s"${result.solution}")
    assertEqualsDouble(result.objective, expected("objective").num, 1e-6 * 17000.0, s"${result.solution}")
  }

  test("the commitment schedule matches PyPSA's") {
    assume(available, "goldens missing")
    // The schedule, not just its cost. Several schedules can cost nearly the
    // same, so agreeing on the objective alone would not establish that the
    // binaries landed where PyPSA put them.
    val n        = network("unit-commitment")
    val expected = results("unit-commitment")("generator_status")
    val result   = UnitCommitment.solve(n, params)

    // Restricted to the committable units. PyPSA's status frame covers every
    // generator and reports 0 for a non-committable one -- `peak` here -- which
    // is a placeholder rather than a decision, since such a unit has no status.
    // Comparing it would assert agreement about something neither model decides.
    val gens = n.require("Generator")
    val committable = gens.ids.filter(g => gens.bool("committable", g))
    assert(committable.nonEmpty, "the fixture has no committable generator")

    var offCells = 0
    n.snapshots.indices.foreach { t =>
      committable.foreach { g =>
        val theirs = frameValue(expected, t, g) > 0.5
        if !theirs then offCells += 1
        assertEquals(result.committed(g, t), theirs, s"snapshot $t, generator $g")
      }
    }
    // Otherwise an implementation that commits everything always would pass.
    assert(offCells >= 3, s"only $offCells off-cells in the golden; the schedule is trivial")
  }

  test("generator dispatch matches PyPSA's") {
    assume(available, "goldens missing")
    val n        = network("unit-commitment")
    val expected = results("unit-commitment")("generator_p")
    val result   = UnitCommitment.solve(n, params)

    n.snapshots.indices.foreach { t =>
      n.require("Generator").ids.foreach { g =>
        assertEqualsDouble(result.output(g, t), frameValue(expected, t, g), 1e-4, s"snapshot $t, $g")
      }
    }
  }

  test("a committed unit respects its minimum output, and an idle one produces nothing") {
    assume(available, "goldens missing")
    // The disjunction that makes this a MILP rather than an LP: off, or on and
    // at least p_min_pu * p_nom. A solver that treated p_min_pu as an ordinary
    // lower bound could not produce the zeros, and one that ignored it entirely
    // would put output below the minimum.
    val n      = network("unit-commitment")
    val gens   = n.require("Generator")
    val result = UnitCommitment.solve(n, params)
    var onCells = 0

    n.snapshots.indices.foreach { t =>
      gens.ids.foreach { g =>
        val p = result.output(g, t)
        if result.committed(g, t) then
          val floor = gens.float("p_nom", g) * gens.valueAt("p_min_pu", g, t)
          assert(p >= floor - 1e-4, s"snapshot $t, $g: $p below its minimum $floor")
          onCells += 1
        else assertEqualsDouble(p, 0.0, 1e-4, s"snapshot $t, $g is off but producing $p")
      }
    }
    assert(onCells > 0)
  }

  test("supply meets demand at every snapshot") {
    assume(available, "goldens missing")
    val n      = network("unit-commitment")
    val loads  = n.require("Load")
    val result = UnitCommitment.solve(n, params)

    n.snapshots.indices.foreach { t =>
      val supplied = n.require("Generator").ids.map(result.output(_, t)).sum
      val demanded = loads.ids.map(loads.valueAt("p_set", _, t)).sum
      assertEqualsDouble(supplied, demanded, 1e-4, s"snapshot $t")
    }
  }

  test("minimum down time is enforced, and it is what makes this fixture cost 17000") {
    assume(available, "goldens missing")
    // The constraint binds: PyPSA gives 16700 without it and 17000 with it. So
    // an implementation that dropped it would come out 300 cheap -- cheaper than
    // the network permits, which is the direction that cannot be caught
    // downstream. Checked structurally as well as by cost: every off-run of a
    // unit with min_down_time > 1 must be at least that long.
    val n      = network("unit-commitment")
    val gens   = n.require("Generator")
    val result = UnitCommitment.solve(n, params)

    gens.ids.foreach { g =>
      val minDown = if gens.static.contains("min_down_time") then gens.int("min_down_time", g) else 0
      if minDown > 1 then
        val schedule = n.snapshots.indices.map(result.committed(g, _))
        // Run-lengths of consecutive off snapshots, ignoring one that runs to the
        // end of the horizon since it is not required to complete.
        var runStart = -1
        schedule.zipWithIndex.foreach { (on, t) =>
          if !on && runStart < 0 then runStart = t
          if on && runStart >= 0 then
            assert(t - runStart >= minDown, s"$g was off for ${t - runStart} < $minDown from $runStart")
            runStart = -1
        }
    }
  }

  test("a network with ramp limits is rejected rather than under-constrained") {
    assume(available, "goldens missing")
    // Ramp limits are ordinary in a commitment study and are not modelled.
    // Ignoring one gives a schedule the network cannot follow, and it comes out
    // cheaper, so it has to be refused.
    val dir = Files.createTempDirectory("noaidi-uc-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("unit-commitment")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    val target = dir.resolve("generators.csv")
    val lines  = Files.readString(target).linesIterator.toIndexedSeq
    val edited = (lines.head + ",ramp_limit_up") +: lines.tail.map(_ + ",0.5")
    Files.writeString(target, edited.mkString("\n") + "\n")

    val broken  = CsvReader.read(dir, schema, "unit-commitment")
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("ramp"), failure.getMessage)
  }

  test("a ramp limit arriving only as a series is rejected too") {
    assume(available, "goldens missing")
    // The regression, at the site where it happened. The test above appends a
    // *static* `ramp_limit_up`, which the old `static.contains` sweep already
    // caught; this one writes the series file with no static counterpart, which
    // it did not. `ramp_limit_up` is `static or series` in PyPSA -- the guard
    // one block above it in the source says so about `stand_by_cost` and the
    // ramp check asserted the opposite -- so without this, reverting
    // `UnitCommitment.reject` to the old sweep leaves every test green.
    val dir = Files.createTempDirectory("noaidi-uc-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("unit-commitment")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }

    // Deliberately not touching generators.csv: the static column stays absent,
    // so a reader that only consults it sees no limit anywhere.
    val original   = CsvReader.read(dir, schema, "unit-commitment")
    val generators = original.require("Generator").ids
    val header     = ("" +: generators).mkString(",")
    val rows =
      original.snapshots.indices.map(t => (t.toString +: generators.map(_ => "0.4")).mkString(","))
    Files.writeString(dir.resolve("generators-ramp_limit_up.csv"), (header +: rows).mkString("\n") + "\n")

    val broken = CsvReader.read(dir, schema, "unit-commitment")
    assert(!broken.require("Generator").static.contains("ramp_limit_up"),
      "the fixture gained a static column, so this no longer tests the series path")
    assert(broken.require("Generator").series.contains("ramp_limit_up"),
      "the series file was not read, so this asserts nothing")

    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("ramp"), failure.getMessage)
  }

  test("a network with transmission is refused rather than solved bus-by-bus") {
    assume(available, "goldens missing")
    // The balance rows carry no flow variables, so a multi-bus network would be
    // solved with every bus forced to balance locally: import and export are
    // deleted, and the answer comes back either spuriously infeasible or far
    // more expensive than the real network's. Nothing caught this because the
    // only fixture is a single bus with no branches.
    val dir = Files.createTempDirectory("noaidi-uc-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("unit-commitment")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    Files.writeString(
      dir.resolve("buses.csv"),
      Files.readString(dir.resolve("buses.csv")).stripTrailing + "\nfar,110.0\n",
    )
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nl,bus,far,1.0,0.0,500.0\n")

    val broken  = CsvReader.read(dir, schema, "unit-commitment")
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("Line"), failure.getMessage)
  }

  test("a load attached to a nonexistent bus is refused") {
    assume(available, "goldens missing")
    // It would match no balance row and simply vanish, and the schedule would
    // come out cheaper with no diagnostic. Lopf already guarded this; leaving
    // one entry point loud and the other silently wrong is the thing to avoid.
    val dir = Files.createTempDirectory("noaidi-uc-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("unit-commitment")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    // Only the data rows: a blanket replace would rename the `bus` *column* too,
    // which is a different (also rejected) error and not the one under test.
    val loads = dir.resolve("loads.csv")
    val rows  = Files.readString(loads).linesIterator.toIndexedSeq
    Files.writeString(
      loads,
      (rows.head +: rows.tail.map(_.replace(",bus", ",buss"))).mkString("\n") + "\n",
    )

    val broken  = CsvReader.read(dir, schema, "unit-commitment")
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("buss"), failure.getMessage)
  }

  test("a unit part-way through its minimum up time is held on at the horizon head") {
    assume(available, "goldens missing")
    // Two earlier versions of this test were vacuous, and the second taught me
    // why the obvious construction does not work. Solving the stock fixture
    // asserts nothing, because `base` runs throughout regardless. Making `base`
    // expensive does not help either: a `min_up_time` of 3 means starting it
    // later locks it on for three snapshots, so keeping it on from the start is
    // genuinely cheaper and it stays committed with or without the residual rows
    // -- PyPSA agrees, at the same 91500.
    //
    // So the case is built rather than mutated: one expensive committable unit
    // that is never needed, beside ample cheap capacity. Nothing but the residual
    // condition can justify running it. PyPSA holds it on at t = 0 and 1 at its
    // 50 MW minimum and releases it after, for 57000 against the 8000 an
    // unconstrained schedule costs. Disabling the residual rows makes this fail,
    // which is what the previous versions could not do.
    val dir = Files.createTempDirectory("noaidi-uc-residual-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nbus,110.0,AC\n")
    Files.writeString(
      dir.resolve("generators.csv"),
      "name,bus,p_nom,p_min_pu,marginal_cost,committable,min_up_time,min_down_time\n" +
        "unit,bus,100.0,0.5,500.0,True,3,1\n" +
        "cheap,bus,500.0,0.0,10.0,False,0,0\n",
    )
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nd,bus,200.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,0\n1,1\n2,2\n3,3\n")

    val n      = CsvReader.read(dir, schema, "uc-residual")
    val gens   = n.require("Generator")
    val result = UnitCommitment.solve(n, params)
    assertEquals(result.status, MilpStatus.Optimal, s"${result.solution}")

    val forced = math.max(gens.int("min_up_time", "unit") - gens.int("up_time_before", "unit"), 0)
    assertEquals(forced, 2, "the fixture should force exactly two snapshots")

    (0 until forced).foreach { t =>
      assert(result.committed("unit", t), s"unit must be held on at snapshot $t")
      // Held on means held at its minimum, not merely flagged.
      assertEqualsDouble(result.output("unit", t), 50.0, 1e-4, s"snapshot $t")
    }
    // And released once the residual expires -- otherwise this would pass for an
    // implementation that commits everything always.
    (forced until n.snapshots.length).foreach { t =>
      assert(!result.committed("unit", t), s"unit should be free to shut down at snapshot $t")
    }
    // PyPSA's objective for this network. An unconstrained schedule costs 8000.
    assertEqualsDouble(result.objective, 57000.0, 1e-3, s"${result.solution}")
  }

  /** A copy of the commitment golden with extra or rewritten files. */
  private def fixtureWith(files: (String, String)*): Network =
    val dir = Files.createTempDirectory("noaidi-uc-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("unit-commitment")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    files.foreach((name, content) => Files.writeString(dir.resolve(name), content))
    CsvReader.read(dir, schema, "unit-commitment")

  test("a global constraint is refused, naming its own reason") {
    assume(available, "goldens missing")
    // Lopf models primary-energy caps and this does not, so a commitment network
    // carrying one would be solved with the cap deleted -- cheaper than the truth.
    // The message has to name that: routing it into the branch-flow diagnostic
    // reported "this network carries GlobalConstraint (1)" under a heading about
    // transmission, which states the wrong cause.
    val broken = fixtureWith(
      "global_constraints.csv" ->
        "name,type,carrier_attribute,sense,constant\nco2_limit,primary_energy,co2_emissions,<=,1000.0\n"
    )
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("global constraint"), failure.getMessage)
    assert(!failure.getMessage.contains("branch flows"), failure.getMessage)
  }

  test("a series-only stand-by cost is refused") {
    assume(available, "goldens missing")
    // The shape that slipped past a `static.contains` check: the attribute is
    // `static or series`, and PyPSA writes it as its own file when only the time
    // series is non-default. There is no static column here at all, so a guard
    // reading only the static value finds nothing and the cost is dropped --
    // over-committing becomes free and the schedule prices below the truth.
    val broken = fixtureWith(
      "generators-stand_by_cost.csv" ->
        ",base\n0,0.0\n1,0.0\n2,25.0\n3,0.0\n4,0.0\n5,0.0\n6,0.0\n7,0.0\n"
    )
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("stand_by_cost"), failure.getMessage)
  }

  test("a priced attribute is refused even with no snapshots to sweep") {
    assume(available, "goldens missing")
    // The per-snapshot sweep replaced a static read, which narrowed the guard: a
    // network with no snapshots evaluated nothing at all. Degenerate, but a guard
    // whose whole purpose is to let nothing priced through should not have a
    // hole. Built from scratch rather than by emptying the golden's snapshots,
    // because the reader rightly refuses a series file with more rows than the
    // network has snapshots.
    val dir = Files.createTempDirectory("noaidi-uc-empty-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nbus,110.0,AC\n")
    Files.writeString(
      dir.resolve("generators.csv"),
      "name,bus,p_nom,p_min_pu,marginal_cost,committable,stand_by_cost\n" +
        "unit,bus,100.0,0.5,10.0,True,25.0\n",
    )
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nd,bus,50.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n")

    val broken  = CsvReader.read(dir, schema, "uc-empty")
    assertEquals(broken.snapshots.length, 0, "the fixture should have no snapshots")
    val failure = intercept[UnitCommitment.UnsupportedNetwork](UnitCommitment.solve(broken, params))
    assert(failure.getMessage.contains("stand_by_cost"), failure.getMessage)
  }

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }
