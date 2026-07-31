package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema, Topology}
import org.noaidi.pf.Lodf
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** Security-constrained dispatch against PyPSA's own SCLOPF.
  *
  * The fixture is built so the contingency constraints are the '''only''' thing
  * separating this from a plain dispatch: at a rating of 150 the ordinary LOPF
  * costs 6900, exactly what it costs at 200, so the pre-contingency limits are
  * slack. The secure optimum is 14100. An implementation that dropped the N-1
  * rows would return 6900, so nothing here can pass without them.
  */
class SclopfSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def frameValue(frame: ujson.Value, row: Int, column: String): Double =
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index) match
      case ujson.Num(v)                             => v
      case o: ujson.Obj if o.value.contains("$nan") => Double.NaN
      case other                                    => fail(s"unexpected golden value $other")

  private def outagesFrom(name: String): IndexedSeq[Sclopf.Outage] =
    results(name)("sclopf")("branch_outages").arr.map(v => Sclopf.Outage("Line", v.str)).toIndexedSeq

  test("the secure objective matches PyPSA's") {
    assume(available, "goldens missing — run reference/generate_goldens.py")
    val expected = results("sclopf-triangle")("sclopf")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")

    val result = Sclopf.solve(network("sclopf-triangle"), Some(outagesFrom("sclopf-triangle")), params)
    assertEquals(result.status, SolveStatus.Optimal, s"${result.solution}")

    val target = expected("objective").num
    assertEqualsDouble(result.objective, target, 1e-6 * target, s"against PyPSA's $target")
  }

  test("security costs strictly more than plain dispatch") {
    assume(available, "goldens missing")
    // The property that makes this fixture worth having. The plain optimum is
    // 6900 and the secure one 14100, so an implementation that dropped the
    // contingency rows would land on the wrong number by more than a factor of
    // two rather than by a tolerance.
    val n      = network("sclopf-triangle")
    val plain  = Lopf.solve(n, params)
    val secure = Sclopf.solve(n, Some(outagesFrom("sclopf-triangle")), params)

    assertEquals(plain.status, SolveStatus.Optimal)
    assertEquals(secure.status, SolveStatus.Optimal)
    assertEqualsDouble(plain.objective, results("sclopf-triangle")("optimize")("objective").num, 1e-3)
    assert(
      secure.objective > plain.objective * 1.5,
      s"security barely cost anything: ${secure.objective} against ${plain.objective}",
    )
  }

  test("line flows match PyPSA's secure dispatch") {
    assume(available, "goldens missing")
    val n        = network("sclopf-triangle")
    val expected = results("sclopf-triangle")("sclopf")("line_p0")
    val result   = Sclopf.solve(n, Some(outagesFrom("sclopf-triangle")), params)

    n.snapshots.indices.foreach { t =>
      n.require("Line").ids.foreach { l =>
        assertEqualsDouble(result.dispatch("Line", l, t), frameValue(expected, t, l), 1e-3,
          s"snapshot $t, line $l")
      }
    }
  }

  test("generator dispatch matches PyPSA's secure dispatch") {
    assume(available, "goldens missing")
    val n        = network("sclopf-triangle")
    val expected = results("sclopf-triangle")("sclopf")("generator_p")
    val result   = Sclopf.solve(n, Some(outagesFrom("sclopf-triangle")), params)

    n.snapshots.indices.foreach { t =>
      n.require("Generator").ids.foreach { g =>
        assertEqualsDouble(result.dispatch("Generator", g, t), frameValue(expected, t, g), 1e-3,
          s"snapshot $t, generator $g")
      }
    }
  }

  test("every post-contingency flow stays within its rating") {
    assume(available, "goldens missing")
    // Independent of the goldens, and the property SCLOPF exists to deliver:
    // reconstruct each post-outage flow from the solved pre-outage ones and the
    // factors, and check it against the rating. A model that built the rows with
    // a wrong sign or a transposed factor could still match an objective by
    // coincidence; it cannot satisfy this.
    val n      = network("sclopf-triangle")
    val result = Sclopf.solve(n, Some(outagesFrom("sclopf-triangle")), params)
    val lines  = n.require("Line")
    val lodf   = Lodf.of(n, Topology.subNetworks(n).head)

    var checked = 0
    n.snapshots.indices.foreach { t =>
      lines.ids.foreach { outage =>
        lines.ids.filter(_ != outage).foreach { affected =>
          val before = result.dispatch("Line", affected, t)
          val shed   = result.dispatch("Line", outage, t)
          val after  = before + lodf.factor(("Line", affected), ("Line", outage)) * shed
          val rating = lines.float("s_nom", affected)
          assert(
            math.abs(after) <= rating + 1e-3,
            s"snapshot $t: losing $outage puts $after on $affected, rated $rating",
          )
          checked += 1
        }
      }
    }
    assert(checked >= 12, s"only $checked contingency pairs checked")
  }

  test("the outage factors reproduce a full redispatch") {
    assume(available, "goldens missing")
    // Checks the factors themselves rather than the model built on them: on a
    // triangle, losing one line sends its entire flow round the other two, so
    // both surviving factors must have magnitude exactly 1.
    val n    = network("sclopf-triangle")
    val lodf = Lodf.of(n, Topology.subNetworks(n).head)

    assertEqualsDouble(lodf.factor(("Line", "AB"), ("Line", "AB")), -1.0, 1e-12, "self-outage")
    Seq(("AB", "BC"), ("AB", "AC"), ("BC", "AC")).foreach { (a, b) =>
      val f = lodf.factor(("Line", a), ("Line", b))
      assertEqualsDouble(math.abs(f), 1.0, 1e-9, s"factor from $b onto $a is $f")
    }
  }

  test("a bridge is refused as a contingency") {
    assume(available, "goldens missing")
    // Removing a bridge disconnects the network, so there is no post-outage flow
    // to redistribute and the factor's denominator goes to zero. Producing an
    // infinity would put one into a constraint coefficient and the LP would come
    // back infeasible with nothing to explain it.
    val dir = Files.createTempDirectory("noaidi-sclopf-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nA,380.0,AC\nB,380.0,AC\n")
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nAB,A,B,0.1,0.0,150.0\n")
    Files.writeString(dir.resolve("generators.csv"), "name,bus,control,carrier\ng,A,Slack,wind\n")
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nl,B,50.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    val bridged = CsvReader.read(dir, schema, "bridge")

    val failure = intercept[Lodf.Unsupported](Lodf.of(bridged, Topology.subNetworks(bridged).head))
    assert(failure.getMessage.contains("bridge"), failure.getMessage)
  }

  test("an outage naming something that is not a passive branch is refused") {
    assume(available, "goldens missing")
    val n = network("sclopf-triangle")
    val failure = intercept[Sclopf.UnsupportedNetwork](
      Sclopf.build(n, Some(IndexedSeq(Sclopf.Outage("Line", "nonexistent"))))
    )
    assert(failure.getMessage.contains("nonexistent"), failure.getMessage)
  }

  test("the default outage set is every passive branch") {
    assume(available, "goldens missing")
    // Every other test passes an explicit list, so the documented default --
    // "every passive branch, matching PyPSA" -- was never exercised. On this
    // fixture it is equivalent to the explicit list, which is what makes it a
    // one-line check rather than a second golden.
    val n = network("sclopf-triangle")
    assertEqualsDouble(Sclopf.solve(n, params = params).objective, 14100.0, 1e-3)
  }

  test("an empty outage set is exactly the dispatch model") {
    assume(available, "goldens missing")
    // The documented early return. It has to happen before any factors are
    // computed, because computing them can refuse a network over a bridge that
    // was never named as a contingency.
    val n     = network("sclopf-triangle")
    val plain = Lopf.build(n)
    val empty = Sclopf.build(n, Some(IndexedSeq.empty))
    assertEquals(empty.problem.numConstraints, plain.problem.numConstraints)
    assertEquals(empty.problem.numVariables, plain.problem.numVariables)
  }

  test("a bridge elsewhere does not block a contingency that is well posed") {
    assume(available, "goldens missing")
    // The refusal belongs to the outage column, not the whole matrix. Adding a
    // radial spur -- ordinary in any real network -- used to make every SCLOPF
    // solve throw, even for an outage on the meshed triangle that has nothing to
    // do with it.
    val dir = Files.createTempDirectory("noaidi-sclopf-spur-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve("sclopf-triangle")
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    val buses = dir.resolve("buses.csv")
    Files.writeString(buses, Files.readString(buses).stripTrailing + "\nD,380.0\n")
    val lines = dir.resolve("lines.csv")
    Files.writeString(lines, Files.readString(lines).stripTrailing + "\nCD,C,D,0.1,0.0,150.0\n")
    val loads = dir.resolve("loads.csv")
    Files.writeString(loads, Files.readString(loads).stripTrailing + "\nld,D\n")

    val spurred = CsvReader.read(dir, schema, "sclopf-triangle")
    // The spur really is a bridge, so naming it is still refused.
    intercept[Lodf.Unsupported](
      Sclopf.build(spurred, Some(IndexedSeq(Sclopf.Outage("Line", "CD"))))
    )
    // But a triangle outage builds fine, which it did not before.
    val model = Sclopf.build(spurred, Some(IndexedSeq(Sclopf.Outage("Line", "AB"))))
    assert(model.problem.numConstraints > Lopf.build(spurred).problem.numConstraints)
  }

  test("no security rows cross a sub-network boundary") {
    assume(available, "goldens missing")
    // An outage cannot move flow onto a branch it has no electrical path to.
    // With two islands, the row count must be what one island alone produces --
    // otherwise the cross-island guard is emitting rows with zero coefficients or,
    // worse, non-zero ones.
    val dir = Files.createTempDirectory("noaidi-sclopf-split-")
    temporaries += dir
    Files.writeString(
      dir.resolve("buses.csv"),
      "name,v_nom,carrier\nA,380.0,AC\nB,380.0,AC\nC,380.0,AC\nX,380.0,AC\nY,380.0,AC\n",
    )
    Files.writeString(
      dir.resolve("lines.csv"),
      "name,bus0,bus1,x,r,s_nom\n" +
        "AB,A,B,0.1,0.0,150.0\nBC,B,C,0.1,0.0,150.0\nAC,A,C,0.1,0.0,150.0\n" +
        "XY1,X,Y,0.1,0.0,150.0\nXY2,X,Y,0.1,0.0,150.0\n",
    )
    Files.writeString(
      dir.resolve("generators.csv"),
      "name,bus,p_nom,marginal_cost\ng1,A,400.0,10.0\ng2,X,400.0,10.0\n",
    )
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nl1,C,100.0\nl2,Y,100.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,0\n")
    val split = CsvReader.read(dir, schema, "split")

    assertEquals(Topology.subNetworks(split).length, 2, "the fixture should have two islands")

    val base = Lopf.build(split).problem.numConstraints
    // Outaging only AB: rows for the two other triangle branches, none for XY.
    val one = Sclopf.build(split, Some(IndexedSeq(Sclopf.Outage("Line", "AB"))))
    assertEquals(one.problem.numConstraints - base, 4, "expected two two-sided rows")
  }

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }
