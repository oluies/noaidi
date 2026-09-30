package org.noaidi.lopf

import org.noaidi.network.Network
import org.noaidi.prima.{PdhgParams, Pdhg, RowExpansion, SolveStatus}

/** [[Stability]] against NordPSA's own `stability_constraints` callback.
  *
  * ==The first thing to know: this family is not MILP==
  *
  * `reference/nordpsa/README.md` listed `stability` as unported because it "reaches into
  * MILP rather than LP", and that was wrong for three families' worth of commits. The
  * online variable is continuous, in `[0, p_max_pu · P]`, tied to dispatch by `p <= u` and
  * `p >= m_min · u` — the textbook LP relaxation of unit commitment, and what upstream's
  * own first line calls "linjäriserad inkoppling". Nothing in it needs an integer.
  *
  * ==The networks are NordPSA's, because the requirement has to bind==
  *
  * `stability-toy` is the two-zone network from `tests/unit/test_stability.py`: wind covers
  * the load, so '''nothing synchronous runs at all''' unless a requirement makes it. That
  * is the property that makes the comparison worth anything — on a network where the
  * machines were running anyway, every configuration returns the same answer. The control
  * case asserts it, out of the generator's own measurement.
  *
  * The reservoir is the cheap way to make inertia (20 EUR/MWh at `m_min` 0.3) and the gas
  * the dear one (100 at 0.4), so '''which''' machine a requirement reaches for is itself an
  * assertion, and one a port that had the coefficients transposed would fail.
  *
  * The three expansion networks are upstream's too, and there grid strength can be
  * '''built''': a condenser, a grid-forming battery, or more gas.
  *
  * ==What is asserted, and why not everything is==
  *
  * These toys are degenerate in their zero-cost dispatch: two wind farms feeding one load
  * through an unconstrained link can split it any way at all, and nothing prices the split
  * unless a short-circuit row does. `generate_stability.py` measures that per case and per
  * series, by re-solving each with an interior-point method and comparing — the discipline
  * the `nordic-today` fixture established — and this suite asserts a series only where the
  * file says both methods agree. Two solvers that disagree about a quantity while agreeing
  * about the cost are reporting a tie-break, and no assertion may rest on one.
  *
  * `online` is determined in every case, which is the useful part: it is exactly what this
  * formulation decides.
  */
class StabilitySuite extends munit.FunSuite, NordPsaFixtures:

  override protected def tempPrefix: String = "noaidi-stability-"

  private lazy val fixtures: Boolean      = hasReference("stability.json")
  private lazy val reference: ujson.Value = referenceJson("stability.json")

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)
  private def solver = Pdhg.Solver(params)

  private def stored(name: String): ujson.Value = reference("cases")(name)
  private def controls: ujson.Value             = reference("controls")

  // --------------------------------------------------------------- the config, from file

  /** The technology table as NordPSA's `zones.yaml` defines it.
    *
    * Read from the reference file rather than restated here, which is the rule the other
    * NordPSA suites follow and which matters more for this family than for any of them:
    * fourteen classes times eight numbers is a lot of transcription, and a single wrong
    * digit would make this suite agree with a PyPSA run that answered a different
    * question.
    */
  private lazy val tech: Map[String, Stability.Tech] =
    reference("tech").obj.map { (name, t) =>
      val mode = Stability.Mode.parse(t("mode").str)
        .getOrElse(fail(s"the reference file has an unknown mode '${t("mode").str}' for $name"))
      def num(key: String, fallback: Double): Double =
        t.obj.get(key).filterNot(_.isNull).map(_.num).getOrElse(fallback)
      name -> Stability.Tech(
        mode = mode,
        inertiaSeconds = num("H", 0.0),
        cosPhi = num("cos_phi", 1.0),
        subtransientReactance = num("xd2", Double.NaN),
        minStableFraction = num("m_min", 0.0),
        availability = num("avail", 1.0),
        converterWeight = num("ibr_w", 0.0),
        shortCircuitPerUnit = num("sk_pu", 0.0),
      )
    }.toMap

  /** `zones.yaml`'s data half: what the units are, not what the run asks for. */
  private lazy val zoneData: Stability.Config =
    val z = reference("zone_data")
    Stability.Config(
      tech = tech,
      mapping = z("mapping").obj.map((k, v) => k -> v.str).toMap,
      nameOverrides = z("name_overrides").obj.map((k, v) => k -> v.str).toMap,
      transformerReactance = z("x_t").num,
      syncWeight = z("sync_weight").obj.map((k, v) => k -> v.num).toMap,
      scrExempt = z("scr_exempt").arr.map(_.str).toSet,
    )

  /** One case's configuration, rebuilt from what the generator recorded it as. */
  private def configOf(name: String): Stability.Config =
    val c = stored(name)("config").obj
    def num(key: String): Option[Double] = c.get(key).filterNot(_.isNull).map(_.num)
    zoneData.copy(
      systemInertiaGws = num("sys_gws").getOrElse(0.0),
      zoneInertiaFloorGws = c.get("floors")
        .map(_.obj.map((z, v) => z -> v.num).toMap).getOrElse(Map.empty),
      scrMin = num("scr").getOrElse(0.0),
      // The generator passes one penalty to both slack families, so this does too --
      // reading it from the file rather than deciding here which of them it meant.
      slackPenalty = num("penalty").getOrElse(0.0),
      scrSlackPenalty = num("penalty").getOrElse(0.0),
      // `sync_weight` merges over the yaml block, as `stability_data` merges it; `exempt`
      // and `joint` replace outright, as the generator sets them.
      syncWeight = zoneData.syncWeight ++
        c.get("weights").map(_.obj.map((z, v) => z -> v.num).toMap).getOrElse(Map.empty),
      scrExempt = c.get("exempt").map(_.arr.map(_.str).toSet).getOrElse(zoneData.scrExempt),
      scrJoint = c.get("joint")
        .map(_.obj.map((host, m) => host -> m.obj.map((z, a) => z -> a.num).toMap).toMap)
        .getOrElse(Map.empty),
      // A case may override one class's numbers, as `stability_data(tech = ...)` does. Only
      // the fields any case actually overrides are read, and an unknown one fails loudly
      // rather than being ignored -- a silently dropped override would make this suite
      // compare against a PyPSA run with different technology data.
      tech = c.get("tech").fold(tech) { overrides =>
        overrides.obj.foldLeft(tech) { (table, entry) =>
          val (name, fields) = entry
          val base = table.getOrElse(name, fail(s"unknown technology class '$name'"))
          table.updated(name, fields.obj.foldLeft(base) { (t, field) =>
            field match
              case ("ibr_w", v) => t.copy(converterWeight = v.num)
              case ("m_min", v) => t.copy(minStableFraction = v.num)
              case ("avail", v) => t.copy(availability = v.num)
              case (other, _)   => fail(s"this suite does not read the override '$other'")
          })
        }
      },
    )

  private def networkOf(name: String): Network = variant(stored(name)("network").str)

  private def solveCase(name: String): LopfResult =
    Lopf.solve(networkOf(name), Lopf.Families(stability = configOf(name)), solver)

  /** Assert this port reaches PyPSA's answer on one case, in every series the file says is
    * determined.
    */
  private def agrees(name: String): LopfResult =
    val network = networkOf(name)
    val result  = solveCase(name)
    assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
    val objective = stored(name)("objective").num
    assertEqualsDouble(result.objective, objective, 1e-6 * math.max(1.0, math.abs(objective)),
      s"$name objective disagrees with PyPSA")

    val determined = stored(name)("determined")
    assert(determined("probed").bool, s"$name was never probed for determinacy")
    assert(determined("same_objective").bool,
      s"$name: the two PyPSA solves disagreed about the cost, so the fixture is not sound")

    def series(block: String): Seq[(String, IndexedSeq[Double])] =
      stored(name).obj.get(block).toSeq.flatMap(_.obj.toSeq).collect {
        case (key, values) if determined.obj.get(block).exists(_.obj.get(key).exists(_.bool)) =>
          key -> values.arr.map(_.num).toIndexedSeq
      }

    // `online` is the decision this formulation introduces, so it is the series worth
    // most -- and the generator reports it determined in every case.
    series("online").foreach { (id, theirs) =>
      theirs.zipWithIndex.foreach { (value, t) =>
        val mine = result.solution.primal(result.model.map.column(Stability.Online, id, t))
        assertEqualsDouble(mine, value, 1e-4 * math.max(1.0, math.abs(value)),
          s"$name: $id's online capacity at snapshot $t disagrees with PyPSA")
      }
    }
    series("dispatch").foreach { (id, theirs) =>
      theirs.zipWithIndex.foreach { (value, t) =>
        assertEqualsDouble(power(result, network, id, t), value,
          1e-4 * math.max(1.0, math.abs(value)),
          s"$name: $id's dispatch at snapshot $t disagrees with PyPSA")
      }
    }
    series("slack").foreach { (label, theirs) =>
      theirs.zipWithIndex.foreach { (value, t) =>
        val mine = result.solution.primal(result.model.map.column(Stability.Slack, label, t))
        assertEqualsDouble(mine, value, 1e-4 * math.max(1.0, math.abs(value)),
          s"$name: the slack on '$label' at snapshot $t disagrees with PyPSA")
      }
    }
    stored(name).obj.get("capacity").foreach { built =>
      built.obj.foreach { (id, value) =>
        if determined.obj.get("capacity").exists(_.obj.get(id).exists(_.bool)) then
          assertEqualsDouble(result.capacity(componentOf(network, id), id), value.num,
            1e-4 * math.max(1.0, math.abs(value.num)),
            s"$name: the capacity built for $id disagrees with PyPSA")
      }
    }
    result

  /** The `p` these constraints are written against, which is not the net injection.
    *
    * The generator records a StorageUnit's `storage_units_t.p_dispatch` -- the discharge
    * half -- because that is the variable `p <= u` and `p >= m_min u` are written against
    * upstream. `LopfResult.dispatch` returns `p_dispatch - p_store`, so comparing the two
    * reads a reservoir absorbing free wind as one that is producing: on the control case
    * this port charges 231 MW at no cost, which PyPSA's simplex leaves at zero and which
    * nothing in the objective distinguishes.
    */
  private def power(result: LopfResult, network: Network, id: String, t: Int): Double =
    if componentOf(network, id) == "StorageUnit" then result.discharging(id, t)
    else result.dispatch(componentOf(network, id), id, t)

  /** Which table an id lives in, since the reference records dispatch by name alone. */
  private def componentOf(network: Network, id: String): String =
    IndexedSeq("Generator", "StorageUnit", "Link")
      .find(c => network.tables.get(c).exists(_.has(id)))
      .getOrElse(fail(s"no component table holds '$id'"))

  // ------------------------------------------------------------------------- the cases

  test("without a requirement nothing synchronous runs, which is what makes this fixture work") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The control, and the one every case below is measured against. If the machines were
    // already running there would be no requirement to satisfy and every comparison would
    // be about a network the callback could not change.
    assert(controls("reference_is_idle").bool,
      s"the reference ran ${controls("reference_max_synchronous_mw").num} MW of " +
        "synchronous plant -- weak fixture")
    assert(controls("reference_has_wind").bool, "the reference had no wind -- weak fixture")
    val result = agrees("none")
    // And nothing was emitted: `none` has no requirement, so it must be the plain model.
    val plain = Lopf.build(variant("stability-toy"))
    assertEquals(result.model.problem.numVariables, plain.problem.numVariables)
    assertEquals(result.model.problem.numConstraints, plain.problem.numConstraints)
  }

  test("a system inertia requirement is met, costs more, and uses the cheaper machine") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    assert(controls("system_costs_more").bool, "the requirement was free -- weak fixture")
    val result = agrees("ek-system")
    val flat   = agrees("none")
    assert(result.objective > flat.objective + 1.0,
      s"the requirement cost nothing: ${flat.objective} -> ${result.objective}")

    // The reservoir, not the gas. `m_min / e` is what an MWs costs through each machine,
    // and the reservoir is the cheaper -- so a port that had the two coefficients crossed
    // would meet the requirement, reach a different objective, and fail here as well.
    val network = variant("stability-toy")
    network.snapshots.indices.foreach { t =>
      assertEqualsDouble(power(result, network, "B gas", t), 0.0, 1e-6,
        s"the dear machine ran at snapshot $t")
    }
    assertEqualsDouble(requirementMet(result, "ek-system"), 0.0, 1e-4,
      "the system requirement is not tight, so nothing was binding")
  }

  /** How far the system inertia row's left-hand side exceeds its right, in MWs.
    *
    * Recomputed from the solved online capacities rather than read off a dual, because the
    * point is that the row this port '''built''' is the row upstream describes.
    */
  private def requirementMet(result: LopfResult, name: String): Double =
    val config  = configOf(name)
    val network = variant(stored(name)("network").str)
    val units   = reference("units")("stability-toy").obj
    val weight  = (zone: String) => config.syncWeight.getOrElse(zone, 1.0)
    network.snapshots.indices.map { t =>
      val energy = units.map { (id, u) =>
        if u("mode").str == "commit" && !u("fixed").bool then
          val online = result.solution.primal(result.model.map.column(Stability.Online, id, t))
          weight(u("zone").str) * u("e_coef").num * online
        else 0.0
      }.sum
      energy - 1e3 * config.systemInertiaGws
    }.min

  test("a zonal floor is local: one zone's reservoir cannot answer another zone's row") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The property that distinguishes the zonal rows from the system one, and the reason
    // there are two families rather than one with a weight of 1.
    assert(controls("zone_floor_runs_local_gas").bool,
      "the floor did not force the local machine -- weak fixture")
    val result = agrees("ek-zone-b")
    val network = variant("stability-toy")
    network.snapshots.indices.foreach { t =>
      assert(power(result, network, "B gas", t) > 1e-6,
        s"B's own machine is idle at snapshot $t while B has a floor")
    }
  }

  test("sync_weight moves the requirement between zones") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Weighting A to zero takes the cheap reservoir out of the system sum, so the dear gas
    // has to carry the whole requirement. Both directions are here because only one of
    // them discriminates: weighting *B* to zero changes nothing on this network, since B's
    // gas was not running anyway -- a case that looks like a test of sync_weight and is
    // not, which is why the generator records that too.
    assert(controls("sync_weight_changes_the_answer").bool,
      "weighting a zone out changed nothing -- weak fixture")
    assert(controls("weighting_b_to_zero_changes_nothing").bool,
      "the inert direction stopped being inert, so this test is asserting the wrong pair")
    agrees("ek-system-wa0"): Unit
    val inert = agrees("ek-system-w0")
    val plain = agrees("ek-system")
    assertEqualsDouble(inert.objective, plain.objective, 1e-6 * math.abs(plain.objective),
      "the inert weighting changed the answer after all")

    // A weight of zero is handled by dropping the zone from the sum, which is what upstream's
    // `if w[z] > 0` does -- so neither case above says anything about the multiplication, and
    // a mutation that ignored the weight entirely passed both. Denmark's 0.35 is the real
    // shape, and this is a case with a fractional weight over a zone that has both a variable
    // term and a constant.
    assert(controls("fractional_weight_changes_the_answer").bool,
      "halving a zone's weight changed nothing -- weak fixture")
    val half   = agrees("ek-system-half")
    val units  = reference("units")("stability-mustrun").obj
    val weight = configOf("ek-system-half").syncWeight("A")
    val online = (id: String) =>
      half.solution.primal(half.model.map.column(Stability.Online, id, 0))
    // Zone A's whole contribution -- every online machine plus the constants -- weighted.
    // B contributes nothing, because its gas is idle.
    val zoneA = constantInertia(units, "A") + units.obj.map { (id, u) =>
      if u("zone").str == "A" && !u("fixed").bool && u("mode").str == "commit" then
        u("e_coef").num * online(id)
      else 0.0
    }.sum
    assertEqualsDouble(weight * zoneA,
      1e3 * configOf("ek-system-half").systemInertiaGws, 1e-3,
      "the zone's weighted contribution does not meet the system requirement exactly")
  }

  test("both inertia families at once") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // A system requirement and a zonal floor together, which is the case the commitment
    // bounds below are checked on: both machines are online, so both sets of rows are live.
    agrees("ek-both"): Unit
  }

  test("the commitment bounds hold on every unit and every snapshot") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The three rows that are the whole relaxation. Asserted on the port's own solution
    // rather than against PyPSA, because a row that is absent cannot be detected by
    // comparing a number that another row already pinned.
    //
    // Over two cases, and the second is the one that matters. On `ek-both` the requirement
    // pushes `u` well above `p` on its own -- 170 MW online for 68 MW produced -- so
    // `p <= u` is slack and deleting it changes nothing. A mutation proved that: the row
    // came out and all twenty-three assertions stayed green.
    //
    // `ek-slack-online` is the becalmed network, where the machines run for the energy
    // balance and the requirement is far below what that already delivers. There `p <= u`
    // is the row that decides `u`: 800 MW produced against a floor a fifth of that, so
    // without it `u` would settle near 150 and this loop fails.
    assert(controls("tight_runs_for_the_load").bool,
      "the becalmed network's machines are idle -- weak fixture")
    assert(controls("tight_requirement_is_below_what_the_load_forces").bool,
      "the requirement is not below what the load forces, so `p <= u` is slack here too")
    Seq("ek-both", "ek-slack-online").foreach { name =>
      val result  = solveCase(name)
      val network = networkOf(name)
      val units   = reference("units")(stored(name)("network").str).obj
      assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
      units.foreach { (id, u) =>
        if u("mode").str == "commit" && !u("fixed").bool then
          val capacity = u("cap").num
          val minimum  = u("m_min").num
          network.snapshots.indices.foreach { t =>
            val online = result.solution.primal(result.model.map.column(Stability.Online, id, t))
            val produced = power(result, network, id, t)
            assert(online >= -1e-6 && online <= capacity + 1e-6,
              s"$name: $id's online capacity at $t is outside [0, $capacity]: $online")
            assert(produced <= online + 1e-6,
              s"$name: $id produces $produced at $t with only $online online")
            assert(produced >= minimum * online - 1e-6,
              s"$name: $id produces $produced at $t with $online online, below m_min = $minimum")
          }
      }
    }
  }

  test("a requirement below what the load already forces still counts the machine in full") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The becalmed case's objective and dispatch. Its `online` is deliberately not compared
    // against PyPSA: with `p <= u <= cap` and no cost on `u`, anything in [800, 1000] is
    // optimal, and the generator's probe reports it undetermined. The bound that is not
    // degenerate -- `u >= p` -- is asserted in the test above.
    agrees("ek-slack-online"): Unit
  }

  test("a requirement above what the fleet can deliver is infeasible, not quietly met") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // PyPSA returns `infeasible` here and this port has to reach the same verdict rather
    // than a number. That is the assertion: a formulation missing the coupling rows would
    // find this comfortable.
    assert(controls("hard_is_infeasible").bool, "PyPSA solved the hard case -- weak fixture")
    assertEquals(stored("ek-hard")("condition").str, "infeasible")
    val result = solveCase("ek-hard")
    assertEquals(result.status, SolveStatus.PrimalInfeasible,
      s"a requirement PyPSA calls infeasible came back ${result.status}")
  }

  test("a priced slack turns the same requirement into a cost") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The shortfall is exactly what the fleet cannot deliver, and its price is what makes
    // the run finish instead of failing. The slack value is asserted per snapshot, so a
    // port that priced the slack but sized it wrong -- or weighted it wrong -- fails.
    assert(controls("soft_is_feasible").bool, "PyPSA could not solve the soft case")
    val soft = agrees("ek-soft")
    val hard = solveCase("ek-hard")
    assertEquals(hard.status, SolveStatus.PrimalInfeasible, "the pair is not a pair")
    assert(soft.objective > 0.0, "the priced shortfall cost nothing")

    // And the slack is priced the way every other cost at that snapshot is. Every network
    // above has a snapshot weighting of 1.0, so dropping the weight from the slack's
    // objective coefficient changed nothing and a mutation survived. This one is at 3: the
    // requirement rows are per-snapshot and unweighted, so the shortfall is the same and only
    // its price moves.
    assert(controls("weighting_prices_the_slack_higher").bool,
      "the coarser resolution did not change the cost -- weak fixture")
    assert(controls("weighting_leaves_the_shortfall_alone").bool,
      "the weighting moved the shortfall itself, so this is not testing the price")
    agrees("ek-soft-weighted"): Unit
  }

  test("must-run plant and a fixed condenser count towards a requirement as constants") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // `K(z,t)`, the term that moves a requirement's right-hand side. A must-run unit has no
    // online variable -- its commitment is decided by its data, since `p_min_pu` equals
    // `p_max_pu` -- and a fixed condenser spins whatever the market does, so both enter as
    // numbers rather than columns.
    //
    // Two things make this case discriminate, and the first version of it had neither. The
    // network must have units of both kinds, which `stability-toy` does not, and the
    // requirement must be the only thing bringing the variable machine online, which on the
    // becalmed network it is not -- there the reservoir already runs for the load and a
    // 1 GWs floor is slack with or without the constant. Written both wrong ways first, and
    // a mutation that dropped the constant from the zonal floor stayed green through both.
    assert(controls("mustrun_constant_is_a_real_share").bool,
      "the must-run constant is not a meaningful share of the floor -- weak fixture")
    assert(controls("syncon_constant_is_a_real_share").bool,
      "the condenser contributes nothing measurable -- weak fixture")

    // The reservoir is asked for the floor MINUS what the must-run plant and the condenser
    // already deliver. A port that forgot the constant asks it for the whole floor, which
    // is a different online capacity and a different cost.
    val inertia = agrees("ek-mustrun")
    val units   = reference("units")("stability-mustrun").obj
    val floor   = configOf("ek-mustrun").zoneInertiaFloorGws("A")
    val constant = constantInertia(units, "A")
    assert(constant > 1.0, s"nothing contributed a constant: $constant")

    // The floor, less the constant, less whatever the other online machines supply. The
    // reservoir is the residual: the CHP link is cheaper per MWs and runs flat out, so this
    // pins the reservoir against every other term in the row at once.
    val online = (id: String) =>
      inertia.solution.primal(inertia.model.map.column(Stability.Online, id, 0))
    val others = units.map { (id, u) =>
      if u("zone").str == "A" && !u("fixed").bool && u("mode").str == "commit" &&
        id != "A hydro"
      then u("e_coef").num * online(id)
      else 0.0
    }.sum
    assertEqualsDouble(
      online("A hydro"),
      (1e3 * floor - constant - others) / units("A hydro")("e_coef").num,
      1e-3,
      "the reservoir was not asked for the floor less the constant and the other machines",
    )

    // And the same term on the grid-strength row, where the condenser is most of it.
    agrees("scr-mustrun"): Unit
  }

  test("a combined-heat-and-power link is counted on its electrical side") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The third component shape, and the only one whose coefficients carry an efficiency: a
    // CHP link's `p_nom` is a FUEL rating on `bus0` and its synchronous machine sits at
    // `bus1`, so a 400 MW link is a 100 MW machine. Its zone is `bus1` for the same reason.
    //
    // The fixture makes it the cheapest inertia on the network, so a requirement reaches for
    // it first and the scaling decides the answer. Without a classified link in any fixture
    // the whole path was untested, and a mutation that dropped the efficiency stayed green.
    assert(controls("chp_link_carries_the_requirement").bool,
      "the CHP link is not online -- weak fixture")
    val units = reference("units")("stability-mustrun").obj
    val chp   = units("A chp")
    assertEquals(chp("component").str, "Link")
    assertEquals(chp("zone").str, "A", "the link's zone is not its bus1")
    val tech = reference("tech")("chp")
    assertEqualsDouble(chp("e_coef").num,
      chp("eff").num * tech("H").num / tech("cos_phi").num, 1e-9,
      "the link's inertia coefficient is not scaled by its efficiency")

    // On the coefficients, because the objective cannot distinguish a coefficient that is
    // four times too large from a requirement that is four times too small.
    val model = Lopf.build(variant("stability-mustrun"),
      Lopf.Families(stability = configOf("ek-mustrun")))
    val network = variant("stability-mustrun")
    network.snapshots.indices.foreach { t =>
      val column = model.map.column(Stability.Online, "A chp", t)
      assertEqualsDouble(model.problem.variableUpper(column),
        chp("cap").num * network.require("Link").valueAt("p_max_pu", "A chp", t), 1e-9,
        s"the link's online ceiling at $t is not p_max_pu x p_nom on the fuel side")
    }
  }

  /** What the fixed units in one zone contribute before any machine comes online. */
  private def constantInertia(units: ujson.Value, zone: String): Double =
    units.obj.map { (_, u) =>
      if u("zone").str == zone && u("fixed").bool then
        // A must-run unit follows its own p_max_pu; a condenser contributes in full.
        u("e_coef").num * u("cap").num *
          (if u("mode").str == "commit" then 0.5 else 1.0)
      else 0.0
    }.sum

  // ------------------------------------------------------------------- grid strength

  test("a short-circuit floor holds against the converters' actual infeed") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Measured against what the converters are doing, not what is installed, which is why
    // curtailing wind is one of the ways to satisfy it -- and on this fixture the LP takes
    // a different route from the inertia cases, so this is not the same row twice.
    assert(controls("scr_costs_more").bool, "the floor was free -- weak fixture")
    val result = agrees("scr")
    val flat   = agrees("none")
    assert(result.objective > flat.objective + 1.0, "the floor cost nothing")

    // The row itself, recomputed from the solution: stiffness against infeed, per zone.
    val config  = configOf("scr")
    val network = variant("stability-toy")
    val units   = reference("units")("stability-toy").obj
    Seq("A", "B").foreach { zone =>
      network.snapshots.indices.foreach { t =>
        val stiffness = units.map { (id, u) =>
          if u("zone").str == zone && u("mode").str == "commit" && !u("fixed").bool then
            u("s_coef").num *
              result.solution.primal(result.model.map.column(Stability.Online, id, t))
          else 0.0
        }.sum
        val infeed = units.map { (id, u) =>
          if u("zone").str == zone && u("ibr_w").num > 0.0 then
            u("ibr_w").num * power(result, network, id, t)
          else 0.0
        }.sum
        assert(stiffness >= config.scrMin * infeed - 1e-3,
          s"zone $zone at snapshot $t: stiffness $stiffness is below " +
            s"${config.scrMin} x infeed $infeed")
      }
    }
  }

  test("the converter weight scales the load a zone's converters put on its row") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Upstream's own `gfm_share` case: 30% of the wind grid-forming, so only 0.7 of its
    // output loads the grid-strength row. Every class ships `ibr_w` at 1.0, which makes the
    // multiplication invisible -- a mutation that ignored the weight entirely agreed with
    // PyPSA on every other case in this file.
    assert(controls("gfm_share_changes_the_answer").bool,
      "a 30% grid-forming fleet changed nothing -- weak fixture")
    val shared = agrees("scr-gfm-share")
    val full   = solveCase("scr")
    assert(shared.objective < full.objective - 1.0,
      "a partly grid-forming fleet did not make the floor cheaper to meet")
    assertEqualsDouble(configOf("scr-gfm-share").tech("ibr_wind").converterWeight, 0.7, 1e-9,
      "the override did not reach the config")
  }

  test("a derated machine's online ceiling is p_max_pu times p_nom, not p_nom") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Every committable unit elsewhere sits at `p_max_pu = 1`, which makes the two the same
    // number. Here the gas holds 400 of its 500 MW, and zone B's floor is set on both sides
    // of what that allows -- so the ceiling decides feasibility rather than a tolerance.
    assert(controls("derated_ceiling_decides_feasibility").bool,
      "both floors gave the same verdict -- weak fixture")
    agrees("ek-derated"): Unit
    assertEquals(stored("ek-derated-hard")("condition").str, "infeasible")
    assertEquals(solveCase("ek-derated-hard").status, SolveStatus.PrimalInfeasible,
      "a floor above what the derated machine can hold came back feasible")
  }

  test("a zone whose only converters are grid-forming gets no requirement") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Grid-forming units are excluded from the load side by `ibr_w`, which their class sets
    // to zero: a converter that supplies fault current is not a load on the row it helps
    // satisfy. Upstream skips a zone with no loading converter altogether, and so does this.
    //
    // Structural rather than behavioural, and it has to be: the terms are the same either
    // way, because a coefficient of `-scrMin x 0` is zero. What differs is whether the zone
    // gets a row -- a vacuous `S >= 0` that every dispatch satisfies, plus a priced slack
    // column that can never be needed. A mutation removing the filter stayed green without
    // this, precisely because no objective can see it.
    val noLoad = zoneData.copy(
      scrMin = 1.5,
      scrExempt = Set.empty,
      scrSlackPenalty = 1.0,
      tech = tech
        .updated("ibr_wind", tech("ibr_wind").copy(converterWeight = 0.0))
        .updated("ibr_solar", tech("ibr_solar").copy(converterWeight = 0.0))
        .updated("ibr_battery", tech("ibr_battery").copy(converterWeight = 0.0)),
    )
    val model = Lopf.build(variant("stability-toy"), Lopf.Families(stability = noLoad))
    Seq("A", "B").foreach { zone =>
      assert(!model.map.columns.contains((Stability.Slack, s"SCR_$zone", 0)),
        s"zone $zone got a grid-strength row with nothing to hold a ratio against")
    }
    // And with the weights back, both zones do get one -- so the assertion above is about
    // the filter and not about the config having switched the family off.
    val loaded = noLoad.copy(tech = tech)
    val with_  = Lopf.build(variant("stability-toy"), Lopf.Families(stability = loaded))
    Seq("A", "B").foreach { zone =>
      assert(with_.map.columns.contains((Stability.Slack, s"SCR_$zone", 0)),
        s"zone $zone lost its grid-strength row")
    }
  }

  test("an exempt zone gets no row at all") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Denmark's case. Asserted structurally as well as behaviourally, because on this
    // network the exemption happens to cost nothing -- the objective is the same as the
    // control's -- while the dispatch is not the same at all. A port that emitted the row
    // anyway would match the objective and fail the dispatch.
    val exempt = agrees("scr-exempt-a")
    val both   = solveCase("scr")
    assert(both.objective > exempt.objective + 1.0,
      "exempting a zone did not make the problem cheaper, so the exemption did nothing")
    // No slack column and no row for the exempt zone; the other zone still has both.
    val model = Lopf.build(variant("stability-toy"),
      Lopf.Families(stability = configOf("scr-exempt-a").copy(scrSlackPenalty = 1.0)))
    assert(!model.map.columns.contains((Stability.Slack, "SCR_A", 0)),
      "the exempt zone got a requirement anyway")
    assert(model.map.columns.contains((Stability.Slack, "SCR_B", 0)),
      "the zone that is not exempt lost its requirement")
  }

  test("a joint zone carries both its stiffness and its converters into the host's row") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The Öresund case: DK2 belongs electrically with SE4, and crediting it a constant
    // stiffness would count SE4's machines twice. The share brings the exempt zone's wind
    // across as well, which is what makes it cost the host something -- so a port that
    // folded in the stiffness and forgot the infeed gets a cheaper answer here.
    val joint = agrees("scr-joint")
    val alone = solveCase("scr-exempt-a" /* B exempt, A not: the no-share comparison */)
    assert(joint.objective > 1.0,
      "the joint case cost nothing, so the folded-in wind reached nobody")
    assert(alone.status == SolveStatus.Optimal, "the comparison case did not solve")

    // The share on the stiffness side needs a member zone with a machine actually online,
    // and in `scr-joint` there is none: the host's reservoir is cheaper per MVA even at half
    // credit, and curtailing wind is cheaper still, so B's gas never runs and the share
    // multiplies zero. A mutation that dropped it stayed green on every case above.
    //
    // `scr-joint-online` is a near-islanded B -- a 100 MW link against a 900 MW load -- whose
    // gas produces about 260 MW whatever the requirement says.
    assert(controls("joint_member_has_stiffness_online").bool,
      "the folded-in zone has no machine online -- weak fixture")
    // B also has a must-run machine, so its stiffness reaches the row through two separate
    // terms -- an online column and a constant -- each carrying the share on its own. Only
    // the online one was covered at first, and a mutation that dropped the share from the
    // constant stayed green.
    assert(controls("joint_member_has_a_constant").bool,
      "the folded-in zone contributes no constant -- weak fixture")
    val online = agrees("scr-joint-online")
    val units  = reference("units")("stability-joint").obj
    val config = configOf("scr-joint-online")
    val share  = config.scrJoint("A")("B")
    val at     = (id: String, t: Int) =>
      online.solution.primal(online.model.map.column(Stability.Online, id, t))
    val network = variant("stability-joint")
    // A's row, written out: A's own stiffness plus `share` of B's, against A's own infeed
    // plus `share` of B's. Recomputed rather than read off a dual, because the point is that
    // the row this port built is the row upstream describes.
    network.snapshots.indices.foreach { t =>
      val stiffness = units.map { (id, u) =>
        val credit = if u("zone").str == "A" then 1.0 else share
        if u("mode").str == "commit" && !u("fixed").bool then
          u("s_coef").num * at(id, t) * credit
        // The must-run machine, which has no online column: its contribution is
        // `s x p_max_pu x cap`, a constant, and it carries the share too.
        else if u("mode").str == "commit" && u("fixed").bool then
          u("s_coef").num * u("cap").num * credit
        else 0.0
      }.sum
      val infeed = units.map { (id, u) =>
        if u("ibr_w").num > 0.0 then
          u("ibr_w").num * power(online, network, id, t) *
            (if u("zone").str == "A" then 1.0 else share)
        else 0.0
      }.sum
      assertEqualsDouble(stiffness, config.scrMin * infeed, 1e-2 * math.max(1.0, infeed),
        s"A's joint row is not tight at snapshot $t: $stiffness against " +
          s"${config.scrMin} x $infeed")
    }
  }

  test("inertia and grid strength together do not write into each other's rows") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Two families over the same online columns with different coefficients, which is the
    // arrangement where a right-hand side assembled from the wrong constant map shows up.
    agrees("ek-and-scr"): Unit
  }

  // ----------------------------------------------------------------------- expansion

  test("grid strength is built rather than curtailed for: a synchronous condenser") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // A condenser contributes from its capacity whatever the market does, and draws a
    // little auxiliary power for it. The capacity is the assertion: it is the requirement
    // divided by the condenser's stiffness, so a port that used the wrong reactance or
    // forgot the transformer builds a different machine.
    val result = agrees("exp-syncon")
    assert(result.capacity("Generator", "Z syncon") > 100.0,
      "no condenser was built, so the wind must have been curtailed instead")
  }

  test("grid strength is built rather than curtailed for: a grid-forming battery") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Two things at once, and both are coefficients a port can get wrong in isolation: it
    // contributes `sk_pu x avail` per MW of capacity whatever it is doing, and it is
    // *not* a load in the row it helps satisfy, because its class sets `ibr_w` to zero.
    val result = agrees("exp-gfm")
    val built  = result.capacity("StorageUnit", "Z battery gfm")
    assert(built > 1.0, "no grid-forming battery was built")
    val gfm = reference("tech")("gfm")
    val network = variant("stability-exp-gfm")
    val worst = network.snapshots.indices.map(t => power(result, network, "Z wind", t)).max
    assertEqualsDouble(
      built * gfm("sk_pu").num * gfm("avail").num,
      configOf("exp-gfm").scrMin * worst,
      1e-3 * math.max(1.0, worst),
      "the battery's contribution is not sk_pu x avail per MW against the wind it covers",
    )
  }

  test("an extendable machine's online capacity is capped by what was built") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The fourth commitment row, and the one an expansion model needs: with no condenser
    // on offer the gas is built for its stiffness, and `u <= p_max_pu x P` has to be a row
    // against the capacity column rather than a bound, because PyPSA's expansion idiom
    // starts that column's `p_nom` at zero.
    val result = agrees("exp-gas")
    val built  = result.capacity("Generator", "Z gas")
    assert(built > 0.0, "no gas was built, so nothing provided the stiffness")
    val network = variant("stability-exp-gas")
    network.snapshots.indices.foreach { t =>
      val online = result.solution.primal(result.model.map.column(Stability.Online, "Z gas", t))
      assert(online <= built + 1e-6,
        s"$online MW online at snapshot $t out of $built MW built")
    }
  }

  // ------------------------------------------------------------------- the refusals

  private def refusal(n: Network, config: Stability.Config): String =
    intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(n, Lopf.Families(stability = config))
    }.getMessage

  test("a class that hands out free inertia is refused, as NordPSA refuses it") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // `m_min = 0` on a non-must-run class satisfies `p >= 0 x u` for nothing, so the LP
    // brings the whole fleet online at no cost and meets any requirement with a model that
    // did nothing. This is the one refusal that is about the '''price''' of the
    // formulation rather than about its shape, and it is upstream's own.
    assert(reference("refused")("free-inertia")("refused").bool,
      "NordPSA accepted a zero minimum stable fraction")
    val free = zoneData.copy(
      tech = tech.updated("gas", tech("gas").copy(minStableFraction = 0.0)),
      systemInertiaGws = 2.0,
    )
    val message = refusal(variant("stability-toy"), free)
    assert(message.contains("B gas") && message.contains("minimum stable fraction"), message)
  }

  test("an scrJoint that would double-count, or overcount, is refused") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Every arm upstream checks, and `reference("refused")` records that it checks them, so
    // the two sides reject the same configurations rather than this port inventing rules.
    val recorded = reference("refused")
    Seq("unknown-zone", "host-exempt", "member-not-exempt", "share-zero", "share-above-1")
      .foreach { name =>
        assert(recorded(name)("refused").bool, s"NordPSA accepted $name")
      }
    val base = zoneData.copy(scrMin = 1.5, scrExempt = Set("B"))
    val cases = Seq(
      "an unknown member" -> base.copy(scrJoint = Map("A" -> Map("C" -> 0.3))),
      "an exempt host"    -> base.copy(scrJoint = Map("B" -> Map("B" -> 0.3))),
      "a member that is not exempt" ->
        base.copy(scrExempt = Set("A"), scrJoint = Map("A" -> Map("B" -> 0.3))),
      "a zero share"      -> base.copy(scrJoint = Map("A" -> Map("B" -> 0.0))),
      "a share above one" -> base.copy(scrJoint = Map("A" -> Map("B" -> 1.5))),
    )
    cases.foreach { (what, config) =>
      val message = refusal(variant("stability-toy"), config)
      assert(message.contains("scrJoint"), s"$what: $message")
    }

    // The sum arm needs two hosts taking a share of one exempt zone, which a two-zone
    // network cannot express -- so it gets a third. Written first as one host at 0.7, which
    // is a legal configuration and refused nothing: the case passed by intercepting a
    // different arm's refusal, which is the vacuous kind of test.
    val message = refusal(threeZones, base.copy(
      scrExempt = Set("C"),
      scrJoint = Map("A" -> Map("C" -> 0.7), "B" -> Map("C" -> 0.7)),
    ))
    assert(message.contains("scrJoint") && message.contains("more than the zone"), message)
  }

  /** `stability-toy` with a third zone, for the one arm two zones cannot reach. */
  private def threeZones: Network = copiedWith(
    variantDir("stability-toy"),
    "stability-toy",
    "buses.csv" -> "name\nA\nB\nC\n",
    "links.csv" ->
      ("name,bus0,bus1,carrier,p_nom,p_min_pu\n" +
        "A-B,A,B,AC,2000.0,-1.0\n" +
        "B-C,B,C,AC,2000.0,-1.0\n"),
    "generators.csv" ->
      ("name,bus,p_nom,p_max_pu,carrier,marginal_cost\n" +
        "A wind,A,2000.0,0.9,wind_onshore,0.0\n" +
        "B wind,B,1000.0,0.9,wind_onshore,0.0\n" +
        "B gas,B,500.0,1.0,gas,100.0\n" +
        "C gas,C,500.0,1.0,gas,100.0\n"),
    "loads.csv" -> "name,bus,p_set\nA load,A,800.0\nB load,B,300.0\nC load,C,100.0\n",
  )

  test("a floor against a bus that is not a zone is refused; a weight against one is not") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The distinction the guard turns on. A floor asks for something, so a floor naming a
    // bus that is not an electrical zone is silently dropped and the run reports a number
    // as though it had applied. A weight and an exemption ask for nothing -- an absent zone
    // weighs its default of 1 and is exempt from a row it never had -- and `zones.yaml`
    // carries one weight block for the whole Nordic system, so every study over part of it
    // has entries for buses it does not contain. This fixture is exactly that case: the
    // shipped weight block names DK, and this network has no DK.
    val message = refusal(variant("stability-toy"),
      zoneData.copy(systemInertiaGws = 2.0, zoneInertiaFloorGws = Map("NO2" -> 1.0)))
    assert(message.contains("NO2") && message.contains("matches no AC bus"), message)

    // A bus that exists but is not electrical is the same mistake with a better disguise.
    // `stability-mustrun` has a fuel bus for its CHP link, and a floor against it asks for
    // rotational energy from a pipe. Reached only through a refusal: no unit is classified
    // there, so widening the zone set to every bus changes no row and no objective.
    //
    // The REASON is asserted and not just the name, and that is what makes this catch it. A
    // mutation that treated every bus as a zone still refused this floor -- the row it then
    // emitted had no terms and a positive right-hand side, which is its own refusal, and it
    // names the zone too. Matching on the name alone passed either way.
    val fuel = refusal(variant("stability-mustrun"),
      zoneData.copy(systemInertiaGws = 2.0, zoneInertiaFloorGws = Map("A chp fuel" -> 1.0)))
    assert(fuel.contains("A chp fuel") && fuel.contains("matches no AC bus"), fuel)

    assert(zoneData.syncWeight.contains("DK"), "the shipped weight block no longer names DK")
    assert(!variant("stability-toy").require("Bus").has("DK"), "this network gained a DK bus")
    Lopf.build(variant("stability-toy"),
      Lopf.Families(stability = zoneData.copy(systemInertiaGws = 2.0))): Unit
  }

  test("a NaN requirement is refused rather than read as off") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // The hole this module family has shipped twice: `isOff` testing `<= 0.0` while
    // emission selects `> 0.0`, and NaN being neither -- so the config reads as on, nothing
    // is emitted, and the run reports the unconstrained objective as though it had been
    // constrained. One predicate, `!(v > 0.0)`, and every non-finite value named.
    Seq(
      "the system requirement" -> zoneData.copy(systemInertiaGws = Double.NaN),
      "the short-circuit floor" -> zoneData.copy(scrMin = Double.NaN),
      "a zonal floor" -> zoneData.copy(zoneInertiaFloorGws = Map("B" -> Double.NaN)),
    ).foreach { (what, config) =>
      val message = refusal(variant("stability-toy"), config)
      assert(message.contains("NaN"), s"$what: $message")
    }
  }

  test("a mapping onto a class that does not exist is refused") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // A unit mapped onto a missing class falls out of the fleet, contributes no inertia,
    // and the run reports a requirement met by a fleet it forgot about. The failure is one
    // character wide and entirely silent.
    val message = refusal(variant("stability-toy"),
      zoneData.copy(systemInertiaGws = 2.0,
        mapping = zoneData.mapping.updated("Generator:gas", "gaz")))
    assert(message.contains("gaz"), message)
  }

  test("a synchronous class with no reactance is refused rather than reaching a coefficient") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // Without it the stiffness is 1/(NaN + x_t) or a division by the transformer alone, and
    // the failure surfaces hundreds of lines away as a non-finite constraint coefficient on
    // an anonymous column -- in a module whose every other refusal names the machine.
    val message = refusal(variant("stability-toy"),
      zoneData.copy(systemInertiaGws = 2.0,
        tech = tech.updated("gas", tech("gas").copy(subtransientReactance = Double.NaN))))
    assert(message.contains("gas") && message.contains("reactance"), message)
  }

  test("a mapping that classifies nothing on this network is refused by name") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // A requirement over no units is not satisfied, it is a claim about a fleet that is not
    // there. Refused here rather than reaching the row builder, which would raise an
    // infeasibility with no explanation of where it came from.
    //
    // A mapping for another model's fleet is how this happens in practice, and it is the
    // case written here. It was first written as `ac-dc-meshed` under NordPSA's own
    // mapping, on the assumption that a British test network has no carrier the Nordic
    // mapping knows -- and it has two, `gas` and `wind`, so the requirement was emitted and
    // the test asserted nothing.
    val elsewhere = zoneData.copy(
      mapping = Map("Generator:coal" -> "thermal", "Generator:lignite" -> "thermal"),
      systemInertiaGws = 2.0,
    )
    val message = refusal(variant("stability-toy"), elsewhere)
    assert(message.contains("no unit"), message)
  }

  test("a snapshot a machine does not exist at gets no online column") {
    assume(available, "reference/goldens is not present")
    // Skipped rather than refused, which is the choice `BidLadder` documents: a requirement
    // spans every snapshot and a multi-period machine legitimately is absent from some of
    // them, so refusing would make the family unusable on an expansion horizon rather than
    // correct. Without a multi-period fixture the guard was untested and a mutation that
    // removed it survived.
    //
    // `investment-periods` is two periods of two snapshots with a generator built in the
    // second, so it exists at two of the four. Its carrier is added here because the golden
    // has none and an unclassified unit would make this test about an empty fleet.
    val periods = copiedWith(
      goldens.resolve("networks").resolve("investment-periods"),
      "investment-periods",
      "generators.csv" ->
        ("name,bus,control,p_nom,marginal_cost,build_year,lifetime,carrier\n" +
          "new,b,Slack,200.0,5.0,2040,30.0,gas\n" +
          "old,b,PQ,200.0,80.0,0,inf,gas\n"),
    )
    val table  = periods.require("Generator")
    val active = periods.snapshots.indices.filter(t => Periods.activeAt(periods, table, "new", t))
    assert(active.nonEmpty, "the late machine is active at no snapshot -- weak fixture")
    assert(active.length < periods.snapshots.length,
      "the late machine is active at every snapshot, so nothing is being skipped")

    val model = Lopf.build(periods,
      Lopf.Families(stability = zoneData.copy(systemInertiaGws = 0.5)))
    periods.snapshots.indices.foreach { t =>
      assertEquals(
        model.map.columns.contains((Stability.Online, "new", t)),
        active.contains(t),
        s"the late machine's online column at snapshot $t does not match whether it exists",
      )
      // And the machine that is always there always has one, so the assertion above is
      // about activity and not about the family having emitted nothing.
      assert(model.map.columns.contains((Stability.Online, "old", t)),
        s"the machine that exists throughout lost its online column at snapshot $t")
    }
  }

  test("the requirement rows keep the row identity Sclopf depends on") {
    assume(fixtures, "reference/nordpsa stability fixtures are not present")
    // This family emits no equality -- three inequalities per online unit and one per
    // requirement -- so unlike `TerminalValue` and `BidLadder` its placement cannot break
    // the identity. Asserted rather than assumed, because "emits no equality" is a property
    // of the code and a later row added as an equality would break `Sclopf` silently.
    val model = Lopf.build(variant("stability-toy"),
      Lopf.Families(stability = configOf("ek-both")))
    val problem = model.problem
    assert(problem.numConstraints > problem.numEqualities, "no inequality rows -- weak test")
    val plain = Lopf.build(variant("stability-toy"))
    assert(problem.numVariables > plain.problem.numVariables,
      "no online columns were added, so there is nothing here to misindex")
    assert(problem.numEqualities == plain.problem.numEqualities,
      "this family emitted an equality, which its placement in Lopf.build does not allow")

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
