package org.noaidi.lopf

import org.noaidi.network.*

/** Storage in the dispatch model: the variable names, and what is refused.
  *
  * A storage unit is the only component here whose behaviour at one snapshot
  * depends on another. Everything else in [[Lopf]] is separable — a generator's
  * output at 3am constrains nothing at 4am — which is why a dispatch model
  * without storage is really a sequence of independent LPs wearing one matrix.
  * The energy balance is what joins them, and it lives in `Lopf.build` beside
  * the constraints it sits among; what is here is the naming and the guard rail.
  *
  * ==Four variables, not one==
  *
  * PyPSA gives a storage unit `p_dispatch`, `p_store`, `state_of_charge` and
  * `spill`, and this follows it. The split is forced rather than stylistic:
  * charging and discharging enter the state of charge through *different*
  * efficiencies (`· efficiency_store` one way, `/ efficiency_dispatch` the
  * other), and a single signed power variable cannot carry both. Modelling it
  * as one variable would be exact only when the two efficiencies are 1, which is
  * the case no real unit has.
  *
  * The bus sees `p_dispatch − p_store`; `spill` never reaches it, being inflow
  * that leaves unused.
  *
  * They are keyed into the shared variable map under compound component names
  * rather than by widening the map's shape, so `Sclopf`'s row-by-row copy and
  * everything else reading it stay untouched.
  */
object Storage:

  val Dispatch = "StorageUnit-p_dispatch"
  val Store    = "StorageUnit-p_store"
  val SoC      = "StorageUnit-state_of_charge"
  val Spill    = "StorageUnit-spill"

  /** Whether a unit's first snapshot wraps to its last.
    *
    * Read through the column rather than the schema default because a network
    * that never sets it has no column at all — `scigrid-de`'s 38 units are all
    * of that shape, and reading a missing column as anything but false would
    * turn every one of them cyclic.
    */
  def isCyclic(table: ComponentTable, id: String): Boolean =
    table.static.contains("cyclic_state_of_charge") && table.bool("cyclic_state_of_charge", id)

  /** Refuse the storage features this model does not build.
    *
    * Each of these changes the answer rather than decorating it, and each is
    * silent if ignored — a `state_of_charge_set` that is dropped leaves the
    * trajectory free, which is a cheaper problem returning `Optimal`.
    */
  def reject(table: ComponentTable): Unit =
    def refuse(message: String): Nothing = throw new Lopf.UnsupportedNetwork(message)

    // `state_of_charge_set` is built -- see `Lopf.build`. The three power set
    // points are not: PyPSA fixes `p_set` on the *net* `p_dispatch − p_store`
    // rather than on either variable, and `p_dispatch_set`/`p_store_set` fix
    // them individually, so the three are three different constraints. No golden
    // uses any of them.
    Seq("p_set", "p_dispatch_set", "p_store_set").foreach { attribute =>
      val set = table.ids.filter { id =>
        val hasSeries = table.series.get(attribute).exists(_.covers(id))
        val hasStatic = table.static.contains(attribute) && table.float(attribute, id).isFinite
        hasSeries || hasStatic
      }
      if set.nonEmpty then
        refuse(
          s"StorageUnit '${set.head}' sets $attribute, which pins the dispatch at a snapshot; " +
            "that constraint is not built here, and dropping it would leave the trajectory free " +
            "and the answer cheaper"
        )
    }

    // Multi-period wrapping. Both flags subdivide the horizon into investment
    // periods, which this model has no notion of; treating them as the
    // whole-horizon flags would cycle across period boundaries PyPSA does not.
    Seq("cyclic_state_of_charge_per_period", "state_of_charge_initial_per_period").foreach {
      attribute =>
        val set = table.ids.filter(id => table.static.contains(attribute) && table.bool(attribute, id))
        if set.nonEmpty then
          refuse(
            s"StorageUnit '${set.head}' sets $attribute, which is a multi-investment-period " +
              "concept; this model has a single horizon"
          )
    }

    // A quadratic term makes the objective non-linear, which Prima does not
    // solve. Silently dropping it returns the optimum of a different problem.
    val quadratic = table.ids.filter { id =>
      val attribute = "marginal_cost_quadratic"
      (table.static.contains(attribute) && table.float(attribute, id) != 0.0) ||
      table.series.get(attribute).exists(_.covers(id))
    }
    if quadratic.nonEmpty then
      refuse(
        s"StorageUnit '${quadratic.head}' has a non-zero marginal_cost_quadratic; the objective " +
          "here is linear"
      )

    // `max_hours` is what turns p_nom into an energy capacity. A zero would cap
    // the state of charge at zero, making the unit inert -- which is a legal
    // model but almost always a missing column rather than an intent, and it is
    // free to say so.
    table.ids.foreach { id =>
      val maxHours = table.float("max_hours", id)
      if !(maxHours > 0.0) then
        refuse(
          s"StorageUnit '$id' has max_hours = $maxHours, so its state of charge is capped at zero " +
            "and it can store nothing"
        )
      val standing = table.float("standing_loss", id)
      if standing < 0.0 || standing >= 1.0 then
        refuse(s"StorageUnit '$id' has standing_loss = $standing, which is not a fraction below 1")
    }
