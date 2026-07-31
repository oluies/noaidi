package org.noaidi.pf

import org.noaidi.network.*

/** Passive-branch geometry shared by the linear flow and the outage factors.
  *
  * Both need the same two things — which branches lie inside a sub-network, and
  * what each one's per-unit susceptance is — and both had their own copy. The
  * stated reason for putting `Lodf` in this module at all was "one definition of
  * susceptance and slack across both layers", and duplicating it made that
  * false: the two could silently diverge, most obviously when transformer
  * per-unit conversion is eventually added to one and not the other -- which is
  * exactly what happened next, so it is public now and `network-lopf`'s cycle
  * constraints read the same definition rather than carrying a third copy.
  */
object Branches:

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

  /** Per-unit susceptance of a branch, on PyPSA's base of 1 MVA.
    *
    * Reactance for an AC sub-network and resistance for a DC one. Not a
    * simplification: the reference network's DC lines carry `x = 0` and its AC
    * lines carry `r = 0`, so either mistake divides by zero rather than
    * degrading.
    *
    * ==The per-unit base differs by component==
    *
    * A line is referred to voltage and a transformer to its own rating:
    *
    * {{{
    * Line:        z_pu = z / v_nom²        so susceptance = v_nom² / z
    * Transformer: z_pu = z / s_nom,        so susceptance = s_nom / (z · tap_ratio)
    *              then z_pu_eff = z_pu · tap_ratio
    * }}}
    *
    * The difference is not small. On `scigrid_de` a transformer comes out at
    * 20000 and a line at 3707 — reusing the line formula for a transformer there
    * would be wrong by a factor of about 5, and on a 380 kV unit rated 500 MVA it
    * is six orders of magnitude. That is why transformers were refused outright
    * until there was a network with one to check against.
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
    if !(z > 0.0) then
      throw onBadData(
        s"${edge.component} '${edge.id}' has ${if sub.carrier == "DC" then "r" else "x"} = $z in a " +
          s"${sub.carrier} sub-network, so it has no susceptance"
      )

    edge.component match
      case "Line" =>
        val vNom = network.require("Bus").float("v_nom", edge.bus0)
        if !(vNom > 0.0) then
          throw onBadData(
            s"bus '${edge.bus0}' has v_nom = $vNom, so ${edge.component} '${edge.id}' has no " +
              "per-unit base; every branch in a sub-network must share one"
          )
        vNom * vNom / z

      case "Transformer" =>
        val sNom = table.float("s_nom", edge.id)
        if !(sNom > 0.0) then
          throw onBadData(
            s"Transformer '${edge.id}' has s_nom = $sNom, which is its per-unit base"
          )
        val tap = tapRatio(table, edge.id)
        sNom / (z * tap)

      case other =>
        // A passive branch class this does not know. Falling back to the line
        // formula is what made admitting transformers a silent wrong answer
        // rather than a crash, so a new class gets refused instead.
        throw onBadData(
          s"$other '${edge.id}' is a passive branch whose per-unit base is not known here"
        )

  /** A transformer's tap ratio, defaulting to 1 when absent.
    *
    * PyPSA folds it into the effective impedance as a plain multiplier
    * (`x_pu_eff = x_pu · tap_ratio`), which is what the linear models use.
    */
  def tapRatio(table: ComponentTable, id: String): Double =
    if table.spec.attribute("tap_ratio").isEmpty && !table.static.contains("tap_ratio") then 1.0
    else
      val value = table.float("tap_ratio", id)
      if value.isFinite && value > 0.0 then value else 1.0
