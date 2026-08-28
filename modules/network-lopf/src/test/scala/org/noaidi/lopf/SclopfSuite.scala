package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.{CsvReader, Network, Topology}
import org.noaidi.pf.Lodf
import org.noaidi.prima.{PdhgParams, RowExpansion, SolveStatus}

/** Security-constrained dispatch against PyPSA's own SCLOPF.
  *
  * The fixture is built so the contingency constraints are the '''only''' thing
  * separating this from a plain dispatch: at a rating of 150 the ordinary LOPF
  * costs 6900, exactly what it costs at 200, so the pre-contingency limits are
  * slack. The secure optimum is 14100. An implementation that dropped the N-1
  * rows would return 6900, so nothing here can pass without them.
  */
class SclopfSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-sclopf-"

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

  /** The branch outages PyPSA secured against, from its own recorded result. */
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
    val dir = tempDir("noaidi-sclopf-")
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
    val spurred = triangleWithSpur()
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
    val dir = tempDir("noaidi-sclopf-split-")
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

  test("a network carrying a global constraint still builds") {
    assume(available, "goldens missing")
    // The regression this pins: `Lopf.build` emits a CO2 cap through `lessThan`,
    // which the builder records as a negated row, and an invariant demanding
    // every row be `Direct` rejected the whole network. Every fixture in this
    // suite is derived from `sclopf-triangle`, which carries no
    // global_constraints.csv, so nothing here noticed -- secure dispatch under an
    // emissions cap is among SCLOPF's commonest uses.
    val n = network("ac-dc-co2")
    assert(n.table("GlobalConstraint").exists(_.size > 0), "the fixture lost its cap")

    // Line 6 is a bridge, so the credible contingencies are the meshed triangle.
    val outages = IndexedSeq("0", "1", "5").map(Sclopf.Outage("Line", _))
    val model   = Sclopf.build(n, Some(outages))
    assert(
      model.problem.numConstraints > Lopf.build(n).problem.numConstraints,
      "no security rows were added",
    )
  }

  test("a negated row keeps its sign through the rebuild") {
    assume(available, "goldens missing")
    // The reason a negated row is re-emitted negated rather than through
    // `greaterThan`: the latter copies the primal correctly and silently flips
    // the dual that `translation.originalDuals` hands back, which is public API.
    val n       = network("ac-dc-co2")
    val base    = Lopf.build(n)
    val secure  = Sclopf.build(n, Some(IndexedSeq("0", "1", "5").map(Sclopf.Outage("Line", _))))

    val baseNegated = (0 until base.translation.numOriginalRows)
      .count(r => base.translation.expansionOf(r).isInstanceOf[RowExpansion.Negated])
    assert(baseNegated > 0, "the fixture has no negated row, so this proves nothing")

    val secureNegated = (0 until base.translation.numOriginalRows)
      .count(r => secure.translation.expansionOf(r).isInstanceOf[RowExpansion.Negated])
    assertEquals(secureNegated, baseNegated, "the rebuild changed which rows are negated")
  }

  /** The triangle plus a radial load spur, which is a bridge.
    *
    * Rows are built from each file's actual header rather than an assumed column
    * order: the golden's lines.csv is `name,bus0,bus1,x,s_nom,sub_network`, and
    * CsvReader maps by position, so a row written against a guessed order landed
    * s_nom in sub_network and gave the spur a zero rating.
    */
  private def triangleWithSpur(): Network =
    val dir = copyOf("sclopf-triangle")
    // Rows are built from each file's actual header rather than from an assumed
    // column order. The golden's lines.csv is `name,bus0,bus1,x,s_nom,sub_network`
    // -- not the `...,x,r,s_nom` this first assumed -- and CsvReader maps by
    // position, so the spur was created with s_nom = 0 and sub_network = "150.0".
    // The assertions still held, since CD is a bridge topologically whatever its
    // rating, but the fixture was not the network its comment described, and a
    // zero rating would drive the answer the moment it were solved.
    def append(file: String, values: Map[String, String]): Unit =
      val target = dir.resolve(file)
      val lines  = Files.readString(target).linesIterator.toIndexedSeq
      val header = lines.head.split(",", -1)
      val row    = header.map(c => values.getOrElse(c, ""))
      header.foreach { c =>
        assert(values.contains(c), s"$file column '$c' was not given a value")
      }
      Files.writeString(target, (lines :+ row.mkString(",")).mkString("\n") + "\n")

    append("buses.csv", Map(
      "name" -> "D", "v_nom" -> "380.0", "control" -> "PQ",
      "generator" -> "", "sub_network" -> "0",
    ))
    append("lines.csv", Map(
      "name" -> "CD", "bus0" -> "C", "bus1" -> "D",
      "x" -> "0.1", "s_nom" -> "150.0", "sub_network" -> "0",
    ))
    append("loads.csv", Map("name" -> "ld", "bus" -> "D"))
    // And a real load on the spur, so it is the radial *load* spur the comment
    // describes rather than an empty stub.
    val series = dir.resolve("loads-p_set.csv")
    val rows   = Files.readString(series).linesIterator.toIndexedSeq
    Files.writeString(
      series,
      ((rows.head + ",ld") +: rows.tail.map(_ + ",40.0")).mkString("\n") + "\n",
    )

    CsvReader.read(dir, schema, "sclopf-triangle")

  test("an unrequested bridge column fails loudly rather than reading as zero") {
    assume(available, "goldens missing")
    // Restricting validation to the requested outages leaves the bridge columns
    // uncomputable, and they used to be filled with 0.0 off-diagonal. Reading one
    // then gave a zero meaning "not computed" dressed as a zero meaning "no
    // effect" -- the hazard `factor` was changed to avoid, reinstated one layer
    // up. Note this is specific to *bridges*: an unrequested ordinary column is
    // computed and perfectly valid, so it reads back normally.
    val spurred = triangleWithSpur()
    val sub     = Topology.subNetworks(spurred).head
    val some    = Lodf.of(spurred, sub, Some(Set(("Line", "AB"))))

    // The requested column is computed.
    assert(some.factor(("Line", "BC"), ("Line", "AB")).abs > 0.0)
    // An unrequested ordinary column is computed too -- nothing was lost.
    assert(some.factor(("Line", "AB"), ("Line", "BC")).abs > 0.0)
    // The unrequested bridge is not, and says so.
    val failure = intercept[NoSuchElementException](some.factor(("Line", "AB"), ("Line", "CD")))
    assert(failure.getMessage.contains("not computed"), failure.getMessage)
    // The message on this one too: without it the assertion would pass for a
    // NoSuchElementException thrown from anywhere at all.
    val orZero = intercept[NoSuchElementException](some.factorOrZero(("Line", "AB"), ("Line", "CD")))
    assert(orZero.getMessage.contains("not computed"), orZero.getMessage)
    // A branch of another sub-network is still a legitimate zero.
    assertEqualsDouble(some.factorOrZero(("Line", "AB"), ("Line", "elsewhere")), 0.0, 0.0)
  }

  test("factors stay bounded as a network approaches a bridge") {
    assume(available, "goldens missing")
    // Why there is no magnitude bound on the factors. Driving a network towards a
    // bridge -- a stiff branch parallel to a path whose impedance is raised by
    // orders of magnitude -- does not make the factors grow: all of an outaged
    // branch's flow moves to the alternative path, so the ratio is exactly one
    // until the denominator check fires. A bound of 1e6 was added here on the
    // reasoning that a denominator just past the threshold would yield a huge
    // finite factor; this is the measurement that showed it could never trip.
    Seq(1e2, 1e4, 1e6).foreach { ratio =>
      val dir = tempDir("noaidi-sclopf-weak-")
      Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nA,380.0,AC\nB,380.0,AC\nC,380.0,AC\n")
      Files.writeString(
        dir.resolve("lines.csv"),
        "name,bus0,bus1,x,r,s_nom\n" +
          s"AB,A,B,0.1,0.0,150.0\nAC,A,C,${0.1 * ratio},0.0,150.0\nCB,C,B,${0.1 * ratio},0.0,150.0\n",
      )
      Files.writeString(dir.resolve("generators.csv"), "name,bus,control,carrier\ng,A,Slack,wind\n")
      Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nl,B,50.0\n")
      Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")

      val n    = CsvReader.read(dir, schema, "weak")
      val lodf = Lodf.of(n, Topology.subNetworks(n).head, None)
      val ids  = Seq("AB", "AC", "CB")
      val worst = (for a <- ids; o <- ids if a != o
                   yield math.abs(lodf.factor(("Line", a), ("Line", o)))).max
      assertEqualsDouble(worst, 1.0, 1e-6, s"impedance ratio $ratio")
    }
  }

  test("an extendable transmission network is refused, and only for the transmission") {
    assume(available, "goldens missing")
    // The guard capacity expansion silently removed. The security rows cap
    // post-contingency flow at the branch rating in the file, which is not the
    // capacity being chosen; where the optimum shrinks a line below its given
    // rating -- what happens on `ac-dc-meshed` -- the limit is looser than the
    // network being built can carry.
    val failure = intercept[Sclopf.UnsupportedNetwork] {
      Sclopf.build(network("ac-dc-meshed"), Some(IndexedSeq(Sclopf.Outage("Line", "0"))))
    }
    assert(failure.getMessage.contains("extendable"), failure.getMessage)
    assert(failure.getMessage.contains("branch rating"), failure.getMessage)
  }

  test("extendable generation alone is allowed, and the secure answer is sound") {
    assume(available, "goldens missing")
    // The first version of the guard refused every extendable component. Only a
    // passive branch's rating appears in these rows -- an extendable generator,
    // link or store leaves every branch rating static and the rows exactly
    // right -- so refusing those blocked secure dispatch under generation
    // expansion with no correctness argument behind it.
    //
    // Asserted on the *answer*, not on a constraint count. Admitting a new class
    // of network on the strength of `numConstraints > 0` says nothing about the
    // failure mode the refusal exists for -- "cheaper than reality, reported
    // Optimal" is invisible to a count. Under expansion this path additionally
    // copies the incremental-objective offset and re-emits `Expansion`'s
    // capacity rows with their signs, none of which a count would notice.
    val n = mutate("ac-dc-meshed", "lines.csv", _.replace(",True,", ",False,"))
    val generators = n.require("Generator")
    assert(
      generators.ids.exists(id => Expansion.isExtendable(generators, id)),
      "the fixture no longer has an extendable generator, so this proves nothing",
    )
    assert(
      n.require("Line").ids.forall(id => !Expansion.isExtendable(n.require("Line"), id)),
      "a line is still extendable, so the refusal would fire for the wrong reason",
    )

    val outages = IndexedSeq(Sclopf.Outage("Line", "0"))
    val secure  = Sclopf.build(n, Some(outages))
    val plain   = Lopf.build(n)
    assert(
      secure.problem.numConstraints > plain.problem.numConstraints,
      "no contingency rows were added, so the factors were never built",
    )

    val result = Sclopf.solve(n, Some(outages), params)
    assertEquals(result.status, SolveStatus.Optimal, s"${result.solution}")

    // A secure dispatch cannot cost less than an unconstrained one. This is the
    // property the refusal was protecting, and the one a count cannot see.
    val unconstrained = Lopf.solve(n, params)
    // Checked before its objective is used as a bound. A stalled solve returns
    // an arbitrary iterate, which would make this assertion fail spuriously or
    // pass vacuously -- and in both cases point at the SCLOPF path rather than
    // at the plain solve that actually went wrong.
    assertEquals(unconstrained.status, SolveStatus.Optimal, s"${unconstrained.solution}")
    assert(
      result.objective >= unconstrained.objective - 1e-6 * math.abs(unconstrained.objective),
      s"secure cost ${result.objective} is below the unconstrained ${unconstrained.objective}",
    )

    // And the capacities read back through the reused variable map, within the
    // bounds the file gives them.
    generators.ids.filter(id => Expansion.isExtendable(generators, id)).foreach { id =>
      val chosen = result.capacity("Generator", id)
      assert(chosen.isFinite, s"Generator '$id' capacity is $chosen")
      assert(
        chosen >= generators.float("p_nom_min", id) - 1e-6 &&
          chosen <= generators.float("p_nom_max", id) + 1e-6,
        s"Generator '$id' capacity $chosen is outside its bounds",
      )
    }
  }

  test("the default outage list refuses extendable transmission by the right type") {
    assume(available, "goldens missing")
    // The refusal sits above `Lodf.of`, not merely below the early returns.
    // Computing the factors first meant `ac-dc-meshed` under its documented
    // default -- every branch a contingency -- threw `Lodf.Unsupported` about
    // its Bremen-Frankfurt bridge instead of this, a different exception *type*,
    // so a caller catching `Sclopf.UnsupportedNetwork` did not catch it at all.
    val failure = intercept[Sclopf.UnsupportedNetwork](Sclopf.build(network("ac-dc-meshed")))
    assert(failure.getMessage.contains("extendable"), failure.getMessage)
  }

  test("an extendable transformer is refused, not only an extendable line") {
    assume(available, "goldens missing")
    // The component set is derived from `Role`, so a Transformer is covered
    // without being named -- but nothing reached that arm: `ac-dc-meshed` has no
    // transformers, so only the Line branch was ever taken.
    val n = mutate(
      "transformer-levels",
      "transformers.csv",
      setColumn(_, "s_nom_extendable", "True"),
    )
    val failure = intercept[Sclopf.UnsupportedNetwork] {
      Sclopf.build(n, Some(IndexedSeq(Sclopf.Outage("Line", "hv"))))
    }
    assert(failure.getMessage.contains("Transformer"), failure.getMessage)
  }

  test("an empty outage list is a dispatch model even when the network is extendable") {
    assume(available, "goldens missing")
    // The early return produces a model with no security rows at all, so there
    // is nothing for a stale rating to be wrong about. Evaluating the refusal
    // before it threw on a call that would have been correct.
    val base = Sclopf.build(network("ac-dc-meshed"), Some(IndexedSeq.empty))
    assertEquals(base.problem.numConstraints, Lopf.build(network("ac-dc-meshed")).problem.numConstraints)
  }

  Seq("ac-dc-meshed", "storage-hvdc").foreach { name =>
    test(s"the base model's rows stay one-for-one under expansion on $name") {
      assume(available, "goldens missing")
      // `LpBuilder.build` hoists every equality to the front, so an inequality
      // emitted between two equality blocks shifts the later ones and breaks the
      // row-by-row copy `Sclopf` performs. Both networks, not just one:
      // `ac-dc-meshed` has cycles and no storage, `storage-hvdc` has both, and
      // the equality blocks that were being shifted are the storage energy
      // balance and the set-point rows as well as Kirchhoff.
      val model = Lopf.build(network(name))
      (0 until model.translation.numOriginalRows).foreach { r =>
        val mapped = model.translation.expansionOf(r) match
          case RowExpansion.Direct(row)  => row
          case RowExpansion.Negated(row) => row
          case other                     => fail(s"row $r expanded to $other")
        assertEquals(mapped, r, s"original row $r moved to standard-form row $mapped")
      }
    }
  }
