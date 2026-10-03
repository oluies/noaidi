package org.noaidi.lopf

import org.noaidi.network.*

/** A cap on how much energy a carrier may produce over the horizon.
  *
  * PyPSA's `define_operational_limit`, and the fourth global-constraint `type` this model
  * builds. It was refused, and the refusal named `operational_limit` as the example of why
  * `type` cannot be assumed: assuming one would take a cap on a carrier's '''energy''' and
  * build it as an emissions-weighted sum over every emitting generator, which is a different
  * constraint wearing the same right-hand side, returning `Optimal`. That is still why the
  * two are separate functions; this is the other one.
  *
  * ==The left-hand side==
  *
  * {{{
  * Σ_g Σ_t weight(t) · p(g,t)                  over generators whose carrier matches
  *   − Σ_s soc(s, last) + Σ_s soc_initial(s)   over non-cyclic storage units whose carrier matches
  *   − Σ_e e(e, last)   + Σ_e e_initial(e)     over non-cyclic stores whose carrier matches
  * }}}
  *
  * Three things in that are easy to get wrong, each in a direction that returns `Optimal`:
  *
  *   - '''It is `generators`, not `objective`.''' `define_operational_limit` weights with
  *     `window.snapshot_weightings("generators")` — the column `primary_energy` uses, because
  *     both are quantities of energy rather than costs. Every golden holds the three snapshot
  *     weightings equal, so no comparison here can see the difference; the column is read
  *     rather than assumed for the reason the emissions sum reads it.
  *   - '''The storage term is a net depletion, not a sum of discharges.''' A non-cyclic unit
  *     contributes how much lower it ends than it started — one variable at one snapshot plus
  *     a constant, not a sum over the horizon. Reading it as `Σ_t p_dispatch` would count the
  *     same water again every time the unit refills.
  *   - '''A cyclic unit contributes nothing and is left out.''' It returns to its starting
  *     level, so its net production is zero by construction, and PyPSA filters
  *     `not cyclic_state_of_charge` and `not e_cyclic`. Including it would add a term over a
  *     variable the cyclic row has already pinned.
  *
  * The constant on the left — the initial levels — is returned separately rather than folded
  * in, because [[org.noaidi.prima.LpBuilder]] takes terms and a bound and there is nowhere
  * for a left-hand constant to go. The caller subtracts it from the right-hand side, which is
  * the same row.
  *
  * ==`last`, which is not `snapshots.last` on a multi-period network==
  *
  * PyPSA takes the final level as `soc.ffill("snapshot").isel(snapshot=-1)`, and the forward
  * fill is load-bearing: a unit absent from the final period has no variable there, so
  * without the fill the term would be missing rather than the last level the unit held. Here
  * the column exists at every snapshot and is pinned to `[0, 0]` outside the activity window,
  * so reading `snapshots.last` literally would charge the depletion as though the unit had
  * emptied itself on retirement. The last '''active''' snapshot is where PyPSA's fill lands.
  *
  * ==What is refused==
  *
  * PyPSA raises `NotImplementedError` for a non-cyclic unit whose state of charge runs
  * continuously across periods whose `years` weighting is anything but 1 — "the operational
  * constraint will be inconsistent" — and names the three ways out. That raise is reproduced
  * rather than worked around: the alternative is a row whose own author says it means
  * nothing. Two ten-year periods trip it, because upstream's test is `ne(1)` and not "the
  * periods differ from each other".
  *
  * The other refusal is the one per-period cycling flag that changes this term's '''shape'''.
  * `define_operational_limit` splits non-cyclic assets in two: `sus_continuous` takes one
  * final level over the whole horizon, which is what this builds, while `sus_per_period`
  * takes the final level of '''every''' period and sums them against a per-period weighting. An
  * asset restarting from its initial level each period depletes once per period, so one term
  * is the wrong number of terms rather than the wrong coefficient. It is checked '''before'''
  * the weighting refusal, because upstream's weighting test reads `sus_continuous` and such
  * an asset is never in it.
  */
object OperationalLimit:

  /** The cap's left-hand side: its terms, and the constant that sits on the left with them.
    *
    * `refuse` is [[Lopf]]'s, so a refusal from here reads like every other refusal of the
    * same build rather than like a different exception.
    */
  def terms(
      network: Network,
      snapshots: IndexedSeq[Int],
      columns: scala.collection.Map[(String, String, Int), Int],
      id: String,
      carrier: String,
      refuse: String => Nothing,
  ): (Seq[(Int, Double)], Double) =

    // As `primary_energy` weights its sum: the `generators` snapshot column, times the
    // period's `years` rather than its `objective`, because this is a quantity of energy and
    // not a cost to discount. And times nothing at all on a flat index.
    def weightAt(t: Int): Double =
      network.weighting("generators", t) *
        network.periodOf(t).map(network.periodWeighting("years", _)).getOrElse(1.0)

    def declaresCarrier(table: ComponentTable): Boolean =
      table.spec.attribute("carrier").isDefined || table.static.contains("carrier")

    val generation =
      network.table("Generator").toIndexedSeq.flatMap { table =>
        if !declaresCarrier(table) then IndexedSeq.empty
        else
          table.ids.filter(g => table.string("carrier", g) == carrier).flatMap { g =>
            snapshots.map(t => columns((table.spec.name, g, t)) -> weightAt(t))
          }
      }

    // The condition PyPSA states and raises on, checked once rather than per unit: it is
    // about the network and not about one asset.
    //
    // Named for what it means rather than for what it reads like. `ne(1)` is upstream's
    // test, so each period's `years` is compared against 1.0 and not against the other
    // periods': a horizon of two ten-year periods trips this just as readily as a horizon of
    // a ten and a five. Measured, because the name invites the other reading.
    lazy val unequalPeriodWeights: Boolean =
      network.isMultiPeriod &&
        network.snapshotPeriods.distinct.exists(p => network.periodWeighting("years", p) != 1.0)

    /** The last snapshot the asset exists at, which is where PyPSA's forward fill lands. */
    def lastActive(table: ComponentTable, unit: String): Option[Int] =
      snapshots.filter(t => Periods.activeAt(network, table, unit, t)).lastOption

    val depletion =
      IndexedSeq(
        ("StorageUnit", Storage.SoC, "state_of_charge_initial", "state_of_charge_initial_per_period"),
        ("Store", Stores.Energy, "e_initial", "e_initial_per_period"),
      ).flatMap { (component, variable, initial, perPeriodInitial) =>
        network.table(component).toIndexedSeq.flatMap { table =>
          // Matched exhaustively rather than defaulting, for the reason the emissions sum
          // gives: the two predicates read different columns, so a third component added to
          // the list above would silently get Store semantics and come back not-cyclic for
          // every entity.
          val cyclic: String => Boolean = component match
            case "StorageUnit" => Storage.isCyclic(table, _)
            case "Store"       => Stores.isCyclic(table, _)
            case other =>
              refuse(
                s"no cyclicity predicate for component '$other' in the operational_limit " +
                  "depletion scan; adding one to that list has to choose a predicate."
              )
          if !declaresCarrier(table) then IndexedSeq.empty
          else
            table.ids
              .filter(unit => table.string("carrier", unit) == carrier && !cyclic(unit))
              .flatMap { unit =>
                // The one cycling flag that changes this term's SHAPE rather than its
                // value. PyPSA splits non-cyclic assets into two branches here:
                // `sus_continuous`, which takes one final level over the whole horizon --
                // what this builds -- and `sus_per_period`, which takes the final level of
                // EVERY period and sums them against a per-period weighting. An asset that
                // restarts from its initial level each period depletes once per period, so
                // one term is the wrong number of terms rather than the wrong coefficient.
                //
                // Refused rather than approximated. The per-period cycling flags are built
                // now -- see `Cycling` -- so this is reachable, which it was not while they
                // were refused outright.
                if Cycling.flag(table, perPeriodInitial, unit) && network.isMultiPeriod then
                  refuse(
                    s"global constraint '$id' caps carrier '$carrier', and $component " +
                      s"'$unit' sets $perPeriodInitial. PyPSA then measures its depletion " +
                      "once per investment period and weights each by that period's years, " +
                      "which is a different number of terms from the single final level " +
                      "built here -- not a different coefficient on the same one."
                  )
                // And PyPSA's own NotImplementedError, which applies to the continuous
                // branch alone. The order matters: upstream tests
                // `if not sus_continuous.empty and period_weighting.ne(1).any()`, so a unit
                // that restarts each period never reaches it -- it is in the other branch.
                // Checked after the refusal above for that reason, and the first version
                // here had them the other way round, which named the wrong cause for an
                // asset that set both.
                //
                // `ne(1)`, not "the periods differ from each other": a horizon of two
                // ten-year periods trips it just as readily as a horizon of a ten and a
                // five, which is why the check compares each weighting against 1.0 rather
                // than against its neighbour. Measured -- years [10, 10] raises and years
                // [1, 1] solves.
                if unequalPeriodWeights then
                  refuse(
                    s"global constraint '$id' caps carrier '$carrier', and $component " +
                      s"'$unit' is non-cyclic while an investment period carries a `years` " +
                      "weighting other than 1. PyPSA raises NotImplementedError on exactly " +
                      "this -- a state of charge running continuously across periods of " +
                      "unequal length has no consistent net depletion -- and names the ways " +
                      "out: make the unit cyclic, set the per-period initial level, or give " +
                      "every period a years weighting of 1."
                  )
                val level = table.float(initial, unit)
                if !level.isFinite then
                  refuse(
                    s"global constraint '$id' caps carrier '$carrier', and $component " +
                      s"'$unit' has $initial = $level. It is the constant on the left-hand " +
                      "side of the cap, so a non-finite one makes the whole row NaN."
                  )
                lastActive(table, unit) match
                  // Active at no snapshot. PyPSA's forward fill over an all-masked row
                  // leaves NaN; here the column exists and is pinned to zero everywhere, so
                  // the depletion is exactly the initial level. Carried as a constant
                  // rather than dropped, since dropping it would loosen the cap by that
                  // much.
                  case None    => IndexedSeq((None, level))
                  case Some(t) =>
                    IndexedSeq((Some(columns((variable, unit, t)) -> -1.0), level))
              }
        }
      }

    (generation ++ depletion.flatMap(_._1), depletion.map(_._2).sum)
