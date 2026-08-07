package org.noaidi.pf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema, Topology}

/** Newton-Raphson AC power flow against PyPSA's `n.pf()`. */
class NewtonRaphsonSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  // PyPSA 1.2.4 cannot run an AC power flow on the ac-dc networks at all -- it
  // raises inside its own sub-network handling -- so their pf goldens record
  // that error rather than an answer, and KNOWN_UNSUPPORTED in the generator
  // says so. `scigrid-de` is absent for a different reason: PyPSA's own
  // Newton-Raphson diverges on it from a flat start, so its golden holds the
  // convergence flags and no values.
  //
  // `standard-types` is here because it is the only fixture where the shunt
  // admittance comes from a type. A line's `b` is `2*pi*1e-9*f_nom*c_per_length
  // *length*num_parallel` and a transformer's is a magnetising current, and
  // neither enters a linear flow at all -- so this suite is the only thing that
  // can tell whether they were derived correctly.
  private val networks = List("ac-pf-pv", "storage-hvdc", "standard-types")

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def pf(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))("pf")

  private def frameValue(frame: ujson.Value, row: Int, column: String): Double =
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index) match
      case ujson.Num(v)                             => v
      case o: ujson.Obj if o.value.contains("$nan") => Double.NaN
      case other                                    => fail(s"unexpected golden value $other")

  networks.foreach { name =>
    test(s"voltage magnitudes match PyPSA on $name") {
      assume(available, "goldens missing — run reference/generate_goldens.py")
      val expected = pf(name)
      assert(!expected.obj.contains("error"), s"golden pf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = NewtonRaphson.solve(n)
      assert(result.allConverged, s"did not converge: ${result.converged}")
      val frame = expected("bus_v_mag_pu")

      n.snapshots.indices.foreach { t =>
        n.require("Bus").ids.foreach { bus =>
          assertEqualsDouble(
            result.voltageMagnitude(bus, t),
            frameValue(frame, t, bus),
            1e-9,
            s"$name snapshot $t, bus $bus",
          )
        }
      }
    }

    test(s"voltage angles match PyPSA on $name") {
      assume(available, "goldens missing")
      val expected = pf(name)
      assert(!expected.obj.contains("error"), s"golden pf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = NewtonRaphson.solve(n)
      val frame  = expected("bus_v_ang")

      n.snapshots.indices.foreach { t =>
        n.require("Bus").ids.foreach { bus =>
          assertEqualsDouble(
            result.voltageAngle(bus, t),
            frameValue(frame, t, bus),
            1e-9,
            s"$name snapshot $t, bus $bus",
          )
        }
      }
    }

    test(s"bus active and reactive power match PyPSA on $name") {
      assume(available, "goldens missing")
      // This is what separates a converged-to-something from a converged-to-the-
      // right-thing. P at the slack and Q at a PV bus were unknowns, so agreeing
      // on them means the equations, not just the iteration, are right.
      val expected = pf(name)
      assert(!expected.obj.contains("error"), s"golden pf failed: ${expected.obj.get("error")}")

      val n      = network(name)
      val result = NewtonRaphson.solve(n)
      val p      = expected("bus_p")
      val q      = expected("bus_q")

      n.snapshots.indices.foreach { t =>
        n.require("Bus").ids.foreach { bus =>
          // Looser than the voltage comparison, and that asymmetry is physics
          // rather than slack. Voltages agree with PyPSA to 3e-12 on
          // storage-hvdc, but P and Q are computed *from* those voltages through
          // admittances of order 1e5, so agreement to 1e-12 in |V| is agreement
          // to a few times 1e-6 in Q. Tightening this would assert that two
          // implementations round identically.
          assertEqualsDouble(result.busPower(bus, t), frameValue(p, t, bus), 1e-5, s"P at $bus, $t")
          assertEqualsDouble(result.busReactive(bus, t), frameValue(q, t, bus), 1e-5, s"Q at $bus, $t")
        }
      }
    }
  }

  test("bus types are derived to match PyPSA's own classification") {
    assume(available, "goldens missing")
    // PyPSA writes a derived `control` column into buses.csv. Deriving the split
    // from generator controls rather than reading that column means a network
    // that never went through PyPSA classifies correctly too -- and turns the
    // exported column into an independent check instead of the source.
    val n     = network("ac-pf-pv")
    val buses = n.require("Bus")
    val subs  = Topology.subNetworks(n)
    val slack = Slack.all(n, subs).head

    val expected = Map("Slack" -> BusType.Slack, "PV" -> BusType.PV, "PQ" -> BusType.PQ)
    var seen     = Set.empty[BusType]
    buses.ids.foreach { bus =>
      val mine = NewtonRaphson.classify(n, bus, slack.bus)
      seen += mine
      assertEquals(mine, expected(buses.string("control", bus)), s"bus $bus")
    }
    // All three types present, so this cannot pass by classifying everything PQ.
    assertEquals(seen, Set(BusType.Slack, BusType.PV, BusType.PQ))
  }

  test("the AC solution satisfies the power balance it was solving") {
    assume(available, "goldens missing")
    // Independent of the goldens: the converged voltages must reproduce the
    // specified injections at every bus where they were specified. A solver that
    // converged to the wrong fixed point would still match itself here, but one
    // with an error between the Jacobian and the power equations would not.
    val n      = network("ac-pf-pv")
    val result = NewtonRaphson.solve(n)
    val loads  = n.require("Load")
    val gens   = n.require("Generator")
    val subs   = Topology.subNetworks(n)
    val slack  = Slack.all(n, subs).head

    n.snapshots.indices.foreach { t =>
      n.require("Bus").ids.foreach { bus =>
        if NewtonRaphson.classify(n, bus, slack.bus) != BusType.Slack then
          val generated = gens.ids.filter(g => gens.string("bus", g) == bus).map { g =>
            val p = gens.valueAt("p_set", g, t)
            if p.isFinite then p else 0.0
          }.sum
          val consumed = loads.ids.filter(l => loads.string("bus", l) == bus)
            .map(loads.valueAt("p_set", _, t)).sum
          assertEqualsDouble(result.busPower(bus, t), generated - consumed, 1e-7, s"P at $bus, $t")
      }
    }
  }

  test("AC and linear power flow agree to first order on a lossless network") {
    assume(available, "goldens missing")
    // storage-hvdc's lines carry r that is small relative to x, so the linear
    // approximation should be close but not equal. Asserting *close* rather than
    // equal is the point: identical would mean one of the two is not doing what
    // it claims.
    val n      = network("storage-hvdc")
    val ac     = NewtonRaphson.solve(n)
    val linear = LinearPowerFlow.solve(n)

    var worst = 0.0
    n.snapshots.indices.foreach { t =>
      n.require("Bus").ids.foreach { bus =>
        worst = math.max(worst, math.abs(ac.voltageAngle(bus, t) - linear.voltageAngle(bus, t)))
      }
    }
    assert(worst < 1e-3, s"AC and linear angles differ by $worst rad, which is more than linearisation")
    assert(worst > 0.0, "AC and linear angles are identical, so one of them is not doing its job")
  }

  test("a DC sub-network is refused rather than solved with AC equations") {
    assume(available, "goldens missing")
    // Not a gap in ambition: PyPSA 1.2.4 does not implement a DC power flow
    // either. `SubNetwork.calculate_Y` returns early for any non-AC carrier with
    // "Non-AC networks not supported for Y!", leaving `Y` unset so `pf()` raises
    // AttributeError -- which is exactly what the ac-dc goldens record, and what
    // a hand-built pure-DC network does too. With no reference to check against,
    // a DC solve here would produce numbers nothing could validate.
    //
    // Applying the AC equations regardless would not even fail cleanly: a DC line
    // has x = 0, so every off-diagonal susceptance is zero, dP/dtheta vanishes at
    // a flat start and the Jacobian is singular. Refusing says why.
    val dir = Files.createTempDirectory("noaidi-nr-dc-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nd0,150.0,DC\nd1,150.0,DC\n")
    Files.writeString(dir.resolve("lines.csv"), "name,bus0,bus1,x,r,s_nom\nm,d0,d1,0.0,0.6,400.0\n")
    Files.writeString(dir.resolve("generators.csv"), "name,bus,control,carrier\ng,d0,Slack,DC\n")
    Files.writeString(dir.resolve("loads.csv"), "name,bus,p_set\nl,d1,80.0\n")
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    val n = CsvReader.read(dir, schema, "nr-dc")

    val failure = intercept[NewtonRaphson.UnsupportedNetwork](NewtonRaphson.solve(n))
    assert(failure.getMessage.contains("AC only"), failure.getMessage)
    // And the linear flow, whose PyPSA counterpart *does* support DC, handles it.
    assert(LinearPowerFlow.solve(n).voltageAngle("d1", 0).isFinite)
  }

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }

  test("a phase-shifting transformer is refused by the AC path, not answered") {
    assume(available, "goldens missing")
    // PyPSA converges on `phase-shift` and the golden records its voltages, so
    // this is a genuine gap rather than an impossibility -- `exp(jphi)` on one
    // off-diagonal and its conjugate on the other, which makes Y asymmetric.
    //
    // Refused rather than approximated, and the refusal is the point: the linear
    // flow silently returned the unshifted answer for months because nothing read
    // the attribute. A gap with a diagnostic is a different thing from a gap
    // without one.
    val n = CsvReader.read(goldens.resolve("networks").resolve("phase-shift"), schema, "phase-shift")
    val failure = intercept[Admittance.Unsupported](NewtonRaphson.solve(n))
    assert(failure.getMessage.contains("phase_shift"), failure.getMessage)

    // And PyPSA does have an answer here, so the golden is evidence of the gap.
    val pf = ujson.read(
      Files.readString(goldens.resolve("results").resolve("phase-shift.json"))
    )("pf")
    assert(!pf.obj.contains("error"), "PyPSA no longer solves this, so the gap is moot")
  }
