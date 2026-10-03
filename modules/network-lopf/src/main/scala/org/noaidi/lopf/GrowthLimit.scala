package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** How much of a carrier a single investment period may add.
  *
  * PyPSA's `define_growth_limit`, and the one expansion feature that exists only because
  * there are periods: with a single period there is nothing for a growth rate to be measured
  * against. It opens with `if not n._multi_invest: return`, so on a flat index it is inert.
  *
  * One row per `(carrier, period)`:
  *
  * {{{
  * Σ_{a first active in p} nominal(a)
  *   − max_relative_growth(c) · Σ_{a first active in p−1} nominal(a)   ≤ max_growth(c)
  * }}}
  *
  * ==Which assets a period is charged for==
  *
  * An asset is charged to the period it is '''first active''' in, which is normally its
  * build year. There is one capacity variable per asset — see [[Expansion.costWeight]] — so
  * this is the only place the model has to decide which period a capacity belongs to, and
  * `build_year` is what decides it.
  *
  * PyPSA computes that as `active.cumsum() == 1` along the period axis, and that expression
  * is '''not''' "the first period this asset is active in". For an asset active in exactly
  * one period the cumulative count stays at 1 for every later period too, so the asset is
  * charged again to every period after its own. Measured on a three-period network whose
  * first unit has `lifetime = 10`:
  *
  * {{{
  * period 2030:  p_nom(w2030)                ≤ max_growth
  * period 2040:  p_nom(w2030) + p_nom(w2040) ≤ max_growth
  * period 2050:  p_nom(w2030) + p_nom(w2050) ≤ max_growth
  * }}}
  *
  * A unit retired before the second period is still counted as growth in the second and
  * third. That reads like an oversight upstream and it is reproduced rather than corrected,
  * for the reason every such decision here is: the alternative is a tighter or looser row
  * than the pinned PyPSA builds, and nothing in this port can say which.
  *
  * ==`max_relative_growth`, and why it is gated on the other column==
  *
  * `max_growth` defaults to infinity and `max_relative_growth` to '''0.0'''. So the gate is
  * `max_growth != inf` alone: PyPSA selects the carriers that filter picks and reads the
  * relative column only for them, clipped at zero. Testing the two independently refuses
  * every network carrying an ordinary `carriers.csv`, because "no relative limit" reads as a
  * limit of nothing — a mistake this port has already made once, in the refusal this
  * replaces.
  *
  * The relative term is subtracted, so a positive `max_relative_growth` '''loosens''' the
  * row by that fraction of the previous period's additions. The first period has no
  * predecessor and PyPSA's `shift` leaves its term empty rather than zero-valued, which is
  * the same row.
  */
object GrowthLimit:

  /** Emit one `<=` row per `(carrier, period)`, or nothing on a flat index.
    *
    * Inequalities only, so this is emitted among the other inequality families and its
    * position relative to them does not matter — but it must come after every equality, for
    * the reason the global-constraint block gives.
    */
  def constrain(
      network: Network,
      columns: scala.collection.Map[(String, String, Int), Int],
      builder: LpBuilder,
      refuse: String => Nothing,
  ): Unit =
    // `if not n._multi_invest: return`. A growth rate over one period has nothing to be a
    // rate of, and upstream builds no row at all rather than treating the single period as
    // its own predecessor.
    //
    // Redundant, and kept anyway: `periods` below is empty on a flat index, so the loop over
    // it emits nothing and removing this line changes no row. Stated rather than left for a
    // reader to work out, because a guard that looks load-bearing and is not is the kind of
    // line someone later moves. It is here because it is PyPSA's own first line.
    if !network.isMultiPeriod then return

    val carriers = network.table("Carrier") match
      case None       => return
      case Some(found) => found

    if !(carriers.spec.attribute("max_growth").isDefined ||
          carriers.static.contains("max_growth")) then return

    // The gate, and it is `max_growth` alone. See the note above on why testing
    // `max_relative_growth` beside it refuses every ordinary network.
    val limited = carriers.ids.filter(c => carriers.float("max_growth", c).isFinite)
    if limited.isEmpty then return

    val periods = network.snapshotPeriods.distinct

    /** Whether `id` is charged to `periods(k)`: PyPSA's `active.cumsum() == 1`. */
    def chargedTo(table: ComponentTable, id: String, k: Int): Boolean =
      (0 to k).count(i => Periods.activeIn(table, id, periods(i))) == 1

    /** Extendable assets of one carrier charged to one period, with their capacity columns. */
    def charged(carrier: String, k: Int): IndexedSeq[Int] =
      Expansion.nominalAttribute.keys.toIndexedSeq.sorted.flatMap { component =>
        network.table(component).toIndexedSeq.flatMap { table =>
          // PyPSA's `if "carrier" not in static: continue`.
          if !(table.spec.attribute("carrier").isDefined || table.static.contains("carrier"))
          then IndexedSeq.empty
          else
            Expansion
              .extendables(table)
              .filter(id => table.string("carrier", id) == carrier && chargedTo(table, id, k))
              .map(id => columns((Expansion.capacityKey(component), id, Expansion.NoSnapshot)))
        }
      }

    limited.foreach { carrier =>
      val limit = carriers.float("max_growth", carrier)
      // Defaulted to zero when the column is absent, and NOT clipped here, which needs
      // saying because PyPSA does clip it.
      //
      // Upstream's `.clip(min=0)` and the `relative > 0.0` test below coincide exactly. A
      // negative rate clipped to zero gives PyPSA a term whose coefficient is zero; the test
      // below emits no term at all. Same row. Clipping as well would be a second spelling of
      // one condition, and the one that survives a mutation is the one that is actually
      // doing the work -- `math.max` here was an equivalent mutant, which is how it was
      // found.
      //
      // Why either is needed: honoured rather than clipped, a rate of -0.5 would *subtract*
      // half the previous period's additions from this period's allowance, making the row
      // tighter than no relative allowance at all. That is not what "no limit" means.
      val relative =
        if carriers.spec.attribute("max_relative_growth").isDefined ||
          carriers.static.contains("max_relative_growth")
        then carriers.float("max_relative_growth", carrier)
        else 0.0
      // Positive infinity only, and the shape of this test is deliberate.
      //
      // `relative > 0.0` is false for NaN, so a NaN relative rate falls through to "no
      // relative allowance" rather than to this refusal. That is the conservative direction
      // -- it tightens nothing and loosens nothing, and it is what the attribute's own
      // default of 0.0 does -- and it is chosen over refusing because a blank cell in a
      // hand-written `carriers.csv` reads as NaN, and refusing that would reject an ordinary
      // network. This port has already made the mirror-image mistake once, in the refusal
      // `GrowthLimit` replaces: `max_relative_growth` defaults to 0.0, which is finite, so
      // testing it the way `max_growth` is tested called "no relative limit" a limit of
      // nothing and refused every multi-period network carrying a `carriers.csv`.
      //
      // An infinity is different because it would reach `LpBuilder` as a coefficient and
      // surface rows away as an anonymous failure. PyPSA clips at zero and does nothing
      // else, so upstream carries it into linopy.
      if relative > 0.0 && !relative.isFinite then
        refuse(
          s"Carrier '$carrier' sets max_relative_growth = " +
            s"${carriers.float("max_relative_growth", carrier)}, which would be a coefficient " +
            "in its growth row. It has to be a finite fraction."
        )

      // Emitted only where the carrier has something to limit, which is PyPSA's
      // `if limited_names.empty: continue` per component and `if not lhs_list: return`
      // overall. A row over no terms would be `0 <= max_growth`, vacuous for the
      // non-negative limit this attribute is, so the difference is a row count rather than
      // an answer -- but `Sclopf` copies rows one for one, so a row count is not free.
      val anywhere = periods.indices.exists(k => charged(carrier, k).nonEmpty)
      if anywhere then
        periods.indices.foreach { k =>
          val here = charged(carrier, k).map(_ -> 1.0)
          val before =
            if relative > 0.0 && k > 0 then charged(carrier, k - 1).map(_ -> -relative)
            else IndexedSeq.empty
          builder.lessThan(here ++ before, limit)
        }
    }
