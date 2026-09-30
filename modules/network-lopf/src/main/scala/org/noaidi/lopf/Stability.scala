package org.noaidi.lopf

import scala.collection.mutable
import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** Rotational-energy and grid-strength requirements, with linearised commitment.
  *
  * A port of NordPSA's `stability_constraints`. The last of its `extra_functionality`
  * families, and the one whose status in `reference/nordpsa/README.md` was wrong for three
  * families' worth of commits: it said this reaches into MILP. It does not. The online
  * variable is '''continuous''':
  *
  * {{{
  * 0 <= u(i,t) <= p_max_pu(i,t) · P(i)
  * p(i,t) − u(i,t)                <= 0
  * p(i,t) − m_min(i) · u(i,t)     >= 0
  * }}}
  *
  * which is the textbook LP relaxation of unit commitment — "linjäriserad inkoppling" in
  * the upstream module's own first line. Nothing in the family needs an integer, so all of
  * it fits this layer.
  *
  * ==Why inertia costs anything when switching a machine on is free==
  *
  * The second row. `p >= m_min · u` means capacity brought online has to '''produce''',
  * and that production displaces something cheaper or spends water with a positive value.
  * `m_min` therefore sets the entire price of inertia — which is why a non-must-run class
  * with `m_min = 0` is refused rather than solved: it would hand out rotational energy for
  * nothing, and the LP would meet any requirement at zero cost.
  *
  * ==The three requirements==
  *
  * Per zone `z` and snapshot `t`, with `e = eff·H/cosφ` [MWs/MW] and
  * `s = eff/((X''_d + X_T)·cosφ)` [MVA/MW]:
  *
  * {{{
  * E(z,t) = Σ_i∈z e(i)·u(i,t) + Σ_j∈z e(j)·avail(j)·P(j) + K(z,t)
  * S(z,t) = Σ_i∈z s(i)·u(i,t) + Σ_j∈z s(j)·avail(j)·P(j) + Kˢ(z,t)
  *
  * Σ_z w(z)·E(z,t) + σ(t)   >= 1e3 · systemInertiaGws
  * E(z,t)          + σ(z,t) >= 1e3 · zoneInertiaFloorGws(z)
  * S(z,t) − scrMin·P_ibr(z,t) + σ(z,t) >= 0
  * }}}
  *
  * `j` ranges over the units that contribute from their '''capacity''' rather than their
  * operation — synchronous condensers, which spin whatever the market does, and
  * grid-forming batteries, which supply fault current from their converter. `K` is the
  * must-run units and the fixed `j` capacity: constants out of the data, not variables.
  *
  * `P_ibr` is measured against the converters' '''actual''' infeed and not their installed
  * capacity, so the LP has four ways to satisfy a grid-strength floor: run more synchronous
  * plant, build a condenser, build a grid-forming battery, or '''curtail''' wind and solar.
  * Which of those is cheapest is the answer the model exists to give.
  *
  * ==Inertia is a system quantity; the zonal numbers are a robustness measure==
  *
  * One synchronous area has one frequency, so `E(z,t)` per zone is not physics — it is a
  * floor against the area splitting. The system row weights the zones by `syncWeight`,
  * which is how a zone that is only partly inside the synchronous area enters: NordPSA
  * gives Denmark 0.35, because only DK2 is. HVDC contributes no inertia at all, which is
  * why the continental valves map to a class with `H = 0`.
  *
  * ==Joint short-circuit zones==
  *
  * An exempt zone can be folded into a neighbour's requirement with a share `a`, carrying
  * '''both''' its stiffness and its converter infeed:
  *
  * {{{
  * S(host,t) + a·S(z,t) − scrMin·(P_ibr(host,t) + a·P_ibr(z,t)) + σ >= 0
  * }}}
  *
  * DK2 belongs electrically with SE4 across Öresund, and a constant stiffness credited to
  * DK2 would count SE4's machines twice. Only exempt zones may be folded in, for the same
  * reason, and a zone's shares may not sum past one — all three refused here as upstream
  * refuses them.
  *
  * ==What is not here==
  *
  * NordPSA's `analysis/stability.py` measures these quantities on a solved network —
  * `stability_metrics`, the `lo`/`hi`/`max` bracketing of an unknown commitment, the
  * summary tables — and `stability_feasibility_report` predicts infeasibility before a
  * solve. Both are measurement rather than formulation, so neither is ported; the port's
  * business is the rows.
  *
  * ==A caution the aggregation carries==
  *
  * A zone value treats every machine in the zone as sitting at one electrical point, which
  * is optimistic, and ignores the neighbours' contribution, which is pessimistic. The two
  * do not cancel, and neither is a property of this formulation — they are properties of
  * asking a zonal model a question about network strength.
  */
object Stability:

  /** The component name the online columns are keyed under. */
  val Online = "Stability-p_online"

  /** The component name the slack columns are keyed under. */
  val Slack = "Stability-slack"

  /** The entity name of the system-wide inertia requirement, as NordPSA labels it. */
  val System = "SYSTEM"

  /** How a technology class contributes. */
  enum Mode:
    /** Synchronous plant whose contribution follows what is online. */
    case Commit

    /** A synchronous condenser: contributes from its capacity, spins regardless. */
    case Syncon

    /** A grid-forming converter: fault current from capacity, derated by availability. */
    case Gfm

    /** A converter that contributes nothing and '''loads''' the grid-strength row. */
    case Ibr

  object Mode:
    /** The spelling NordPSA's `zones.yaml` uses, so the config can be read from it. */
    def parse(text: String): Option[Mode] = text match
      case "commit" => Some(Commit)
      case "syncon" => Some(Syncon)
      case "gfm"    => Some(Gfm)
      case "ibr"    => Some(Ibr)
      case _        => None

  /** One technology class, in the units `zones.yaml` states them in.
    *
    * `subtransientReactance` is `X''_d` and is required for the synchronous modes, where
    * it sets the stiffness; it is unused and may be absent for the others. A grid-forming
    * class gives its fault current as `shortCircuitPerUnit` instead, because a converter's
    * contribution is a design limit rather than a machine reactance.
    */
  final case class Tech(
      mode: Mode,
      inertiaSeconds: Double = 0.0,
      cosPhi: Double = 1.0,
      subtransientReactance: Double = Double.NaN,
      minStableFraction: Double = 0.0,
      availability: Double = 1.0,
      converterWeight: Double = 0.0,
      shortCircuitPerUnit: Double = 0.0,
  )

  /** The technology data, the zone data and the three requirements.
    *
    * Split the way NordPSA splits it: `tech`, `mapping`, `nameOverrides`,
    * `transformerReactance`, `syncWeight`, `scrExempt` and `scrJoint` are the '''data'''
    * out of `zones.yaml`, while the last five are what a run '''asks for'''. A network
    * carries none of it, which is why [[Stability]] takes it as a parameter and why the
    * suite reads it out of the reference file rather than restating it.
    *
    * `mapping` is keyed `"<component>:<carrier>"`, as upstream keys it.
    * `nameOverrides` maps an id '''suffix''' to a class, applied after the carrier
    * mapping and only to units that mapping already classified.
    */
  final case class Config(
      tech: Map[String, Tech] = Map.empty,
      mapping: Map[String, String] = Map.empty,
      nameOverrides: Map[String, String] = Map.empty,
      transformerReactance: Double = 0.12,
      syncWeight: Map[String, Double] = Map.empty,
      scrExempt: Set[String] = Set.empty,
      scrJoint: Map[String, Map[String, Double]] = Map.empty,
      systemInertiaGws: Double = 0.0,
      zoneInertiaFloorGws: Map[String, Double] = Map.empty,
      scrMin: Double = 0.0,
      slackPenalty: Double = 0.0,
      scrSlackPenalty: Double = 0.0,
  ):
    /** Whether this asks for nothing, in which case [[constrain]] emits nothing.
      *
      * `== 0.0`, which is "the field is at its default", and not `!(v > 0.0)`, which is
      * "the field would emit nothing". Those differ on exactly the values that are neither
      * zero nor positive — negatives and NaN — and the difference is the whole hole this
      * module family has now opened three times.
      *
      * `!(v > 0.0)` is right for an emission predicate and wrong for an off switch. As an
      * off switch it makes `systemInertiaGws = NaN` read as "nothing was asked for", so
      * [[constrain]] returns before any refusal runs and the solve reports the
      * '''unconstrained''' objective as though a requirement had applied. It was written
      * that way here first, on the reasoning that mirroring the emission predicate is what
      * closed the hole in `HydroOps` and `TerminalValue` — and in those two the predicate
      * being mirrored was the '''guard''', not the early return.
      *
      * With `== 0.0`, every value that is not the default and not positive falls through to
      * [[checkZones]], which names it. The floors are `isEmpty` for the same reason: an
      * entry of NaN is an entry.
      */
    def isOff: Boolean =
      systemInertiaGws == 0.0 && zoneInertiaFloorGws.isEmpty && scrMin == 0.0

  /** No stability requirements, which is what a plain PyPSA network means. */
  val off: Config = Config()

  /** The electrical zones: the AC buses, which is what a zonal requirement is indexed by.
    *
    * One definition, and it was two -- the row builder had a copy and [[unitTable]] had
    * another. They had to agree and nothing made them: a mutation that widened the filter in
    * one of them survived the whole suite, because every refusal still read the other and
    * every row still read the first. A unit would have been classified onto a bus the guards
    * called a non-zone.
    *
    * `carrier == "AC"` is upstream's filter. A CHP link's fuel bus, a DC link's converter
    * bus and a heat bus are all buses and none of them has a frequency.
    */
  private def zonesOf(network: Network): Set[String] =
    network.table("Bus")
      .map(bus => bus.ids.filter(id => bus.string("carrier", id) == "AC").toSet)
      .getOrElse(Set.empty)

  /** The bus attribute naming the zone a unit's electrical output lands in.
    *
    * `bus1` for a Link, because a combined-heat-and-power link is modelled on its
    * '''fuel''' side: `bus0` is the fuel bus, `p_nom` is a fuel rating, and the electrical
    * machine sits at the other end. That is also why the coefficients carry `efficiency`.
    */
  private def busAttribute(component: String): String =
    if component == "Link" then "bus1" else "bus"

  /** The dispatch column a unit's `p` is, per component. */
  private def dispatchKey(component: String): String =
    if component == "StorageUnit" then Storage.Dispatch else component

  /** One classified machine, with its coefficients already in per-MW-of-capacity form. */
  private final case class Machine(
      id: String,
      component: String,
      table: ComponentTable,
      techName: String,
      zone: String,
      mode: Mode,
      extendable: Boolean,
      capacity: Double,
      fixed: Boolean,
      minStableFraction: Double,
      availability: Double,
      converterWeight: Double,
      inertiaCoefficient: Double,
      stiffnessCoefficient: Double,
  ):
    /** What one MW of capacity contributes when the contribution is not operational.
      *
      * A condenser is credited in full because it spins whether or not the market wants
      * it; a grid-forming converter is derated, because its fault contribution depends on
      * the converter being available.
      */
    def capacityFactor: Double = if mode == Mode.Gfm then availability else 1.0

  /** `e` and `s` for one class, per MW of '''active''' rating.
    *
    * `s` is a machine reactance for the synchronous modes and a converter design limit for
    * a grid-forming one; everything else has no stiffness at all, which is the whole point
    * of the grid-strength requirement.
    */
  private def coefficients(config: Config, tech: Tech): (Double, Double) =
    val e = tech.inertiaSeconds / tech.cosPhi
    val s = tech.mode match
      case Mode.Commit | Mode.Syncon =>
        1.0 / ((tech.subtransientReactance + config.transformerReactance) * tech.cosPhi)
      case Mode.Gfm => tech.shortCircuitPerUnit
      case Mode.Ibr => 0.0
    (e, s)

  /** Every unit the configuration classifies, with its coefficients.
    *
    * Mirrors NordPSA's `unit_table`: `(component, carrier)` through `mapping`, then the
    * name-suffix overrides, then the electrical-bus and capacity filters. `fixed` means
    * `p_min_pu` equals `p_max_pu` at every snapshot, which is how upstream recognises a
    * must-run unit — its commitment is decided by the data, so it gets no online variable
    * and enters the requirements as a constant.
    */
  private def unitTable(network: Network, snapshots: Range, config: Config): IndexedSeq[Machine] =
    val zones = zonesOf(network)

    val unknown = config.mapping.values.filterNot(config.tech.contains).toSeq.distinct.sorted ++
      config.nameOverrides.values.filterNot(config.tech.contains).toSeq.distinct.sorted
    if unknown.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"Stability was given a mapping onto technology classes it does not define: " +
          unknown.mkString(", ") + s". Defined classes are " +
          (if config.tech.isEmpty then "none" else config.tech.keys.toSeq.sorted.mkString(", ")) +
          ". A unit mapped onto a missing class falls silently out of the fleet and its " +
          "inertia is never counted.",
      )

    // The synchronous modes need a reactance, and a missing one would become an infinite
    // or NaN stiffness coefficient hundreds of lines from here.
    val missingReactance = config.tech.collect {
      case (name, t) if (t.mode == Mode.Commit || t.mode == Mode.Syncon) &&
        !(t.subtransientReactance > 0.0) => s"$name (X''_d = ${t.subtransientReactance})"
    }.toSeq.sorted
    if missingReactance.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        "Stability needs a positive subtransient reactance for every synchronous class, " +
          s"and these have none: ${missingReactance.mkString(", ")}. Without one the " +
          "stiffness coefficient is infinite or NaN, and a NaN reaches the solver as an " +
          "anonymous constraint coefficient.",
      )

    IndexedSeq("Generator", "StorageUnit", "Link").flatMap { component =>
      network.tables.get(component).toIndexedSeq.flatMap { table =>
        val bus = busAttribute(component)
        table.ids.flatMap { id =>
          val carrier   = table.string("carrier", id)
          val mapped    = config.mapping.get(s"$component:$carrier")
          // Applied only where the carrier mapping already classified the unit, which is
          // upstream's rule: a suffix is a refinement of a known class, not a way to
          // classify something the mapping left out.
          val techName = mapped.map { base =>
            config.nameOverrides.collectFirst { case (suffix, t) if id.endsWith(suffix) => t }
              .getOrElse(base)
          }
          val extendable = Expansion.isExtendable(table, id)
          val pNom       = table.float("p_nom", id)
          techName.filter { _ =>
            zones.contains(table.string(bus, id)) && (pNom > 0.0 || extendable)
          }.map { name =>
            val tech      = config.tech(name)
            val (e, s)    = coefficients(config, tech)
            // A CHP link's rating is on its fuel side, so its electrical machine is
            // `efficiency` times as large. Folded into the coefficients, as upstream does,
            // so everything downstream is per MW of `p_nom`.
            val efficiency = if component == "Link" then table.float("efficiency", id) else 1.0
            Machine(
              id = id,
              component = component,
              table = table,
              techName = name,
              zone = table.string(bus, id),
              mode = tech.mode,
              extendable = extendable,
              capacity = pNom,
              fixed = snapshots.forall { t =>
                math.abs(table.valueAt("p_max_pu", id, t) - table.valueAt("p_min_pu", id, t)) < 1e-9
              },
              minStableFraction = tech.minStableFraction,
              availability = tech.availability,
              converterWeight = tech.converterWeight,
              inertiaCoefficient = e * efficiency,
              stiffnessCoefficient = s * efficiency,
            )
          }
        }
      }
    }

  /** `{host: {zone: share}}`, checked against the zones as upstream checks it.
    *
    * Only an exempt zone may be folded into a host — otherwise its machines are counted
    * in its own row and again in the host's — the host must have a row of its own, and one
    * zone's shares may not sum past its whole self.
    */
  private def checkJoint(config: Config, zones: Set[String]): Unit =
    val problems = mutable.ArrayBuffer.empty[String]
    val used     = mutable.Map.empty[String, Double]
    config.scrJoint.foreach { (host, members) =>
      if !zones.contains(host) then problems += s"host '$host' is not an electrical zone"
      else if config.scrExempt.contains(host) then
        problems += s"host '$host' is itself exempt, so it has no requirement to fold into"
      members.foreach { (zone, share) =>
        if !zones.contains(zone) then problems += s"'$zone' is not an electrical zone"
        else if !config.scrExempt.contains(zone) then
          problems += s"'$zone' is not exempt, so folding it in would count it twice"
        if !(share > 0.0 && share <= 1.0) then
          problems += s"the share for '$zone' is $share, which is not in (0, 1]"
        used(zone) = used.getOrElse(zone, 0.0) + share
      }
    }
    used.foreach { (zone, total) =>
      if total > 1.0 + 1e-9 then
        problems += s"the shares for '$zone' sum to $total, which is more than the zone"
    }
    if problems.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"Stability was given an scrJoint that cannot hold: ${problems.mkString("; ")}.",
      )

  /** Refuse configuration that names something the network does not have.
    *
    * The same argument as `HydroOps.inertZones`, but applied to one of the three zone maps
    * and not the other two, and the difference is the point.
    *
    * A '''floor''' asks for something. Named against a bus that is not an electrical zone
    * it is silently dropped upstream, and the run then reports a number as though the
    * requirement had applied — the failure this whole family of guards exists for.
    *
    * A '''weight''' and an '''exemption''' do not ask for anything. An absent zone weighs
    * its default of 1 and an absent zone is exempt from a row it never had, so an entry for
    * a zone outside the network is inert '''by design''': `zones.yaml` carries one weight
    * block and one exempt list for the whole Nordic system, and any study over part of it
    * legitimately has entries for buses it does not contain. Refusing those would refuse
    * every sub-network, which is not the same kind of mistake at all.
    *
    * A non-finite floor is the NaN hole in its other form.
    */
  private def checkZones(network: Network, config: Config, zones: Set[String]): Unit =
    val problems = mutable.ArrayBuffer.empty[String]
    (config.zoneInertiaFloorGws.keySet -- zones).toSeq.sorted.foreach { z =>
      problems += s"the inertia floor for '$z' matches no AC bus"
    }
    config.zoneInertiaFloorGws.toSeq.sortBy(_._1).foreach { (z, floor) =>
      if floor.isNaN then problems += s"the inertia floor for '$z' is NaN"
      else if floor < 0.0 then problems += s"the inertia floor for '$z' is negative ($floor)"
    }
    config.syncWeight.toSeq.sortBy(_._1).foreach { (z, weight) =>
      if !(weight >= 0.0 && weight <= 1.0) then
        problems += s"the synchronous weight for '$z' is $weight, which is not in [0, 1]"
    }
    // Every value that `isOff` does not treat as the default and `constrain` does not
    // treat as positive has to be named here, or it falls between the two and emits
    // nothing while the run reports a number.
    if config.systemInertiaGws.isNaN then problems += "systemInertiaGws is NaN"
    else if config.systemInertiaGws < 0.0 then
      problems += s"systemInertiaGws is negative (${config.systemInertiaGws})"
    if config.scrMin.isNaN then problems += "scrMin is NaN"
    else if config.scrMin < 0.0 then problems += s"scrMin is negative (${config.scrMin})"
    if config.slackPenalty < 0.0 then
      problems += s"slackPenalty is negative (${config.slackPenalty})"
    if config.scrSlackPenalty < 0.0 then
      problems += s"scrSlackPenalty is negative (${config.scrSlackPenalty})"
    if problems.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' was given stability configuration that cannot take " +
          s"effect: ${problems.mkString("; ")}. Its AC buses are " +
          (if zones.isEmpty then "none" else zones.toSeq.sorted.mkString(", ")) + ".",
      )

  /** Emit the online columns, the three requirement families and their slacks. */
  def constrain(
      network: Network,
      snapshots: Range,
      columns: scala.collection.Map[(String, String, Int), Int],
      declare: (String, String, Int, Double, Double, Double) => Int,
      builder: LpBuilder,
      config: Config,
  ): Unit =
    if config.isOff then return

    val zones = zonesOf(network)

    // Every refusal before a column is allocated.
    checkZones(network, config, zones)
    checkJoint(config, zones)

    val units = unitTable(network, snapshots, config)
    if units.isEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has no unit that the stability mapping classifies, " +
          "so every requirement would be a row over nothing -- infeasible where it asks " +
          "for anything and vacuous where it does not.",
      )

    // The units that get an online variable, and the check that makes inertia cost
    // something. A non-must-run class with `m_min = 0` satisfies `p >= 0 · u` for free, so
    // the LP brings the whole fleet online at no cost and any requirement is met by a
    // model that did nothing.
    val committable = units.filter { u =>
      u.mode == Mode.Commit && !u.fixed && (u.capacity > 0.0 || u.extendable)
    }
    val free = committable.filterNot(_.minStableFraction > 0.0).map(_.id).sorted
    if free.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"Stability would give free rotational energy to ${free.mkString(", ")}: their " +
          "class has no minimum stable fraction, so bringing capacity online costs " +
          "nothing and the requirement is met without displacing anything. Only a " +
          "must-run class -- one whose p_min_pu equals its p_max_pu -- may have m_min 0.",
      )

    // Zone x snapshot, so a requirement row can be assembled per snapshot.
    val inertia   = mutable.Map.empty[(String, Int), mutable.ArrayBuffer[(Int, Double)]]
    val stiffness = mutable.Map.empty[(String, Int), mutable.ArrayBuffer[(Int, Double)]]
    val inertiaConstant   = mutable.Map.empty[(String, Int), Double]
    val stiffnessConstant = mutable.Map.empty[(String, Int), Double]

    def addTerm(
        into: mutable.Map[(String, Int), mutable.ArrayBuffer[(Int, Double)]],
        zone: String, t: Int, column: Int, coefficient: Double,
    ): Unit =
      if coefficient != 0.0 then
        into.getOrElseUpdate((zone, t), mutable.ArrayBuffer.empty) += ((column, coefficient))

    def addConstant(
        into: mutable.Map[(String, Int), Double], zone: String, t: Int, value: Double,
    ): Unit =
      if value != 0.0 then into(zone -> t) = into.getOrElse(zone -> t, 0.0) + value

    // ---- linearised commitment -------------------------------------------------------
    committable.foreach { u =>
      snapshots.foreach { t =>
        // Snapshots the unit exists at, skipped rather than refused where it does not --
        // the choice `BidLadder` documents. A requirement spans every snapshot and a
        // multi-period unit legitimately is absent from some of them.
        if Periods.activeAt(network, u.table, u.id, t) then
          columns.get((dispatchKey(u.component), u.id, t)).foreach { dispatch =>
            val maxPu = u.table.valueAt("p_max_pu", u.id, t)
            // An extendable unit's ceiling is a row against its capacity column, not a
            // bound: the bound would cap the expansion at whatever `p_nom` the network
            // came with, which for PyPSA's expansion idiom is zero.
            val ceiling =
              if u.extendable then Double.PositiveInfinity else maxPu * u.capacity
            val online = declare(Online, u.id, t, 0.0, ceiling, 0.0)
            if u.extendable then
              val capacity = columns((Expansion.capacityKey(u.component), u.id, Expansion.NoSnapshot))
              builder.lessThan(Seq(online -> 1.0, capacity -> -maxPu), 0.0)
            // p <= u, and p >= m_min · u.
            builder.lessThan(Seq(dispatch -> 1.0, online -> -1.0), 0.0)
            builder.greaterThan(Seq(dispatch -> 1.0, online -> -u.minStableFraction), 0.0)
            addTerm(inertia, u.zone, t, online, u.inertiaCoefficient)
            addTerm(stiffness, u.zone, t, online, u.stiffnessCoefficient)
          }
      }
    }

    // ---- capacity that contributes whatever it is doing ------------------------------
    //
    // Condensers and grid-forming batteries, plus a must-run unit that is extendable: all
    // three enter through capacity rather than operation, and for a must-run one the
    // availability at the snapshot is its own `p_max_pu` because that is what the data
    // already decided it would be doing.
    units.foreach { u =>
      val capacityContributor =
        u.mode == Mode.Syncon || u.mode == Mode.Gfm || (u.mode == Mode.Commit && u.fixed)
      if capacityContributor && (u.capacity > 0.0 || u.extendable) then
        snapshots.foreach { t =>
          if Periods.activeAt(network, u.table, u.id, t) then
            val followsProfile = u.mode == Mode.Commit
            val factor =
              if followsProfile then u.table.valueAt("p_max_pu", u.id, t) else u.capacityFactor
            if u.extendable then
              val capacity = columns((Expansion.capacityKey(u.component), u.id, Expansion.NoSnapshot))
              addTerm(inertia, u.zone, t, capacity, u.inertiaCoefficient * factor)
              addTerm(stiffness, u.zone, t, capacity, u.stiffnessCoefficient * factor)
            else
              addConstant(inertiaConstant, u.zone, t, u.inertiaCoefficient * factor * u.capacity)
              addConstant(stiffnessConstant, u.zone, t, u.stiffnessCoefficient * factor * u.capacity)
        }
    }

    // ---- the requirement rows --------------------------------------------------------

    /** One requirement row at one snapshot, with a slack where one is priced.
      *
      * A row with no terms at all is not "satisfied", it is a claim about a fleet that is
      * not there: refused when it asks for anything, and skipped when it does not.
      */
    def requirement(
        label: String, t: Int, terms: Seq[(Int, Double)], rhs: Double, penalty: Double,
    ): Unit =
      val withSlack =
        if penalty > 0.0 then
          // Weighted as `Lopf` weights every other cost at this snapshot, which is what
          // `snapshot_weightings.objective` does upstream and additionally carries the
          // period discount here.
          val slack = declare(Slack, label, t, 0.0, Double.PositiveInfinity,
            penalty * Periods.objectiveWeight(network, t))
          terms :+ (slack -> 1.0)
        else terms
      if withSlack.isEmpty then
        if rhs > 1e-6 then
          throw new Lopf.UnsupportedNetwork(
            s"the stability requirement '$label' asks for $rhs at snapshot $t and there " +
              "is no unit in the model that could contribute to it, so it cannot be met " +
              "by any dispatch. Either the mapping classified nothing in that zone or " +
              "the requirement belongs to a different network.",
          )
      else builder.greaterThan(withSlack, rhs)

    if config.systemInertiaGws > 0.0 then
      val weightOf = (zone: String) => config.syncWeight.getOrElse(zone, 1.0)
      snapshots.foreach { t =>
        val terms = zones.toSeq.sorted.filter(weightOf(_) > 0.0).flatMap { zone =>
          inertia.get(zone -> t).toSeq.flatten.map((c, v) => c -> v * weightOf(zone))
        }
        val constant = zones.toSeq.map(z => inertiaConstant.getOrElse(z -> t, 0.0) * weightOf(z)).sum
        requirement(System, t, terms, 1e3 * config.systemInertiaGws - constant, config.slackPenalty)
      }

    // Only positive floors, which is upstream's filter and the reason a zero entry is
    // legal: it says "this zone has no floor" rather than "this zone must reach zero".
    config.zoneInertiaFloorGws.filter(_._2 > 0.0).toSeq.sortBy(_._1).foreach { (zone, floor) =>
      snapshots.foreach { t =>
        val terms    = inertia.get(zone -> t).toSeq.flatten
        val constant = inertiaConstant.getOrElse(zone -> t, 0.0)
        requirement(zone, t, terms, 1e3 * floor - constant, config.slackPenalty)
      }
    }

    if config.scrMin > 0.0 then
      // Grid-forming units are excluded by `converterWeight`, which their class sets to
      // zero: a converter that supplies fault current is not a load on the requirement it
      // helps meet.
      val converters = units.filter { u =>
        (u.mode == Mode.Ibr || u.mode == Mode.Gfm) && u.converterWeight > 0.0 &&
          (u.capacity > 0.0 || u.extendable)
      }
      zones.toSeq.sorted.filterNot(config.scrExempt.contains).foreach { host =>
        val shares = Map(host -> 1.0) ++ config.scrJoint.getOrElse(host, Map.empty)
        val inZone = converters.filter(u => shares.contains(u.zone))
        // A zone with no converter at all has nothing to hold a ratio against. Upstream
        // skips it, and so does this: a row of `S >= 0` would be satisfied by every
        // dispatch while reading like a requirement.
        if inZone.nonEmpty then
          snapshots.foreach { t =>
            val stiff = shares.toSeq.sortBy(_._1).flatMap { (zone, share) =>
              stiffness.get(zone -> t).toSeq.flatten.map((c, v) => c -> v * share)
            }
            val load = inZone.flatMap { u =>
              if Periods.activeAt(network, u.table, u.id, t) then
                columns.get((dispatchKey(u.component), u.id, t)).map { p =>
                  p -> -config.scrMin * shares(u.zone) * u.converterWeight
                }
              else None
            }
            val constant =
              shares.toSeq.map((z, share) => stiffnessConstant.getOrElse(z -> t, 0.0) * share).sum
            requirement(s"SCR_$host", t, stiff ++ load, -constant, config.scrSlackPenalty)
          }
      }
