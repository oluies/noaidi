package org.noaidi.lopf

import org.noaidi.network.Network
import org.noaidi.prima.{PdhgParams, Pdhg, RowExpansion, SolveStatus}

/** [[BidLadder]] against NordPSA's own `hydro_bid_ladder` callback, on two networks.
  *
  * ==Two fixtures, because one of them cannot carry the headline claim==
  *
  * `ladder-week` is synthetic: one zone, a week at 3-hourly resolution, a reservoir that
  * is price-marginal throughout. `nordic-today` is the network `nordpsa today` actually
  * builds — six zones, 117 generators, eight cross-border links, five reservoirs, a year
  * at daily resolution, real load and real inflow.
  *
  * The real one is the stronger end-to-end evidence and the weaker evidence about the
  * ladder, and the reason is worth stating because it decides what this suite asserts.
  *
  * What the ladder is '''for''' is the side effect: a flat bid makes the fleet go
  * all-or-nothing as the price crosses its single bid, and a rising curve spreads the
  * output. On `nordic-today` that fraction is '''not a property of the model'''. The
  * continental price generators are perfectly elastic at one price over thousands of MW,
  * so shifting water between two hours that both price against them changes the objective
  * by nothing; the optimal face is enormous. `generate_nordic.py` measures this rather
  * than leaving it to be argued: HiGHS simplex returns a vertex with 37.2% of hours
  * pinned, HiGHS interior-point returns 4.9% — the same objective to eleven digits. This
  * port's first-order solver lands at 4.9% too, which is the agreement one should expect
  * between two methods that both stop in the interior, and is '''not''' evidence about
  * the formulation.
  *
  * So `nordic-today` is asserted on what is determined — the objective, which agrees to
  * ten digits with the ladder on and off, over 57,305 columns — and `ladder-week` carries
  * the bang-bang assertions. That fixture is built so its trajectory is the unique
  * optimum: its competitor's marginal cost sweeps hydro's bid band and repeats no value,
  * so the reservoir's water value is the one number clearing its cyclic balance and the
  * hours it runs full are pinned down. Both sides then agree on the whole dispatch
  * trajectory and not merely on its cost.
  *
  * The first version of that fixture had a competitor at a constant 45 EUR/MWh and was
  * degenerate in exactly the way `nordic-today` is. It passed on objectives and every
  * shape statistic disagreed — flat at 16% pinned in PyPSA against 0% here — which reads
  * as a porting error and is not one.
  */
class BidLadderSuite extends munit.FunSuite, NordPsaFixtures:

  override protected def tempPrefix: String = "noaidi-ladder-"

  private lazy val fixtures: Boolean      = hasReference("ladder.json")
  private lazy val reference: ujson.Value = referenceJson("ladder.json")

  private lazy val nordicFixtures: Boolean = hasReference("nordic.json")
  private lazy val nordic: ujson.Value     = referenceJson("nordic.json")

  /** A year of the real network takes about a minute a solve, so each is done once.
    *
    * Not a convenience: written without it this suite solved `nordic-today` four times
    * for three assertions, which is four minutes of a module that otherwise runs in
    * seconds -- and the default munit timeout is thirty seconds, so it did not even fail
    * for the right reason.
    */
  private lazy val nordicNetwork: Network      = variant("nordic-today")
  private lazy val nordicFlat: LopfResult      = solveNordic("flat")
  private lazy val nordicLaddered: LopfResult  = solveNordic("ladder")

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, "min")

  // The same tolerances LopfSuite drives the goldens with.
  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)
  private def solver  = Pdhg.Solver(params)

  private def stored(name: String): ujson.Value = reference("cases")(name)
  private def unit: String  = reference("shape")("unit").str
  private def pNom: Double   = reference("shape")("p_nom").num

  /** The config for one stored case, built from the numbers the generator wrote.
    *
    * Read from the file rather than restated here, which is the rule the other NordPSA
    * suites follow: a callback config written on both sides is a config that drifts, and
    * the drift shows up as this suite agreeing with a PyPSA run that answered a different
    * question.
    */
  private def configOf(name: String): BidLadder.Config =
    val c = stored(name)
    if c("tiers").isNull then BidLadder.off
    else BidLadder.Config(tiers = c("tiers").num.toInt, width = c("width").num)

  /** This port's dispatch on `ladder-week` under one stored case. */
  private def trajectory(name: String): (LopfResult, IndexedSeq[Double]) =
    val network = variant("ladder-week")
    val result  = Lopf.solve(network, Lopf.Families(ladder = configOf(name)), solver)
    assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
    (result, network.snapshots.indices.map(t => result.discharging(unit, t)))

  private def pinnedFraction(dispatch: IndexedSeq[Double]): (Double, Double) =
    val ceiling = dispatch.count(_ / pNom > 0.999).toDouble / dispatch.length
    val floor   = dispatch.count(_ / pNom < 0.001).toDouble / dispatch.length
    (ceiling, floor)

  /** Assert this port reaches PyPSA's objective and its whole trajectory on one case. */
  private def agrees(name: String): IndexedSeq[Double] =
    val (result, dispatch) = trajectory(name)
    val c = stored(name)
    val objective = c("objective").num
    assertEqualsDouble(
      result.objective,
      objective,
      1e-6 * math.max(1.0, math.abs(objective)),
      s"$name objective disagrees with PyPSA",
    )
    // Snapshot by snapshot, not through a summary statistic. This fixture is built so
    // the trajectory is determined, so the full comparison is available -- and a summary
    // can agree while the hours it is made of do not.
    val theirs = c("dispatch").arr.map(_.num).toIndexedSeq
    assertEquals(dispatch.length, theirs.length, s"$name has a different number of hours")
    dispatch.zip(theirs).zipWithIndex.foreach { case ((mine, pypsa), t) =>
      assertEqualsDouble(mine, pypsa, 1e-4 * pNom, s"$name dispatch at snapshot $t")
    }
    dispatch

  test("a flat bid is bang-bang and the ladder spreads it, as PyPSA has it") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // The control, from the generator: if the flat case were not pinned to its bounds
    // there would be no bang-bang to spread and every assertion below would be about a
    // fixture that cannot show the effect.
    assert(reference("flat_is_bang_bang").bool, "the flat bid was not bang-bang -- weak fixture")
    assert(
      reference("flat_pinned_frac").num > 0.9,
      s"only ${reference("flat_pinned_frac").num} of hours pinned -- weak fixture",
    )

    val flat   = agrees("flat")
    val ladder = agrees("k3-w36")

    val (flatCeiling, flatFloor)     = pinnedFraction(flat)
    val (ladderCeiling, ladderFloor) = pinnedFraction(ladder)
    assertEqualsDouble(flatCeiling, stored("flat")("at_ceiling_frac").num, 1e-9,
      "the flat case's ceiling fraction disagrees with PyPSA")
    assertEqualsDouble(flatFloor, stored("flat")("at_floor_frac").num, 1e-9,
      "the flat case's floor fraction disagrees with PyPSA")
    assertEqualsDouble(ladderCeiling, stored("k3-w36")("at_ceiling_frac").num, 1e-9,
      "the ladder's ceiling fraction disagrees with PyPSA")
    assertEqualsDouble(ladderFloor, stored("k3-w36")("at_floor_frac").num, 1e-9,
      "the ladder's floor fraction disagrees with PyPSA")

    assert(
      flatCeiling + flatFloor > ladderCeiling + ladderFloor + 1e-9,
      s"the ladder did not spread the dispatch: ${flatCeiling + flatFloor} -> " +
        s"${ladderCeiling + ladderFloor}",
    )
  }

  test("the objective falls under the ladder, which is why raw objectives do not compare") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // Mean-preserving in the margin, not in the total: at partial output only the cheap
    // tiers are in. Asserted because the documentation warns about it, and a warning
    // about behaviour nothing checks is a warning that can quietly stop being true.
    assert(reference("ladder_lowers_objective").bool, "PyPSA's ladder did not lower it")
    val (flat, _)   = trajectory("flat")
    val (ladder, _) = trajectory("k3-w36")
    assert(
      ladder.objective < flat.objective - 1.0,
      s"the ladder did not lower the objective: ${flat.objective} -> ${ladder.objective}",
    )
  }

  test("width and tiers each move the dispatch, not only the cost") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // Both knobs need a case where they change the answer, or a port that read one and
    // ignored the other would pass. The generator records how far each moves the
    // trajectory on PyPSA's side; these are the controls for the two assertions below.
    assert(reference("width_moves_dispatch_mw").num > 1.0, "width changed nothing -- weak fixture")
    assert(reference("tiers_move_dispatch_mw").num > 1.0, "tiers changed nothing -- weak fixture")

    val narrow = agrees("k3-w36")
    val wide   = agrees("k3-w72")
    val more   = agrees("k5-w36")

    def maxGap(a: IndexedSeq[Double], b: IndexedSeq[Double]): Double =
      a.zip(b).map((x, y) => math.abs(x - y)).max
    assert(maxGap(wide, narrow) > 1.0, "doubling the width moved no hour's dispatch")
    assert(maxGap(more, narrow) > 1.0, "adding tiers moved no hour's dispatch")
  }

  test("the tiers sum to the dispatch and fill cheapest first") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // The mechanism, and the part that needs no ordering rows: `offset(k)` increases in
    // `k`, so a cost-minimising LP fills tier k before tier k+1 unaided. That is unique
    // for a given dispatch -- unlike the trajectory itself it cannot be degenerate --
    // so it is assertable on every unit and every hour.
    //
    // Without the summing equality the tiers would be free of the dispatch they
    // decompose: the cheap ones would sit at their bounds collecting a negative offset
    // for output nobody produced.
    val network = variant("ladder-week")
    val config  = BidLadder.Config(3, 36.0)
    val result  = Lopf.solve(network, Lopf.Families(ladder = config), solver)
    val cap     = pNom / config.tiers
    val tolerance = 1e-6 * pNom

    network.snapshots.indices.foreach { t =>
      val tiers = (0 until config.tiers).map { k =>
        result.solution.primal(result.model.map.column(BidLadder.Tier, s"$unit#$k", t))
      }
      assertEqualsDouble(tiers.sum, result.discharging(unit, t), tolerance,
        s"the tiers do not sum to the dispatch at snapshot $t")
      tiers.zipWithIndex.foreach { (value, k) =>
        assert(value >= -tolerance && value <= cap + tolerance,
          s"tier $k at snapshot $t is outside [0, $cap]: $value")
      }
      // Tier k+1 is only used once tier k is full.
      tiers.sliding(2).zipWithIndex.foreach { (pair, k) =>
        if pair(1) > tolerance then
          assertEqualsDouble(pair(0), cap, tolerance,
            s"tier ${k + 1} was used at snapshot $t while tier $k was not full")
      }
    }
  }

  test("tier k carries offset(k), weighted, in order") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // The midpoint convention is the part of this formulation that is easy to get wrong
    // -- `width` is not the span -- and behaviour sees it only through an objective that
    // several wrong conventions could also reach. So it is asserted on the coefficients,
    // against the offsets the generator wrote, and against the weight `Lopf` puts on
    // `marginal_cost` at the same snapshot.
    val network = variant("ladder-week")
    val config  = BidLadder.Config(3, 36.0)
    val theirs  = stored("k3-w36")("offsets").arr.map(_.num).toIndexedSeq
    assertEquals(BidLadder.offsets(config).length, theirs.length)
    BidLadder.offsets(config).zip(theirs).zipWithIndex.foreach { case ((mine, pypsa), k) =>
      assertEqualsDouble(mine, pypsa, 1e-9, s"offset $k disagrees with NordPSA")
    }

    val model = Lopf.build(network, Lopf.Families(ladder = config))
    network.snapshots.indices.foreach { t =>
      val weight = Periods.objectiveWeight(network, t)
      theirs.zipWithIndex.foreach { (offset, k) =>
        val column = model.map.column(BidLadder.Tier, s"$unit#$k", t)
        assertEqualsDouble(model.problem.objective(column), offset * weight, 1e-9,
          s"tier $k at snapshot $t does not carry offset($k) = $offset weighted by $weight")
        // And the tier's cap, which is what makes the bid a ladder rather than a
        // discount on the whole dispatch.
        assertEqualsDouble(model.problem.variableUpper(column), pNom / config.tiers, 1e-9,
          s"tier $k at snapshot $t is not capped at p_nom / K")
      }
    }
  }

  test("the offsets are mean-zero and span width * (K - 1) / K") {
    // Both properties the documentation leans on. Mean zero is why the ladder moves the
    // spread and not the level, so it does not disturb a lambda calibration; the span is
    // the surprise, and the reason K and width are not independent.
    Seq(2, 3, 5, 8).foreach { k =>
      val offsets = BidLadder.offsets(BidLadder.Config(k, 36.0))
      assertEquals(offsets.length, k)
      assertEqualsDouble(offsets.sum, 0.0, 1e-9, s"K = $k: the offsets are not mean-zero")
      assertEqualsDouble(offsets.last - offsets.head, 36.0 * (k - 1) / k, 1e-9,
        s"K = $k: the realised span is not width * (K - 1) / K")
      assert(offsets.sliding(2).forall(p => p(1) > p(0)), s"K = $k: the offsets do not rise")
    }
  }

  test("the ladder off changes nothing about the model") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // What a plain PyPSA network means, and the property that lets `Lopf.build` take the
    // parameter unconditionally. Compared against a build that never mentions the
    // ladder, so a default that quietly emitted something would show up here.
    val network = variant("ladder-week")
    val plain   = Lopf.build(network)
    val explicit = Lopf.build(network, Lopf.Families(ladder = BidLadder.off))
    assertEquals(explicit.problem.numVariables, plain.problem.numVariables)
    assertEquals(explicit.problem.numConstraints, plain.problem.numConstraints)
  }

  test("the tier equality keeps the row identity Sclopf depends on") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // `build()` sorts equalities before inequalities, so an equality emitted after any
    // inequality gets a standard-form index that differs from its original one -- and
    // `Sclopf.build` refuses to proceed unless every row it copies maps `Direct(r)` to
    // the same `r`. This family has now broken that twice, once in the very commit that
    // removed the previous instance, so it is pinned per family rather than once.
    val hydro = HydroOps.Config(maxWeeklyFraction = 0.6)
    val model = Lopf.build(
      variant("ladder-week"),
      Lopf.Families(hydro = hydro, ladder = BidLadder.Config(3, 36.0)),
    )
    val problem = model.problem
    assert(problem.numConstraints > problem.numEqualities, "no inequality rows -- weak test")

    // And the ladder has to have emitted something, or the scan below is about a model
    // that never had a tier in it -- which is the state a NaN width reached in this
    // family's predecessor: config said "on", emission selected nothing, and the model
    // came out byte-identical.
    val plain = Lopf.build(variant("ladder-week"), Lopf.Families(hydro = hydro))
    assert(
      problem.numVariables > plain.problem.numVariables,
      "no tier columns were added, so there is no tier equality to misindex",
    )

    // Sclopf's own condition, not a weaker one: it accepts `Direct(r)` and `Negated(r)`
    // and rejects everything else, `Range` included.
    val translation = model.translation
    val misindexed = (0 until translation.numOriginalRows).filterNot { r =>
      translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(misindexed.toList, Nil,
      "a row does not map to the standard-form row of the same index, so Sclopf would refuse it")
  }

  // ---------------------------------------------------------------- the real network

  private def nordicUnits: IndexedSeq[String] =
    nordic("shape")("hydro_reservoirs").arr.map(_.str).toIndexedSeq

  private def nordicCase(name: String): ujson.Value = nordic("cases")(name)

  /** `nordic-today` under one stored case, with the config the generator recorded. */
  private def solveNordic(name: String): LopfResult =
    val config =
      if name == "flat" then BidLadder.off
      else
        BidLadder.Config(
          tiers = nordic("ladder")("tiers").num.toInt,
          width = nordic("ladder")("width").num,
        )
    val result = Lopf.solve(nordicNetwork, Lopf.Families(ladder = config), solver)
    assertEquals(result.status, SolveStatus.Optimal, s"nordic $name did not solve")
    result

  private def agreesOnNordic(name: String, result: LopfResult): Unit =
    val objective = nordicCase(name)("objective").num
    assertEqualsDouble(
      result.objective,
      objective,
      1e-6 * math.abs(objective),
      s"nordic $name objective disagrees with PyPSA",
    )
    // Per zone, and every zone. The mean is pinned by the cyclic balance -- the
    // horizon's production equals its inflow -- so this is not evidence about the
    // ladder. It is evidence that both sides built the same reservoir: it catches an
    // inflow series read at the wrong resolution, a weighting dropped, a zone whose
    // storage went to the wrong bus.
    nordicUnits.foreach { id =>
      val theirs = nordicCase(name)("hydro")("per_zone_mean_mw")(id).num
      val mine   = nordicNetwork.snapshots.indices.map(t => result.discharging(id, t)).sum /
        nordicNetwork.snapshots.length
      assertEqualsDouble(mine, theirs, 1e-6 * math.max(1.0, theirs),
        s"nordic $name: $id's mean dispatch disagrees with PyPSA")
    }

  test("the real Nordic network agrees with PyPSA, ladder off") {
    assume(nordicFixtures, "reference/nordpsa nordic fixtures are not present")
    // The control for the case below: 57,305 columns of network with no ladder in it, so
    // a disagreement here is about everything except the ladder.
    agreesOnNordic("flat", nordicFlat)
  }

  test("the real Nordic network agrees with PyPSA, ladder on") {
    assume(nordicFixtures, "reference/nordpsa nordic fixtures are not present")
    // The side-by-side this family exists for: the same network the model is actually
    // for, solved with the same callback's rows on top of it. 5,475 tier columns and
    // 1,825 tier equalities across five reservoirs and 365 snapshots.
    agreesOnNordic("ladder", nordicLaddered)
    assert(
      nordicLaddered.objective < nordicFlat.objective - 1.0,
      "the ladder did not lower the objective: " +
        s"${nordicFlat.objective} -> ${nordicLaddered.objective}",
    )
  }

  test("this network's bang-bang fraction is a solver artifact, and the fixture says so") {
    assume(nordicFixtures, "reference/nordpsa nordic fixtures are not present")
    // Not an assertion about the port. It records why the two tests above assert the
    // objective and the zonal means and not the fraction of hours pinned to a bound,
    // which is the effect the ladder is FOR -- and it is here so that a later reader who
    // reaches for that assertion finds the measurement instead of rediscovering it.
    //
    // If this ever comes back `unique = true`, the nordic fixture has become able to
    // carry a shape assertion and the two tests above should gain one.
    val degeneracy = nordic("trajectory_degeneracy")
    assume(degeneracy("probed").bool, "the generator did not probe for degeneracy")
    assert(
      !degeneracy("unique").bool,
      "nordic-today's trajectory is now unique -- the shape assertions ladder-week " +
        "carries can and should move here too",
    )
    assert(degeneracy("same_objective").bool, "the two PyPSA solves disagreed on the cost")
    val simplex  = degeneracy("simplex")("pinned_frac").num
    val interior = degeneracy("interior")("pinned_frac").num
    assert(
      simplex > interior + 0.1,
      s"the two methods pinned the same fraction ($simplex vs $interior), so the " +
        "degeneracy this documents is not there any more",
    )

    // And this port lands where the other interior method does, which is the agreement
    // to expect and not evidence about the ladder.
    val store  = nordicNetwork.require("StorageUnit")
    val counts = nordicUnits.flatMap { id =>
      val cap = store.float("p_nom", id)
      nordicNetwork.snapshots.indices.map(t => nordicFlat.discharging(id, t) / cap)
    }
    val pinned = counts.count(f => f > 0.999 || f < 0.001).toDouble / counts.length
    assert(
      math.abs(pinned - interior) < 0.05,
      s"this port pinned $pinned of hours where HiGHS' interior point pinned $interior",
    )
  }

  // ------------------------------------------------------------------- the refusals

  private def refusal(n: Network, config: BidLadder.Config): String =
    intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(n, Lopf.Families(ladder = config))
    }.getMessage

  test("a width that cannot make a ladder is refused, NaN included") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // NaN is the hole this family has now shipped twice: `isOff` tested `<= 0.0` while
    // emission selected `> 0.0`, and NaN is neither -- so the config said "on", nothing
    // was emitted, and the run reported the unpriced objective as though it had been
    // priced. One predicate, `!(width > 0.0)`, everywhere.
    //
    // NordPSA refuses zero and negative too, which `reference("refused")` records, so
    // these two sides reject the same inputs rather than this port inventing a rule. It
    // has no NaN case, because Python's `W <= 0.0` has the same hole; that one is this
    // port refusing more, which is the direction that cannot produce a wrong number.
    assert(reference("refused")("zero-width")("refused").bool, "NordPSA accepted width 0")
    assert(reference("refused")("negative-width")("refused").bool, "NordPSA accepted width < 0")
    Seq(Double.NaN, 0.0, -5.0).foreach { bad =>
      val message = refusal(variant("ladder-week"), BidLadder.Config(3, bad))
      assert(message.contains("width"), s"width $bad: $message")
    }
  }

  test("fewer than two tiers is refused") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // K = 1 is not a ladder but the flat bid this exists to replace, and NordPSA refuses
    // it for that reason. K = 0 with a width set is the half-written configuration `off`
    // deliberately does not match: `isOff` needs both at zero, so `Config(0, 36.0)` is a
    // mistake rather than an off switch.
    assert(reference("refused")("k1")("refused").bool, "NordPSA accepted one tier")
    Seq(1, 0, -3).foreach { tiers =>
      val message = refusal(variant("ladder-week"), BidLadder.Config(tiers, 36.0))
      assert(message.contains("tier"), s"tiers $tiers: $message")
    }
  }

  test("a network with no reservoir is refused by name") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // A ladder on a battery would be a rising bid curve over an aggregation error that
    // is not there -- the fleet is one unit because it is one unit. With no reservoir at
    // all the ladder reshapes nothing while the run reports a number as though it had.
    val battery = copiedWith(
      variantDir("ladder-week"),
      "ladder-week",
      "storage_units.csv" ->
        ("name,bus,p_nom,p_min_pu,carrier,spill_cost,marginal_cost," +
          "cyclic_state_of_charge,max_hours\n" +
          "Z battery,b,900.0,0.0,battery,0.1,30.0,True,100.0\n"),
    )
    val message = refusal(battery, BidLadder.Config(3, 36.0))
    assert(message.contains("no reservoir"), message)
  }

  test("a reservoir with no capacity is refused rather than laddered at zero") {
    assume(fixtures, "reference/nordpsa ladder fixtures are not present")
    // NordPSA's filter is `carrier == hydro` AND `p_nom > 0`, so a zero-capacity
    // reservoir is not in its ladder either. Here it would give tiers capped at zero:
    // columns that cannot move, and a run that reports a ladder it did not build.
    val empty = copiedWith(
      variantDir("ladder-week"),
      "ladder-week",
      "storage_units.csv" ->
        ("name,bus,p_nom,p_min_pu,carrier,spill_cost,marginal_cost," +
          "cyclic_state_of_charge,max_hours\n" +
          "Z hydro,b,0.0,0.0,hydro,0.1,30.0,True,100.0\n"),
    )
    val message = refusal(empty, BidLadder.Config(3, 36.0))
    assert(message.contains("no reservoir"), message)
  }

  test("a network with no StorageUnit table is refused by name") {
    assume(available, "reference/goldens is not present")
    // Reachable through a bare `None.get` before, naming neither the network nor what it
    // was looking for.
    val message = refusal(network("ac-dc-meshed"), BidLadder.Config(3, 36.0))
    assert(message.contains("no StorageUnit table"), message)
  }

  test("a snapshot the reservoir does not exist at is skipped, not refused") {
    assume(available, "reference/goldens is not present")
    // The opposite choice from `TerminalValue`, and deliberately. That prices one level
    // at one snapshot, so a reservoir absent from it has nothing to price and a silent
    // skip would leave the horizon unpriced. A ladder spans every snapshot, and a
    // multi-period reservoir legitimately does not exist in all of them -- refusing
    // would make the ladder unusable there rather than correct.
    //
    // `investment-periods` is two periods of two snapshots; this reservoir is built in
    // the second, so it exists at two of the four and should get tiers at exactly those.
    val periods = copiedWith(
      goldens.resolve("networks").resolve("investment-periods"),
      "investment-periods",
      "storage_units.csv" ->
        ("name,bus,p_nom,p_min_pu,carrier,spill_cost,marginal_cost," +
          "cyclic_state_of_charge,max_hours,build_year,lifetime\n" +
          "late hydro,b,100.0,0.0,hydro,0.1,3.0,True,10.0,2040,30.0\n"),
    )
    val config = BidLadder.Config(2, 10.0)
    val model  = Lopf.build(periods, Lopf.Families(ladder = config))
    val store  = periods.require("StorageUnit")
    val active = periods.snapshots.indices.filter(t =>
      Periods.activeAt(periods, store, "late hydro", t))
    assert(active.nonEmpty, "the reservoir is active at no snapshot -- weak fixture")
    assert(
      active.length < periods.snapshots.length,
      "the reservoir is active at every snapshot, so nothing is being skipped",
    )
    periods.snapshots.indices.foreach { t =>
      (0 until config.tiers).foreach { k =>
        val present =
          try
            model.map.column(BidLadder.Tier, s"late hydro#$k", t): Unit
            true
          catch case _: NoSuchElementException => false
        assertEquals(present, active.contains(t),
          s"tier $k at snapshot $t: column present = $present, reservoir active = " +
            s"${active.contains(t)}")
      }
    }
  }
