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
  * `up_time_before` is '''not''' read, and it was until the pin moved to PyPSA 1.4.0.
  * 1.3.0 set `initially_up = up_time_before > 0` and forced `p_init` to 0 where that was
  * false; 1.4.0's `_status_history` ends `history.where(committable, 1.0)`, so a
  * non-committable unit is always on whatever the attribute says, and `_dispatch_before`
  * then returns `p_init` unchanged. Every unit this module builds is non-committable, so
  * the attribute has no effect here. `p_init` has no effect on anything except these rows.
  *
  * ==Committable units are refused, not built==
  *
  * PyPSA's ramp rows for a committable unit carry `ramp_limit_start_up` and
  * `ramp_limit_shut_down` against the binary status, which is a mixed-integer
  * formulation. [[Commitment.reject]] refuses every committable entity ahead of
  * [[Lopf]]'s build, so the rows [[constrain]] emits are never reached for one
  * and this module needs no refusal of its own. It carried one until the plain
  * committable case was refused generally, at which point a second check
  * describing one fragment of the same gap was only a second place to keep in
  * step.
  *
  * '''Not''' that committable units never reach this module. [[limited]] is
  * called on them, by [[UnitCommitment]]'s own refusal, which sweeps every
  * generator of a network whose whole point is that some are committable — and
  * the `ramp_limit_start_up`/`ramp_limit_shut_down` reads in [[limitsAt]] exist
  * for exactly that case, since PyPSA gives a unit carrying only a start-up
  * limit a full-rating up limit. Read as "committable never gets here" they look
  * like dead code, and deleting them would break that refusal quietly.
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

  /** Which half of the ramp band a row constrains.
    *
    * Named rather than a `Boolean`, because the two rows differ in sense as well as in
    * direction -- the up row is a `lessThan` and the down row a `greaterThan` -- and a caller
    * reading a dual back has to know which it got. `true` would carry the direction and lose
    * the sense.
    */
  enum Side:
    case Up, Down

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
      /** Called with the row index about to be used, so a caller can read its dual back.
        *
        * Not optional and not defaulted. A default of "record nothing" is how a new emission
        * site ends up unrecorded with no diagnostic, which is the omission this whole file is
        * careful about elsewhere -- and there are four sites below, two of them only reachable
        * at the first snapshot.
        */
      record: (String, Int, Side, Int) => Unit,
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

      // `p_init` as given, and `up_time_before` is not read at all.
      //
      // PyPSA 1.3.0 read it: `initially_up = up_time_before > 0` and
      // `p_init = p_init.where(initially_up, 0)`, so a unit declared down before the horizon
      // got `p_init = 0` and a row holding it near zero at the first snapshot. 1.4.0 replaced
      // that with `_status_history`, which ends `history.where(committable, 1.0)` -- a
      // NON-committable unit is always on, whatever `up_time_before` says -- and
      // `_dispatch_before`, which is `p_init.where(status > 0, 0.0)`. With the status 1, that
      // is `p_init` unchanged.
      //
      // Every unit this module builds is non-committable, because `Commitment.reject` refuses
      // the others ahead of it. So `up_time_before` has no effect here at all, and the prior
      // status that used to scale the up row is 1.
      //
      // That reads as a bug fix rather than a change of model: `up_time_before` and
      // `down_time_before` are commitment attributes, and 1.3.0 applied them to units with no
      // commitment status. `ramp-limits` is the fixture that measures it -- its `cold`
      // generator sets `up_time_before = 0` and is not committable, and the answer moves from
      // 50,240 to 48,640 with `cold` starting at 120 MW instead of 0.
      val pInit = optional(table, "p_init", id)

      snapshots.foreach { t =>
        val limit = limitsAt(table, id, t, start, shut)
        if limit.binds then
          val here = column(id, t)

          if t == snapshots.head then
            if !pInit.isNaN then
              // No prior-status factor on the up row. It was `s_init`, which 1.3.0 set
              // from `up_time_before` and 1.4.0 sets to 1 for a non-committable unit --
              // and 1.4.0 dropped the extendable half of it outright, writing
              // `rhs.add(limit_up * p_nom_ext_var)` where it had carried an `sp_ext`
              // factor. With the status 1 the two spellings agree, and this is the
              // shorter one.
              if limit.hasUp then
                record(id, t, Side.Up, builder.rowCount)
                builder.lessThan(
                  Seq((here, 1.0)) ++ cap.map(c => (c, -limit.up)),
                  pInit + limit.up * pNom,
                )
              if limit.hasDown then
                record(id, t, Side.Down, builder.rowCount)
                builder.greaterThan(
                  Seq((here, 1.0)) ++ cap.map(c => (c, limit.down)),
                  pInit - limit.down * pNom,
                )
          else
            val previous = column(id, t - 1)
            if limit.hasUp then
              record(id, t, Side.Up, builder.rowCount)
              builder.lessThan(
                Seq((here, 1.0), (previous, -1.0)) ++ cap.map(c => (c, -limit.up)),
                limit.up * pNom,
              )
            if limit.hasDown then
              record(id, t, Side.Down, builder.rowCount)
              builder.greaterThan(
                Seq((here, 1.0), (previous, -1.0)) ++ cap.map(c => (c, limit.down)),
                -limit.down * pNom,
              )
      }
    }
