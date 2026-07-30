package org.noaidi.lopf

import org.noaidi.network.*
import scala.collection.mutable

/** One independent cycle, as signed branch coefficients.
  *
  * `terms` holds `(component, entity, orientation)` where orientation is `+1`
  * when the cycle traverses the branch from `bus0` to `bus1` and `-1` the other
  * way. Kirchhoff's voltage law is then `sum(orientation * impedance * flow) = 0`.
  */
final case class Cycle(subNetwork: String, terms: IndexedSeq[(String, String, Double)]):
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

    network.table("Bus").foreach { buses =>
      buses.ids.foreach { root =>
        if !visited.contains(root) && adjacency.contains(root) then
          cycles ++= basisFrom(root, adjacency, visited, network)
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
      network: Network,
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

    val carrier = subNetworkCarrier(network, root)
    val cycles  = mutable.ArrayBuffer.empty[Cycle]

    // Every edge not in the tree closes exactly one cycle with the tree path
    // between its endpoints.
    adjacency.values.flatten.toSeq.distinct.foreach { e =>
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

  private def subNetworkCarrier(network: Network, bus: String): String =
    network.table("Bus").map(_.string("carrier", bus)).getOrElse("")

  /** Per-unit impedance a branch contributes to a voltage constraint.
    *
    * Reactance for an AC sub-network, resistance for a DC one — and that is not
    * a simplification. In the reference network the DC lines carry `x = 0` with
    * `r` non-zero, so using reactance unconditionally would give every DC cycle
    * an all-zero constraint: vacuously satisfied, and the flows left as
    * underdetermined as before.
    *
    * Divided by `v_nom^2`, which is PyPSA's per-unit base at its default 1 MVA.
    * Within a cycle at one voltage the division cancels, since the constraint is
    * homogeneous — it matters only where a cycle spans a transformer, and it
    * costs nothing to be right about now.
    */
  def impedance(network: Network, component: String, id: String, carrier: String): Double =
    val table = network.require(component)
    val raw   = if carrier == "DC" then table.float("r", id) else table.float("x", id)
    val vNom  = busVoltage(network, table.string("bus0", id))
    if vNom > 0.0 then raw / (vNom * vNom) else raw

  private def busVoltage(network: Network, bus: String): Double =
    network.table("Bus").map(_.float("v_nom", bus)).getOrElse(0.0)
