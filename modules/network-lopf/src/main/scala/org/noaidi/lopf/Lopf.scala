package org.noaidi.lopf

import org.noaidi.prima.{LpProblem, LpSolution, Pdhg, PdhgParams, RowTranslation, SolveStatus, Unsafe}
import org.noaidi.network.*
import scala.collection.mutable

/** Linear optimal power flow: economic dispatch over a network's snapshots.
  *
  * Builds the LP, hands it to Prima, and maps the solution back onto component
  * names. This is the first module that connects the network model to the
  * solver, so the conventions it commits to are the ones everything above will
  * inherit.
  *
  * ==Scope==
  *
  * '''Dispatch and capacity.''' `p_nom_extendable` makes capacity a decision
  * rather than a given — see [[Expansion]], which also documents why PyPSA's
  * reported objective is the cost of the *change* and reads negative on
  * `ac-dc-meshed`. What remains refused there is everything that makes capital
  * cost something other than a per-unit constant: an annuitised
  * `overnight_cost`, modular capacity (`p_nom_mod`), and multi-investment-period
  * build years.
  *
  * '''Storage and stores''' carry state across snapshots — see [[Storage]] and
  * [[Stores]]. A `Store` is not a storage unit renamed: one signed power
  * variable rather than four, no efficiencies, and no power rating at all. Each
  * refuses its own remaining gaps — set points, per-period cycling and
  * quadratic costs.
  *
  * '''Kirchhoff voltage law is enforced''' over a cycle basis of the passive
  * branches, which is PyPSA's own formulation rather than a bus-angle DC-OPF —
  * the brief is specific about that, because equivalence is judged against
  * PyPSA's output and the two formulations differ in their duals. Without these
  * the model is a relaxation: bus balance alone admits flow patterns no set of
  * voltage angles could produce, and on a network whose line ratings bind it
  * finds a cheaper answer than exists.
  *
  * ==Sign conventions==
  *
  * Taken from PyPSA, since the goldens are PyPSA's:
  *
  *   - A branch's flow variable is `p0`, the power entering the branch at `bus0`.
  *     So `bus0` sees `-p0` in its balance and `bus1` sees `+p0` for a lossless
  *     line, or `+efficiency * p0` for a link.
  *   - A load's `p_set` is consumption, entering its bus's balance negatively.
  *   - Generator output is positive.
  */
object Lopf:

  /** Indices of the LP's variables, so a solution can be read back. */
  final case class VariableMap(
      /** `(component, entity, snapshot) -> column index`. */
      columns: Map[(String, String, Int), Int],
      /** `(bus, snapshot) -> row index of that bus's balance constraint`. */
      balanceRows: Map[(String, Int), Int],
      numVariables: Int,
  ):
    def column(component: String, entity: String, snapshot: Int): Int =
      columns.getOrElse(
        (component, entity, snapshot),
        throw new NoSuchElementException(s"no variable for $component '$entity' at snapshot $snapshot"),
      )

  final case class Model(problem: LpProblem, translation: RowTranslation, map: VariableMap)

  final class UnsupportedNetwork(message: String) extends RuntimeException(message)

  /** Turn a network into a dispatch LP. */
  def build(input: Network): Model =
    // Idempotent, and called here as well as in `solve` so a caller that builds
    // a model directly -- `Sclopf` does -- cannot get a network whose typed
    // branches still have no impedance.
    val network = Active.only(StandardTypes.expand(input))
    rejectUnhandled(network)
    rejectDanglingBuses(network)
    // Before everything else that reads a snapshot. A multi-period network's
    // snapshots are `(period, timestep)` pairs, and the reader collapses them to
    // the period alone -- so every later diagnostic would be phrased in terms of
    // duplicated labels for a network whose real problem is that this model does
    // not have periods at all.
    Periods.reject(network, m => throw new UnsupportedNetwork(m))
    rejectDelayedLinks(network)
    // Ahead of `Expansion.reject`, which used to carry the committable half of
    // this itself for the extendable case only. One refusal rather than three
    // partial ones: the narrower checks each described a different fragment of
    // the same gap, and the fragment nobody had written down -- a unit that is
    // merely committable -- was the one that returned a number.
    Commitment.reject(network, m => throw new UnsupportedNetwork(m))
    Expansion.reject(network)

    val snapshots = network.snapshots.indices
    if snapshots.isEmpty then throw new UnsupportedNetwork("network has no snapshots")


    val buses = network.table("Bus").map(_.ids).getOrElse(IndexedSeq.empty)
    if buses.isEmpty then throw new UnsupportedNetwork("network has no buses")

    // One variable per dispatchable entity per snapshot. Generators produce;
    // branches carry flow. Loads are data, not variables.
    val columns  = mutable.LinkedHashMap.empty[(String, String, Int), Int]
    val bounds   = mutable.ArrayBuffer.empty[(Double, Double)]
    val costs    = mutable.ArrayBuffer.empty[Double]

    def declare(component: String, entity: String, t: Int, lo: Double, hi: Double, cost: Double): Int =
      val index = bounds.length
      columns((component, entity, t)) = index
      bounds += ((lo, hi))
      costs += cost
      index

    // Selected by role rather than by name, so nothing carrying a bus0/bus1 is
    // silently dropped. Transformer is a passive branch too, and is now modelled:
    // `Cycles.impedance` refers it to `s_nom` and `tap_ratio` rather than to
    // `v_nom`, which is what made reusing the line formula a plausible wrong
    // answer and kept it refused until there was a network with one to check.
    def tablesWith(role: Role): IndexedSeq[ComponentTable] =
      network.tables.values.toIndexedSeq.filter(t => Role.of(t.spec) == role && t.size > 0)

    val passive      = tablesWith(Role.PassiveBranch)
    val controllable = tablesWith(Role.ControllableBranch)
    val attached     = tablesWith(Role.Attached)

    val generators = attached.filter(_.spec.name == "Generator")
    val loadTables = attached.filter(_.spec.name == "Load")
    val storage    = attached.find(_.spec.name == "StorageUnit")
    val stores     = attached.find(_.spec.name == "Store")

    storage.foreach(Storage.reject)
    stores.foreach(Stores.reject)

    // Elapsed hours per snapshot, from the `stores` weighting rather than the
    // `objective` one. They are separate columns of snapshots.csv and PyPSA
    // reads them for different purposes -- one scales energy, the other cost.
    // Every fixture but `storage-cycle` holds both at 1.0, so the confusion is
    // invisible everywhere else.
    def elapsedHours(t: Int): Double = network.weighting("stores", t)

    // Capacity variables: one per extendable entity for the whole horizon, not
    // one per snapshot. They are declared before anything else so that a network
    // with none produces exactly the column layout it did before expansion
    // existed.
    val expandable = (passive ++ controllable ++ attached)
      .map(table => table -> Expansion.extendables(table))
      .filter((_, ids) => ids.nonEmpty)

    expandable.foreach { (table, ids) =>
      val attribute = Expansion.nominalAttribute(table.spec.name)
      ids.foreach { id =>
        declare(
          Expansion.capacityKey(table.spec.name),
          id,
          Expansion.NoSnapshot,
          table.float(s"${attribute}_min", id),
          table.float(s"${attribute}_max", id),
          Expansion.periodizedCost(table, id),
        ): Unit
      }
    }

    /** Whether this entity's operational bounds come from a variable. */
    def extendable(table: ComponentTable, id: String): Boolean = Expansion.isExtendable(table, id)

    snapshots.foreach { t =>
      // The objective weighting scales this snapshot's cost. It is not
      // decoration: a representative-period study expresses itself entirely
      // through these, and ignoring them silently rescales the objective.
      val weight = network.weighting("objective", t)

      // An extendable entity's limits move out of its column and into two rows
      // per snapshot, against the capacity variable. So the column itself is
      // declared unbounded here and constrained below -- PyPSA's formulation,
      // and leaving a stale `p_nom` bound on it would cap the expansion at the
      // capacity the network came with.
      generators.foreach { g =>
        g.ids.foreach { id =>
          val cost = g.valueAt("marginal_cost", id, t) * weight
          if extendable(g, id) then
            declare(g.spec.name, id, t, Double.NegativeInfinity, Double.PositiveInfinity, cost): Unit
          else
            val pNom = g.float("p_nom", id)
            val lo   = pNom * g.valueAt("p_min_pu", id, t)
            val hi   = pNom * g.valueAt("p_max_pu", id, t)
            declare(g.spec.name, id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
        }
      }

      passive.foreach { branch =>
        branch.ids.foreach { id =>
          // A rating is symmetric and applies to apparent power; in the linear
          // model that is a bound on flow in either direction.
          //
          // Derated by `s_max_pu`, which every fixture leaves at 1.0 but an n-1
          // study does not: at the ordinary value of 0.7 a bound of plain `s_nom`
          // lets flows run 43% above the real rating.
          if extendable(branch, id) then
            declare(branch.spec.name, id, t, Double.NegativeInfinity, Double.PositiveInfinity, 0.0): Unit
          else
            val limit = branch.float("s_nom", id) * branch.valueAt("s_max_pu", id, t)
            declare(branch.spec.name, id, t, -limit, limit, 0.0): Unit
        }
      }

      controllable.foreach { branch =>
        branch.ids.foreach { id =>
          val cost = branch.valueAt("marginal_cost", id, t) * weight
          if extendable(branch, id) then
            declare(branch.spec.name, id, t, Double.NegativeInfinity, Double.PositiveInfinity, cost): Unit
          else
            val pNom = branch.float("p_nom", id)
            val lo   = pNom * branch.valueAt("p_min_pu", id, t)
            val hi   = pNom * branch.valueAt("p_max_pu", id, t)
            declare(branch.spec.name, id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
        }
      }

      // Four variables per storage unit, not one. Charging and discharging are
      // separate non-negative variables because they meet the state of charge
      // through different efficiencies -- a single signed variable cannot carry
      // `· eff_store` one way and `/ eff_dispatch` the other. `spill` exists so
      // inflow that will not fit has somewhere to go; without it a unit whose
      // inflow exceeds its remaining capacity makes the LP infeasible rather
      // than merely different.
      storage.foreach { s =>
        s.ids.foreach { id =>
          // All three lower bounds are zero whether or not the unit is
          // extendable, so only the upper bounds move into rows -- which keeps
          // the columns bounded below and the problem better conditioned than
          // making them free would.
          //
          // The upper bound is set to infinity directly rather than by
          // multiplying an infinite `p_nom` by the per-unit factor. `Infinity *
          // 0.0` is NaN, so a discharge-only unit (`p_min_pu = 0`, legal in
          // PyPSA) or a snapshot where a time-varying `p_max_pu` is zero
          // produced the bound `[0.0, NaN]` and an opaque crash inside the
          // builder. The real limit is carried by the capacity rows regardless.
          val extendableUnit = extendable(s, id)
          val pNom           = s.float("p_nom", id)
          def upper(perUnit: => Double): Double =
            if extendableUnit then Double.PositiveInfinity else pNom * perUnit

          declare(Storage.Dispatch, id, t, 0.0, upper(s.valueAt("p_max_pu", id, t)),
                  s.valueAt("marginal_cost", id, t) * weight): Unit
          // `p_min_pu` is negative for a storage unit -- it is how far the unit
          // may run *backwards* -- so the charging bound is its negation.
          declare(Storage.Store, id, t, 0.0, upper(-s.valueAt("p_min_pu", id, t)), 0.0): Unit
          declare(Storage.SoC, id, t, 0.0, upper(s.float("max_hours", id)),
                  s.valueAt("marginal_cost_storage", id, t) * weight): Unit
          // Bounded by the inflow itself, so a snapshot with none gets [0, 0] --
          // which is what PyPSA's masking amounts to, without a second shape of
          // variable map to carry it.
          declare(Storage.Spill, id, t, 0.0, math.max(0.0, s.valueAt("inflow", id, t)),
                  s.valueAt("spill_cost", id, t) * weight): Unit
        }
      }

      // Two variables per store: its energy level and one signed power. `p` is
      // deliberately unbounded -- PyPSA generates no operational constraint for
      // it, so a store's rate is limited only by its energy band and the elapsed
      // hours. Bounding it by `e_nom` is the obvious reading and is strictly
      // tighter.
      stores.foreach { store =>
        store.ids.foreach { id =>
          // Free at *both* ends when extendable, not just above. `e_min_pu` is
          // "the minimal value of `e` relative to `e_nom`" and is not restricted
          // to non-negative values, so clamping the column at zero is strictly
          // tighter than the LP being reproduced -- PyPSA leaves `Store-e` free
          // and carries both ends in rows. A StorageUnit's zero lower bound is
          // sound only because its three variables are non-negative by
          // construction, which a store's energy is not.
          val eNom = store.float("e_nom", id)
          val lo   = if extendable(store, id) then Double.NegativeInfinity
                     else eNom * store.valueAt("e_min_pu", id, t)
          val hi   = if extendable(store, id) then Double.PositiveInfinity
                     else eNom * store.valueAt("e_max_pu", id, t)
          declare(Stores.Energy, id, t, math.min(lo, hi), math.max(lo, hi),
                  store.valueAt("marginal_cost_storage", id, t) * weight): Unit
          declare(Stores.Power, id, t, Double.NegativeInfinity, Double.PositiveInfinity,
                  store.valueAt("marginal_cost", id, t) * weight): Unit
        }
      }
    }

    val builder = LpProblem.builder(bounds.length)
    bounds.zipWithIndex.foreach { case ((lo, hi), i) => builder.bounds(i, lo, hi) }
    costs.zipWithIndex.foreach { (c, i) => builder.objectiveCoefficient(i, c) }

    // PyPSA charges capital cost on the whole optimal capacity and then
    // subtracts the capital cost of what already existed, so its reported
    // objective is the cost of the *change* -- negative on `ac-dc-meshed`, where
    // the optimum builds less than the network came with. Reporting the total
    // instead would be out by 21.9 million and look entirely plausible.
    if expandable.nonEmpty then builder.objectiveOffset(-Expansion.objectiveConstant(network))

    // Bus balance: everything injected at a bus must equal everything withdrawn.
    val balanceRows = mutable.LinkedHashMap.empty[(String, Int), Int]
    var rowIndex    = 0

    snapshots.foreach { t =>
      buses.foreach { bus =>
        val terms = mutable.ArrayBuffer.empty[(Int, Double)]

        generators.foreach { g =>
          g.ids.filter(id => g.string("bus", id) == bus).foreach { id =>
            terms += ((columns((g.spec.name, id, t)), 1.0))
          }
        }

        // A storage unit injects what it discharges and withdraws what it
        // charges. `spill` never reaches the bus -- it is inflow leaving the
        // system unused, not power.
        storage.foreach { s =>
          s.ids.filter(id => s.string("bus", id) == bus).foreach { id =>
            terms += ((columns((Storage.Dispatch, id, t)), 1.0))
            terms += ((columns((Storage.Store, id, t)), -1.0))
          }
        }

        // A store's `p` is already signed: positive discharges into the bus.
        stores.foreach { store =>
          store.ids.filter(id => store.string("bus", id) == bus).foreach { id =>
            terms += ((columns((Stores.Power, id, t)), 1.0))
          }
        }

        (passive ++ controllable).foreach { branch =>
          val ports = Topology.branchPorts(branch)
          branch.ids.foreach { id =>
            val c = columns((branch.spec.name, id, t))
            ports.foreach { port =>
              if branch.string(port, id) == bus then
                // bus0 is where flow enters the branch, so it leaves that bus.
                // Every other port receives, scaled by that port's efficiency --
                // the difference is conversion loss, not a balance violation.
                if port == "bus0" then terms += ((c, -1.0))
                else terms += ((c, Topology.portEfficiency(branch, id, port, t)))
            }
          }
        }

        // Load is fixed demand, so it moves to the right-hand side.
        val demand = loadTables.map { loads =>
          loads.ids.filter(id => loads.string("bus", id) == bus).map(loads.valueAt("p_set", _, t)).sum
        }.sum

        builder.equalityConstraint(terms.toSeq, demand)
        balanceRows((bus, t)) = rowIndex
        rowIndex += 1
      }
    }

    // The storage energy balance, which is the only constraint in this model
    // that couples two snapshots. Everything else is separable by snapshot; this
    // is what makes a storage network a single LP over the horizon rather than
    // one LP per snapshot.
    //
    //   soc(t) = eff_stand · soc(t-1)
    //          + eh · eff_store · p_store(t)
    //          - eh / eff_dispatch · p_dispatch(t)
    //          - eh · spill(t)
    //          + eh · inflow(t)
    //
    // with `eff_stand = (1 - standing_loss)^eh`. Written with every variable on
    // the left and the constants on the right, as an equality.
    //
    // `soc(t-1)` is the previous snapshot's variable; at t = 0 it is either the
    // *last* snapshot's variable (cyclic) or absent, with `state_of_charge_initial`
    // moving to the right-hand side. Those are different constraint matrices, not
    // different numbers, which is why `storage-cycle` carries one unit of each.
    storage.foreach { s =>
      s.ids.foreach { id =>
        val effDispatch = (t: Int) => s.valueAt("efficiency_dispatch", id, t)
        val effStore    = (t: Int) => s.valueAt("efficiency_store", id, t)
        val cyclic      = Storage.isCyclic(s, id)
        val initial     = s.float("state_of_charge_initial", id)

        snapshots.foreach { t =>
          val eh = elapsedHours(t)
          if !(effDispatch(t) > 0.0) then
            throw new UnsupportedNetwork(
              s"StorageUnit '$id' has efficiency_dispatch = ${effDispatch(t)} at snapshot $t, " +
                "which the energy balance divides by"
            )

          val standing  = s.valueAt("standing_loss", id, t)
          val effStand  = math.pow(1.0 - standing, eh)
          val terms     = mutable.ArrayBuffer.empty[(Int, Double)]

          terms += ((columns((Storage.SoC, id, t)), 1.0))
          terms += ((columns((Storage.Dispatch, id, t)), eh / effDispatch(t)))
          terms += ((columns((Storage.Store, id, t)), -eh * effStore(t)))
          terms += ((columns((Storage.Spill, id, t)), eh))

          val previous = if t > 0 then Some(t - 1) else if cyclic then Some(snapshots.last) else None
          previous.foreach(p => terms += ((columns((Storage.SoC, id, p)), -effStand)))

          // Inflow is a rate, so it is energy only after multiplying by the
          // elapsed hours -- the same scaling the dispatch terms get.
          val rhs = eh * s.valueAt("inflow", id, t) + (if previous.isEmpty then initial else 0.0)
          builder.equalityConstraint(terms.toSeq, rhs)

          // A set point pins the state of charge at this snapshot. It is sparse
          // by construction -- `storage-hvdc` sets two values across 6 units and
          // 12 snapshots -- and the absent entries are NaN rather than zero,
          // which is what distinguishes "not set" from "set to empty".
          val target = s.valueAt("state_of_charge_set", id, t)
          if target.isFinite then
            builder.equalityConstraint(Seq(columns((Storage.SoC, id, t)) -> 1.0), target)
        }
      }
    }

    // Kirchhoff's voltage law, one constraint per independent cycle per snapshot.
    //
    // This is what makes flow a consequence of impedance rather than a free
    // variable. Bus balance alone leaves the pattern around a cycle
    // underdetermined, and where a rating binds it lets the model route around
    // the limit and undercut the true cost.
    val cycles = Cycles.basis(network)

    // Impedance is static, so it is computed once per branch rather than once per
    // branch per snapshot. Each lookup walks a table and reads two columns, and a
    // year-long network has 8760 snapshots.
    val impedances = cycles.flatMap { cycle =>
      cycle.terms.map { (component, id, _) =>
        (component, id) -> Cycles.impedance(network, component, id, cycle.carrier)
      }
    }.toMap

    // A cycle whose coefficients are all zero imposes nothing. Earlier this was
    // dropped to keep the matrix free of empty rows, which is a real but
    // secondary concern -- the primary one is that such a cycle leaves its flows
    // exactly as underdetermined as having no constraint at all, and the solve
    // then returns a too-cheap answer reporting Optimal. Since the condition is
    // detectable here, discarding the evidence is the one thing not to do.
    cycles.foreach { cycle =>
      if !cycle.terms.exists((component, id, _) => impedances((component, id)) != 0.0) then
        throw new UnsupportedNetwork(
          "every branch in the cycle " +
            cycle.terms.map((c, id, _) => s"$c '$id'").mkString(", ") +
            s" has zero impedance in its ${cycle.carrier} sub-network, so Kirchhoff's voltage law " +
            "would impose nothing and the flows around it would be left free"
        )
    }

    snapshots.foreach { t =>
      cycles.foreach { cycle =>
        val terms = cycle.terms.map { (component, id, orientation) =>
          (columns((component, id, t)), orientation * impedances((component, id)))
        }
        builder.equalityConstraint(terms, 0.0)
      }
    }

    // A store's energy balance. Simpler than a storage unit's -- no efficiency
    // either way, no inflow, no spill -- but the sign is the thing to get right:
    //
    //   e(t) = (1 - standing_loss)^eh · e(t-1)  -  eh · p(t)
    //
    // `p` is *subtracted*, because a positive `p` is energy leaving the store for
    // the bus. Reversing it gives a store that charges when it should discharge
    // and still balances every bus at every snapshot.
    stores.foreach { store =>
      store.ids.foreach { id =>
        val cyclic  = Stores.isCyclic(store, id)
        val initial = store.float("e_initial", id)

        snapshots.foreach { t =>
          val eh       = elapsedHours(t)
          val effStand = math.pow(1.0 - store.valueAt("standing_loss", id, t), eh)
          val terms    = mutable.ArrayBuffer.empty[(Int, Double)]

          terms += ((columns((Stores.Energy, id, t)), 1.0))
          terms += ((columns((Stores.Power, id, t)), eh))

          val previous = if t > 0 then Some(t - 1) else if cyclic then Some(snapshots.last) else None
          previous.foreach(p => terms += ((columns((Stores.Energy, id, p)), -effStand)))

          builder.equalityConstraint(terms.toSeq, if previous.isEmpty then initial else 0.0)
        }
      }
    }

    // Capacity coupling, two rows per extendable entity per snapshot. This is
    // where an expansion model differs from a dispatch one: the operational
    // limits are no longer constants in the column bounds but multiples of a
    // variable.
    //
    //   min_pu · capacity  <=  dispatch  <=  max_pu · capacity
    //
    // Emitted *after every equality block* -- bus balance, storage energy
    // balance, state-of-charge set points and Kirchhoff -- and not merely after
    // the balances. `LpBuilder.build` hoists all equalities to the front, so an
    // inequality emitted in between shifts every later equality's standard-form
    // index and breaks the row-by-row copy `Sclopf` performs. That copy requires
    // each original row to map to the standard-form row of the *same* index;
    // balances-first is necessary for it and not sufficient. Putting these rows
    // in the middle made `Sclopf.build` fail on any extendable network with a
    // cycle -- which is every extendable network worth running it on.
    expandable.foreach { (table, ids) =>
      val component = table.spec.name
      val capacity  = Expansion.capacityKey(component)
      ids.foreach { id =>
        val cap = columns((capacity, id, Expansion.NoSnapshot))
        snapshots.foreach { t =>
          Expansion.operationalBounds(table, id, t).foreach { (variable, minPu, maxPu) =>
            val column = columns((variable, id, t))
            // `lessThan`/`greaterThan` rather than a range row: the base model's
            // rows are copied one-for-one by `Sclopf`, which a range row -- one
            // original row becoming two -- would break.
            if maxPu.isFinite then builder.lessThan(Seq(column -> 1.0, cap -> -maxPu), 0.0)
            if minPu.isFinite then builder.greaterThan(Seq(column -> 1.0, cap -> -minPu), 0.0)
          }
        }
      }
    }

    // Ramp limits and energy budgets. Both are inequalities, and both belong
    // after every equality block for the reason the capacity rows above give:
    // `LpBuilder.build` hoists equalities to the front, so an inequality emitted
    // among them shifts every later equality's standard-form index and breaks
    // the row-by-row copy `Sclopf` performs.
    //
    // Ramp rows are the first rows here to reference two snapshots of the same
    // variable. Everything else in this model is either within one snapshot or,
    // in the storage balances, a chain the builder already emits per snapshot --
    // so nothing about the column layout had to change to carry them.
    (generators ++ controllable).foreach { table =>
      val component = table.spec.name
      Ramps.constrain(
        table,
        snapshots,
        (id, t) => columns((component, id, t)),
        id =>
          if extendable(table, id) then
            Some(columns((Expansion.capacityKey(component), id, Expansion.NoSnapshot)))
          else None,
        builder,
      )
    }

    // The same tables the ramp rows sweep, not just `generators`. PyPSA hardcodes
    // Generator here and no other class declares the attributes, so this changes
    // nothing today -- `EnergySum.constrain` returns immediately for a spec
    // without them. It is written this way so the module's claim to be driven by
    // the schema rather than by a component name is actually true at the call
    // site, which it was not when the call was `generators.foreach`.
    (generators ++ controllable).foreach { table =>
      val component = table.spec.name
      EnergySum.constrain(table, network, snapshots, (id, t) => columns((component, id, t)), builder)
    }

    // Global constraints -- an emissions cap, typically. Ignoring one is not
    // conservative: it drops a restriction, so the answer comes out cheaper than
    // the real network's, and the "objective is a lower bound on PyPSA's" test
    // that guarded the Kirchhoff work would have passed for exactly that reason.
    // A dropped constraint and a missing constraint are indistinguishable to an
    // inequality.
    network.table("GlobalConstraint").foreach { constraints =>
      constraints.ids.foreach { id =>
        val sense    = constraints.string("sense", id)
        val constant = constraints.float("constant", id)

        // `type` selects an entirely different left-hand side in PyPSA --
        // primary_energy, tech_capacity_expansion_limit and operational_limit are
        // three separate builders. Assuming the first would take an
        // `operational_limit` capping one carrier's *energy* and build it as an
        // emissions-weighted sum over every emitting generator: a different
        // constraint wearing the same right-hand side, returning Optimal.
        val kind = constraints.string("type", id)
        if kind != "primary_energy" then
          throw new UnsupportedNetwork(
            s"global constraint '$id' has type '$kind'; only 'primary_energy' is implemented"
          )
        if sense != "<=" then
          throw new UnsupportedNetwork(
            s"global constraint '$id' has sense '$sense'; only '<=' is implemented"
          )

        // Which carrier column is charged is data, not a constant. Hardcoding
        // `co2_emissions` silently reads the wrong column -- or, since a missing
        // column reads as zero, drops every term and then drops the row.
        val attribute = constraints.string("carrier_attribute", id)

        // Emissions are per unit of *primary energy*, so a generator's output is
        // divided by its efficiency before being charged.
        //
        // Weighted by the `generators` column, not `objective`. PyPSA uses a
        // different weighting for the emissions sum than for cost, and they are
        // separate columns of snapshots.csv that a representative-period study
        // sets apart on purpose. Every fixture holds both at 1.0, so no
        // comparison here can see the difference -- which is exactly why it has to
        // be read rather than assumed.
        val terms = snapshots.flatMap { t =>
          val weight = network.weighting("generators", t)
          generators.flatMap { g =>
            g.ids.flatMap { gid =>
              val intensity  = carrierAttribute(network, g.string("carrier", gid), attribute)
              val efficiency = g.valueAt("efficiency", gid, t)
              if intensity == 0.0 || efficiency == 0.0 then None
              else Some((columns((g.spec.name, gid, t)), intensity / efficiency * weight))
            }
          }
        }

        if terms.nonEmpty then builder.lessThan(terms, constant)
      }
    }

    val (problem, translation) = builder.build()
    Model(problem, translation, VariableMap(columns.toMap, balanceRows.toMap, bounds.length))

  /** Solve, and map the answer back onto component names.
    *
    * The result holds the '''expanded''' network — the one the model was built
    * from — so an accessor reading a branch's impedance back sees what the
    * constraints used rather than the `x = 0` a typed line carries in its file.
    */
  def solve(input: Network, params: PdhgParams = PdhgParams.default): LopfResult =
    val expanded = StandardTypes.expand(input)
    val network  = Active.only(expanded)
    val model    = build(network)
    val solution = Pdhg.solve(model.problem, params)
    LopfResult(network, model, solution, Active.inactive(expanded))

  /** Reject component classes the builder does not model.
    *
    * Silently dropping a component is the worst available outcome: the LP stays
    * feasible and returns a plausible dispatch for a network that is not the one
    * given. A ShuntImpedance draws power and a Process converts it, and neither
    * is built here.
    */
  private def rejectUnhandled(network: Network): Unit =
    val handled = Set("Generator", "Load", "Line", "Transformer", "Link", "Bus",
                      "Carrier", "GlobalConstraint", "SubNetwork", "LineType",
                      "TransformerType", "Shape", "StorageUnit", "Store")
    val unhandled = network.tables.values.filter(t => t.size > 0 && !handled.contains(t.spec.name))
    if unhandled.nonEmpty then
      throw new UnsupportedNetwork(
        s"network contains unmodelled component(s): " +
          unhandled.map(t => s"${t.spec.name} (${t.size})").mkString(", ")
      )

  /** Reject a link that delivers its energy in a later snapshot.
    *
    * PyPSA's `delay` shifts a link's output by whole snapshots: power entering
    * at `t` leaves at `t + delay`, and `cyclic_delay` decides whether what is
    * still in flight at the end of the horizon wraps to the beginning or is
    * lost. The balance rows built here pair `bus0` and `bus1` within a single
    * snapshot, so a delayed link is modelled as instantaneous.
    *
    * The default is 0 and the attribute reads as inert, which is why nothing
    * caught it: no fixture sets one. On a two-bus network whose only load sits
    * at the first snapshot, a delay of 1 makes the cheap import useless and
    * PyPSA pays 9,000 for local generation; this model delivered the import
    * instantly and reported 500. An eighteen-fold under-price, `Optimal`.
    *
    * Only `delay` is checked, and `cyclic_delay` deliberately is not. It defaults
    * to `True` rather than `False`, so it is not inert on its face — but it
    * decides only what happens to energy still in flight at the end of the
    * horizon, and nothing is ever in flight while `delay` is zero. Every network
    * that could observe it is refused here first, which is what
    * `SchemaSweepSuite` records against it rather than leaving it to read as an
    * attribute nobody looked at.
    */
  private def rejectDelayedLinks(network: Network): Unit =
    network.tables.values.foreach { table =>
      if table.spec.attribute("delay").isDefined && table.static.contains("delay") then
        table.ids.foreach { id =>
          val delay = table.int("delay", id)
          if delay != 0 then
            throw new UnsupportedNetwork(
              s"${table.spec.name} '$id' has delay = $delay, so its energy arrives $delay " +
                "snapshot(s) after it enters; the balance rows here pair both ends within one " +
                "snapshot, which would deliver it instantly"
            )
        }
    }

  /** Reject a component whose bus does not exist.
    *
    * A branch with a stale endpoint contributes a term at only its valid end, so
    * its flow becomes a free source or sink bounded only by its rating and the
    * objective comes out cheaper than the real network's. A generator or load
    * with a bad bus simply vanishes. Both stay feasible, and a dropped load makes
    * the answer cheaper with no diagnostic -- so this is loud, matching
    * `Topology.danglingReferences`, which the model layer already made throw.
    */
  private def rejectDanglingBuses(network: Network): Unit =
    Topology.danglingBusReferences(network).headOption.foreach { (component, id, port, bus) =>
      throw new UnsupportedNetwork(s"$component '$id' references unknown bus '$bus' via $port")
    }

  /** A carrier's value for the attribute a global constraint charges.
    *
    * Zero when the carrier is unknown or the column absent, which is the right
    * reading: a carrier with no declared intensity contributes nothing to the cap.
    */
  private def carrierAttribute(network: Network, carrier: String, attribute: String): Double =
    network
      .table("Carrier")
      .filter(t => t.has(carrier) && (t.spec.attribute(attribute).isDefined || t.static.contains(attribute)))
      .map(_.float(attribute, carrier))
      .filter(_.isFinite)
      .getOrElse(0.0)



/** A solved dispatch, addressable by component name. */
final case class LopfResult(
    network: Network,
    model: Lopf.Model,
    solution: LpSolution,
    /** Entities the network switched off, which have no variable in the model.
      *
      * PyPSA keeps an inactive component's column in its result frames carrying
      * 0.0, so the accessors below do the same rather than throwing. The
      * distinction the accessors exist to preserve is kept: a genuinely unknown
      * name still throws, because that means the caller and the model disagree
      * about what the network contains.
      */
    inactive: Map[String, IndexedSeq[String]] = Map.empty,
):
  private def isInactive(component: String, entity: String): Boolean =
    inactive.get(component).exists(_.contains(entity))
  def status: SolveStatus = solution.status

  /** Total cost, which for a dispatch problem is the objective. */
  def objective: Double = solution.objectiveValue

  /** Dispatch of one entity at one snapshot.
    *
    * For a `StorageUnit` this is the net injection `p_dispatch − p_store`, which
    * is what PyPSA's `storage_units_t.p` holds — the unit has no single dispatch
    * variable, and returning either half alone would report a charging unit as
    * idle.
    */
  def dispatch(component: String, entity: String, snapshot: Int): Double =
    if isInactive(component, entity) then 0.0
    else if component == "StorageUnit" then
      storage(Storage.Dispatch, entity, snapshot) - storage(Storage.Store, entity, snapshot)
    else if component == "Store" then storage(Stores.Power, entity, snapshot)
    else solution.primal(model.map.column(component, entity, snapshot))

  /** A store's energy level at the '''end''' of a snapshot.
    *
    * PyPSA's `stores_t.e`, and the convention the balance is written in: what
    * remains after that snapshot's charging and discharging.
    */
  def energy(entity: String, snapshot: Int): Double = storage(Stores.Energy, entity, snapshot)

  /** A storage unit's state of charge at the '''end''' of a snapshot.
    *
    * PyPSA's convention, and the one the energy balance is written in: the value
    * recorded against snapshot `t` is what remains after that snapshot's
    * charging and discharging, not what was there before it.
    */
  def stateOfCharge(entity: String, snapshot: Int): Double =
    storage(Storage.SoC, entity, snapshot)

  def charging(entity: String, snapshot: Int): Double   = storage(Storage.Store, entity, snapshot)
  def discharging(entity: String, snapshot: Int): Double = storage(Storage.Dispatch, entity, snapshot)
  def spill(entity: String, snapshot: Int): Double      = storage(Storage.Spill, entity, snapshot)

  /** The chosen capacity of an entity — PyPSA's `<attr>_opt`.
    *
    * The given `p_nom`/`s_nom` for anything not extendable, so a caller can read
    * it uniformly without first asking which components were expanded. That is
    * the same convention PyPSA writes into `p_nom_opt`.
    */
  def capacity(component: String, entity: String): Double =
    val table = network.require(component)
    if !Expansion.isExtendable(table, entity) then
      table.float(Expansion.nominalAttribute(component), entity)
    else
      solution.primal(
        model.map.column(Expansion.capacityKey(component), entity, Expansion.NoSnapshot)
      )

  /** Total system cost: the objective plus the capital cost already sunk.
    *
    * PyPSA's objective is the cost of the *change* — it charges capital cost on
    * the whole optimal capacity and subtracts what the network came with — so on
    * `ac-dc-meshed` it reads −3,474,256 against a system cost of 18,441,021.
    */
  def totalSystemCost: Double = objective + Expansion.objectiveConstant(network)

  private def storage(variable: String, entity: String, snapshot: Int): Double =
    solution.primal(model.map.column(variable, entity, snapshot))

  /** Marginal price at a bus: the dual of its balance constraint.
    *
    * This is why the dual side of the solver was built carefully. A nodal price
    * is not a diagnostic here — it is a headline output of the model, and the
    * one an operator acts on.
    */
  def marginalPrice(bus: String, snapshot: Int): Double =
    val row = model.map.balanceRows.getOrElse(
      (bus, snapshot),
      throw new NoSuchElementException(s"no balance row for bus '$bus' at snapshot $snapshot"),
    )
    // Recovered through the row translation, so the sign convention matches the
    // constraint as it was written rather than as the solver reordered it.
    model.translation.originalDuals(solution.dual)(row) / network.weighting("objective", snapshot)
