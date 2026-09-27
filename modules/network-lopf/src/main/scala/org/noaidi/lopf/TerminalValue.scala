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
  * on their order — reversing the profile changes no objective and no level, which
  * `TerminalValueSuite` asserts rather than assumes.
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
    /** Whether this prices nothing, in which case [[constrain]] emits nothing. */
    def isOff: Boolean = lambdaPerUnit.isEmpty || lambdaPerUnit.values.forall(_ <= 0.0)

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
    val rising = profile.zip(profile.tail).zipWithIndex.collectFirst {
      case ((a, b), k) if b - a > 1e-9 => (k, a, b)
    }
    rising.foreach { (k, a, b) =>
      throw new Lopf.UnsupportedNetwork(
        s"TerminalValue's profile for $whose rises from $a to $b between segments $k " +
          s"and ${k + 1}, read empty-end first. V(SoC) is then not concave, and the LP " +
          "fills the segments in the wrong order and returns a number that looks like " +
          "an answer -- so this is refused rather than solved. A profile must be " +
          "non-increasing.",
      )
    }
    profile

  /** One reservoir's segment columns, and the level column they have to sum to. */
  final case class Planned(unit: String, segments: Seq[Int], stateOfCharge: Int)

  /** Nothing to emit. */
  val nothing: Seq[Planned] = Seq.empty

  /** Allocate the segment columns, with their objective coefficients.
    *
    * Split from [[emit]] because [[Lopf]] sizes its builder from the column count:
    * a column declared after the builder exists is outside its range, and the row
    * referring to it fails with an index error rather than a wrong answer. So this
    * runs while columns are still being declared and [[emit]] runs once the builder
    * is there -- variables first, then rows, which is the order the rest of the
    * builder already works in.
    *
    * Every refusal lives here too, so a bad configuration fails before anything has
    * been allocated for it.
    *
    * `declare` is [[Lopf]]'s own allocator. The cost goes on at declaration, which
    * is what keeps this from having to modify an existing coefficient --
    * [[org.noaidi.prima.LpBuilder.objectiveCoefficient]] sets rather than adds, and
    * the state-of-charge column already carries `marginal_cost_storage`.
    */
  def plan(
      network: Network,
      snapshots: Range,
      columns: Map[(String, String, Int), Int],
      declare: (String, String, Int, Double, Double, Double) => Int,
      config: Config,
  ): Seq[Planned] =
    if config.isOff || snapshots.isEmpty then return nothing

    val table   = network.tables.get("StorageUnit")
    val present = table.toIndexedSeq.flatMap(_.ids)

    // Inert configuration is refused, for the reason `HydroOps.inertZones` gives at
    // greater length: a λ against a reservoir that is not there leaves the horizon
    // unpriced while the run reports a number as though it had been priced.
    val priced   = config.lambdaPerUnit.filter(_._2 > 0.0).keys.toSeq.sorted
    val unmatched = priced.filterNot(present.contains)
    if unmatched.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has no StorageUnit named " +
          unmatched.map(u => s"'$u'").mkString(", ") +
          s", so the terminal value configured for it prices nothing. Its storage " +
          s"units are ${if present.isEmpty then "none" else present.mkString(", ")}.",
      )

    // Profiles are checked before anything is emitted, so a bad curve fails the build
    // rather than half-building one.
    val profiles = priced.map(id => id -> checkProfile(
      config.profileByUnit.getOrElse(id, config.profile), s"'$id'",
    )).toMap
    val widths = profiles.values.map(_.length).toSet
    if widths.sizeIs > 1 then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' was given terminal profiles of differing lengths " +
          s"(${widths.toSeq.sorted.mkString(", ")}). The segment count is one axis of " +
          "the model, so every reservoir has to share it; the multipliers need not.",
      )

    val storage = table.get
    val last    = snapshots.last

    priced.flatMap { id =>
      // The last snapshot has to be one the reservoir exists at. Outside its window
      // every column is pinned to [0, 0], so the defining equality would force all
      // segments to zero and the reward would be nil -- correct, but silently so.
      val socColumn =
        if Periods.activeAt(network, storage, id, last) then columns.get((Storage.SoC, id, last))
        else None
      socColumn.map { soc =>
        val pNom     = storage.float("p_nom", id)
        val maxHours = storage.float("max_hours", id)
        val capacity = pNom * maxHours
        if !(capacity > 0.0) then
          throw new Lopf.UnsupportedNetwork(
            s"reservoir '$id' has p_nom * max_hours = $capacity, so its capacity " +
              "cannot be cut into segments. A terminal value on a reservoir that " +
              "holds nothing prices nothing.",
          )
        if storage.spec.attribute("p_nom_extendable").isDefined &&
          storage.bool("p_nom_extendable", id)
        then
          throw new Lopf.UnsupportedNetwork(
            s"reservoir '$id' is extendable, so its state of charge is unbounded " +
              "above and the segment widths have nothing to divide. A terminal " +
              "value over a capacity the solver is still choosing is not defined " +
              "here.",
          )

        val profile = profiles(id)
        val lambda  = config.lambdaPerUnit(id)
        val width   = capacity / profile.length

        // One column per segment, its objective coefficient set at declaration:
        // negative, because holding water is a reward in a cost-minimising problem.
        val segments = profile.zipWithIndex.map { (multiplier, k) =>
          declare(Segment, segmentOf(id, k), last, 0.0, width, -lambda * multiplier)
        }
        Planned(id, segments, soc)
      }
    }

  /** Tie each reservoir's segments to its level: `Σ_k s_k − SoC(T) = 0`.
    *
    * The segments have nowhere else to be, so this is what makes the reward apply to
    * water that is actually there. Without it they would sit at their upper bounds
    * and collect the whole curve for nothing.
    */
  def emit(planned: Seq[Planned], builder: LpBuilder): Unit =
    planned.foreach { p =>
      builder.equalityConstraint(p.segments.map(_ -> 1.0) :+ (p.stateOfCharge -> -1.0), 0.0)
    }
