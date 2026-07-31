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
      upTimeBefore: Int,
      downTimeBefore: Int,
  ):
    def initiallyUp: Boolean = upTimeBefore > 0

    /** Snapshots at the head of the horizon whose status is already determined
      * by what the unit was doing before it.
      *
      * PyPSA enforces the '''residual''' condition, not merely a seed for the
      * linking equality: a unit that has already run `up_time_before` snapshots
      * must stay committed for a further `min_up_time − up_time_before`, and the
      * mirror holds for one that has been down. Seeding `u[-1]` alone leaves that
      * unconstrained, so this model would accept a schedule PyPSA rejects — and
      * accept it at a '''lower''' cost, the direction that cannot be caught
      * downstream.
      *
      * In the reference fixture `base` has `min_up_time = 3` against an
      * `up_time_before` of 1, so PyPSA holds it on at t = 0 and 1. That went
      * unnoticed because `base` runs throughout anyway.
      */
    def forcedPrefix: Int =
      if initiallyUp then math.max(minUp - upTimeBefore, 0)
      else math.max(minDown - downTimeBefore, 0)

    def forcedValue: Double = if initiallyUp then 1.0 else 0.0

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
        // `ComponentTable.float`/`int` already fall back to the schema default
        // when a column is absent, which is the normal case since PyPSA omits
        // columns left at their default. The local helpers that used to wrap
        // these re-implemented that fallback and hardcoded a default the schema
        // already carries -- two places to change, with the code silently
        // winning.
        startUpCost = generators.float("start_up_cost", id),
        shutDownCost = generators.float("shut_down_cost", id),
        minUp = generators.int("min_up_time", id),
        minDown = generators.int("min_down_time", id),
        // Read as counts rather than collapsed to a flag. PyPSA's
        // `up_time_before` defaults to 1, so a unit counts as having run before
        // the horizon -- not cosmetic, since the opposite convention charges a
        // start-up at t=0 to every unit that begins committed and the objective
        // comes out wrong by the sum of those while the schedule looks
        // identical. The magnitudes matter too, for `forcedPrefix`.
        upTimeBefore = generators.int("up_time_before", id),
        downTimeBefore = generators.int("down_time_before", id),
      )
    }

    // Derived once per (generator, snapshot) and read by all three loops. The
    // column bound and the `p - hi*u <= 0` row have to agree about `hi`, and
    // computing it twice from the same lookups left nothing enforcing that a
    // change to one site would reach the other.
    final case class Limits(lo: Double, hi: Double, committable: Boolean)
    val limits: Map[(String, Int), Limits] =
      (for
        t  <- snapshots
        id <- generators.ids
      yield
        val pNom = generators.float("p_nom", id)
        (id, t) -> Limits(
          lo = pNom * generators.valueAt("p_min_pu", id, t),
          hi = pNom * generators.valueAt("p_max_pu", id, t),
          committable = isCommittable(generators, id),
        )
      ).toMap

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
        val limit = limits((id, t))
        val cost  = generators.valueAt("marginal_cost", id, t) * weight
        // A committable unit's lower bound is enforced by its status variable,
        // not by its box: the whole point is that zero is also allowed.
        if limit.committable then declare("p", id, t, 0.0, math.max(limit.hi, 0.0), cost): Unit
        else
          declare("p", id, t, math.min(limit.lo, limit.hi), math.max(limit.lo, limit.hi), cost): Unit
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

    // Bus balance over generators and load only: there are no flow variables,
    // so this holds *locally* at each bus. That is why `reject` refuses a
    // network with any branch -- without flows, import and export are silently
    // deleted and a multi-bus network comes back either spuriously infeasible or
    // with a schedule far more expensive than the real one.
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

        val limit = limits((u.id, t))

        // p - p_max * u <= 0  and  p - p_min * u >= 0, with the same `hi` the
        // column bound was built from.
        builder.lessThan(Seq((p, 1.0), (on, -limit.hi)), 0.0)
        builder.greaterThan(Seq((p, 1.0), (on, -limit.lo)), 0.0)

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

        // The pre-horizon residual: what the unit was doing before t=0 can
        // still be binding at the head of the horizon.
        if t < u.forcedPrefix then builder.equalityConstraint(Seq((on, 1.0)), u.forcedValue)

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
            if limits((id, t)).committable then milp.primal(columns(("u", id, t))) > 0.5
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

  /** Refuse what this formulation does not model.
    *
    * Everything here would otherwise be dropped silently and produce a schedule
    * cheaper than the network permits — the direction that cannot be caught
    * downstream, and the failure this port keeps designing against.
    */
  private def reject(network: Network): Unit =
    // Any transmission at all. The balance rows carry no flow variables, so a
    // network with branches is not being solved with its transmission ignored --
    // it is being solved with every bus forced to balance on its own. `Lopf`
    // models flows and refuses only what it cannot build; this refuses the whole
    // class, because a commitment model without flows is a single-bus model.
    // Global constraints get their own refusal, ahead of the branch check.
    // Merely dropping them from `handled` routed a CO2 cap into the
    // transmission diagnostic -- "this network carries GlobalConstraint (1)"
    // under a message about branch flows -- which states the wrong cause and
    // points the reader at the wrong fix. Lopf models primary-energy caps; this
    // does not, and solving with the cap deleted prices the schedule below the
    // truth.
    network.table("GlobalConstraint").foreach { table =>
      if table.size > 0 then
        throw new UnsupportedNetwork(
          s"network has ${table.size} global constraint(s); emissions and primary-energy caps " +
            "are not modelled here, and dropping one would price the schedule below the truth"
        )
    }

    val handled = Set("Generator", "Load", "Bus", "Carrier",
                      "SubNetwork", "LineType", "TransformerType", "Shape")
    val unhandled = network.tables.values.filter(t => t.size > 0 && !handled.contains(t.spec.name))
    if unhandled.nonEmpty then
      throw new UnsupportedNetwork(
        "unit commitment is formulated without branch flows, so it models a single bus; " +
          "this network carries " + unhandled.map(t => s"${t.spec.name} (${t.size})").mkString(", ")
      )

    // A one-port whose bus does not exist matches no balance row, so it simply
    // vanishes -- a load disappears and the schedule comes out cheaper, with no
    // diagnostic. `Lopf` guards this through the same helper; leaving one entry
    // point loud and the other silently wrong is the thing to avoid.
    Topology.danglingBusReferences(network).headOption.foreach { (component, id, port, bus) =>
      throw new UnsupportedNetwork(s"$component '$id' references unknown bus '$bus' via $port")
    }

    // Costs PyPSA charges and this objective does not. `stand_by_cost` is levied
    // per committed unit per snapshot, so ignoring it makes over-committing free
    // and the schedule comes out below the true cost -- the same reason ramp
    // limits are refused rather than ignored, and they were treated
    // inconsistently until now.
    network.table("Generator").foreach { table =>
      Seq("stand_by_cost", "marginal_cost_quadratic").foreach { attribute =>
        // Evaluated per snapshot, not read from the static column. Both are
        // `static or series` with `varying: true`, and a value arriving as
        // `generators-stand_by_cost.csv` with no static counterpart would slip
        // past a `static.contains` check and be dropped -- the same
        // under-pricing this guard exists to prevent. The ramp-limit check below
        // is static-only because those attributes are genuinely `varying: false`,
        // so the pattern does not transfer.
        // Gated on the *data*, not the spec. Both attributes are declared in the
        // schema, so `spec.attribute(...).isDefined` is unconditionally true and
        // the sweep ran for every solve even when neither appears in the file --
        // and each `valueAt` on an absent column re-parses the default string, so
        // a few thousand generators over a year is tens of millions of parses to
        // find nothing. Checking the columns that exist still catches the
        // series-only case, since `valueAt` resolves the override where there is
        // one and the static or default value elsewhere.
        val present = table.static.contains(attribute) || table.series.contains(attribute)
        if present then
          table.ids.foreach { id =>
            // The static value too, not only the per-snapshot sweep: a network
            // with no snapshots would otherwise slip through a guard whose whole
            // point is to let nothing priced past. Each carries where it came
            // from, so a value buried in a year-long series is reported with its
            // snapshot rather than leaving the reader to search the file.
            val everywhere =
              ("statically", table.float(attribute, id)) +:
                network.snapshots.indices.map(t => (s"at snapshot $t", table.valueAt(attribute, id, t)))
            everywhere.foreach { (where, value) =>
              if value.isFinite && value != 0.0 then
                throw new UnsupportedNetwork(
                  s"generator '$id' has $attribute = $value $where; it enters PyPSA's objective " +
                    "and not this one, so ignoring it would price the schedule below the truth"
                )
            }
          }
      }
    }

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
