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
  * '''Dispatch only.''' Capacity is taken as given: `p_nom` and `s_nom` are read,
  * not chosen. A network with `p_nom_extendable` set poses a capacity expansion
  * problem with investment variables and capital costs, which is a strictly
  * larger model and is not built here — [[build]] rejects such a network rather
  * than silently solving a different problem. The reference `ac-dc-meshed` is
  * such a network, which is why the goldens carry an `ac-dc-dispatch` variant.
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
  def build(network: Network): Model =
    rejectExtendable(network)
    rejectTransformers(network)
    rejectUnhandled(network)
    rejectDanglingBuses(network)

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

    // Selected by role rather than by name, so nothing with a bus0/bus1 is
    // silently dropped. Transformer is a passive branch too and reaches this
    // point, but `rejectTransformers` above has already refused it -- see there
    // for why admitting it would be worse than refusing it.
    def tablesWith(role: Role): IndexedSeq[ComponentTable] =
      network.tables.values.toIndexedSeq.filter(t => Role.of(t.spec) == role && t.size > 0)

    val passive      = tablesWith(Role.PassiveBranch)
    val controllable = tablesWith(Role.ControllableBranch)
    val attached     = tablesWith(Role.Attached)

    val generators = attached.filter(_.spec.name == "Generator")
    val loadTables = attached.filter(_.spec.name == "Load")

    snapshots.foreach { t =>
      // The objective weighting scales this snapshot's cost. It is not
      // decoration: a representative-period study expresses itself entirely
      // through these, and ignoring them silently rescales the objective.
      val weight = network.weighting("objective", t)

      generators.foreach { g =>
        g.ids.foreach { id =>
          val pNom = g.float("p_nom", id)
          val lo   = pNom * g.valueAt("p_min_pu", id, t)
          val hi   = pNom * g.valueAt("p_max_pu", id, t)
          val cost = g.valueAt("marginal_cost", id, t) * weight
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
          val limit = branch.float("s_nom", id) * branch.valueAt("s_max_pu", id, t)
          declare(branch.spec.name, id, t, -limit, limit, 0.0): Unit
        }
      }

      controllable.foreach { branch =>
        branch.ids.foreach { id =>
          val pNom = branch.float("p_nom", id)
          val lo   = pNom * branch.valueAt("p_min_pu", id, t)
          val hi   = pNom * branch.valueAt("p_max_pu", id, t)
          val cost = branch.valueAt("marginal_cost", id, t) * weight
          declare(branch.spec.name, id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
        }
      }
    }

    val builder = LpProblem.builder(bounds.length)
    bounds.zipWithIndex.foreach { case ((lo, hi), i) => builder.bounds(i, lo, hi) }
    costs.zipWithIndex.foreach { (c, i) => builder.objectiveCoefficient(i, c) }

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

  /** Solve, and map the answer back onto component names. */
  def solve(network: Network, params: PdhgParams = PdhgParams.default): LopfResult =
    val model    = build(network)
    val solution = Pdhg.solve(model.problem, params)
    LopfResult(network, model, solution)

  private def rejectExtendable(network: Network): Unit =
    val extendable = Seq(
      ("Generator", "p_nom_extendable"),
      ("Line", "s_nom_extendable"),
      ("Link", "p_nom_extendable"),
      ("StorageUnit", "p_nom_extendable"),
      ("Store", "e_nom_extendable"),
    ).flatMap { (component, attribute) =>
      network.table(component).toSeq.flatMap { table =>
        if table.spec.attribute(attribute).isEmpty then Seq.empty
        else table.ids.filter(id => table.bool(attribute, id)).map(id => s"$component '$id'")
      }
    }

    if extendable.nonEmpty then
      throw new UnsupportedNetwork(
        s"${extendable.size} component(s) are extendable, e.g. ${extendable.take(3).mkString(", ")}. " +
          "This is a capacity expansion problem; only dispatch is implemented, and solving it as " +
          "dispatch would answer a different question."
      )

    // Storage and stores carry state across snapshots, which dispatch alone
    // cannot represent -- their energy balance couples consecutive snapshots.
    Seq("StorageUnit", "Store").foreach { component =>
      network.table(component).foreach { table =>
        if table.size > 0 then
          throw new UnsupportedNetwork(
            s"network has ${table.size} $component(s); inter-snapshot storage balance is not implemented"
          )
      }
    }

  /** Reject transformers, which are passive branches this cannot yet price.
    *
    * Selecting branches by role rather than by name fixed one defect and created
    * a worse one. A transformer now gets an LP column and enters the cycle basis,
    * but [[Cycles.impedance]] applies the '''line''' per-unit base, and the two
    * are not the same. PyPSA converts a line from voltage (`x / v_nom²`) and a
    * transformer from its own rating (`x / s_nom`, then scaled by `tap_ratio`).
    * For a 380 kV, 500 MVA transformer with `x = 0.1` that is 2.0e-4 against
    * 6.9e-10 — six orders of magnitude, enough that any cycle crossing the
    * transformer behaves as though the transformer were not there, and the LP
    * comes back optimal with wrong flows.
    *
    * So this is refused until there is a golden with a transformer in it to
    * validate the conversion against. Refusing is not the timid option here: a
    * crash and a rejection are both loud, and a silently plausible dispatch is
    * the one outcome that cannot be caught downstream.
    */
  private def rejectTransformers(network: Network): Unit =
    network.table("Transformer").foreach { table =>
      if table.size > 0 then
        throw new UnsupportedNetwork(
          s"network has ${table.size} Transformer(s); their per-unit impedance is based on s_nom " +
            "and tap_ratio rather than v_nom, which is not implemented, and reusing the line " +
            "formula would give a feasible LP with wrong flows"
        )
    }

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
                      "TransformerType", "Shape")
    val unhandled = network.tables.values.filter(t => t.size > 0 && !handled.contains(t.spec.name))
    if unhandled.nonEmpty then
      throw new UnsupportedNetwork(
        s"network contains unmodelled component(s): " +
          unhandled.map(t => s"${t.spec.name} (${t.size})").mkString(", ")
      )

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
):
  def status: SolveStatus = solution.status

  /** Total cost, which for a dispatch problem is the objective. */
  def objective: Double = solution.objectiveValue

  /** Dispatch of one entity at one snapshot. */
  def dispatch(component: String, entity: String, snapshot: Int): Double =
    solution.primal(model.map.column(component, entity, snapshot))

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
