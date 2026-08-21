package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.pf.Branches
import scala.collection.mutable

/** One independent cycle, as signed branch coefficients.
  *
  * `terms` holds `(component, entity, orientation)` where orientation is `+1`
  * when the cycle traverses the branch from `bus0` to `bus1` and `-1` the other
  * way. Kirchhoff's voltage law is then `sum(orientation * impedance * flow) = 0`.
  */
final case class Cycle(carrier: String, terms: IndexedSeq[(String, String, Double)]):
  def length: Int = terms.length

object Cycles:

  /** A cycle basis of the network's passive branches, one set per sub-network.
    *
    * Built as a spanning tree plus one cycle per non-tree edge, which yields
    * exactly `edges - nodes + components` independent cycles — the circuit rank.
    * That is the same count and the same span as any other basis; which
    * particular cycles are chosen does not affect the feasible set, only the
    * shape of the matrix.
    *
    * Only passive branches take part. A link's flow is a decision variable, not a
    * consequence of impedance, so it imposes no voltage constraint — which is
    * also why it does not merge sub-networks.
    */
  def basis(network: Network): IndexedSeq[Cycle] =
    val edges = passiveEdges(network)
    if edges.isEmpty then return IndexedSeq.empty

    val adjacency = mutable.Map.empty[String, mutable.ArrayBuffer[Edge]]
    edges.foreach { e =>
      adjacency.getOrElseUpdate(e.bus0, mutable.ArrayBuffer.empty) += e
      adjacency.getOrElseUpdate(e.bus1, mutable.ArrayBuffer.empty) += e
    }

    val cycles  = mutable.ArrayBuffer.empty[Cycle]
    val visited = mutable.Set.empty[String]

    // Carrier labels come from the decomposition rather than being recomputed
    // here. `Topology.subNetworks` reads its label off the island's *sorted* first
    // bus so that it agrees with the `buses` field a caller sees; walking from the
    // BFS root would read file order instead, and on a mixed-carrier island --
    // which `SubNetwork.mixedCarriers` reports rather than rejects -- the two
    // disagree. Cycles is the side that decides whether `x` or `r` is used, so a
    // disagreement here is a wrong impedance, not a cosmetic mismatch.
    val carrierOf = Topology
      .subNetworks(network)
      .flatMap(sub => sub.buses.map(_ -> sub.carrier))
      .toMap

    network.table("Bus").foreach { buses =>
      buses.ids.foreach { root =>
        if !visited.contains(root) && adjacency.contains(root) then
          cycles ++= basisFrom(root, adjacency, visited, carrierOf.getOrElse(root, ""))
      }
    }
    cycles.toIndexedSeq

  private final case class Edge(component: String, id: String, bus0: String, bus1: String):
    def other(bus: String): String = if bus == bus0 then bus1 else bus0

  private def passiveEdges(network: Network): IndexedSeq[Edge] =
    network.tables.values.toIndexedSeq.flatMap { table =>
      if Role.of(table.spec) != Role.PassiveBranch then IndexedSeq.empty
      else
        table.ids.map { id =>
          Edge(table.spec.name, id, table.string("bus0", id), table.string("bus1", id))
        }
    }

  /** Spanning tree from `root`, emitting one cycle per non-tree edge. */
  private def basisFrom(
      root: String,
      adjacency: mutable.Map[String, mutable.ArrayBuffer[Edge]],
      visited: mutable.Set[String],
      carrier: String,
  ): IndexedSeq[Cycle] =
    // Parent edge and depth per bus, so a cycle's two arms can be walked up to
    // their common ancestor without searching.
    val parentEdge = mutable.Map.empty[String, Edge]
    val depth      = mutable.Map[String, Int](root -> 0)
    val treeEdges  = mutable.Set.empty[(String, String)]
    val order      = mutable.Queue(root)
    visited += root

    while order.nonEmpty do
      val bus = order.dequeue()
      adjacency.getOrElse(bus, mutable.ArrayBuffer.empty).foreach { e =>
        val next = e.other(bus)
        if !visited.contains(next) then
          visited += next
          parentEdge(next) = e
          depth(next) = depth(bus) + 1
          treeEdges += ((e.component, e.id))
          order.enqueue(next)
      }

    val cycles = mutable.ArrayBuffer.empty[Cycle]

    // Every edge not in the tree closes exactly one cycle with the tree path
    // between its endpoints. Scanned over this component's own buses rather than
    // the whole network's adjacency, which was O(components x edges).
    depth.keys.toIndexedSeq.sorted
      .flatMap(bus => adjacency.getOrElse(bus, mutable.ArrayBuffer.empty))
      .distinct
      .foreach { e =>
      if !treeEdges.contains((e.component, e.id)) && depth.contains(e.bus0) && depth.contains(e.bus1) then
        val terms = mutable.ArrayBuffer.empty[(String, String, Double)]
        // Traverse the closing edge from bus0 to bus1.
        terms += ((e.component, e.id, 1.0))

        // Walk both endpoints up to their common ancestor. The cycle traverses
        // the closing edge bus0 -> bus1 and then returns bus1 -> bus0 through the
        // tree, so the two arms are travelled in opposite senses and their sign
        // rules are mirror images:
        //
        //   ascending from bus1, travel is `node -> parent`, so a tree edge whose
        //   bus0 is the node is traversed forwards;
        //   descending towards bus0, travel is `parent -> node`, so a tree edge
        //   whose bus1 is the node is traversed forwards.
        //
        // Getting this backwards does not produce a wrong flow -- it produces an
        // infeasible LP, because the cycle equation then contradicts bus balance.
        var a = e.bus1
        var b = e.bus0
        val fromB1 = mutable.ArrayBuffer.empty[(String, String, Double)]
        val toB0   = mutable.ArrayBuffer.empty[(String, String, Double)]

        while depth(a) > depth(b) do
          val pe = parentEdge(a)
          fromB1 += ((pe.component, pe.id, if pe.bus0 == a then 1.0 else -1.0))
          a = pe.other(a)
        while depth(b) > depth(a) do
          val pe = parentEdge(b)
          toB0 += ((pe.component, pe.id, if pe.bus1 == b then 1.0 else -1.0))
          b = pe.other(b)
        while a != b do
          val pa = parentEdge(a)
          fromB1 += ((pa.component, pa.id, if pa.bus0 == a then 1.0 else -1.0))
          a = pa.other(a)
          val pb = parentEdge(b)
          toB0 += ((pb.component, pb.id, if pb.bus1 == b then 1.0 else -1.0))
          b = pb.other(b)

        terms ++= fromB1
        terms ++= toB0
        cycles += Cycle(carrier, terms.toIndexedSeq)
    }
    cycles.toIndexedSeq

  /** Per-unit impedance a branch contributes to a voltage constraint.
    *
    * Reactance for an AC sub-network, resistance for a DC one — and that is not
    * a simplification. In the reference network the DC lines carry `x = 0` with
    * `r` non-zero, so using reactance unconditionally would give every DC cycle
    * an all-zero constraint: vacuously satisfied, and the flows left as
    * underdetermined as before.
    *
    * The base differs by component, exactly as it does for the susceptance a
    * power flow uses. A line is referred to voltage, `z / v_nom²`; a transformer
    * to its own rating and tap, `z · tap_ratio / s_nom`. Within a cycle at one
    * voltage the line division cancels, since the constraint is homogeneous — it
    * matters precisely where a cycle spans a transformer, which is the case this
    * now supports.
    */
  def impedance(network: Network, component: String, id: String, carrier: String): Double =
    val table = network.require(component)
    val raw   = if carrier == "DC" then table.float("r", id) else table.float("x", id)

    component match
      case "Line" =>
        val vNom = busVoltage(network, table.string("bus0", id))
        if vNom > 0.0 then raw / (vNom * vNom) else raw

      case "Transformer" =>
        val sNom = table.float("s_nom", id)
        val tap  = Branches.tapRatio(table, id)
        if sNom > 0.0 then raw * tap / sNom
        else
          throw new Lopf.UnsupportedNetwork(
            s"Transformer '$id' has s_nom = $sNom, which is its per-unit base"
          )

      case other =>
        // Not the line formula by default: that is what made admitting a
        // transformer a plausible wrong answer rather than a refusal.
        throw new Lopf.UnsupportedNetwork(
          s"$other '$id' is a passive branch whose per-unit base is not known here"
        )

  /** Refuse a transformer whose phase shift is a decision rather than a given.
    *
    * PyPSA 1.3.0 makes the shift a per-snapshot variable when
    * `phase_shift_min < phase_shift_max`: `define_phase_shift_variables` bounds
    * `Transformer-phase_shift` by the two, and
    * `define_kirchhoff_voltage_constraints` puts that variable into the cycle sum
    * where the constant would otherwise go. The row built in [[Lopf]] takes the
    * shift as given, so on such a network it would hold every shifter at its
    * static `phase_shift` and return the cost of a tap position the optimiser was
    * free to move -- dearer than the truth, or infeasible and reported as the
    * network's problem rather than as this model's.
    *
    * `min < max` exactly as PyPSA tests it. A pair left at the default `0.0` and
    * `0.0` is not a range and does not refuse, which is what keeps every network
    * that has never heard of the feature working. Nor is an inverted pair:
    * `check_phase_shift_bounds` flags `min > max` as a likely mistake and PyPSA
    * then holds the shift fixed at `phase_shift`, which is what this model does
    * with it anyway -- so refusing there would turn an agreement into an error.
    */
  def rejectOptimisableShift(network: Network, refuse: String => Nothing): Unit =
    // Not `Branches.optional`, which maps every non-finite value to zero. That
    // rule exists to keep a NaN out of a susceptance, and used for a *comparison*
    // it silently rewrites an unbounded range to no range at all: a transformer
    // with `phase_shift_max = inf` would read as `0.0 < 0.0` and pass, while
    // PyPSA's own `min < max` is satisfied and the variable is created. `inf` is
    // how PyPSA spells an unbounded bound everywhere else, so it is the value
    // most likely to turn up.
    //
    // NaN is left as NaN rather than mapped to zero, because every comparison
    // against it is false -- which is exactly what pandas does with the same
    // test, so a half-written pair refuses here if and only if it creates a
    // variable there.
    def bound(table: ComponentTable, attribute: String, id: String): Double =
      if table.spec.attribute(attribute).isEmpty && !table.static.contains(attribute) then 0.0
      else table.float(attribute, id)

    network.table("Transformer").foreach { table =>
      if table.spec.attribute("phase_shift_min").isDefined then
        table.ids.foreach { id =>
          val low  = bound(table, "phase_shift_min", id)
          val high = bound(table, "phase_shift_max", id)
          if low < high then
            refuse(
              s"Transformer '$id' has phase_shift_min = $low below phase_shift_max = $high, so its " +
                "phase shift is a per-snapshot decision variable in the Kirchhoff row rather than " +
                "the constant this model puts there; the shift would be held at its static value " +
                "and the tap left unoptimised"
            )
        }
    }

  private def busVoltage(network: Network, bus: String): Double =
    network.table("Bus").map(_.float("v_nom", bus)).getOrElse(0.0)
