package org.noaidi.lopf

import org.noaidi.network.*

/** Multi-investment periods: several build years optimised at once.
  *
  * A multi-period network's snapshots are `(period, timestep)` pairs. Three
  * things follow, and this module is all three:
  *
  *   - '''An asset exists only in some periods.''' `build_year <= period <
  *     build_year + lifetime`, and-ed with `active`. Outside that window PyPSA
  *     masks the asset's variables, which here means pinning its columns to
  *     zero — not dropping them, so the column layout is the one every other
  *     part of this model and `Sclopf`'s row-by-row copy already agree on.
  *   - '''Every cost in a period carries that period's weighting.'''
  *     `investment_periods.csv` holds an `objective` column, which is the
  *     discount factor, and PyPSA multiplies the snapshot's own objective
  *     weighting by it. So does the nodal price, which is divided by the
  *     product — see [[LopfResult.marginalPrice]].
  *   - '''`years` is a different weighting for a different purpose.''' It is how
  *     many years the period stands for, and it is what a global constraint sums
  *     against rather than what a cost is scaled by. Two columns in one small
  *     file that are easy to swap, in a repository that has already been caught
  *     doing exactly that with `snapshots.csv`'s three.
  *
  * ==What this cost before it was modelled==
  *
  * `investment_periods.csv` sat in the reader's set of non-component files and
  * '''no code read it''', so a multi-period network arrived at the builder
  * indistinguishable from an ordinary one and was solved as though every asset
  * existed from the start. On a two-period network whose cheap generator has
  * `build_year = 2040`, PyPSA spends 17,000 — the expensive unit carries the
  * whole of 2030 because the cheap one does not exist yet — and this port
  * returned '''2,000''', running the cheap generator ten years before it was
  * built, reporting `Optimal`.
  *
  * The reader compounded it. With `period` and `timestep` columns and no
  * `snapshot` column, the label came from the first column after the index, so
  * four snapshots read back as `2030, 2030, 2040, 2040` — two pairs of
  * duplicates — and `timestep` was parsed as though it were a weighting.
  */
object Periods:

  /** Whether an asset exists in a period.
    *
    * PyPSA's `build_year <= @period < build_year + lifetime`, evaluated on the
    * static frame. `lifetime` defaults to infinity, so an asset with a build
    * year and no lifetime is active from that year onwards; `build_year`
    * defaults to 0, so an asset with neither is active always. A component class
    * declaring neither attribute is active always too, which is what PyPSA's
    * `issubset` guard amounts to.
    */
  def activeIn(table: ComponentTable, id: String, period: String): Boolean =
    if !declares(table, "build_year") || !declares(table, "lifetime") then true
    else
      period.trim.toDoubleOption match
        // A period label PyPSA would not accept as a year. `reject` refuses such
        // a network before any build reaches here -- reading every asset as
        // present is the "cheaper than the truth" direction, so it is not a
        // safety net, only what a direct caller gets for a network that would
        // never have been solved.
        case None => true
        case Some(year) =>
          val built    = table.int("build_year", id).toDouble
          val lifetime = table.float("lifetime", id)
          // `NaN` is not the documented default -- infinity is -- but a file can
          // carry one, and every comparison against NaN is false, which would
          // silently retire the asset in every period.
          val end = if lifetime.isNaN then Double.PositiveInfinity else built + lifetime
          built <= year && year < end

  /** Whether an asset exists at a snapshot. Always true on a flat index. */
  def activeAt(network: Network, table: ComponentTable, id: String, snapshot: Int): Boolean =
    network.periodOf(snapshot).forall(activeIn(table, id, _))

  /** The factor every cost at this snapshot is multiplied by.
    *
    * The snapshot's own `objective` weighting times its period's. One accessor
    * rather than two multiplications spread through the builder, because the
    * price recovery has to divide by exactly the same thing and the two going
    * out of step is not visible in the objective.
    *
    * ==Which PyPSA this is==
    *
    * PyPSA keys two things off two different signals, and this port deliberately
    * keys both off one. Whether an asset '''exists''' in a period comes from the
    * snapshot index: `get_activity_mask` branches on `has_investment_periods`, so
    * `build_year` and `lifetime` bind on any network whose snapshots are
    * `(period, timestep)` pairs. Whether the period weightings are '''applied'''
    * comes from `n._multi_invest`, which only
    * `optimize(multi_investment_periods=True)` sets -- `define_objective` guards
    * the `objective` column on it and `define_primary_energy_limit` guards the
    * `years` column on it.
    *
    * So PyPSA will mask a network by build year and then charge every period
    * undiscounted, if asked. This port has no such switch: a network whose index
    * carries periods is weighted, which is PyPSA's flagged behaviour. That is the
    * choice a solver reading a file has to make -- the `_multi_invest` field in
    * `network.csv` is a residue of whatever the last solve was told, and PyPSA
    * writes 0 into it for a network exported before it was solved -- but it was an
    * '''unvalidated''' choice for as long as the only multi-period fixture held
    * both weightings at 1.0 and was generated with no flag at all.
    * `investment-periods-discounted` is the fixture that can tell: `objective`
    * [1.0, 0.6], `years` [10, 5], a CO2 cap that only binds once `years` is
    * applied, and PyPSA's own answer under the flag.
    */
  def objectiveWeight(network: Network, snapshot: Int): Double =
    network.weighting("objective", snapshot) * network.periodObjectiveWeighting(snapshot)

  /** Refuse the parts of multi-period that are still not built.
    *
    * Narrower than it was: this used to refuse every multi-period network
    * outright. What is left is the combinations whose formulation differs from
    * the single-period one rather than merely being weighted differently.
    */
  def reject(network: Network, refuse: String => Nothing): Unit =
    if !network.isMultiPeriod then return

    // A period label that is not a year. `activeIn` compares `build_year <=
    // period < build_year + lifetime`, which needs a number; with none it reads
    // every asset as present, so a network declaring e.g. `2030-01` would be
    // solved with every build year ignored -- the "cheaper than the truth"
    // direction refused everywhere else here. PyPSA evaluates that expression
    // through `static.eval` and raises rather than admit a non-numeric label.
    network.investmentPeriods.foreach { period =>
      if period.trim.toDoubleOption.isEmpty then
        refuse(
          s"investment_periods.csv declares period '$period', which is not a year; no build year " +
            "can be compared against it and every asset would read as built"
        )
    }

    // A non-finite weighting on a declared period. `objective` multiplies every cost and
    // divides every nodal price; `years` scales a primary-energy sum and an operational
    // limit; and `Expansion.costWeight` sums `objective` into the capital coefficient of
    // every extendable asset and into an unscoped transmission cost limit. So one NaN in a
    // two-column file reaches the objective, the duals and three constraint families, and
    // `ComponentTable.periodWeighting` has no filter of its own -- it returns what the file
    // holds.
    //
    // PyPSA does not refuse it, and what it does instead is the argument for refusing:
    //
    // {{{
    // objective = nan -> ok/optimal, objective 11500   (a wrong number, reported as optimal)
    // objective = inf -> ok/unknown, objective 0.0
    // years     = nan -> ok/optimal                    (inert only where nothing reads it)
    // }}}
    //
    // Measured on `tx-cost-periods`. A NaN that makes a solve report `Optimal` on a number
    // nobody computed is exactly the failure this port refuses on sight.
    //
    // The two columns this model reads, not every column the file carries: a network with an
    // extra weighting nothing here consumes is one PyPSA solves, and refusing it would be the
    // over-refusal `max_relative_growth` already taught.
    //
    // Over the periods the SNAPSHOTS carry, not the periods the file declares, for the same
    // reason `Expansion.costWeight` sums over those: PyPSA tolerates a declared period no
    // snapshot belongs to, and nothing here ever reads its weighting. Refusing a NaN in a row
    // nobody looks at is the same over-refusal in a different place. The refusal below
    // guarantees this set is a subset of the declared periods, so nothing reachable is missed.
    //
    // The message is per column because the two columns do different jobs and saying
    // otherwise is the conflation this module's docstring exists to prevent: `objective`
    // multiplies every cost and divides every nodal price, while `years` is read only by the
    // primary-energy sum and the operational limit. A NaN `years` leaves the objective alone.
    Seq("objective", "years").foreach { kind =>
      if network.investmentPeriodWeightings.contains(kind) then
        network.snapshotPeriods.distinct.foreach { period =>
          val weighting = network.periodWeighting(kind, period)
          if !weighting.isFinite then
            val consequence = kind match
              case "objective" =>
                "It multiplies every cost in that period and divides every nodal price there, " +
                  "so a non-finite one makes the objective and the duals NaN"
              case _ =>
                "It is what a primary-energy limit and an operational limit sum against in " +
                  "that period, so a non-finite one makes those rows NaN"
            refuse(
              s"investment period '$period' has a $kind weighting of $weighting. " +
                consequence + " -- and PyPSA reports such a solve as optimal rather than " +
                "refusing it."
            )
        }
    }

    // A snapshot in no declared period has no weighting and no activity window,
    // so every asset would read as present and the costs would be unscaled.
    network.snapshots.indices.foreach { t =>
      val period = network.snapshotPeriods(t)
      if !network.investmentPeriods.contains(period) then
        refuse(
          s"snapshot $t is in period '$period', which investment_periods.csv does not declare; " +
            "its costs would carry no period weighting and every asset would read as built"
        )
    }

    // Capacity expansion across periods used to be refused here, on the stated
    // grounds that "PyPSA gives each build year its own asset and the choice of
    // *when* to build interacts with the activity window and the discounting".
    // The second half is right and the first is not: PyPSA keeps '''one''' capacity
    // variable per asset and changes only its objective coefficient, to
    // `periodized_cost` times the sum of the `objective` weightings of the periods
    // the asset is active in. A build year is an input on the static frame, not a
    // decision -- which is why this turned out to be a weighting and three masks
    // rather than a new formulation. See `Expansion.costWeight`.
    //
    // What `Expansion` refuses is unchanged and is the part that really is a
    // different model: `overnight_cost`, which PyPSA annuitises over `lifetime` at
    // `discount_rate`, and a modular capacity, which is an integer. The growth
    // limits below are refused too, and they are the one expansion feature that is
    // specific to having periods at all.

    // `max_growth` and `max_relative_growth` are built now, in [[GrowthLimit]], and the
    // refusal that used to sit here is gone. What it got right is worth keeping: the gate is
    // `max_growth` alone, because `max_relative_growth` defaults to '''0.0''' -- finite, so
    // testing the two independently read "no relative limit" as a limit of nothing and
    // refused every multi-period network carrying an ordinary `carriers.csv`. PyPSA selects
    // `carrier_i = max_growth[max_growth != inf]` and reads the relative column only for the
    // carriers that filter picked, clipped at zero.
    //
    // The refusal also said growth limits "only bind on an extendable network, which is
    // refused just above" -- and that was the ledger entry that became a gap the moment
    // expansion across periods was built.

    // A global constraint scoped to one period is built now, in `Lopf.build`'s
    // global-constraint block, and the refusal that used to sit here is gone. The
    // reasoning it gave for refusing was sound and is worth keeping: left unread,
    // a cap meant for 2040 alone would be applied to the whole horizon, which is
    // '''tighter''' than the network states and makes the answer dearer, or -- read
    // the other way, as a cap per period rather than in total -- looser. Neither
    // direction has a defensible sign.
    //
    // What replaces it is three separate behaviours, each of which is PyPSA's and
    // none of which is the same as the others:
    //
    //   - the snapshot-summing types, `primary_energy` and `operational_limit`,
    //     sum over that period's snapshots alone
    //   - the capacity types filter their assets by activity in that period, and
    //     their '''unscoped''' cases disagree with each other about what to do on a
    //     multi-period network -- see the comments at each
    //   - a scope naming a period the horizon does not cover makes '''no row at
    //     all''', which is not the same as a row over an empty left-hand side
    //
    // The scope is resolved numerically rather than by string, because
    // `investment_period` is a float column: a CSV round-trip writes `2040.0`
    // where `investment_periods.csv` writes `2040`.
    //
    // A scope on a network whose snapshots carry no periods is still refused, and
    // that refusal lives beside the others in `Lopf.build` because it is about the
    // constraint rather than about the periods. PyPSA raises `UnboundLocalError`
    // there, having bound its period index only under `multi_investment_periods`.

    // Two row families are built once over the whole horizon rather than once
    // per period, so an asset whose activity window is not the whole horizon
    // changes rows this model does not rebuild. Pinning the asset's columns to
    // zero is enough for a *bound*; it is not enough for a row whose shape
    // depends on which assets exist.
    //
    //   - Kirchhoff's voltage law. `Cycles.basis` is computed on the
    //     whole-horizon topology and the same rows are emitted at every
    //     snapshot. PyPSA rebuilds the cycle matrix per period --
    //     `define_kirchhoff_voltage_constraints` loops `sns.unique("period")`
    //     and calls `n.cycle_matrix(investment_period=period)`, "reflecting the
    //     changing network topology over time". A line built in 2040 has its
    //     flow correctly pinned to zero in 2030, but its 2040 cycle row is still
    //     emitted there, collapsing to `x_A*f_A + x_B*f_B = 0` over what is
    //     really a radial path: zero flow forced along it, or 2030 infeasible.
    //   - Ramp limits. PyPSA masks every ramp row by `c.da.active`. Here a row
    //     spanning a period boundary ties the first snapshot after an asset is
    //     built to the zero it was pinned to before, so a unit may not start at
    //     more than one period's worth of ramp.
    //
    // The storage and store energy balance is the third such family and is *not*
    // refused: its rows are emitted over each asset's active snapshots, which is
    // PyPSA's `mask=active` exactly. That was worth doing rather than refusing
    // because the alternative -- pinned columns and a row everywhere -- reads as
    // masking and is not: at the first snapshot after a unit retires the row
    // collapses to `eff_stand · soc(t-1) = 0`, forcing it empty at its last
    // active snapshot. See `Lopf.build`.
    //
    // Refused rather than approximated, until the basis is rebuilt per period.
    // The staged line build is the canonical multi-investment case, so this is
    // not a corner: `investment-periods` is single-bus only because it is the
    // one fixture, not because branches are unusual.
    val horizon = network.snapshots.indices

    // Membership rather than role: a passive branch in no cycle contributes no
    // Kirchhoff row, so pinning its flow to zero is the whole of its masking and
    // there is nothing to refuse. Computed lazily -- a network with no
    // partly-built asset never asks, and the basis is the expensive part of the
    // build.
    lazy val cycled: Set[(String, String)] =
      Cycles.basis(network).flatMap(_.terms.map((component, id, _) => (component, id))).toSet

    network.tables.values.foreach { table =>
      table.ids.foreach { id =>
        val absent = network.investmentPeriods.filterNot(activeIn(table, id, _))
        if absent.nonEmpty then
          val window = s"is not active in investment period(s) ${absent.mkString(", ")}"
          if Role.of(table.spec) == Role.PassiveBranch && cycled.contains((table.spec.name, id)) then
            refuse(
              s"${table.spec.name} '$id' $window; the cycle basis is built once over the whole " +
                "horizon, so its cycle rows would still be imposed in the periods it does not exist"
            )
          else if Ramps.limited(table, id, horizon) then
            refuse(
              s"${table.spec.name} '$id' is ramp-limited and $window; the ramp rows are not masked " +
                "by the activity window, so the period it is built in would be entered at a ramp " +
                "from zero rather than freely"
            )
      }
    }

    // Per-period storage cycling is built -- see [[Cycling]] -- and the refusals in
    // `Storage.reject` and `Stores.reject` that this paragraph used to point at are gone.
    // What is still worth recording is why the copy that lived here was wrong before it was
    // removed: it covered two of the four flags, which reads as if the other two were
    // handled. PyPSA treats an asset as per-period when *either* the cyclic or the initial
    // flag is set (`CP | IP` in `define_storage_unit_constraints`), so a list naming only the
    // cyclic half is a shorter list of the same gap and not a narrower gap. `Cycling` carries
    // all four and the precedence between them.

  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
