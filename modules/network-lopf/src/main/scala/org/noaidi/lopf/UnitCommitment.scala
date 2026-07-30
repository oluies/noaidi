package org.noaidi.lopf

import org.noaidi.prima.*
import org.noaidi.network.*
import scala.collection.mutable

/** Result of a unit-commitment solve. */
final class UcResult private[lopf] (
    val status: MilpStatus,
    private val dispatch: Map[(String, Int), Double],
    private val commitment: Map[(String, Int), Boolean],
    val objective: Double,
    val bestBound: Double,
    val nodesExplored: Int,
    val solution: MilpSolution,
):
  /** Output in MW. */
  def output(generator: String, snapshot: Int): Double =
    dispatch.getOrElse(
      (generator, snapshot),
      throw new NoSuchElementException(s"no dispatch for '$generator' at snapshot $snapshot"),
    )

  /** Whether the unit is committed.
    *
    * A non-committable generator has no commitment decision, and this reports
    * `true` for one: it is always available, which is what the model does with
    * it — its output is bounded directly rather than through a status variable.
    *
    * PyPSA's `generators_t.status` frame instead carries 0 for such a generator,
    * because the frame spans every generator and has to put something there.
    * Neither is a decision, so the two are not compared.
    */
  def committed(generator: String, snapshot: Int): Boolean =
    commitment.getOrElse(
      (generator, snapshot),
      throw new NoSuchElementException(s"no commitment for '$generator' at snapshot $snapshot"),
    )

/** Unit commitment: which thermal units run, as well as how much they produce.
  *
  * Dispatch alone cannot express a unit that is either off or producing at least
  * some minimum — that is a disjunction, not an interval, and it is what makes
  * this a mixed-integer problem rather than a linear one. It is solved by
  * [[BranchAndBound]] over Prima, so the whole model stays inside this project's
  * own solver.
  *
  * ==The formulation==
  *
  * Per committable generator `g` and snapshot `t`, a binary `u[g,t]` and two
  * continuous switch variables:
  *
  * {{{
  * p_min_pu · p_nom · u  <=  p  <=  p_max_pu · p_nom · u     (on, or off and at zero)
  * su >= u[t] - u[t-1]                                        (a start-up happened)
  * sd >= u[t-1] - u[t]                                        (a shut-down happened)
  * sum of su over the last min_up_time snapshots   <= u[t]     (stay up once started)
  * sum of sd over the last min_down_time snapshots <= 1 - u[t] (stay down once stopped)
  * }}}
  *
  * `su` and `sd` are continuous rather than binary on purpose. They are driven to
  * 0 or 1 by the constraints above whenever `u` is integral, and every objective
  * coefficient on them is a non-negative cost, so the optimum never inflates
  * them. Declaring them integer would triple the size of the search tree for a
  * decision that is already determined.
  *
  * ==What is not modelled==
  *
  * Ramp limits, and start-up profiles that depend on how long a unit has been
  * down. Both are rejected rather than ignored — see [[reject]].
  */
object UnitCommitment:

  final class UnsupportedNetwork(message: String) extends RuntimeException(message)

  /** A generator's commitment parameters, read once.
    *
    * Not named `Unit`, which is Scala's own type and shadows it here.
    */
  private final case class Committable(
      table: ComponentTable,
      id: String,
      pNom: Double,
      startUpCost: Double,
      shutDownCost: Double,
      minUp: Int,
      minDown: Int,
      initiallyUp: Boolean,
  )

  def solve(
      network: Network,
      params: BnbParams = BnbParams(),
  ): UcResult =
    reject(network)

    val snapshots = network.snapshots.indices
    if snapshots.isEmpty then throw new UnsupportedNetwork("network has no snapshots")

    val generators = network.require("Generator")
    val buses      = network.require("Bus").ids

    val committable = generators.ids.filter(isCommittable(generators, _))
    val units = committable.map { id =>
      Committable(
        table = generators,
        id = id,
        pNom = generators.float("p_nom", id),
        startUpCost = optional(generators, "start_up_cost", id),
        shutDownCost = optional(generators, "shut_down_cost", id),
        minUp = optionalInt(generators, "min_up_time", id),
        minDown = optionalInt(generators, "min_down_time", id),
        // PyPSA's `up_time_before` defaults to 1, so a unit is treated as having
        // been running before the horizon. That is not cosmetic: with the
        // opposite convention every unit that starts the horizon on would be
        // charged a start-up at t=0, and the objective would be wrong by the sum
        // of those costs while the schedule looked identical.
        initiallyUp = optionalInt(generators, "up_time_before", id, default = 1) > 0,
      )
    }

    val columns = mutable.LinkedHashMap.empty[(String, String, Int), Int]
    val bounds  = mutable.ArrayBuffer.empty[(Double, Double)]
    val costs   = mutable.ArrayBuffer.empty[Double]

    def declare(kind: String, id: String, t: Int, lo: Double, hi: Double, cost: Double): Int =
      val index = bounds.length
      columns((kind, id, t)) = index
      bounds += ((lo, hi))
      costs += cost
      index

    snapshots.foreach { t =>
      val weight = network.weighting("objective", t)
      generators.ids.foreach { id =>
        val pNom = generators.float("p_nom", id)
        val hi   = pNom * generators.valueAt("p_max_pu", id, t)
        val lo   = pNom * generators.valueAt("p_min_pu", id, t)
        val cost = generators.valueAt("marginal_cost", id, t) * weight
        // A committable unit's lower bound is enforced by its status variable,
        // not by its box: the whole point is that zero is also allowed.
        if isCommittable(generators, id) then declare("p", id, t, 0.0, math.max(hi, 0.0), cost): Unit
        else declare("p", id, t, math.min(lo, hi), math.max(lo, hi), cost): Unit
      }
      units.foreach { u =>
        declare("u", u.id, t, 0.0, 1.0, 0.0): Unit
        declare("su", u.id, t, 0.0, 1.0, u.startUpCost): Unit
        declare("sd", u.id, t, 0.0, 1.0, u.shutDownCost): Unit
      }
    }

    val builder = LpProblem.builder(bounds.length)
    bounds.zipWithIndex.foreach { case ((lo, hi), i) => builder.bounds(i, lo, hi) }
    costs.zipWithIndex.foreach { (c, i) => builder.objectiveCoefficient(i, c) }

    // Bus balance. Single-bus networks are the common shape for a commitment
    // study, but nothing here assumes one.
    snapshots.foreach { t =>
      buses.foreach { bus =>
        val terms = generators.ids
          .filter(id => generators.string("bus", id) == bus)
          .map(id => (columns(("p", id, t)), 1.0))
        val demand = network
          .table("Load")
          .map(l => l.ids.filter(id => l.string("bus", id) == bus).map(l.valueAt("p_set", _, t)).sum)
          .getOrElse(0.0)
        if terms.nonEmpty || demand != 0.0 then builder.equalityConstraint(terms, demand)
      }
    }

    snapshots.foreach { t =>
      units.foreach { u =>
        val p  = columns(("p", u.id, t))
        val on = columns(("u", u.id, t))
        val su = columns(("su", u.id, t))
        val sd = columns(("sd", u.id, t))

        val lo = u.pNom * generators.valueAt("p_min_pu", u.id, t)
        val hi = u.pNom * generators.valueAt("p_max_pu", u.id, t)

        // p - p_max * u <= 0  and  p - p_min * u >= 0.
        builder.lessThan(Seq((p, 1.0), (on, -hi)), 0.0)
        builder.greaterThan(Seq((p, 1.0), (on, -lo)), 0.0)

        // su - sd = u[t] - u[t-1], which pins both switch variables at once:
        // they cannot both be positive at an optimum because both carry a
        // non-negative cost, so the equality does the work of two inequalities.
        val previous = if t == 0 then None else Some(columns(("u", u.id, t - 1)))
        previous match
          case Some(before) =>
            builder.equalityConstraint(Seq((su, 1.0), (sd, -1.0), (on, -1.0), (before, 1.0)), 0.0)
          case None =>
            val initial = if u.initiallyUp then 1.0 else 0.0
            builder.equalityConstraint(Seq((su, 1.0), (sd, -1.0), (on, -1.0)), -initial)

        // Minimum up time: having started within the last `minUp` snapshots
        // forces the unit to still be on now.
        if u.minUp > 1 then
          val window = (math.max(0, t - u.minUp + 1) to t).map(i => (columns(("su", u.id, i)), 1.0))
          builder.lessThan(window :+ (on, -1.0), 0.0)

        // Minimum down time: mirror image against `1 - u`.
        if u.minDown > 1 then
          val window = (math.max(0, t - u.minDown + 1) to t).map(i => (columns(("sd", u.id, i)), 1.0))
          builder.lessThan(window :+ (on, 1.0), 1.0)
      }
    }

    val (problem, _) = builder.build()

    // Only the status variables are integer. `su` and `sd` follow from them.
    val integers = columns.collect { case (("u", _, _), index) => index }.toSet

    val milp = BranchAndBound.solve(problem, integers, params)

    val dispatch   = mutable.Map.empty[(String, Int), Double]
    val commitment = mutable.Map.empty[(String, Int), Boolean]
    if milp.primal.nonEmpty then
      snapshots.foreach { t =>
        generators.ids.foreach { id =>
          dispatch((id, t)) = milp.primal(columns(("p", id, t)))
          commitment((id, t)) =
            if isCommittable(generators, id) then milp.primal(columns(("u", id, t))) > 0.5
            else true
        }
      }

    UcResult(
      status = milp.status,
      dispatch = dispatch.toMap,
      commitment = commitment.toMap,
      objective = milp.objectiveValue,
      bestBound = milp.bestBound,
      nodesExplored = milp.nodesExplored,
      solution = milp,
    )

  private def isCommittable(table: ComponentTable, id: String): Boolean =
    table.spec.attribute("committable").isDefined &&
      table.static.contains("committable") &&
      table.bool("committable", id)

  /** An int-typed attribute, absent-tolerant.
    *
    * Separate from [[optional]] because the store is genuinely typed: PyPSA
    * declares `min_up_time` as an int, and reading it as a float throws rather
    * than coercing — which is the point of having the types.
    */
  private def optionalInt(
      table: ComponentTable,
      attribute: String,
      id: String,
      default: Int = 0,
  ): Int =
    if table.spec.attribute(attribute).isEmpty && !table.static.contains(attribute) then default
    else if !table.static.contains(attribute) then default
    else table.int(attribute, id)

  private def optional(table: ComponentTable, attribute: String, id: String): Double =
    if table.spec.attribute(attribute).isEmpty && !table.static.contains(attribute) then 0.0
    else
      val value = table.float(attribute, id)
      if value.isFinite then value else 0.0

  /** Refuse what this formulation does not model.
    *
    * Ramp limits and time-dependent start-up costs are ordinary in a real
    * commitment study and are simply absent here. Silently dropping either gives
    * a cheaper schedule than the network permits — the same failure the rest of
    * this port keeps designing against — so they are refused.
    */
  private def reject(network: Network): Unit =
    val generators = network.table("Generator")
    generators.foreach { table =>
      Seq("ramp_limit_up", "ramp_limit_down", "ramp_limit_start_up", "ramp_limit_shut_down")
        .foreach { attribute =>
          if table.static.contains(attribute) then
            table.ids.foreach { id =>
              val value = table.float(attribute, id)
              // The default is NaN, meaning "no limit"; a finite value is a limit
              // this model would ignore.
              if value.isFinite then
                throw new UnsupportedNetwork(
                  s"generator '$id' has $attribute = $value; ramp limits are not modelled, and " +
                    "ignoring one yields a schedule the network cannot actually follow"
                )
            }
        }
    }

    Seq("StorageUnit", "Store").foreach { component =>
      network.table(component).foreach { table =>
        if table.size > 0 then
          throw new UnsupportedNetwork(
            s"network has ${table.size} $component(s); their energy balance couples snapshots " +
              "and is not modelled here"
          )
      }
    }

    network.table("Generator").foreach { table =>
      table.ids.foreach { id =>
        if table.spec.attribute("p_nom_extendable").isDefined &&
          table.static.contains("p_nom_extendable") &&
          table.bool("p_nom_extendable", id)
        then
          throw new UnsupportedNetwork(
            s"generator '$id' is extendable; commitment takes capacity as given"
          )
      }
    }
