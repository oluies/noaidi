package org.noaidi.stats

import java.sql.{Connection, DriverManager}
import org.duckdb.DuckDBConnection
import org.noaidi.lopf.{Expansion, LopfResult, Periods}
import org.noaidi.network.{ComponentSpec, ComponentTable, Network, Role}

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
  * ==Five metrics, seven groupings, every golden==
  *
  * [[supply]] and [[opex]] of the nineteen were the pair worth having first because they
  * weight DIFFERENTLY: `supply` uses `w_generators` and `opex` uses `w_objective` times
  * `p_objective`. A port that reached for one weighting for both would pass one metric and
  * fail the other, and `operational-limit` -- the only golden whose three snapshot weightings
  * differ -- is the fixture that separates them.
  *
  * [[capex]], [[installedCapex]] and [[expandedCapex]] are the first three that are not sums
  * over snapshots at all: a capacity is one number for the whole horizon, so their period
  * axis comes from `capital_share` rather than from the snapshot index. They are also where
  * the design note's own formula was wrong -- it named `Expansion.costWeight`, which is the
  * factor the OBJECTIVE charges capital with, where the statistic is per period with that
  * period's weighting. The two agree about the total and differ in every breakdown.
  *
  * Both answer all seven of PyPSA's grouping dimensions, and four of those are read off the
  * component's BUS rather than off the component, which is a different kind of mistake to
  * make: `bus_carrier`, `country`, `location` and `unit` all map the component's bus through
  * a column of `n.c.buses.static`. `statistics-dimensions` is the fixture, because
  * `location` and `unit` were carried by no golden at all and `country` only by one that is
  * skipped for degeneracy.
  *
  * Both group by investment period as well, so no golden is skipped for having periods --
  * eleven were. The period is the third level of the key, which is what upstream returns: a
  * multi-period frame has a column per period rather than a single series.
  *
  * The remaining fourteen metrics are listed in the design note, along with what is still
  * missing from these five: branches at their ports in `supply`, which PyPSA's
  * `energy_balance` includes with a sign per end.
  */
final class Stats private (
    private val conn: Connection,
    private val multiPeriod: Boolean,
    private val hasCountry: Boolean,
) extends AutoCloseable:

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

  /** One `"Component|group"` entry per group, or `"Component|group|period"` on periods.
    *
    * Keyed by component AND group, because PyPSA does not aggregate across components by
    * default (`aggregate_across_components=False`). A network with hydro generators and hydro
    * reservoirs gets two hydro rows, one per component, and a reader that summed them would
    * be comparing against a number upstream never produced.
    *
    * ==The period is a third part of the key, not a fourth table==
    *
    * On a multi-period network `statistics` returns a frame with a column per investment
    * period rather than a series, so every number is per period and every key carries the
    * period as its last level. `StatsSuite` used to detect that key shape and SKIP the
    * network -- eleven of them -- because these queries summed over the whole horizon and
    * would have compared one number against several.
    *
    * It is one `GROUP BY` term, added by [[periodColumns]] when the snapshot table has
    * periods at all. Worth saying why it is not a per-period variant of each metric: the
    * grouping is a property of the index, not of the metric, so nineteen metrics would
    * otherwise need thirty-eight queries, which is the duplication this module's whole
    * argument is against.
    */
  private def grouped(sql: String): Map[String, Double] =
    query(sql).map { row =>
      val component = Option(row("component")).map(_.toString).getOrElse("")
      val group     = Option(row("grp")).map(_.toString).getOrElse("")
      val period    = row.get("period").flatMap(Option(_)).map(_.toString)
      val value = row("value") match
        case null                => 0.0
        case n: java.lang.Number => n.doubleValue
        case other =>
          throw new IllegalStateException(s"metric returned a non-numeric value: $other")
      (component +: group +: period.toSeq).mkString("|") -> value
    }.toMap

  /** `", s.period AS period"` on a multi-period network, and nothing on a flat index.
    *
    * Two strings rather than a branch per metric, so a query reads as one statement with a
    * term that is sometimes empty. The alternative -- selecting `period` always and dropping
    * a NULL in Scala -- would make every flat-index group carry a NULL level that only the
    * key builder knows to ignore.
    */
  private val periodSelect: String = if multiPeriod then ", s.period AS period" else ""
  private val periodGroup: String  = if multiPeriod then ", s.period" else ""

  /** The same two fragments for a metric whose period comes from `capital_share`.
    *
    * A capital metric has no snapshot axis to read a period off, so it takes one from
    * `capital_share` instead -- the alias differs and nothing else does. Two more `val`s
    * rather than a parameter, because the alias is part of the query text and a metric that
    * passed the wrong one would group by a column that happens to exist on the other table.
    */
  private val capitalPeriodSelect: String = if multiPeriod then ", cs.period AS period" else ""
  private val capitalPeriodGroup: String  = if multiPeriod then ", cs.period" else ""

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
      |SELECT e.component                     AS component,
      |       d.${dimension(groupBy)}          AS grp$periodSelect,
      |       ROUND(SUM(f.value * s.w_generators), 5) AS value
      |FROM fact f
      |JOIN entity e USING (component, entity)
      |JOIN dimensions d USING (component, entity)
      |JOIN snapshot s USING (snapshot)
      |-- One quantity per component kind, not a set union: a StorageUnit contributes BOTH
      -- `p` and `discharge` to `fact`, and `quantity IN ('p','discharge')` counted it twice.
      -- Every storage figure came out at exactly 2x PyPSA -- 1,200 against 600 on
      -- `operational-limit`, 54,593 against 27,296 on `storage-cyclic-co2` -- which is the
      -- kind of factor that looks like a weighting bug and is not.
      |WHERE f.value > 0
      |  AND ( (f.quantity = 'p'         AND e.component IN ('Generator', 'Store'))
      |     OR (f.quantity = 'discharge' AND e.component = 'StorageUnit') )
      |GROUP BY e.component, grp$periodGroup
      |HAVING ROUND(SUM(f.value * s.w_generators), 5) <> 0
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
      |SELECT p.component                                AS component,
      |       d.${dimension(groupBy)}                     AS grp$periodSelect,
      |       ROUND(SUM(p.cost * s.w_objective * s.p_objective), 5) AS value
      |FROM priced p
      |JOIN dimensions d ON d.component = p.component AND d.entity = p.entity
      |JOIN snapshot s ON s.snapshot = p.snapshot
      |GROUP BY p.component, grp$periodGroup
      |HAVING ROUND(SUM(p.cost * s.w_objective * s.p_objective), 5) <> 0
      |""".stripMargin)

  /** Capital cost of the capacity the solve chose, by `groupBy`.
    *
    * `p_nom_opt * capital_cost`, summed, and on a multi-period network reported per period
    * with that period's `objective` weighting -- see `capital_share` in [[StatsSchema]] for
    * the per-period axis and for why it is not `Expansion.costWeight`.
    *
    * ==Two sums and a subtraction==
    *
    * [[capex]] charges the chosen capacity and [[installedCapex]] the capacity the network
    * came with; both are this one query against a different column. [[expandedCapex]] is
    * their difference and is NOT a third query -- see its own note for why that matters.
    *
    * ==`capital_cost_piecewise_opt` is always zero here==
    *
    * PyPSA adds it to the cost (`capex` reads
    * `static.get(cost_attribute + "_piecewise_opt", 0)`). A piecewise curve is refused by
    * `CsvReader` and `NetCdfReader`, so a network carrying one never reaches this module and
    * the term is structurally zero rather than dropped.
    */
  def capex(groupBy: String = "carrier"): Map[String, Double] =
    capital(groupBy, "e.nominal * e.capital_cost")

  /** Capital cost of the capacity the network came with, by `groupBy`.
    *
    * `p_nom * capital_cost` -- the static column rather than the solved one, which is the
    * only difference from [[capex]]. Empty on a network whose extendables all start from
    * zero: `tx-cost-periods`' two lines have no `s_nom` column at all, so this is 0 for both
    * and `drop_zero` removes them, which is what upstream records.
    */
  def installedCapex(groupBy: String = "carrier"): Map[String, Double] =
    capital(groupBy, "COALESCE(e.nominal_in, 0) * e.capital_cost")

  /** Capital cost of the capacity the solve ADDED, by `groupBy`, which may be negative.
    *
    * '''Not''' one query over `nominal - nominal_in`, and the difference is a group rather
    * than a number. Upstream is `capex.sub(installed_capex, fill_value=0)`, and both operands
    * have already been rounded and zero-dropped by the time the subtraction happens --
    * `_apply_option_kwargs` runs inside each metric and not on the difference. So
    * `expanded_capex` is the one metric here that '''reports exact zeros''': on
    * `ac-dc-co2`, `ac-dc-dispatch`, `storage-hvdc` and `storage-cyclic-co2` every
    * non-extendable group comes back as `0.0` rather than being dropped.
    *
    * A single `(nominal - nominal_in)` query with the usual `HAVING ... <> 0` drops them, and
    * it agreed with upstream on every assertion anyway: the suite's own floor filters a zero
    * off both sides, so the group sets matched and `getOrElse(key, 0.0)` made the missing
    * values compare equal. Fixed because the agreement was accidental, and because
    * `round(a) - round(b)` is not `round(a - b)` in the fifth decimal.
    *
    * The sign convention is `Expansion`'s: `ac-dc-meshed`'s gas comes out at -21,109,107.32
    * because the expansion retires gas.
    */
  def expandedCapex(groupBy: String = "carrier"): Map[String, Double] =
    val chosen    = capex(groupBy)
    val installed = installedCapex(groupBy)
    (chosen.keySet ++ installed.keySet)
      .map(key => key -> (chosen.getOrElse(key, 0.0) - installed.getOrElse(key, 0.0)))
      .toMap

  /** The shared body of the two capital metrics that are a single sum.
    *
    * `cost` is an expression over `entity`, interpolated -- which is safe only because the
    * three call sites are the only ones and each passes a literal. It is not a parameter in
    * the sense `groupBy` is, and it is deliberately `private`.
    *
    * No join to `fact` or `snapshot`: a capacity is one number for the whole horizon. That is
    * what makes these three the first metrics here whose period axis comes from
    * `capital_share` rather than from the snapshot index.
    */
  private def capital(groupBy: String, cost: String): Map[String, Double] =
    grouped(s"""
      |SELECT e.component                      AS component,
      |       d.${dimension(groupBy)}           AS grp$capitalPeriodSelect,
      |       ROUND(SUM(($cost) * cs.weight), 5) AS value
      |FROM entity e
      |JOIN dimensions d USING (component, entity)
      |JOIN capital_share cs USING (component, entity)
      |GROUP BY e.component, grp$capitalPeriodGroup
      |HAVING ROUND(SUM(($cost) * cs.weight), 5) <> 0
      |""".stripMargin)

  /** A grouping dimension, validated against the `dimensions` view rather than interpolated.
    *
    * `groupBy` reaches a query as a column name, so an unchecked value is SQL injection
    * through a parameter that looks like an enum.
    *
    * All seven of PyPSA's are here -- `carrier`, `bus_carrier`, `bus`, `country`, `location`,
    * `unit` and `name` -- plus `component`, which upstream has no grouper for because every
    * frame is already keyed by it.
    *
    * ==`country` is refused where the buses do not carry one==
    *
    * It is the one dimension that is not a declared PyPSA attribute. The grouper reads
    * `n.c.buses.static.country` unguarded, so on a network whose `buses.csv` has no such
    * column upstream raises `AttributeError: 'DataFrame' object has no attribute 'country'` --
    * which `generate_goldens.py` records as an error for 37 of the 39 networks. Grouping them
    * all under the empty string would be answering a question PyPSA declines, so this
    * declines it too, and says which column is missing.
    */
  private def dimension(groupBy: String): String =
    if !Stats.Dimensions.contains(groupBy) then
      throw new IllegalArgumentException(
        s"no grouping dimension '$groupBy'; PyPSA groups by " +
          s"${Stats.Dimensions.toSeq.sorted.mkString(", ")}"
      )
    else if groupBy == "country" && !hasCountry then
      throw new IllegalArgumentException(
        "this network's buses carry no `country` column, and PyPSA's own grouper raises " +
          "AttributeError on such a network rather than grouping it under one empty country"
      )
    else groupBy

  override def close(): Unit = conn.close()

object Stats:

  /** Every grouping dimension a metric accepts.
    *
    * PyPSA's seven, plus `component`. Upstream has no `component` grouper because every frame
    * it returns is keyed by component already -- see `grouped` -- so grouping by it alone is
    * this module's own convenience and is named here rather than left as a surprise.
    */
  val Dimensions: Set[String] =
    Set("carrier", "bus_carrier", "bus", "country", "location", "unit", "name", "component")

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
      // Both read off the network rather than out of SQL, because both are questions about
      // what the network HAS rather than about what it holds: a flat index has no period
      // column to be NULL, and a `buses.csv` without a `country` is a different thing from
      // one whose countries are all empty -- which is the distinction PyPSA's own grouper
      // makes by raising.
      val n = result.network
      new Stats(conn, n.isMultiPeriod, n.table("Bus").exists(_.static.contains("country")))
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
      insert("bus", 6)(buses.ids.map { id =>
        Seq(
          id,
          if declares(buses, "carrier") then buses.string("carrier", id) else null,
          buses.float("v_nom", id),
          // NULL where the column is absent, not the empty string, and the difference is
          // load-bearing for `country`: PyPSA's grouper raises on a network that has no such
          // column, so `Stats.dimension` has to be able to tell that from a network whose
          // countries happen to be blank.
          if declares(buses, "country") then buses.string("country", id) else null,
          if declares(buses, "location") then buses.string("location", id) else null,
          if declares(buses, "unit") then buses.string("unit", id) else null,
        )
      })
    }

    // `carriers.csv`, for `nice_name` alone. A network with no `Carrier` table leaves this
    // empty and the view's `LEFT JOIN` then falls back to the raw carrier, which is what
    // PyPSA's `fillna(carrier)` does -- so the absent case needs no branch here.
    n.table("Carrier").foreach { carriers =>
      insert("carrier", 2)(carriers.ids.map { id =>
        Seq(
          id,
          if declares(carriers, "nice_name") then carriers.string("nice_name", id) else null,
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
    val modelled = n.tables.values.filter(t => quantitiesOf(t.spec).nonEmpty).toSeq

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
          quantitiesOf(table.spec).foreach { quantity =>
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
    * INPUT, not an output, and it is in `fact` rather than on `entity` because PyPSA lets it
    * vary per snapshot. `opex` joins it fact-to-fact for that reason. Any other time-varying
    * attribute a metric needs belongs here on the same grounds.
    *
    * ==Branches by `Role`, one-ports by name, and nothing silently empty==
    *
    * The branch cases are selected the way `Lopf.build` selects its tables, so `Process` --
    * PyPSA's second controllable branch -- contributes `p0` and its cost without being named.
    * The first version of this matched on `"Line" | "Transformer"` and `"Link"`, which was
    * correct for every component that exists today and would have let a new branch contribute
    * no rows and no error either.
    *
    * The one-ports cannot be keyed that way and it is worth being precise about why: `Role`
    * does not distinguish them, because PyPSA's own category does not. `Generator`,
    * `StorageUnit`, `Store` and `Load` are all one-ports, and their variable sets are not
    * variations on a theme -- a generator has one series, a storage unit five, a store two,
    * and a load is data rather than a variable at all. There is nothing to generalise over,
    * so they stay named.
    *
    * What changed with them is the fall-through. It used to be `case _ => Seq.empty`, so a
    * one-port added upstream would contribute nothing to `fact`, appear in no metric, and
    * raise nothing -- the silent-omission failure this repository is arranged against. An
    * unknown one-port now refuses by name. `Load` is the one exception and is listed
    * explicitly, because "data rather than a variable" is a decision and not an absence.
    */
  private def quantitiesOf(spec: ComponentSpec): Seq[String] = Role.of(spec) match
    case Role.PassiveBranch      => Seq("p0")
    case Role.ControllableBranch => Seq("p0", "marginal_cost")
    case Role.Bus | Role.None    => Seq.empty
    case Role.Attached =>
      spec.name match
        case "Generator" => Seq("p", "marginal_cost")
        case "StorageUnit" =>
          Seq("p", "soc", "charge", "discharge", "spill",
              "marginal_cost", "marginal_cost_storage", "spill_cost")
        case "Store" => Seq("e", "p", "marginal_cost", "marginal_cost_storage")
        // Consumption, which enters the bus balance as a constant. It has no solved series
        // and so no row in `fact`; `withdrawal` will read it off the network when it exists.
        case "Load" => Seq.empty
        case other =>
          throw new IllegalStateException(
            s"'$other' is a one-port this module has no series for. Every other one-port " +
              "contributes to `fact` and to every metric over it; returning nothing here " +
              "would leave it out of all nineteen with no diagnostic."
          )

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
