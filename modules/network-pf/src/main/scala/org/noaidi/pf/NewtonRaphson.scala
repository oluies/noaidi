package org.noaidi.pf

import org.noaidi.network.*
import scala.collection.mutable

/** How a bus participates in the power flow.
  *
  * The three-way split is what makes AC power flow more than a nonlinear DC one.
  * Which quantities are known and which are solved differs per bus, and the
  * unknown vector is assembled from that.
  */
enum BusType:
  /** Voltage magnitude and angle both given; P and Q both solved. Exactly one per
    * sub-network, and it is what closes the balance — losses are not known until
    * the flow is solved, so some bus has to absorb them.
    */
  case Slack

  /** P and |V| given; θ and Q solved. A generator holding its terminal voltage.
    */
  case PV

  /** P and Q given; |V| and θ solved. Everything else. */
  case PQ

/** Result of a Newton-Raphson AC power flow. */
final class PfResult private[pf] (
    private val vMag: Map[(String, Int), Double],
    private val vAng: Map[(String, Int), Double],
    private val busP: Map[(String, Int), Double],
    private val busQ: Map[(String, Int), Double],
    val iterations: Map[(String, Int), Int],
    val converged: Map[(String, Int), Boolean],
):
  /** Voltage magnitude in per unit. */
  def voltageMagnitude(bus: String, snapshot: Int): Double = get(vMag, bus, snapshot, "|V|")

  /** Voltage angle in radians. */
  def voltageAngle(bus: String, snapshot: Int): Double = get(vAng, bus, snapshot, "angle")

  /** Net active power injection in MW. */
  def busPower(bus: String, snapshot: Int): Double = get(busP, bus, snapshot, "P")

  /** Net reactive power injection in MVAr. */
  def busReactive(bus: String, snapshot: Int): Double = get(busQ, bus, snapshot, "Q")

  def allConverged: Boolean = converged.values.forall(identity)

  private def get(m: Map[(String, Int), Double], bus: String, t: Int, what: String): Double =
    m.getOrElse((bus, t), throw new NoSuchElementException(s"no $what for bus '$bus' at snapshot $t"))

/** Newton-Raphson AC power flow — PyPSA's `n.pf()`.
  *
  * Solves the nonlinear power balance at every bus for the voltages that produce
  * it. Where [[LinearPowerFlow]] assumes a flat unit voltage profile, ignores
  * reactive power and linearises the sine, this solves the real equations:
  *
  * {{{
  * P_i = |V_i| Σ_k |V_k| (G_ik cos θ_ik + B_ik sin θ_ik)
  * Q_i = |V_i| Σ_k |V_k| (G_ik sin θ_ik − B_ik cos θ_ik)
  * }}}
  *
  * with `θ_ik = θ_i − θ_k`. The unknowns are θ at every non-slack bus and |V| at
  * every PQ bus; the mismatch is P at every non-slack bus and Q at every PQ bus,
  * so the Jacobian is square by construction.
  *
  * Each sub-network and snapshot is solved independently. Unlike the linear flow
  * the factorisation cannot be reused across snapshots: the Jacobian depends on
  * the voltages, which is precisely what makes this iterative.
  */
object NewtonRaphson:

  /** The network uses a feature this formulation does not model. */
  final class UnsupportedNetwork(message: String) extends RuntimeException(message)

  /** Iteration controls.
    *
    * `tolerance` is on the largest power mismatch, in MW and MVAr, so it is in
    * the caller's units rather than in a scaled residual.
    *
    * The default of 1e-6 is a floor, not a preference. On `storage-hvdc` every
    * snapshot converges within three iterations at 1e-6, and at 1e-7 three of
    * them do not converge in sixty: injections are of order 1000 MW and the
    * admittances of order 1e5, so a 1e-7 MW mismatch is about 1e-13 relative and
    * the iteration is chasing round-off. Newton's quadratic convergence leaves
    * nothing in between — an iterate either lands inside the noise or bounces
    * around in it. A microwatt is far below any engineering significance, so this
    * is not a limitation in practice, but asking for less will not converge.
    */
  final case class Params(tolerance: Double = 1e-6, maxIterations: Int = 30)

  def solve(network: Network, params: Params = Params()): PfResult =
    rejectUnhandled(network)

    val subs   = Topology.subNetworks(network)
    val slacks = Slack.all(network, subs)

    val vMag       = mutable.Map.empty[(String, Int), Double]
    val vAng       = mutable.Map.empty[(String, Int), Double]
    val busP       = mutable.Map.empty[(String, Int), Double]
    val busQ       = mutable.Map.empty[(String, Int), Double]
    val iterations = mutable.Map.empty[(String, Int), Int]
    val converged  = mutable.Map.empty[(String, Int), Boolean]

    slacks.foreach { choice =>
      val sub = choice.subNetwork
      rejectNonAc(sub)
      val y = Admittance.of(network, sub)
      val n   = sub.buses.length

      val types = sub.buses.map(bus => classify(network, bus, choice.bus))

      // Index vectors, fixed across snapshots: the unknowns are θ at every
      // non-slack bus followed by |V| at every PQ bus, and the mismatch rows are
      // in the same order.
      val nonSlack = sub.buses.indices.filter(i => types(i) != BusType.Slack)
      val pq       = sub.buses.indices.filter(i => types(i) == BusType.PQ)
      val unknowns = nonSlack.length + pq.length

      val label = sub.buses.mkString("{", ", ", "}")

      network.snapshots.indices.foreach { t =>
        val pSpec = sub.buses.map(bus => activeInjection(network, sub, bus, t)).toArray
        val qSpec = sub.buses.map(bus => reactiveInjection(network, sub, bus, t)).toArray

        // Flat start: unit magnitude, zero angle, with PV and slack buses held at
        // their setpoints. This is PyPSA's default and the reason partial
        // pivoting is required -- at a flat start dP/d|V| is zero for a lossless
        // branch, so the Jacobian's diagonal has structural zeros on iteration 0.
        val magnitude = Array.tabulate(n) { i =>
          if types(i) == BusType.PQ then 1.0 else voltageSetpoint(network, sub.buses(i))
        }
        val angle = new Array[Double](n)

        var iteration = 0
        var done      = false

        while !done && iteration < params.maxIterations do
          val mismatch = new Array[Double](unknowns)
          var worst    = 0.0

          nonSlack.zipWithIndex.foreach { (i, row) =>
            val value = pSpec(i) - activePower(y, magnitude, angle, i)
            mismatch(row) = value
            worst = math.max(worst, math.abs(value))
          }
          pq.zipWithIndex.foreach { (i, row) =>
            val value = qSpec(i) - reactivePower(y, magnitude, angle, i)
            mismatch(nonSlack.length + row) = value
            worst = math.max(worst, math.abs(value))
          }

          if worst < params.tolerance then done = true
          else
            val jacobian = buildJacobian(y, magnitude, angle, nonSlack, pq)
            val step =
              try Lu.solve(unknowns, IArray.unsafeFromArray(jacobian), IArray.unsafeFromArray(mismatch))
              catch
                case e: Lu.Singular =>
                  throw new UnsupportedNetwork(
                    s"sub-network $label has a singular Jacobian at snapshot $t, iteration " +
                      s"$iteration: ${e.getMessage}"
                  )

            nonSlack.zipWithIndex.foreach((i, row) => angle(i) += step(row))
            pq.zipWithIndex.foreach((i, row) => magnitude(i) += step(nonSlack.length + row))
            iteration += 1

        sub.buses.zipWithIndex.foreach { (bus, i) =>
          vMag((bus, t)) = magnitude(i)
          vAng((bus, t)) = angle(i)
          // Recomputed from the converged voltages rather than taken from the
          // specification, so the slack's P and a PV bus's Q -- the quantities
          // that were unknown -- come out of the same equations as everything
          // else.
          busP((bus, t)) = activePower(y, magnitude, angle, i)
          busQ((bus, t)) = reactivePower(y, magnitude, angle, i)
        }
        iterations((label, t)) = iteration
        converged((label, t)) = done
      }
    }

    PfResult(vMag.toMap, vAng.toMap, busP.toMap, busQ.toMap, iterations.toMap, converged.toMap)

  /** `P_i = |V_i| Σ_k |V_k| (G_ik cos θ_ik + B_ik sin θ_ik)`. */
  private def activePower(y: Admittance, v: Array[Double], theta: Array[Double], i: Int): Double =
    var total = 0.0
    var k     = 0
    while k < y.size do
      val d = theta(i) - theta(k)
      total += v(k) * (y.conductance(i, k) * math.cos(d) + y.susceptance(i, k) * math.sin(d))
      k += 1
    v(i) * total

  /** `Q_i = |V_i| Σ_k |V_k| (G_ik sin θ_ik − B_ik cos θ_ik)`. */
  private def reactivePower(y: Admittance, v: Array[Double], theta: Array[Double], i: Int): Double =
    var total = 0.0
    var k     = 0
    while k < y.size do
      val d = theta(i) - theta(k)
      total += v(k) * (y.conductance(i, k) * math.sin(d) - y.susceptance(i, k) * math.cos(d))
      k += 1
    v(i) * total

  /** The polar-form Jacobian, in four blocks.
    *
    * Rows are P at every non-slack bus then Q at every PQ bus; columns are θ at
    * every non-slack bus then |V| at every PQ bus. Written out analytically
    * rather than approximated by differences: a numerical Jacobian would converge
    * too, and would hide an error in the power equations by deriving from them.
    */
  private def buildJacobian(
      y: Admittance,
      v: Array[Double],
      theta: Array[Double],
      nonSlack: IndexedSeq[Int],
      pq: IndexedSeq[Int],
  ): Array[Double] =
    val rows = nonSlack.length + pq.length
    val j    = new Array[Double](rows * rows)

    def dpdTheta(i: Int, k: Int): Double =
      if i == k then
        // The self term excludes k = i, which is what the -V_i² B_ii correction
        // amounts to after substituting the full sum.
        -reactivePower(y, v, theta, i) - y.susceptance(i, i) * v(i) * v(i)
      else
        val d = theta(i) - theta(k)
        v(i) * v(k) * (y.conductance(i, k) * math.sin(d) - y.susceptance(i, k) * math.cos(d))

    def dpdV(i: Int, k: Int): Double =
      if i == k then
        activePower(y, v, theta, i) / v(i) + y.conductance(i, i) * v(i)
      else
        val d = theta(i) - theta(k)
        v(i) * (y.conductance(i, k) * math.cos(d) + y.susceptance(i, k) * math.sin(d))

    def dqdTheta(i: Int, k: Int): Double =
      if i == k then
        activePower(y, v, theta, i) - y.conductance(i, i) * v(i) * v(i)
      else
        val d = theta(i) - theta(k)
        -v(i) * v(k) * (y.conductance(i, k) * math.cos(d) + y.susceptance(i, k) * math.sin(d))

    def dqdV(i: Int, k: Int): Double =
      if i == k then
        reactivePower(y, v, theta, i) / v(i) - y.susceptance(i, i) * v(i)
      else
        val d = theta(i) - theta(k)
        v(i) * (y.conductance(i, k) * math.sin(d) - y.susceptance(i, k) * math.cos(d))

    // The mismatch is `specified - computed`, so the Newton step solves
    // `J Δx = mismatch` with J the derivative of the *computed* injection. The
    // signs below are therefore those of ∂P/∂x directly, with no negation.
    nonSlack.zipWithIndex.foreach { (i, row) =>
      nonSlack.zipWithIndex.foreach((k, col) => j(row * rows + col) = dpdTheta(i, k))
      pq.zipWithIndex.foreach((k, col) => j(row * rows + nonSlack.length + col) = dpdV(i, k))
    }
    pq.zipWithIndex.foreach { (i, row) =>
      val r = nonSlack.length + row
      nonSlack.zipWithIndex.foreach((k, col) => j(r * rows + col) = dqdTheta(i, k))
      pq.zipWithIndex.foreach((k, col) => j(r * rows + nonSlack.length + col) = dqdV(i, k))
    }
    j

  /** A bus's role, derived from its generators rather than read off `Bus.control`.
    *
    * PyPSA writes a derived `control` column into `buses.csv`, but deriving it
    * here means a network that has never been through PyPSA classifies correctly
    * too — and the exported column then becomes an independent check rather than
    * the source. The rule is PyPSA's: the slack bus is the slack, a bus carrying
    * a generator with `control = PV` holds its voltage, everything else is PQ.
    */
  private[pf] def classify(network: Network, bus: String, slackBus: String): BusType =
    if bus == slackBus then BusType.Slack
    else
      val holdsVoltage = network
        .table("Generator")
        .exists { t =>
          t.spec.attribute("control").isDefined &&
          t.ids.exists(id => t.string("bus", id) == bus && t.string("control", id) == "PV")
        }
      if holdsVoltage then BusType.PV else BusType.PQ

  /** The magnitude a slack or PV bus is held at, in per unit. */
  private def voltageSetpoint(network: Network, bus: String): Double =
    val value = network.require("Bus").valueAt("v_mag_pu_set", bus, 0)
    if value.isFinite && value > 0.0 then value else 1.0

  private def activeInjection(network: Network, sub: SubNetwork, bus: String, t: Int): Double =
    onePortSum(network, bus, t, "p_set") + linkInjection(network, sub, bus, t)

  private def reactiveInjection(network: Network, sub: SubNetwork, bus: String, t: Int): Double =
    onePortSum(network, bus, t, "q_set")

  /** `Σ sign × <attribute>` over the one-ports at a bus.
    *
    * The same `sign` convention as the linear flow, and the same NaN rule: an
    * unset `p_set` is zero rather than a missing value.
    */
  private def onePortSum(network: Network, bus: String, t: Int, attribute: String): Double =
    var total = 0.0
    network.tables.values.foreach { table =>
      if Role.of(table.spec) == Role.Attached && table.spec.attribute(attribute).isDefined then
        table.ids.foreach { id =>
          if table.string("bus", id) == bus then
            val sign =
              if table.spec.attribute("sign").isDefined then table.valueAt("sign", id, t) else 1.0
            val value = table.valueAt(attribute, id, t)
            if value.isFinite then total += sign * value
        }
    }
    total

  /** A controllable branch's fixed flow at this bus. */
  private def linkInjection(network: Network, sub: SubNetwork, bus: String, t: Int): Double =
    var total = 0.0
    network.tables.values.foreach { table =>
      if Role.of(table.spec) == Role.ControllableBranch then
        val ports = Topology.branchPorts(table)
        table.ids.foreach { id =>
          val p     = table.valueAt("p_set", id, t)
          val fixed = if p.isFinite then p else 0.0
          ports.foreach { port =>
            if table.string(port, id) == bus then
              if port == "bus0" then total -= fixed
              else total += fixed * Topology.portEfficiency(table, id, port, t)
          }
        }
    }
    total

  /** Refuse a sub-network that is not AC.
    *
    * The brief asks for "Newton-Raphson AC/DC", and the DC half cannot be
    * delivered under this project's own rule that every physics module is gated
    * on golden-file agreement with the pinned PyPSA — because '''PyPSA 1.2.4 does
    * not implement it either'''. `SubNetwork.calculate_Y` is documented as
    * "Calculate bus admittance matrices for AC sub-networks" and returns early
    * for any other carrier, logging "Non-AC networks not supported for Y!"; `Y`
    * is then never assigned and `pf()` dies with `AttributeError`. That is why
    * the ac-dc fixtures record an error instead of a power flow, and it holds for
    * a pure DC network too rather than only a mixed one — checked directly rather
    * than inferred from the mixed case failing.
    *
    * So there is no reference for a DC power flow, and writing one anyway would
    * produce numbers nothing could check. Refused until either PyPSA gains the
    * capability or a different oracle is chosen for it.
    *
    * [[LinearPowerFlow]] does handle DC islands, because `lpf` supports them —
    * the asymmetry is PyPSA's, not this port's.
    */
  private def rejectNonAc(sub: SubNetwork): Unit =
    if sub.carrier != "AC" then
      throw new UnsupportedNetwork(
        s"sub-network ${sub.buses.mkString("{", ", ", "}")} has carrier '${sub.carrier}'; " +
          "the non-linear power flow is implemented for AC only, because the pinned PyPSA does " +
          "not implement it for anything else and there is therefore nothing to validate a DC " +
          "solve against"
      )

  private def rejectUnhandled(network: Network): Unit =
    val handled = Set("Bus", "Line", "Link", "Generator", "Load", "StorageUnit", "Store",
                      "Carrier", "GlobalConstraint", "SubNetwork", "LineType",
                      "TransformerType", "Shape")
    val unhandled = network.tables.values.filter(t => t.size > 0 && !handled.contains(t.spec.name))
    if unhandled.nonEmpty then
      throw new UnsupportedNetwork(
        "network contains component(s) this power flow does not model: " +
          unhandled.map(t => s"${t.spec.name} (${t.size})").mkString(", ")
      )

    Topology.danglingBusReferences(network).headOption.foreach { (component, id, port, bus) =>
      throw new UnsupportedNetwork(s"$component '$id' references unknown bus '$bus' via $port")
    }
