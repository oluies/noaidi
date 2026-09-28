package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** A rising bid curve for reservoir hydro, in place of one flat marginal cost.
  *
  * A port of NordPSA's `hydro_bid_ladder`, and the mirror of [[TerminalValue]]: there a
  * concave '''value''' with decreasing multipliers, here a convex '''cost''' with
  * increasing ones. Both are the same piecewise trick and neither needs an integer.
  *
  * ==What it is for, which is not price statistics==
  *
  * The model collapses hundreds of reservoirs into one per zone. A real fleet has spread
  * water values — different reservoir sizes, heads, local conditions — and so a rising
  * aggregate bid curve. A single `marginal_cost` '''is''' that aggregation error: the
  * whole zonal fleet bids at one price, supply is perfectly elastic exactly there, and
  * whenever residual load lands inside the block the price is that bid regardless of what
  * wind and solar did.
  *
  * NordPSA measured the consequence: the zone price sits on hydro's bid (±2) in 65% of
  * hours in SE-N, 79% in NO-N, 61% in NO-S, and the fleet sits at its ceiling 17–38% of
  * hours and at its floor 23–52%. A flat bid gives bang-bang.
  *
  * ==The shape, and why `width` is not the span==
  *
  * Dispatch splits into `K` equal tiers of `p_nom / K`, with symmetric offsets about
  * today's bid:
  *
  * {{{
  * offset(k) = width · ((k + ½) / K − ½)     k = 0 … K−1
  * }}}
  *
  * The offsets are taken at the tiers' '''midpoints''', not their edges, so the realised
  * span is `width · (K − 1) / K` and not `width`. At `K = 3, width = 36` the tiers bid
  * −12 / 0 / +12 — a span of 24. The reading is that `width` describes the spread of the
  * '''underlying''' distribution of water values across the fleet while the tiers sample
  * its bin midpoints; the span approaches `width` as `K` grows.
  *
  * A consequence worth stating because it surprises: `K` and `width` are '''not
  * independent'''. Raising `K` widens the realised ladder at unchanged `width` — `K = 5,
  * width = 36` spans 28.8. Change one at a time.
  *
  * ==Deviation form, so `marginal_cost` is untouched==
  *
  * The mean offset over the tiers is exactly zero, so the ladder moves the '''spread'''
  * and not the '''level''' — it does not disturb a λ calibration or reservoir operation.
  * And because `Σ_k d(k) = p_dispatch` while the dispatch column already carries
  * `marginal_cost`, adding only `Σ_k offset(k) · d(k)` makes the total
  * `Σ_k (marginal_cost + offset(k)) · d(k)`: the ladder exactly, with nothing
  * double-counted. That also makes it mode-independent — the base bid is whatever the
  * dispatch column holds, a capacity-expansion λ or a VOM plus a state-of-charge dual.
  *
  * Because `offset(k)` increases in `k`, a cost-minimising LP fills the cheapest tier
  * first unaided. No ordering rows, no binaries.
  *
  * ==What it does not fix, and what it costs==
  *
  * It does not make λ state-dependent: a dry December and a wet one still bid the same.
  *
  * And it is mean-preserving in the '''margin''', not in the total. At full output the
  * cost is unchanged, but at partial output only the cheap tiers are in, so the average
  * cost falls — NordPSA measured 1200/3600/2400 EUR lower at 100/400/700 MW on a
  * `K = 3, width = 36, p_nom = 900` fleet. The '''price''' is unaffected, because
  * measured mean output is 43–48% of `p_nom` in all five zones, which is the middle tier,
  * the one whose offset is zero — and the margin is what sets the price. But the
  * objective value drops, so raw objectives must not be compared across the ladder, and
  * in expansion cheaper inframarginal hydro could in principle displace other build.
  */
object BidLadder:

  /** The component name the tier columns are keyed under. */
  val Tier = "StorageUnit-bid_tier"

  /** `tiers` and `width`, both off at zero.
    *
    * `tiers` is `K`, the number of bids, and must be at least 2 — `K = 1` is not a
    * ladder, it is today's flat bid. `width` describes the spread of the underlying
    * water-value distribution; see the note above on why the realised span is narrower
    * and why the two knobs are not independent.
    */
  final case class Config(tiers: Int = 0, width: Double = 0.0):
    /** Whether this asks for nothing, in which case [[constrain]] emits nothing. */
    def isOff: Boolean = tiers == 0 && width == 0.0

  /** No bid ladder, which is what a plain PyPSA network means. */
  val off: Config = Config()

  /** The entity name one tier column is keyed under. */
  private def tierOf(id: String, k: Int): String = s"$id#$k"

  /** The bid offsets, cheapest first.
    *
    * Public because the offsets are the whole content of the formulation and a caller
    * checking a ladder wants them without rederiving the midpoint convention — which is
    * the part of this that is easy to get wrong.
    */
  def offsets(config: Config): IndexedSeq[Double] =
    (0 until config.tiers).map(k => config.width * ((k + 0.5) / config.tiers - 0.5))

  /** Emit the tier columns, their objective offsets and their defining rows.
    *
    * `declare` is [[Lopf]]'s allocator, as for [[TerminalValue]]: the offset goes on the
    * column at declaration, since
    * [[org.noaidi.prima.LpBuilder.objectiveCoefficient]] sets rather than adds and the
    * dispatch column beside it already carries `marginal_cost`.
    */
  def constrain(
      network: Network,
      snapshots: Range,
      columns: scala.collection.Map[(String, String, Int), Int],
      declare: (String, String, Int, Double, Double, Double) => Int,
      builder: LpBuilder,
      config: Config,
  ): Unit =
    if config.isOff then return

    // Every refusal before any column is allocated.
    //
    // `!(width > 0.0)` and not `width <= 0.0`, because NaN is neither -- the hole that
    // reached `TerminalValue` through exactly this shape and had to be closed there after
    // it shipped.
    if config.tiers < 2 then
      throw new Lopf.UnsupportedNetwork(
        s"BidLadder was given ${config.tiers} tier(s). A ladder needs at least two: " +
          "K = 1 is not a ladder but the flat bid this exists to replace, and K = 0 with " +
          "a width set is a half-written configuration rather than an off switch.",
      )
    if !(config.width > 0.0) then
      throw new Lopf.UnsupportedNetwork(
        s"BidLadder was given a width of ${config.width}. It has to be a finite number " +
          "above zero; at or below zero the tiers all bid the same and the ladder is the " +
          "flat bid again, and NaN would become the objective coefficient of every tier.",
      )

    val storage = network.tables.get("StorageUnit") match
      case Some(found) => found
      case None        =>
        throw new Lopf.UnsupportedNetwork(
          s"network '${network.name}' has no StorageUnit table, so a bid ladder " +
            "configured for it reshapes nothing.",
        )

    // The same filter `HydroOps` applies. A ladder on a battery would be a rising bid
    // curve over an aggregation error that is not there.
    val units = storage.ids.filter { id =>
      storage.spec.attribute("carrier").isDefined &&
        storage.string("carrier", id) == HydroOps.Carrier &&
        storage.float("p_nom", id) > 0.0
    }
    if units.isEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has no reservoir to build a bid ladder on -- no " +
          s"StorageUnit with carrier '${HydroOps.Carrier}' and a positive p_nom. The " +
          "ladder would reshape nothing while the run reported a number as though it had.",
      )

    val ladder = offsets(config)

    units.foreach { id =>
      val cap = storage.float("p_nom", id) / config.tiers

      snapshots.foreach { t =>
        // Snapshots the unit exists at, and skipped rather than refused where it does
        // not.
        //
        // This is the opposite choice from `TerminalValue`, and deliberately: that values
        // one level at one snapshot, so a reservoir absent from it has nothing to price
        // and a silent skip would leave the horizon unpriced. A ladder spans every
        // snapshot, and a multi-period reservoir legitimately does not exist in all of
        // them -- refusing would make the ladder unusable there rather than correct.
        if Periods.activeAt(network, storage, id, t) then
          columns.get((Storage.Dispatch, id, t)).foreach { dispatch =>
            // Weighted as `Lopf` weights `marginal_cost` on the column these tiers sum
            // to. NordPSA uses `snapshot_weightings.objective`, which is this on a flat
            // index; `Periods.objectiveWeight` additionally carries the period discount,
            // which is right because the offset is a cost at that snapshot and every
            // other cost there is discounted.
            val weight = Periods.objectiveWeight(network, t)

            val tiers = ladder.zipWithIndex.map { (offset, k) =>
              declare(Tier, tierOf(id, k), t, 0.0, cap, offset * weight)
            }

            // Σ_k d(k) − p_dispatch = 0. Without it the tiers are free of the dispatch
            // they are supposed to decompose: the cheap ones would sit at their bounds
            // collecting a negative offset for output nobody produced.
            builder.equalityConstraint(tiers.map(_ -> 1.0) :+ (dispatch -> -1.0), 0.0)
          }
      }
    }
