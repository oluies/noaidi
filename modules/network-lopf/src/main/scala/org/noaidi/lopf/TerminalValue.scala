package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** A concave terminal value on the water left in a reservoir at the horizon's end.
  *
  * A port of NordPSA's `hydro_terminal_value`, and the first thing in this build to
  * add LP '''columns''' rather than only rows.
  *
  * ==What it is for==
  *
  * A rolling-horizon window that prices nothing at its end empties the reservoir into
  * the last few snapshots, because water is worthless after the horizon. That is not
  * a modelling nicety, it is the whole reason rolling horizon needs a terminal term:
  * without one the last window of every roll is wrong in the same direction.
  *
  * ==Why concave, and why that costs no binaries==
  *
  * The capacity is cut into `K` equal segments with '''non-increasing''' marginal
  * value `λ_k = λ · profile(k)`, read empty-end first:
  *
  * {{{
  * V(SoC) = Σ_k λ_k · s_k     0 <= s_k <= cap / K     Σ_k s_k = SoC(T)
  * }}}
  *
  * Because `λ_k` falls, a cost-minimising LP fills the valuable segments first on its
  * own. No ordering constraints and no integers — the standard concave piecewise trick.
  *
  * ==The order of the profile does not reach the LP==
  *
  * Worth stating plainly, because NordPSA's comment says the opposite and this port
  * carried that across before testing it. The segments are '''interchangeable''': same
  * width, same bounds, differing only in their coefficient, and constrained only
  * through their sum. So the optimum depends on the '''multiset''' of multipliers and not
  * on their order — reversing the profile changes no objective and no level.
  *
  * That has no behavioural test, and cannot have one: the reverse of a non-increasing
  * profile is either the same profile or a rising one, and rising is refused. What the
  * suite pins instead is the '''coefficient''' — segment `k` carries `−λ · profile(k)` —
  * which is the part the documentation actually promises.
  *
  * A rising profile is still refused, and the reason is about meaning rather than
  * arithmetic: `profile(k)` is documented as the value of the k-th fill band, read
  * empty-end first, and a rising one says something its author did not intend. NordPSA
  * refuses it too, so refusing keeps the two sides agreeing on which configurations are
  * legal. What it does not do is protect the LP from filling segments "in the wrong
  * order" — it cannot, because there is no order to get wrong.
  *
  * NordPSA's own note on why concave rather than linear is worth carrying: a linear
  * `−λ · SoC(T)` has a constant derivative, so the water's marginal value goes
  * bang-bang — hold everything until the ceiling, then nothing. It measured
  * reservoirs at 100% in 13–29% of hours and zone prices collapsing to VOM for whole
  * weeks.
  *
  * ==One path, not two==
  *
  * NordPSA special-cases `K == 1` to avoid the extra variables, putting
  * `−λ · SoC(T)` straight onto the objective. This does not, and the reason is local
  * rather than stylistic: [[org.noaidi.prima.LpBuilder.objectiveCoefficient]]
  * '''sets''' a column's coefficient rather than adding to it, and the state-of-charge
  * column already carries `marginal_cost_storage`. Reaching for it would silently
  * drop that cost. One segment for `K == 1` costs one column per reservoir and has no
  * branch in which the two formulations can disagree.
  */
object TerminalValue:

  /** The component name the segment columns are keyed under. */
  val Segment = "StorageUnit-terminal_segment"

  /** NordPSA's default curve, empty-end first.
    *
    * Its shape is an assumption rather than a calibration, and the normalisation is
    * the part to know: the marginal value is `λ` itself in the 60–80% band, around
    * the `hydro_soc_initial` anchor. Below that the water's value rises with
    * scarcity; in the top band it collapses because spill becomes likely.
    */
  val DefaultProfile: IndexedSeq[Double] = IndexedSeq(2.0, 1.5, 1.2, 1.0, 0.2)

  /** Base λ per reservoir, and the curve its capacity is valued along.
    *
    * `profileByUnit` overrides `profile` for one reservoir, which is how a zone with
    * an export route differs from one that must spill. Every reservoir must end up
    * with the same '''number''' of segments; the values may differ.
    *
    * λ has to be on the same scale as the margin the optimiser compares it against —
    * it holds water when `λ > busPrice(t) − marginalCost(t)`. A λ quoted on a gross
    * price while `marginal_cost` already carries a water-value proxy double-counts.
    */
  final case class Config(
      lambdaPerUnit: Map[String, Double] = Map.empty,
      profile: IndexedSeq[Double] = DefaultProfile,
      profileByUnit: Map[String, IndexedSeq[Double]] = Map.empty,
  ):
    /** Whether this asks for nothing at all, in which case [[plan]] emits nothing.
      *
      * Emptiness only. A configured λ that cannot price anything -- zero, negative or
      * NaN -- is refused by [[plan]] rather than quietly treated as "off", because
      * there is no global switch here for a per-unit entry to be an override of: every
      * key is something somebody wrote on purpose.
      *
      * Testing `forall(_ <= 0.0)` here is what let a NaN λ through: NaN is not
      * `<= 0.0`, so this said "on", and `> 0.0` in [[plan]] said "skip", and the
      * horizon went unpriced with no diagnostic. That is the hole commit 7d15d7c closed
      * in `HydroOps.inertZones`, reopened here one commit later.
      */
    def isOff: Boolean = lambdaPerUnit.isEmpty

  /** No terminal value, which is what a plain PyPSA network means. */
  val off: Config = Config()

  /** The entity name one segment column is keyed under. */
  private def segmentOf(id: String, k: Int): String = s"$id#$k"

  /** Refuse a profile that cannot produce a concave value.
    *
    * The tolerance matches NordPSA's `1e-9`: a curve that is flat to within rounding
    * is still concave, and rejecting it would refuse `[1.0, 1.0]`, which is a
    * perfectly good two-segment linear value.
    */
  private def checkProfile(profile: IndexedSeq[Double], whose: String): IndexedSeq[Double] =
    if profile.isEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"TerminalValue was given an empty profile for $whose; a reservoir's capacity " +
          "cannot be cut into no segments.",
      )
    // Non-finite entries first, because every comparison below is false for NaN: a
    // profile of `[1.0, NaN, 2.0]` genuinely rises and the concavity test cannot see
    // it, and the multiplier then becomes a NaN objective coefficient that surfaces
    // hundreds of lines away as "objective coefficient 337 is not finite" -- an
    // anonymous column, in a module whose every other refusal names the reservoir.
    profile.zipWithIndex.find((v, _) => !v.isFinite).foreach { (v, k) =>
      throw new Lopf.UnsupportedNetwork(
        s"TerminalValue's profile for $whose has $v at segment $k. A multiplier has to " +
          "be a finite number; this one becomes the objective coefficient of a column " +
          "and fails far from here with nothing to connect it back.",
      )
    }
    val rising = profile.zip(profile.tail).zipWithIndex.collectFirst {
      case ((a, b), k) if b - a > 1e-9 => (k, a, b)
    }
    rising.foreach { (k, a, b) =>
      throw new Lopf.UnsupportedNetwork(
        s"TerminalValue's profile for $whose rises from $a to $b between segments $k " +
          s"and ${k + 1}, read empty-end first. `profile(k)` is the value of the k-th " +
          "fill band, so a rising one says the water is worth more when the reservoir " +
          "is fuller, which is not what a scarcity value means. NordPSA refuses it too, " +
          "so both sides agree on which configurations are legal. It is refused for " +
          "that reason and not to protect the LP: the segments are interchangeable, so " +
          "there is no fill order for it to get wrong.",
      )
    }
    profile

  /** Emit the segment columns, their objective coefficients and their defining rows.
    *
    * One pass. It was two for a while, because `LpBuilder` fixed its column count at
    * construction and a column declared afterwards was out of range -- so allocation
    * had to happen before the builder and the rows after it, with the ordering rule
    * between them unwritten. That split cost two defects of its own (an out-of-range
    * index, then an equality emitted after the inequalities, which silently breaks the
    * row-index identity `Sclopf` depends on) before `LpBuilder.addVariable` made it
    * unnecessary.
    *
    * Every refusal runs before a single column is allocated, so a bad configuration
    * fails without leaving orphan columns behind.
    *
    * The cost goes on at declaration rather than through
    * [[org.noaidi.prima.LpBuilder.objectiveCoefficient]], which sets rather than adds:
    * the state-of-charge column already carries `marginal_cost_storage`, and a family
    * that reached for that setter would drop it.
    */
  def constrain(
      network: Network,
      snapshots: Range,
      // `collection.Map`, so the caller's mutable builder map is read in place. As
      // `Map` it forced a full immutable copy of every column in the horizon on every
      // build -- including the callers that have no terminal value -- to answer one
      // lookup per reservoir.
      columns: scala.collection.Map[(String, String, Int), Int],
      declare: (String, String, Int, Double, Double, Double) => Int,
      builder: LpBuilder,
      config: Config,
  ): Unit =
    if config.isOff || snapshots.isEmpty then return

    val storage = network.tables.get("StorageUnit") match
      case Some(found) => found
      // Guarded rather than `.get`: reachable with a configured lambda and no
      // storage_units.csv, where it gave a bare `None.get` naming neither the network
      // nor the reservoir.
      case None =>
        throw new Lopf.UnsupportedNetwork(
          s"network '${network.name}' has no StorageUnit table at all, so the terminal " +
            s"value configured for ${config.lambdaPerUnit.keys.toSeq.sorted.mkString(", ")} " +
            "prices nothing.",
        )
    val present = storage.ids

    // Every refusal below runs before a single column is allocated, which is what the
    // scaladoc above promises. It previously promised it while two of them sat inside
    // the allocation loop, so a second reservoir's bad capacity threw after the first
    // reservoir's columns had already been appended.
    val configured = config.lambdaPerUnit.keys.toSeq.sorted

    // `!(v > 0.0)` and not `v <= 0.0`: the same predicate emission uses, so nothing can
    // fall between them. NaN is neither, which is exactly how it got through before.
    val inert = configured.filterNot(id => config.lambdaPerUnit(id) > 0.0)
    if inert.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' was given terminal values that cannot price " +
          "anything: " + inert.map(id => s"'$id' at ${config.lambdaPerUnit(id)}").mkString(", ") +
          ". A lambda has to be a finite number above zero; below or at zero it rewards " +
          "nothing, and NaN silently disables the whole term while the run reports a " +
          "number as though the horizon had been priced.",
      )

    // Reservoir hydro only, which `HydroOps` filters for and this did not -- while its
    // own refusals call every match a "reservoir". A battery StorageUnit given a lambda
    // was accepted, got five segment columns with negative coefficients, and reduced the
    // objective by up to lambda times its capacity. `HydroOps` refuses the exact analogue
    // with "matches no reservoir", so the two cited each other while disagreeing.
    val hydroUnits = present.filter(id =>
      storage.spec.attribute("carrier").isDefined && storage.string("carrier", id) == HydroOps.Carrier,
    )
    val unmatched = configured.filterNot(hydroUnits.contains)
    if unmatched.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has no StorageUnit named " +
          unmatched.map(u => s"'$u'").mkString(", ") +
          s", or it is not carrier '${HydroOps.Carrier}', so the terminal value " +
          "configured for it prices nothing. Its reservoirs are " +
          s"${if hydroUnits.isEmpty then "none" else hydroUnits.mkString(", ")}.",
      )

    // A profile override for a name nothing prices is the same shape of inert
    // configuration, and it hid more than the others: a rising or empty curve behind an
    // unmatched key never reached `checkProfile` at all, so a one-character slip valued
    // the reservoir along the global curve and returned a plausible number.
    val strayProfiles = config.profileByUnit.keys.toSeq.sorted.filterNot(configured.contains)
    if strayProfiles.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has a terminal profile for " +
          strayProfiles.map(u => s"'$u'").mkString(", ") +
          ", which has no lambda, so the profile is never read. Configured lambdas are " +
          s"${if configured.isEmpty then "none" else configured.mkString(", ")}.",
      )

    val priced = configured

    // Extendability before capacity, because PyPSA's standard expansion idiom is
    // `p_nom = 0, p_nom_extendable = True` and testing capacity first calls that a
    // reservoir that holds nothing -- which is both wrong and the more confusing of the
    // two messages. `Expansion.isExtendable` rather than a third hand-rolled spelling
    // of the same question.
    priced.filter(id => Expansion.isExtendable(storage, id)).foreach { id =>
      throw new Lopf.UnsupportedNetwork(
        s"reservoir '$id' is extendable, so its state of charge is unbounded above and " +
          "the segment widths have nothing to divide. A terminal value over a capacity " +
          "the solver is still choosing is not defined here.",
      )
    }

    // A cyclic reservoir's level is a free degree of freedom: adding a constant to every
    // state of charge satisfies the wrap and every balance row, so the LP lifts the
    // level to the cap, fills every segment and collects the whole curve without
    // changing one dispatch. Measured on `storage-cycle`: the objective drops 4248 --
    // exactly lambda times the capacity -- with every generator and every level
    // otherwise identical. A pure reward for water nobody paid for, reported Optimal.
    //
    // It is also the case the fixtures cannot see, since the terminal week is
    // deliberately non-cyclic, so there was nothing to catch it.
    priced.filter(id => Storage.isCyclic(storage, id)).foreach { id =>
      throw new Lopf.UnsupportedNetwork(
        s"reservoir '$id' has cyclic_state_of_charge, so its level is tied only to " +
          "itself and can be shifted freely. A terminal value on it is collected for " +
          "nothing rather than earned by holding water back, so it is refused: the term " +
          "belongs to a rolling-horizon window, which is not cyclic.",
      )
    }

    priced.foreach { id =>
      val capacity = storage.float("p_nom", id) * storage.float("max_hours", id)
      if !(capacity > 0.0) then
        throw new Lopf.UnsupportedNetwork(
          s"reservoir '$id' has p_nom * max_hours = $capacity, so its capacity cannot " +
            "be cut into segments. A terminal value on a reservoir that holds nothing " +
            "prices nothing.",
        )
    }

    val last = snapshots.last

    // The horizon's last snapshot has to be one every priced reservoir exists at.
    // Dropping such a reservoir silently -- which is what `socColumn.map` did -- leaves
    // the window unpriced and the drain the family exists to stop reported as an answer.
    priced.filterNot(id => Periods.activeAt(network, storage, id, last)).foreach { id =>
      throw new Lopf.UnsupportedNetwork(
        s"reservoir '$id' does not exist at snapshot $last, the end of the horizon, so " +
          "there is no level there to value. On a multi-period network that means its " +
          "build_year and lifetime end before the final period; a terminal value for it " +
          "would price nothing while the window drained.",
      )
    }

    // Profiles are validated last of the refusals and still before any allocation.
    val profiles = priced.map(id => id -> checkProfile(
      config.profileByUnit.getOrElse(id, config.profile), s"'$id'",
    )).toMap
    val widths = profiles.values.map(_.length).toSet
    if widths.sizeIs > 1 then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' was given terminal profiles of differing lengths " +
          s"(${widths.toSeq.sorted.mkString(", ")}). Every reservoir's segments are " +
          "declared on one axis here, so they have to share a count; the multipliers " +
          "need not.",
      )

    // Every refusal already behind us.
    priced.foreach { id =>
      val soc      = columns((Storage.SoC, id, last))
      val capacity = storage.float("p_nom", id) * storage.float("max_hours", id)
      val profile  = profiles(id)
      val lambda   = config.lambdaPerUnit(id)
      val width    = capacity / profile.length

      // One column per segment, its objective coefficient set at declaration:
      // negative, because holding water is a reward in a cost-minimising problem.
      val segments = profile.zipWithIndex.map { (multiplier, k) =>
        declare(Segment, segmentOf(id, k), last, 0.0, width, -lambda * multiplier)
      }

      // Sigma_k s_k - SoC(T) = 0. The segments have nowhere else to be, so this is what
      // makes the reward apply to water that is actually there; without it they would
      // sit at their upper bounds and collect the whole curve for nothing.
      builder.equalityConstraint(segments.map(_ -> 1.0) :+ (soc -> -1.0), 0.0)
    }

