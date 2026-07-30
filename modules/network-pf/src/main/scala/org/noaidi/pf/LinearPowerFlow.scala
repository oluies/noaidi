package org.noaidi.pf

import org.noaidi.network.*
import scala.collection.mutable

/** Result of a linear power flow.
  *
  * Angles are in radians, flows and injections in MW. Every accessor throws on an
  * unknown key rather than returning a default: a missing entry means the caller
  * and the solver disagree about what the network contains, and a zero would make
  * that look like a valid answer.
  */
final case class LpfResult(
    private val angles: Map[(String, Int), Double],
    private val flows: Map[(String, String, Int), Double],
    private val busPowers: Map[(String, Int), Double],
    private val dispatches: Map[(String, String, Int), Double],
    slacks: IndexedSeq[Slack.Choice],
):
  def voltageAngle(bus: String, snapshot: Int): Double =
    angles.getOrElse((bus, snapshot), missing(s"angle for bus '$bus' at snapshot $snapshot"))

  def flow(component: String, id: String, snapshot: Int): Double =
    flows.getOrElse((component, id, snapshot), missing(s"flow for $component '$id' at snapshot $snapshot"))

  def busPower(bus: String, snapshot: Int): Double =
    busPowers.getOrElse((bus, snapshot), missing(s"power for bus '$bus' at snapshot $snapshot"))

  def dispatch(component: String, id: String, snapshot: Int): Double =
    dispatches.getOrElse((component, id, snapshot), missing(s"dispatch for $component '$id' at snapshot $snapshot"))

  private def missing(what: String): Nothing =
    throw new NoSuchElementException(s"no $what in this result")

/** Linear power flow — PyPSA's `n.lpf()`.
  *
  * Not an optimisation. Dispatch is '''given''' by each component's `p_set`, and
  * the only unknowns are the voltage angles that carry that dispatch across the
  * network. That is why this module does not depend on Prima: the problem is a
  * linear solve, one symmetric positive-definite system per sub-network per
  * snapshot, and routing it through an LP solver would both misrepresent it and
  * couple two layers with no reason to meet.
  *
  * The model, per sub-network and snapshot:
  *
  *   - Net injection at each bus is `sign × p_set` summed over attached
  *     components, plus the fixed flow of any controllable branch touching it.
  *   - `B θ = p` over the passive branches, with the slack bus's angle fixed at
  *     zero and its row and column struck out.
  *   - Branch flow is `b (θ_bus0 − θ_bus1)`.
  *   - The slack bus's injection is whatever closes the balance, and it is
  *     attributed to the slack generator.
  *
  * Three conventions here were established by solving the reference network
  * rather than read off documentation, and each would have been got wrong
  * otherwise. They are called out at their definitions: [[injection]] for the
  * `p_set`-is-NaN rule, [[susceptance]] for resistance-on-DC, and [[Slack]] for
  * the slack rule.
  */
object LinearPowerFlow:

  /** The network uses a feature this formulation does not model. */
  final class UnsupportedNetwork(message: String) extends RuntimeException(message)

  def solve(network: Network): LpfResult =
    rejectUnhandled(network)

    val subs   = Topology.subNetworks(network)
    val slacks = Slack.all(network, subs)

    val angles    = mutable.Map.empty[(String, Int), Double]
    val flows     = mutable.Map.empty[(String, String, Int), Double]
    val busPowers = mutable.Map.empty[(String, Int), Double]
    val dispatch  = mutable.Map.empty[(String, String, Int), Double]

    // Non-slack dispatch is just p_set, so it does not depend on the flow at all.
    network.tables.values.foreach { table =>
      if Role.of(table.spec) == Role.Attached then
        table.ids.foreach { id =>
          network.snapshots.indices.foreach { t =>
            dispatch((table.spec.name, id, t)) = setpoint(table, id, t)
          }
        }
    }

    slacks.foreach { choice =>
      val sub      = choice.subNetwork
      val branches = internalBranches(network, sub)
      // Angle ordering is the sub-network's own bus order minus the slack, so the
      // matrix index of a bus is stable across snapshots and the factorisation
      // could later be reused.
      val free  = sub.buses.filterNot(_ == choice.bus)
      val index = free.zipWithIndex.toMap
      val n     = free.length

      network.snapshots.indices.foreach { t =>
        val b = new Array[Double](n * n)
        branches.foreach { edge =>
          val y = susceptance(network, edge, sub.carrier)
          // A bus at the slack contributes only to the other end's diagonal,
          // which is exactly what striking out the slack row and column means.
          (index.get(edge.bus0), index.get(edge.bus1)) match
            case (Some(i), Some(j)) =>
              b(i * n + i) += y
              b(j * n + j) += y
              b(i * n + j) -= y
              b(j * n + i) -= y
            case (Some(i), None) => b(i * n + i) += y
            case (None, Some(j)) => b(j * n + j) += y
            case (None, None)    => ()
        }

        val rhs   = IArray.from(free.map(injection(network, _, t)))
        val theta =
          try Cholesky.solve(n, IArray.unsafeFromArray(b), rhs)
          catch
            case e: Cholesky.NotPositiveDefinite =>
              // Restated in the network's own terms. A caller cannot act on
              // "pivot 3 is 0.0" but can act on knowing which island failed.
              throw new UnsupportedNetwork(
                s"sub-network ${sub.buses.mkString("{", ", ", "}")} (carrier ${sub.carrier}) " +
                  s"has no unique power flow at snapshot $t: ${e.getMessage}"
              )

        angles((choice.bus, t)) = 0.0
        free.zipWithIndex.foreach { (bus, i) => angles((bus, t)) = theta(i) }

        branches.foreach { edge =>
          val y = susceptance(network, edge, sub.carrier)
          flows((edge.component, edge.id, t)) =
            y * (angles((edge.bus0, t)) - angles((edge.bus1, t)))
        }

        // The slack bus takes whatever closes the balance. Summing the *free*
        // injections rather than recomputing from the flows keeps this exact:
        // a lossless linear flow has total injection zero by construction.
        val slackPower = -free.map(injection(network, _, t)).sum
        free.foreach(bus => busPowers((bus, t)) = injection(network, bus, t))
        busPowers((choice.bus, t)) = slackPower

        // Attributed to the slack generator, on top of its own p_set. With no
        // generator in the island there is nowhere to put it; that is legal when
        // the island is balanced, and `slacks` exposes the case either way.
        choice.generator.foreach { g =>
          val others = injection(network, choice.bus, t) - setpoint(network.require("Generator"), g, t)
          dispatch(("Generator", g, t)) = slackPower - others
        }
      }
    }

    LpfResult(angles.toMap, flows.toMap, busPowers.toMap, dispatch.toMap, slacks)

  private final case class Edge(component: String, id: String, bus0: String, bus1: String)

  /** Passive branches with both ends inside one sub-network.
    *
    * Both ends by definition — a passive branch is what forms the island — but
    * checked rather than assumed, because a branch leaking across islands would
    * silently make the matrix non-symmetric.
    */
  private def internalBranches(network: Network, sub: SubNetwork): IndexedSeq[Edge] =
    val members = sub.buses.toSet
    network.tables.values.toIndexedSeq.flatMap { table =>
      if Role.of(table.spec) != Role.PassiveBranch then IndexedSeq.empty
      else
        table.ids.flatMap { id =>
          val bus0 = table.string("bus0", id)
          val bus1 = table.string("bus1", id)
          if members.contains(bus0) && members.contains(bus1) then
            Some(Edge(table.spec.name, id, bus0, bus1))
          else None
        }
    }

  /** Per-unit susceptance of a branch.
    *
    * '''Reactance for an AC sub-network, resistance for a DC one.''' Not a
    * simplification: the reference network's DC lines carry `x = 0` with `r`
    * non-zero, so using reactance unconditionally divides by zero and the AC
    * lines carry `r = 0`, so the mirror mistake does too. PyPSA emits a warning
    * about exactly this pair of conditions on import.
    *
    * Scaled by `v_nom²`, PyPSA's per-unit base at its default 1 MVA. Unlike the
    * cycle constraints in `network-lopf` — where a constraint is homogeneous and
    * the scale cancels within one voltage level — here it does not cancel: it sets
    * the magnitude of every angle.
    */
  private def susceptance(network: Network, edge: Edge, carrier: String): Double =
    val table = network.require(edge.component)
    val z     = if carrier == "DC" then table.float("r", edge.id) else table.float("x", edge.id)
    val vNom  = network.require("Bus").float("v_nom", edge.bus0)
    if !(z > 0.0) then
      throw new UnsupportedNetwork(
        s"${edge.component} '${edge.id}' has ${if carrier == "DC" then "r" else "x"} = $z in a " +
          s"$carrier sub-network; a branch needs a positive impedance to carry a linear flow"
      )
    if vNom > 0.0 then vNom * vNom / z else 1.0 / z

  /** Net injection at a bus, in MW, from everything whose flow is '''fixed'''.
    *
    * Passive branches are excluded — their flow is the answer, not an input.
    */
  private def injection(network: Network, bus: String, snapshot: Int): Double =
    var total = 0.0

    network.tables.values.foreach { table =>
      Role.of(table.spec) match
        case Role.Attached =>
          table.ids.foreach { id =>
            if table.string("bus", id) == bus then total += setpoint(table, id, snapshot)
          }

        case Role.ControllableBranch =>
          // A link's flow is given, so it is an injection here rather than an
          // unknown. It also spans two sub-networks, which is precisely why it
          // does not merge them: each end sees a fixed number.
          val ports = Topology.branchPorts(table)
          table.ids.foreach { id =>
            val p = rawSetpoint(table, id, snapshot)
            ports.foreach { port =>
              if table.string(port, id) == bus then
                if port == "bus0" then total -= p
                else total += p * Topology.portEfficiency(table, id, port, snapshot)
            }
          }

        case _ => ()
    }
    total

  /** `sign × p_set`, with an absent setpoint read as zero.
    *
    * Two conventions, both load-bearing and neither guessable.
    *
    * `p_set` defaults to '''NaN''', not zero, for every component except Load —
    * so the arithmetic has to special-case it. Propagating the NaN poisons the
    * entire solve into silence; coercing it silently would hide a genuinely
    * missing value. PyPSA reads it as zero, which is what makes `ac-dc-meshed`'s
    * gas generators idle under LPF, and matching that is the point.
    *
    * `sign` is read rather than inferred from the component: it is `-1` for Load
    * and `+1` for a generator or store, so consumption is a negative injection
    * without this module knowing which components consume. Hardcoding "loads are
    * negative" would be right today and wrong for the next one-port added
    * upstream — the same reason branch roles come off `category`.
    */
  private def setpoint(table: ComponentTable, id: String, snapshot: Int): Double =
    val sign =
      if table.spec.attribute("sign").isDefined then table.valueAt("sign", id, snapshot) else 1.0
    sign * rawSetpoint(table, id, snapshot)

  private def rawSetpoint(table: ComponentTable, id: String, snapshot: Int): Double =
    if !table.spec.attribute("p_set").isDefined && !table.static.contains("p_set") then 0.0
    else
      val p = table.valueAt("p_set", id, snapshot)
      if p.isFinite then p else 0.0

  /** Reject what this formulation does not model.
    *
    * A dropped component leaves a solvable system describing a different network,
    * which is the failure mode this port keeps having to design against.
    * Transformers are refused rather than treated as lines: they are passive
    * branches and would decompose correctly, but their per-unit base involves
    * `s_nom` and an off-nominal `tap_ratio` shifts the angle across them, so
    * reusing the line formula would put a plausible number on a wrong model.
    */
  private def rejectUnhandled(network: Network): Unit =
    // StorageUnit and Store are handled, unlike in `network-lopf` which refuses
    // them. The difference is not an inconsistency: LOPF has to reject them
    // because their energy balance couples consecutive snapshots and dispatch
    // alone cannot represent that, whereas here dispatch is an *input* and a
    // storage unit is just another one-port with a `p_set` and a `sign`. That is
    // what makes `storage-hvdc` usable evidence for this module rather than a
    // rejection test.
    val handled = Set("Bus", "Line", "Link", "Generator", "Load", "StorageUnit", "Store",
                      "Carrier", "GlobalConstraint", "SubNetwork", "LineType",
                      "TransformerType", "Shape")
    val unhandled = network.tables.values.filter(t => t.size > 0 && !handled.contains(t.spec.name))
    if unhandled.nonEmpty then
      throw new UnsupportedNetwork(
        "network contains component(s) this power flow does not model: " +
          unhandled.map(t => s"${t.spec.name} (${t.size})").mkString(", ")
      )

    Topology.danglingReferences(network).headOption.foreach { (component, id, port, bus) =>
      throw new UnsupportedNetwork(s"$component '$id' references unknown bus '$bus' via $port")
    }
