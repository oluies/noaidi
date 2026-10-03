package org.noaidi.lopf

import org.noaidi.network.*

/** A cap on how much capacity of one carrier may be built, optionally at one bus.
  *
  * PyPSA's `define_tech_capacity_expansion_limit`, and the fifth and last of the
  * global-constraint types `global_constraints.py` dispatches on.
  *
  * {{{
  * Σ_a nominal(a)   over extendable assets whose carrier matches, at the named bus if there is one
  * }}}
  *
  * The simplest left-hand side of the five — one coefficient of 1.0 per capacity column, no
  * weighting and no snapshot index — which is why it was the last one left rather than the
  * hardest. It needed capacity to be a decision at all, and on a multi-period network it
  * needed the activity window, so it could not have been built before the two changes
  * underneath it.
  *
  * ==What the three columns mean here, and the one that is not a carrier==
  *
  *   - `carrier_attribute` names a '''carrier''', not an attribute of one. In
  *     `primary_energy` and `operational_limit` the same column names a column of
  *     `carriers.csv` (`co2_emissions`) or a carrier (`hydro`) respectively, and PyPSA
  *     reads it both ways from the same field. So a `carrier_attribute` of
  *     `co2_emissions` on this type matches nothing rather than charging emissions.
  *   - `bus` is optional and is a '''bus name'''. Absent, the cap is over the whole
  *     network. PyPSA treats an empty string as absent (`glc.get("bus") or None`), which
  *     matters because a CSV round-trip writes an unset `bus` as the empty string rather
  *     than omitting the column.
  *   - A branch is placed at its `bus0`, not at both ends. So a cap at one bus counts a
  *     line leaving it and not the same line arriving. Asymmetric, and PyPSA's.
  *
  * ==Capacity, not expansion, despite the name==
  *
  * The left-hand side is the capacity '''variable''', which for an extendable asset is the
  * whole optimal capacity and not the increment over what the network came with. An asset
  * arriving with `p_nom = 100` and extendable counts its full `p_nom_opt` here, so a cap of
  * 100 permits no addition at all rather than 100 of it. That reading is forced: PyPSA sums
  * `m[var]`, which is the same column the objective charges `capital_cost` on, and nothing
  * in the row subtracts the existing capacity.
  */
object TechCapacityLimit:

  /** PyPSA's `n.branch_components`, by role rather than by name.
    *
    * A branch's bus for this purpose is `bus0`. Selected through [[Role]] so a controllable
    * branch added upstream — `Process` is already one — lands on `bus0` without being named
    * here, which is the same reason `Lopf.build` selects its tables that way.
    */
  private def busAttribute(spec: ComponentSpec): String =
    Role.of(spec) match
      case Role.PassiveBranch | Role.ControllableBranch => "bus0"
      case _                                            => "bus"

  /** The cap's left-hand side: one term per matching extendable capacity column.
    *
    * `bus` is the constraint's own, already trimmed; empty means the whole network.
    */
  def terms(
      network: Network,
      columns: scala.collection.Map[(String, String, Int), Int],
      carrier: String,
      bus: String,
  ): Seq[(Int, Double)] =
    Expansion.nominalAttribute.keys.toIndexedSeq.sorted.flatMap { component =>
      network.table(component).toIndexedSeq.flatMap { table =>
        // PyPSA's `if "carrier" not in static: continue`. A component class with no carrier
        // column matches nothing rather than matching everything, which is the difference
        // between a cap over the named technology and a cap over the whole network.
        if !(table.spec.attribute("carrier").isDefined || table.static.contains("carrier")) then
          IndexedSeq.empty
        else
          val port = busAttribute(table.spec)
          Expansion
            .extendables(table)
            .filter { id =>
              table.string("carrier", id) == carrier &&
                (bus.isEmpty || table.string(port, id) == bus)
            }
            .map(id => columns((Expansion.capacityKey(component), id, Expansion.NoSnapshot)) -> 1.0)
      }
    }
