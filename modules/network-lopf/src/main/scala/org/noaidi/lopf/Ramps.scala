package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** How far a unit's output may move between consecutive snapshots.
  *
  * Nothing read these attributes. `ramp_limit_up` and `ramp_limit_down` are
  * declared on Generator, Link and Process, default to NaN meaning "no limit",
  * and were named nowhere in this module outside [[UnitCommitment]]'s refusal —
  * so a plain LOPF over a network with them solved the '''unconstrained'''
  * problem and reported `Optimal`. That is the failure mode worth naming: a
  * dropped restriction is indistinguishable from an absent one to an inequality,
  * and the answer comes out cheaper than the network can actually run. On a
  * two-generator fixture where the cheap unit cannot follow the load, ignoring a
  * `ramp_limit_up` of 0.1 under-priced the schedule by 626 against PyPSA.
  *
  * ==Formulation==
  *
  * Per entity and snapshot, for the non-committable case this module builds:
  *
  * {{{
  * p(t) − p(t−1)  <=   ramp_limit_up   · p_nom
  * p(t) − p(t−1)  >=  −ramp_limit_down · p_nom
  * }}}
  *
  * and where the capacity is a decision, `p_nom` is that variable rather than
  * the number in the file, so the two limits move to the left-hand side. PyPSA
  * writes the fixed case as a `p_nom` of zero plus a capacity term, which is the
  * same rows; [[constrain]] follows that construction so the two stay comparable
  * term by term.
  *
  * ==The first snapshot, and `p_init`==
  *
  * There is no `p(−1)`, so the first snapshot's pair is a constraint against a
  * given: `p_init`, the output the unit was already at. Its default is NaN, and
  * PyPSA masks the row out entirely where it resolves to NaN rather than
  * treating it as zero — which is the difference between "the unit starts
  * wherever it likes" and "the unit starts shut down", and on a limit of 0.1
  * those differ by 90% of the rating at `t = 0`.
  *
  * `p_init` is read through `up_time_before`, whose default is 1: a unit that
  * was already up keeps its `p_init` (NaN by default, so no row), and one
  * declared down (`up_time_before = 0`) gets `p_init = 0` and a row that holds
  * it at zero for the first snapshot. That is why this reads two attributes to
  * decide one number, and why `p_init` is not a separate gap — it has no effect
  * on anything except these rows.
  *
  * ==Committable units are refused, not built==
  *
  * PyPSA's ramp rows for a committable unit carry `ramp_limit_start_up` and
  * `ramp_limit_shut_down` against the binary status, which is a mixed-integer
  * formulation. [[Commitment.reject]] refuses every committable entity, and it
  * runs before [[Lopf]] builds anything — so the rows below are never reached
  * for one, and this module needs no refusal of its own. It carried one until
  * the plain committable case was refused generally, at which point a second
  * check describing one fragment of the same gap was only a second place to keep
  * in step.
  */
object Ramps:

  /** The four attributes that make a unit ramp-limited. */
  private val attributes =
    Seq("ramp_limit_up", "ramp_limit_down", "ramp_limit_start_up", "ramp_limit_shut_down")

  /** Whether any ramp attribute appears in this table's '''data'''.
    *
    * Gated on the data, not the spec, and for the reason the `stand_by_cost`
    * guard in [[UnitCommitment]] gives at length: every attribute here is
    * declared on Generator and Link, so a spec test is unconditionally true for
    * them and the per-snapshot sweep below would run for every solve even where
    * no ramp column exists. Each `valueAt` on an absent column re-parses the
    * schema default, so a few thousand generators over a year is tens of
    * millions of parses to find nothing.
    *
    * The series is checked as well as the static column, so a `<component>-
    * ramp_limit_up.csv` with no static counterpart still passes -- which is the
    * whole defect this module was written to close, and would be reintroduced
    * here by a `static.contains` test alone.
    */
  private def present(table: ComponentTable): Boolean =
    attributes.exists(a => table.static.contains(a) || table.series.contains(a))

  /** A static float the spec may not declare at all, as NaN where it does not.
    *
    * `ComponentTable.float` throws for an attribute outside the schema, which is
    * right for a typo and wrong here: this module runs over whatever tables the
    * network has, and a component class without `p_init` should read as "no
    * value" rather than crash the build.
    */
  private def optional(table: ComponentTable, attribute: String, id: String): Double =
    if table.spec.attribute(attribute).isDefined then table.float(attribute, id) else Double.NaN

  /** The same, for an attribute that may vary by snapshot.
    *
    * [[present]] admits a table carrying only one of the two limits, so reading
    * the other through `valueAt` -- which resolves to `spec.require` for an
    * undeclared attribute -- would throw rather than emit the one row it can.
    */
  private def optionalAt(
      table: ComponentTable,
      attribute: String,
      id: String,
      snapshot: Int,
  ): Double =
    if table.spec.attribute(attribute).isDefined then table.valueAt(attribute, id, snapshot)
    else Double.NaN

  /** The effective limits at one snapshot.
    *
    * `up`/`down` are the multipliers, `hasUp`/`hasDown` whether a row is emitted
    * at all. The two are separate because PyPSA fills a missing limit with 1.0
    * '''and''' decides emission from whether the start-up and shut-down limits
    * are also missing: a unit carrying only `ramp_limit_start_up` still gets an
    * up row, at a full-rating limit. Collapsing the two into "NaN means no row"
    * is the obvious simplification and drops that row.
    */
  final case class Limits(up: Double, down: Double, hasUp: Boolean, hasDown: Boolean):
    def binds: Boolean = hasUp || hasDown

  /** The start-up and shut-down limits, which are static and so read once per
    * entity rather than once per entity per snapshot.
    */
  private def endpoints(table: ComponentTable, id: String): (Double, Double) =
    (optional(table, "ramp_limit_start_up", id), optional(table, "ramp_limit_shut_down", id))

  private def limitsAt(
      table: ComponentTable,
      id: String,
      snapshot: Int,
      start: Double,
      shut: Double,
  ): Limits =
    val up   = optionalAt(table, "ramp_limit_up", id, snapshot)
    val down = optionalAt(table, "ramp_limit_down", id, snapshot)
    Limits(
      up = if up.isNaN then 1.0 else up,
      down = if down.isNaN then 1.0 else down,
      hasUp = !(up.isNaN && start.isNaN),
      hasDown = !(down.isNaN && shut.isNaN),
    )

  def limitsAt(table: ComponentTable, id: String, snapshot: Int): Limits =
    val (start, shut) = endpoints(table, id)
    limitsAt(table, id, snapshot, start, shut)

  /** Whether this entity is ramp-limited at any snapshot.
    *
    * Read per snapshot, because both limits are `static or series`. A
    * `generators-ramp_limit_up.csv` with no static counterpart is invisible to a
    * `static.contains` check, which is how [[UnitCommitment]]'s refusal came to
    * miss it while its own comment asserted the attributes were static.
    */
  def limited(table: ComponentTable, id: String, snapshots: Range): Boolean =
    if !present(table) then false
    else
      val (start, shut) = endpoints(table, id)
      snapshots.exists(t => limitsAt(table, id, t, start, shut).binds)

  // There is no `reject` here any more. This object carried one, for a unit
  // both committable and ramp-limited -- PyPSA charges its start-up and
  // shut-down ramps against a binary status, which the rows below cannot
  // express. `Commitment.reject` refuses every committable entity ahead of
  // `Lopf`'s build, so that case can no longer arrive, and a second refusal
  // describing one fragment of it would only be a second place to keep in step.

  /** Emit the ramp rows for one table.
    *
    * `column` resolves this table's dispatch variable, `capacity` its capacity
    * variable where the entity is extendable and `None` where it is not. Both
    * are passed in rather than looked up here so that this stays independent of
    * how [[Lopf]] keys its variable map.
    *
    * Every row is one-sided. A range row would be the natural way to write a
    * band around `p(t) − p(t−1)`, and it is exactly what `Sclopf`'s row-by-row
    * copy cannot carry: one original row becoming two breaks the index identity
    * that copy checks for, which is why the capacity rows above are written the
    * same way.
    */
  def constrain(
      table: ComponentTable,
      snapshots: Range,
      column: (String, Int) => Int,
      capacity: String => Option[Int],
      builder: LpBuilder,
  ): Unit =
    if !present(table) then return

    table.ids.foreach { id =>
      val cap           = capacity(id)
      val (start, shut) = endpoints(table, id)

      // Zero where the capacity is a decision, so the fixed term drops out and
      // the whole limit rides on the capacity column. PyPSA's `where(~is_ext,
      // 0)`, and following it keeps the extendable and fixed rows the same
      // shape.
      val pNom = if cap.isDefined then 0.0 else table.float("p_nom", id)

      val upTime =
        if table.spec.attribute("up_time_before").isDefined then table.int("up_time_before", id)
        else 1
      val initiallyUp = upTime > 0

      // NaN unless the unit was declared already down, in which case it started
      // at zero. `!isNaN` below is what decides whether the first snapshot gets
      // a row at all.
      val pInit = if initiallyUp then optional(table, "p_init", id) else 0.0

      snapshots.foreach { t =>
        val limit = limitsAt(table, id, t, start, shut)
        if limit.binds then
          val here = column(id, t)

          if t == snapshots.head then
            if !pInit.isNaN then
              // The unit's committed state before the horizon. It scales the up
              // row only: a unit that starts down may not ramp up from nothing,
              // but it may certainly stay there, so the down row is against a
              // status of 1 either way.
              val was = if initiallyUp then 1.0 else 0.0
              if limit.hasUp then
                builder.lessThan(
                  Seq((here, 1.0)) ++ cap.map(c => (c, -limit.up * was)),
                  pInit + limit.up * pNom * was,
                )
              if limit.hasDown then
                builder.greaterThan(
                  Seq((here, 1.0)) ++ cap.map(c => (c, limit.down)),
                  pInit - limit.down * pNom,
                )
          else
            val previous = column(id, t - 1)
            if limit.hasUp then
              builder.lessThan(
                Seq((here, 1.0), (previous, -1.0)) ++ cap.map(c => (c, -limit.up)),
                limit.up * pNom,
              )
            if limit.hasDown then
              builder.greaterThan(
                Seq((here, 1.0), (previous, -1.0)) ++ cap.map(c => (c, limit.down)),
                -limit.down * pNom,
              )
      }
    }
