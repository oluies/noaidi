package org.noaidi.stats

import java.sql.{Connection, DriverManager}
import org.duckdb.DuckDBConnection
import org.noaidi.lopf.{Expansion, LopfResult, Periods}
import org.noaidi.network.{ComponentTable, Network, Role}

/** Statistics over a solved result, answered in SQL.
  *
  * PyPSA's `statistics` is nineteen metrics over seven grouping dimensions and three time
  * aggregations. That is group-by-and-aggregate with a cross product, which is the one shape
  * in this port where a query language earns its place: nineteen hand-written folds each
  * carrying its own grouping parameter is the version that rots, and one fact table with
  * nineteen `SELECT`s is the version a reviewer can check against upstream one query at a
  * time.
  *
  * ==A consumer of the solve, not the store underneath it==
  *
  * Nothing in `network-model`, `network-lopf` or `network-pf` knows this module exists. That
  * is deliberate and is the whole argument in `docs/network-stats-design.md`: `Lopf.build`
  * walks columns and emits sparse-matrix rows with no join, no group-by and nothing to push
  * down, and `networkModelJs` compiles the entire model source directory to JavaScript for a
  * browser demo that solves an LP in-page. A JNI dependency underneath either of those buys
  * nothing and costs the cross-build.
  *
  * ==Skeleton==
  *
  * Two metrics, chosen because they weight differently and so pin the schema's central
  * claim: [[supply]] uses `w_generators` and [[opex]] uses `w_objective` times
  * `p_objective`, which is `Periods.objectiveWeight`. A port that reached for one weighting
  * for both would pass one metric and fail the other, and `operational-limit` -- the only
  * golden whose three snapshot weightings differ -- is the fixture that separates them. The
  * remaining seventeen metrics are listed in the design note.
  */
final class Stats private (private val conn: Connection) extends AutoCloseable:

  /** One row per result row, columns by name. For anything the named metrics do not cover.
    *
    * Deliberately untyped: this is an escape hatch for exploration, and a typed row mapper
    * would be a second schema to keep in step with the first.
    */
  def query(sql: String): Vector[Map[String, Any]] =
    val st = conn.createStatement()
    try
      val rs   = st.executeQuery(sql)
      val meta = rs.getMetaData
      val cols = (1 to meta.getColumnCount).map(meta.getColumnLabel)
      val out  = Vector.newBuilder[Map[String, Any]]
      while rs.next() do
        out += cols.zipWithIndex.map((name, i) => name -> rs.getObject(i + 1)).toMap
      out.result()
    finally st.close()

  /** One `"Component|group" -> value` entry per group.
    *
    * Keyed by component '''and''' group, because PyPSA does not aggregate across components
    * by default (`aggregate_across_components=False`). A network with hydro generators and
    * hydro reservoirs gets two hydro rows, one per component, and a reader that summed them
    * would be comparing against a number upstream never produced.
    */
  private def grouped(sql: String): Map[String, Double] =
    query(sql).map { row =>
      val component = Option(row("component")).map(_.toString).getOrElse("")
      val group     = Option(row("grp")).map(_.toString).getOrElse("")
      val value = row("value") match
        case null                => 0.0
        case n: java.lang.Number => n.doubleValue
        case other =>
          throw new IllegalStateException(s"metric returned a non-numeric value: $other")
      s"$component|$group" -> value
    }.toMap

  /** Energy injected, in MWh, by whatever dimension `groupBy` names.
    *
    * `w_generators`, and that is '''not''' the obvious choice — it is PyPSA's. `supply`
    * delegates to `energy_balance`, which weights with `n.snapshot_weightings.generators`
    * (`expressions.py:2305`), while `stores` is the elapsed hours the storage balance uses
    * inside the LOPF. Both are "hours" in some sense and the statistics layer picked
    * `generators`.
    *
    * The first version of this used `w_stores`, and `docs/network-stats-design.md` argued for
    * it in so many words — "`stores` is the elapsed hours, so it is what turns MW into MWh;
    * `generators` is not". Backwards, and `operational-limit` is the fixture that says so: it
    * is the only golden whose three weightings differ (`generators` 3.0, the others 1.0), and
    * PyPSA reports gas supply as 120 against a dispatch of 40 MW at one snapshot. 40 x 3, not
    * 40 x 1. Asserted confidently, wrong, and caught only by running it against upstream.
    *
    * An absent carrier groups as `'-'`, which is PyPSA's rendering under `nice_names` and
    * not a choice made here: `energy-budget`'s generators have no carrier and upstream
    * reports `Generator|-`. Grouping them as the empty string gave a different key for every
    * such network and failed the comparison on the group set rather than on a number, which
    * is at least the right place to fail.
    *
    * '''Branches are not covered.''' PyPSA's `energy_balance` includes `Line`, `Link` and
    * `Transformer` at their ports, with a sign per end; this sums injections from
    * `Generator`, `StorageUnit` and `Store` only. `StatsSuite` asserts that the uncovered
    * component set is exactly the branch kinds, so adding them later is a change the suite
    * notices rather than a silent widening.
    */
  def supply(groupBy: String = "carrier"): Map[String, Double] =
    grouped(s"""
      |SELECT e.component                                            AS component,
      |       COALESCE(NULLIF(e.${dimension(groupBy)}, ''), '-')      AS grp,
      |       SUM(f.value * s.w_generators)                           AS value
      |FROM fact f
      |JOIN entity e USING (component, entity)
      |JOIN snapshot s USING (snapshot)
      |-- One quantity per component kind, not a set union: a StorageUnit contributes BOTH
      -- `p` and `discharge` to `fact`, and `quantity IN ('p','discharge')` counted it twice.
      -- Every storage figure came out at exactly 2x PyPSA -- 1,200 against 600 on
      -- `operational-limit`, 54,593 against 27,296 on `storage-cyclic-co2` -- which is the
      -- kind of factor that looks like a weighting bug and is not.
      |WHERE f.value > 0
      |  AND ( (f.quantity = 'p'         AND e.component IN ('Generator', 'Store'))
      |     OR (f.quantity = 'discharge' AND e.component = 'StorageUnit') )
      |GROUP BY e.component, grp
      |HAVING SUM(f.value * s.w_generators) > 1e-9
      |""".stripMargin)

  /** Operating cost by `groupBy`.
    *
    * `w_objective * p_objective` is `Periods.objectiveWeight` spelled out, and spelling it
    * out is the point: a cost carries the snapshot's own weighting '''and''' its period's
    * discount. PyPSA reads both — `snapshot_weightings.objective` and
    * `investment_period_weightings["objective"]`.
    *
    * ==Each cost type times its own variable==
    *
    * `opex` in `expressions.py:1646` sums over cost types, and each multiplies '''the
    * variable the lookup pairs it with''', not a single dispatch series:
    *
    * {{{
    * marginal_cost         x  p          (Generator, Link, Store)
    * marginal_cost         x  p_dispatch (StorageUnit)   <- not the net p
    * marginal_cost_storage x  soc / e
    * spill_cost            x  spill
    * }}}
    *
    * The StorageUnit row is the one that bites. Charging it on net `p` nets the charging
    * against the discharge and can go '''negative''': `storage-hvdc` came out at -6,668
    * against PyPSA's 25,328, and `storage-cyclic-co2` at -4,047 against 15,216. A sign flip
    * is a louder symptom than a factor, which is the only reason it was obvious.
    *
    * ==Zero groups are dropped, because PyPSA drops them==
    *
    * `drop_zero` defaults to true upstream, so a generator that never ran contributes no row
    * at all. Keeping it produced a group PyPSA does not have and failed `store-bank` and
    * `tech-capacity-limit` on the group '''set''' rather than on a number.
    *
    * ==What it refuses==
    *
    * A cost type this query does not model — `marginal_cost_quadratic`, and the three
    * commitment costs — makes the metric refuse rather than return a partial sum. A number
    * that is short by a term nobody named is the failure this whole repository is arranged
    * against, and `UnitCommitment` is refused by the LOPF anyway so the commitment three are
    * unreachable today.
    */
  def opex(groupBy: String = "carrier"): Map[String, Double] =
    grouped(s"""
      |WITH priced AS (
      |  SELECT f.component, f.entity, f.snapshot, f.value * c.value AS cost
      |  FROM fact f
      |  JOIN fact c
      |    ON  c.component = f.component AND c.entity = f.entity AND c.snapshot = f.snapshot
      |  WHERE (c.quantity = 'marginal_cost' AND (
      |           (f.quantity = 'p'         AND f.component IN ('Generator', 'Store'))
      |        OR (f.quantity = 'p0'        AND f.component = 'Link')
      |        OR (f.quantity = 'discharge' AND f.component = 'StorageUnit')))
      |     OR (c.quantity = 'marginal_cost_storage' AND (
      |           (f.quantity = 'soc' AND f.component = 'StorageUnit')
      |        OR (f.quantity = 'e'   AND f.component = 'Store')))
      |     OR (c.quantity = 'spill_cost' AND f.quantity = 'spill')
      |)
      |SELECT e.component                                       AS component,
      |       COALESCE(NULLIF(e.${dimension(groupBy)}, ''), '-') AS grp,
      |       SUM(p.cost * s.w_objective * s.p_objective)        AS value
      |FROM priced p
      |JOIN entity e ON e.component = p.component AND e.entity = p.entity
      |JOIN snapshot s ON s.snapshot = p.snapshot
      |GROUP BY e.component, grp
      |HAVING ABS(SUM(p.cost * s.w_objective * s.p_objective)) > 1e-9
      |""".stripMargin)

  /** The grouping dimensions this skeleton carries, validated rather than interpolated.
    *
    * `groupBy` reaches a query as a column name, so an unchecked value is SQL injection
    * through a parameter that looks like an enum. Rejected by name against the columns
    * `entity` actually has -- PyPSA's `country`, `location` and `unit` groupers are not
    * modelled here yet and say so rather than producing an unknown-column error from DuckDB.
    */
  private def dimension(groupBy: String): String =
    val known = Set("carrier", "bus", "component", "entity")
    if known.contains(groupBy) then groupBy
    else
      throw new IllegalArgumentException(
        s"no grouping dimension '$groupBy'; this module carries ${known.toSeq.sorted.mkString(", ")}. " +
          "PyPSA also groups by bus_carrier, country, location and unit, which are not loaded yet."
      )

  override def close(): Unit = conn.close()

object Stats:

  /** Load one solved result into an in-memory DuckDB.
    *
    * In-memory and per-result: closing the `Stats` drops the database. A file-backed variant
    * would be a different constructor, and nothing here needs one yet -- `scigrid-de` is the
    * size that matters and it is ~55k fact rows.
    */
  def of(result: LopfResult): Stats =
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      try StatsSchema.ddl.foreach(st.execute)
      finally st.close()
      load(conn.asInstanceOf[DuckDBConnection], result)
      new Stats(conn)
    catch
      case e: Throwable =>
        conn.close()
        throw e

  /** Everything the schema holds.
    *
    * Two mechanisms, split on a fact about the pinned driver rather than on taste.
    *
    * `fact` goes through `DuckDBAppender`, because that is where the rows are: `scigrid-de`
    * is 1,423 generators and 852 lines over 24 snapshots, so ~55k rows, and row-by-row JDBC
    * `INSERT` is the one shape of this slow enough to notice.
    *
    * The four dimension tables go through a batched `PreparedStatement`, because
    * `DuckDBAppender` in 1.3.1.0 has '''no `appendNull`''' — checked with `javap` against the
    * jar, not assumed — and `carrier`, `bus`, `marginal_cost` and the rest are legitimately
    * absent on components that do not declare them. Appending a sentinel instead would make
    * "no carrier" and "carrier named empty string" the same row, and would put a 0.0 where a
    * component has no `capital_cost` at all, which any `SUM` would then count. The dimensions
    * are hundreds of rows, so the appender buys nothing there anyway.
    */
  private def load(conn: DuckDBConnection, result: LopfResult): Unit =
    val n = result.network

    /** A batched insert, with NULL where a value is absent. */
    def insert(table: String, columns: Int)(rows: Seq[Seq[Any]]): Unit =
      if rows.nonEmpty then
        val placeholders = Seq.fill(columns)("?").mkString(", ")
        val ps = conn.prepareStatement(s"INSERT INTO $table VALUES ($placeholders)")
        try
          rows.foreach { row =>
            row.zipWithIndex.foreach { (value, i) =>
              value match
                case null       => ps.setObject(i + 1, null)
                case v: String  => ps.setString(i + 1, v)
                // NaN as NULL, deliberately. A float column here means "the network does not
                // say", which is what NaN means in `ComponentTable` -- and a NaN reaching a
                // `SUM` poisons the whole group rather than being skipped the way a NULL is.
                case v: Double  =>
                  if v.isNaN then ps.setObject(i + 1, null) else ps.setDouble(i + 1, v)
                case v: Int     => ps.setInt(i + 1, v)
                case v: Boolean => ps.setBoolean(i + 1, v)
                case other =>
                  throw new IllegalStateException(s"no binding for '$other' in $table")
            }
            ps.addBatch()
          }
          ps.executeBatch(): Unit
        finally ps.close()

    insert("snapshot", 8)(n.snapshots.indices.map { t =>
      val period = n.periodOf(t)
      Seq(
        t,
        n.snapshotLabel(t),
        period.orNull,
        n.weighting("objective", t),
        n.weighting("stores", t),
        n.weighting("generators", t),
        // Both default to 1.0 on a flat index, which is what lets every query multiply
        // unconditionally instead of branching on `isMultiPeriod`.
        period.map(n.periodWeighting("objective", _)).getOrElse(1.0),
        period.map(n.periodWeighting("years", _)).getOrElse(1.0),
      )
    })

    n.table("Bus").foreach { buses =>
      insert("bus", 3)(buses.ids.map { id =>
        Seq(
          id,
          if declares(buses, "carrier") then buses.string("carrier", id) else null,
          buses.float("v_nom", id),
        )
      })
    }

    val busCarrier: Map[String, String] =
      n.table("Bus")
        .filter(declares(_, "carrier"))
        .map(buses => buses.ids.map(id => id -> buses.string("carrier", id)).toMap)
        .getOrElse(Map.empty)

    /** A component's carrier, with PyPSA's fill-from-bus for the three kinds that get it.
      *
      * `calculate_dependent_values` rewrites an '''empty''' carrier from the bus's on `Line`
      * and `Link` (from `bus0`) and on `Store` (from `bus`) --
      * `network/power_flow.py:779`, `:831`, `:835` -- and `optimize` reaches it through
      * `consistency_check`. So a solved PyPSA network reports a store's carrier as its bus's
      * even though the CSV said nothing, and `store-bank` is the golden that says so: its
      * three stores are written with no carrier and upstream reports `Store|AC`.
      *
      * `Generator` and `StorageUnit` are '''not''' on that list and keep the empty carrier
      * that then renders as `'-'`, which is why this cannot be done for every component:
      * `energy-budget`'s generators have no carrier and upstream reports `Generator|-`, so
      * filling from the bus there would invent a group.
      */
    def carrierOf(table: ComponentTable, busAttribute: String, id: String): String =
      val own = if declares(table, "carrier") then table.string("carrier", id) else ""
      if own.nonEmpty then own
      else if fillsCarrierFromBus(table.spec.name) && declares(table, busAttribute) then
        busCarrier.get(table.string(busAttribute, id)).orNull
      else null

    // Only components with a solved series. A `Carrier` or a `LineType` has no rows in
    // `fact`, and putting them in `entity` would make every unqualified count wrong.
    val modelled = n.tables.values.filter(t => quantitiesOf(t.spec.name).nonEmpty).toSeq

    modelled.foreach { table =>
      val component = table.spec.name
      val branch = Role.of(table.spec) match
        case Role.PassiveBranch | Role.ControllableBranch => true
        case _                                            => false
      // `bus0` for a branch, which is PyPSA's own placement and the asymmetry
      // `TechCapacityLimit` reproduces: a branch counts at the end it leaves.
      val busAttr = if branch then "bus0" else "bus"
      insert("entity", 10)(table.ids.map { id =>
        Seq(
          component,
          id,
          carrierOf(table, busAttr, id),
          if declares(table, busAttr) then table.string(busAttr, id) else null,
          if branch && declares(table, "bus1") then table.string("bus1", id) else null,
          result.capacity(component, id),
          Expansion.nominalAttribute.get(component).map(table.float(_, id)).orNull,
          Expansion.isExtendable(table, id),
          if declares(table, "marginal_cost") then table.float("marginal_cost", id) else null,
          if declares(table, "capital_cost") then table.float("capital_cost", id) else null,
        )
      })

      insert("active", 3)(
        for
          id     <- table.ids
          period <- n.snapshotPeriods.distinct
          if Periods.activeIn(table, id, period)
        yield Seq(component, id, period)
      )
    }

    val facts = conn.createAppender("main", "fact")
    try
      modelled.foreach { table =>
        val component = table.spec.name
        table.ids.foreach { id =>
          quantitiesOf(component).foreach { quantity =>
            n.snapshots.indices.foreach { t =>
              facts.beginRow()
              facts.append(component)
              facts.append(id)
              facts.append(t)
              facts.append(quantity)
              facts.append(valueOf(result, table, quantity, id, t))
              facts.endRow()
            }
          }
        }
      }
    finally facts.close()

  /** The components whose empty carrier `calculate_dependent_values` fills from their bus.
    *
    * Exactly upstream's three, and no wider: see `carrierOf` in [[load]].
    */
  private val fillsCarrierFromBus = Set("Line", "Link", "Store")

  /** Which series a component contributes, by the name the queries use.
    *
    * `marginal_cost` is in here beside the solved series, which deserves a word: it is an
    * '''input''', not an output, and it is in `fact` rather than on `entity` because PyPSA
    * lets it vary per snapshot. `opex` joins it fact-to-fact for that reason. Any other
    * time-varying attribute a metric needs belongs here on the same grounds.
    *
    * Driven by the component name rather than by the schema's category, and that is a
    * skeleton shortcut worth flagging: `Lopf.build` selects its tables by [[Role]] so a
    * component added upstream lands correctly without being named, and this does not. A
    * `Process` -- already a second controllable branch -- would contribute no rows here and
    * no error either.
    */
  private def quantitiesOf(component: String): Seq[String] = component match
    case "Generator"            => Seq("p", "marginal_cost")
    case "Line" | "Transformer" => Seq("p0")
    case "Link"                 => Seq("p0", "marginal_cost")
    case "StorageUnit" =>
      Seq("p", "soc", "charge", "discharge", "spill",
          "marginal_cost", "marginal_cost_storage", "spill_cost")
    case "Store"                => Seq("e", "p", "marginal_cost", "marginal_cost_storage")
    case _                      => Seq.empty

  /** One value, for one quantity of one entity at one snapshot.
    *
    * `table` is needed because the input quantities read the network rather than the
    * solution, and `valueAt` is what resolves a static column against a time-varying series
    * the way every LOPF builder resolves it.
    */
  private def valueOf(
      result: LopfResult,
      table: ComponentTable,
      quantity: String,
      id: String,
      t: Int,
  ): Double =
    val component = table.spec.name
    quantity match
      case "p" | "p0"     => result.dispatch(component, id, t)
      case "soc"          => result.stateOfCharge(id, t)
      case "charge"       => result.charging(id, t)
      case "discharge"    => result.discharging(id, t)
      case "spill"        => result.spill(id, t)
      case "e"            => result.energy(id, t)
      // The input cost series. `valueAt` is what resolves a static column against a
      // time-varying one, the way every LOPF builder resolves it -- and `operational-limit`
      // is the fixture that needs it: its run-of-river bids 1/2/3/4 across four snapshots.
      case "marginal_cost" | "marginal_cost_storage" | "spill_cost" =>
        if declares(table, quantity) then table.valueAt(quantity, id, t) else 0.0
      case other =>
        throw new IllegalStateException(s"no accessor for quantity '$other' on $component")

  /** Whether a table carries an attribute at all, declared or present.
    *
    * The same test the LOPF builders use: a schema-declared attribute with no column still
    * has a default, and a column present without a declaration is still readable.
    */
  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
