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

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }
