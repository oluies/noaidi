package org.noaidi.pf

import org.noaidi.network.*

/** Passive-branch geometry shared by the linear flow and the outage factors.
  *
  * Both need the same two things — which branches lie inside a sub-network, and
  * what each one's per-unit susceptance is — and both had their own copy. The
  * stated reason for putting `Lodf` in this module at all was "one definition of
  * susceptance and slack across both layers", and duplicating it made that
  * false: the two could silently diverge, most obviously when transformer
  * per-unit conversion is eventually added to one and not the other.
  */
private[pf] object Branches:

  final case class Edge(component: String, id: String, bus0: String, bus1: String)

  /** Passive branches with both ends inside one sub-network.
    *
    * Both ends by definition: an island is the union-find closure of exactly
    * these edges, so a branch with one end outside cannot arise. The membership
    * test selects this island's edges out of the network's; it is not a
    * validation.
    */
  def within(network: Network, sub: SubNetwork): IndexedSeq[Edge] =
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
    * Reactance for an AC sub-network and resistance for a DC one, scaled by
    * `v_nom^2`. Not a simplification: the reference network's DC lines carry
    * `x = 0` and its AC lines carry `r = 0`, so either mistake divides by zero
    * rather than degrading. A missing or zero `v_nom` is refused for the same
    * reason the impedance is -- `v_nom^2` does not cancel, so one branch scaled
    * differently from the rest makes every angle in that island wrong.
    *
    * `onBadData` builds the exception, so each caller keeps its own type without
    * a second copy of the arithmetic.
    */
  def susceptance(
      network: Network,
      sub: SubNetwork,
      edge: Edge,
      onBadData: String => RuntimeException,
  ): Double =
    val table = network.require(edge.component)
    val z     = if sub.carrier == "DC" then table.float("r", edge.id) else table.float("x", edge.id)
    val vNom  = network.require("Bus").float("v_nom", edge.bus0)
    if !(z > 0.0) then
      throw onBadData(
        s"${edge.component} '${edge.id}' has ${if sub.carrier == "DC" then "r" else "x"} = $z in a " +
          s"${sub.carrier} sub-network, so it has no susceptance"
      )
    if !(vNom > 0.0) then
      throw onBadData(
        s"bus '${edge.bus0}' has v_nom = $vNom, so ${edge.component} '${edge.id}' has no per-unit " +
          "base; every branch in a sub-network must share one"
      )
    vNom * vNom / z
