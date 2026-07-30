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
  * '''No Kirchhoff voltage constraints yet.''' Flows are constrained only by bus
  * balance and by line ratings, so in a meshed network the flow pattern is
  * underdetermined — any pattern satisfying balance is feasible. The objective
  * and the dispatch are therefore reproducible, but individual line flows are
  * not, and this is checked rather than assumed: see `LopfSuite`. Adding the
  * cycle constraints is the next step, and the brief is specific that PyPSA's
  * cycle-based formulation is the one to match rather than a bus-angle DC-OPF,
  * because equivalence is judged against PyPSA's own output.
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

    val generators = network.table("Generator")
    val lines      = network.table("Line")
    val links      = network.table("Link")

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
          declare("Generator", id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
        }
      }

      lines.foreach { l =>
        l.ids.foreach { id =>
          // A line's rating is symmetric and applies to apparent power; for the
          // linear model that is a bound on the flow in either direction.
          val sNom = l.float("s_nom", id)
          declare("Line", id, t, -sNom, sNom, 0.0): Unit
        }
      }

      links.foreach { k =>
        k.ids.foreach { id =>
          val pNom = k.float("p_nom", id)
          val lo   = pNom * k.valueAt("p_min_pu", id, t)
          val hi   = pNom * k.valueAt("p_max_pu", id, t)
          val cost = k.valueAt("marginal_cost", id, t) * weight
          declare("Link", id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
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
            terms += ((columns(("Generator", id, t)), 1.0))
          }
        }

        lines.foreach { l =>
          l.ids.foreach { id =>
            val c = columns(("Line", id, t))
            // p0 enters the branch at bus0, so it leaves that bus.
            if l.string("bus0", id) == bus then terms += ((c, -1.0))
            if l.string("bus1", id) == bus then terms += ((c, 1.0))
          }
        }

        links.foreach { k =>
          k.ids.foreach { id =>
            val c = columns(("Link", id, t))
            if k.string("bus0", id) == bus then terms += ((c, -1.0))
            // A link's output is scaled by its efficiency; the difference is
            // conversion loss, not a violation of balance.
            if k.string("bus1", id) == bus then
              terms += ((c, k.valueAt("efficiency", id, t)))
          }
        }

        // Load is fixed demand, so it moves to the right-hand side.
        val demand = network
          .table("Load")
          .map { loads =>
            loads.ids.filter(id => loads.string("bus", id) == bus).map(loads.valueAt("p_set", _, t)).sum
          }
          .getOrElse(0.0)

        builder.equalityConstraint(terms.toSeq, demand)
        balanceRows((bus, t)) = rowIndex
        rowIndex += 1
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
