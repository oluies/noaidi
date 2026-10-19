package org.noaidi.stats

import java.sql.{Connection, DriverManager}
import org.duckdb.DuckDBConnection
import org.noaidi.lopf.{Expansion, LopfResult, Periods}
import org.noaidi.network.{ComponentSpec, ComponentTable, Network, Role, Topology}

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
  * ==Eight metrics, seven groupings, every golden==
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
  * The remaining eleven metrics are listed in the design note, along with what is still
  * missing from these eight: branches at their ports in `supply`, which PyPSA's
  * `energy_balance` includes with a sign per end -- measured as `SUM |p0| x w_generators` for
  * a line, and the port's own bus for the grouping, which is the one thing the `dimensions`
  * view does not yet resolve.
  */
final class Stats private (
    private val conn: Connection,
    private val multiPeriod: Boolean,
    private val hasCountry: Boolean,
    /** Assets PyPSA cannot give an overnight cost for; see [[overnightCost]]. */
    private val unpriceable: Seq[String],
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
    * `w_generators`, and that is '''not''' the obvious choice -- it is PyPSA's. `supply`
    * delegates to `energy_balance`, which weights with `n.snapshot_weightings.generators`
    * (`expressions.py:2305`), while `stores` is the elapsed hours the storage balance uses
    * inside the LOPF. Both are "hours" in some sense and the statistics layer picked
    * `generators`.
    *
    * The first version of this used `w_stores`, and `docs/network-stats-design.md` argued for
    * it in so many words -- "`stores` is the elapsed hours, so it is what turns MW into MWh;
    * `generators` is not". Backwards, and `operational-limit` is the fixture that says so: it
    * is the only golden whose three weightings differ (`generators` 3.0, the others 1.0), and
    * PyPSA reports gas supply as 120 against a dispatch of 40 MW at one snapshot. 40 x 3, not
    * 40 x 1. Asserted confidently, wrong, and caught only by running it against upstream.
    *
    * An absent carrier groups as `'-'`, which is PyPSA's rendering under `nice_names` and
    * not a choice made here.
    *
    * ==Branches, at their ports==
    *
    * `energy_balance` is called with `at_port="all"`, so a component contributes at EVERY
    * port with that port's sign, and `supply` keeps the positive part. The sign rule is the
    * bus balance's -- see `injection` in [[StatsSchema]]. For a line the two ports give
    * `-p0` and `+p0`, so the positive part sums to `|p0|`, which is what `ac-dc-meshed`
    * records: 3,846.70123 for `Line|0` against a measured `sum |p0|` of 3,846.70123.
    *
    * This covered one-ports only for two releases, with `StatsSuite` asserting that the
    * uncovered component set was exactly the branch kinds. That assertion is gone with the
    * gap, and the suite now compares every component PyPSA reports.
    *
    * '''A third port counts like the second.''' `Lopf` has always built a link with `bus2` --
    * `efficiency2` is read there -- and this module refused such a network by name, because
    * two ports silently carrying three is a supply short by whatever the third injects. The
    * `port` table carries as many as the network gives, and `multi-port-link` is the golden
    * that measures it: its electrolyser's waste heat is 95 of a 323 `supply` group, so the
    * two-port reading is short by 29% of it rather than by a rounding artefact.
    *
    * ==It is `energy_balance`'s positive half, and now says so in one line==
    *
    * This was its own query until [[energyBalance]] and [[withdrawal]] arrived, and the three
    * are one `SELECT` over `injection` with a different filter -- which is what upstream is
    * too: one function with a `direction` argument. See [[balance]].
    */
  def supply(groupBy: String = "carrier"): Map[String, Double] =
    balance(groupBy, filter = "WHERE i.value > 0", term = "i.value")

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
      |JOIN dimensions d
      |  ON d.component = p.component AND d.entity = p.entity AND d.port = 0
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

  /** The capacity the solve chose, in MW, by `groupBy`.
    *
    * `p_nom_opt`, summed, per period on a multi-period network -- and '''not''' weighted,
    * which is the one thing that distinguishes this from [[capex]]. `capex` multiplies each
    * period by its `objective` weighting and these three do not: there is no
    * `has_investment_periods` branch in `optimal_capacity`, and the goldens agree.
    * `investment-periods-expansion` has weightings 1.0 and 0.5 and reports 160 and 200 MW,
    * which is `old 100 + wind 60` and then `+ late 40` with nothing scaled.
    *
    * A megawatt is not a cost, so there is nothing for a discount factor to discount. Obvious
    * once stated and not obvious from the three metrics sharing a view -- a port that passed
    * the weight through here would be wrong by 2x on the second period of that fixture and
    * right on every single-period network.
    *
    * ==Capacity counts at port 0==
    *
    * `optimal_capacity` multiplies by `port_efficiency(n, c, port)`, which is 1 at `bus0`, and
    * the goldens show branch capacity counted ONCE: `ac-dc-meshed`'s seven lines report
    * 280,000 MW installed, which is their plain `s_nom` sum rather than twice it, and
    * `statistics-dimensions`' electrolyser reports 100 MW rather than its 0.7 efficiency
    * times that. So this is the same `bus0` placement [[opex]] uses, and not the per-port
    * treatment `supply` needs.
    */
  def optimalCapacity(groupBy: String = "carrier"): Map[String, Double] =
    capital(groupBy, "e.nominal", weighted = false)

  /** The capacity the network came with, in MW, by `groupBy`. */
  def installedCapacity(groupBy: String = "carrier"): Map[String, Double] =
    capital(groupBy, "COALESCE(e.nominal_in, 0)", weighted = false)

  /** The capacity the solve ADDED, in MW, by `groupBy` -- never negative.
    *
    * The asymmetry with [[expandedCapex]] is real and is upstream's:
    *
    * {{{
    * expanded_capex    = capex.sub(installed_capex, fill_value=0)
    * expanded_capacity = optimal.sub(installed).where(optimal.abs() > installed.abs(), 0)
    * }}}
    *
    * So the capex one reports a '''negative''' where capacity was retired -- `ac-dc-meshed`'s
    * gas comes out at -21,109,107.32 -- and the capacity one reports '''zero''' for the same
    * asset, because 982 MW is not more than the 150,000 MW it started with. Both are in the
    * goldens for that network and the two disagree about the same retirement, which is not
    * something either formula hints at.
    *
    * `reindex(optimal.index)` rather than a union, which is the second difference: a group
    * present only in `installed_capacity` is '''dropped''' here, where `expanded_capex` would
    * carry it as a negative. Reproduced, and unexercised -- it needs an extendable whose
    * optimum is exactly zero beside a non-zero `p_nom`, and no golden has one.
    */
  def expandedCapacity(groupBy: String = "carrier"): Map[String, Double] =
    val optimal   = optimalCapacity(groupBy)
    val installed = installedCapacity(groupBy)
    optimal.map { (key, chosen) =>
      val had = installed.getOrElse(key, 0.0)
      key -> (if math.abs(chosen) > math.abs(had) then chosen - had else 0.0)
    }

  /** Fixed O&M over the modelled horizon, by `groupBy`.
    *
    * `p_nom_opt x fom_cost`, unweighted. `fom_cost` is already a cost for the horizon rather
    * than an annual one -- that is why [[Expansion.periodizedCost]] adds it to the annuity
    * instead of annuitising it -- so there is nothing for a period weighting to do, and
    * upstream's `fom` has no `has_investment_periods` branch either.
    *
    * Empty on every golden but `annuitised-capital`, whose `new` generator is the only asset
    * anywhere that sets `fom_cost`: 100 MW at 7.5 is 750. The others are zero and `drop_zero`
    * removes them, so this is a metric with one live comparison and `StatsSuite` skips the
    * rest as trivial rather than counting them as agreement.
    *
    * ==The unweighted claim is not under test, and that is measured==
    *
    * `annuitised-capital` is a flat index, so its period weighting is 1.0 and passing one
    * through here changes nothing: `weighted = true` is an '''equivalent mutant''', checked
    * rather than assumed. The claim rests on reading `fom` upstream, which has no
    * `has_investment_periods` branch where `capex` does.
    *
    * Closing it needs `fom_cost` on a multi-period golden, and every candidate carries pinned
    * numbers that would move with it -- `tx-cost-periods` is the natural one, an extendable
    * `Line` over two periods. Named here rather than counted as coverage, which is the same
    * hole `nyears` had until `annuitised-capital`'s horizon stopped being a whole year.
    *
    * Note the metric and the objective disagree on purpose: `Expansion.periodizedCost` adds
    * `fom_cost` to the annuity and `Lopf` then multiplies the lot by `costWeight`, so the
    * objective '''does''' weight it. Upstream's `periodized_cost` does the same. It is this
    * statistic alone that reports it flat.
    */
  def fom(groupBy: String = "carrier"): Map[String, Double] =
    capital(groupBy, "e.nominal * COALESCE(e.fom_cost, 0)", weighted = false)

  /** Overnight investment cost of the chosen capacity, by `groupBy`.
    *
    * `p_nom_opt x overnight_cost`, unweighted, where `overnight_cost` is
    * [[Expansion.overnightCost]] -- the column where it is set and
    * `capital_cost / (annuity x nyears)` where it is not.
    *
    * ==It refuses the networks upstream refuses==
    *
    * The back-calculation needs `discount_rate` and `lifetime`, and
    * `Components.overnight_cost` raises without them: "both 'discount_rate' and 'lifetime'
    * must be provided when 'overnight_cost' is not set". `discount_rate` defaults to NaN, so
    * that is every network priced by `capital_cost` alone -- `ac-dc-meshed` names six
    * generators and `store-bank` one store, and `generate_goldens.py` records an error in
    * place of a frame for both.
    *
    * So this refuses them too, by name, rather than returning a partial sum over the assets
    * it can price. A number that is short by whatever the unpriceable assets cost is the
    * failure this module is arranged against, and the group set would not show it: the
    * missing assets simply would not appear.
    */
  def overnightCost(groupBy: String = "carrier"): Map[String, Double] =
    if unpriceable.nonEmpty then
      throw new IllegalArgumentException(
        s"${unpriceable.length} asset(s) have neither an overnight_cost nor the " +
          s"discount_rate and lifetime to back-calculate one from capital_cost: " +
          s"${unpriceable.take(6).mkString(", ")}${if unpriceable.length > 6 then ", ..." else ""}. " +
          "PyPSA raises here rather than pricing the rest."
      )
    else capital(groupBy, "e.nominal * COALESCE(e.overnight_cost, 0)", weighted = false)

  /** Capital, fixed O&M and operating cost together, by `groupBy`.
    *
    * `capex + fom + opex`, and like [[expandedCapex]] it is a '''union of finished metrics'''
    * rather than a query of its own. Upstream is
    * `reduce(lambda a, b: a.add(b, fill_value=0), (capex, fom, opex))`, where each operand has
    * already been rounded and zero-dropped inside its own metric and nothing is dropped from
    * the sum. So a group whose capital and operating terms cancel reports an exact zero, the
    * same way `expanded_capex` does -- and `round(a) + round(b)` is not `round(a + b)` in the
    * fifth decimal, which is the reason the composition has to happen here and not in SQL.
    *
    * `annuitised-capital` pins all three terms on one asset: its `new` generator is
    * 4,290.52586 of capex, 750 of fom -- the only `fom_cost` anywhere in the goldens -- and
    * 4,818,000 of opex, and upstream reports 4,823,040.52586.
    *
    * ==It is NOT the objective, and the gap is `overnight_cost`-shaped==
    *
    * Three cost terms out of PyPSA's own `capex`, `fom` and `opex`, so this inherits every
    * divergence from the objective that those three have: `capex` reads the `capital_cost`
    * property, which leaves `fom_cost` out and annuitises an `overnight_cost`, and `fom` is
    * unweighted where `Expansion.periodizedCost` weights it. A network solved with
    * `fom_cost` on a multi-period index therefore has a `system_cost` that is not its
    * objective value, and that is upstream's arithmetic rather than a port's error.
    *
    * [[overnightCost]] is not a term here, so this works on the networks that one refuses.
    */
  def systemCost(groupBy: String = "carrier"): Map[String, Double] =
    val parts = Seq(capex(groupBy), fom(groupBy), opex(groupBy))
    parts
      .flatMap(_.keySet)
      .distinct
      .map(key => key -> parts.map(_.getOrElse(key, 0.0)).sum)
      .toMap

  /** Available power that was not dispatched, in MWh, by `groupBy`.
    *
    * `(p_max_pu * p_nom_opt - p).clip(lower=0)`, weighted by `w_generators` and summed --
    * upstream's `curtailment` to the letter, including the clip, which matters because a
    * storage unit that is CHARGING has a negative `p` and would otherwise count its charging
    * as curtailment on top of its headroom.
    *
    * `w_generators` rather than `w_objective`, for the same reason [[supply]] uses it: this
    * is an energy and `_aggregate_timeseries` reads `snapshot_weightings.generators`.
    *
    * ==Which components it covers is decided by `p_max_pu`, not by a list==
    *
    * Upstream wraps the body in `@pass_empty_series_if_keyerror` and then asks for
    * `get_switchable_as_dense(c, "p_max_pu")`, so a component without that attribute
    * contributes an empty series and no error. That is the whole membership rule: `Generator`,
    * `StorageUnit` and the controllable branches have a `p_max_pu`; `Store`, `Load`, `Line`
    * and `Transformer` do not. A passive branch has `s_max_pu` instead, which is not the same
    * name, so `inactive` and `standard-types` record no `Line` or `Transformer` group here
    * while [[transmission]] reports both of them.
    *
    * This query reproduces that rule rather than restating the list: the `INNER JOIN` to the
    * `p_max_pu` rows of `fact` is what drops a component, and `quantitiesOf` emits those rows
    * for exactly the components that declare one. A controllable branch added upstream is
    * covered without this SQL changing, and a one-port added without a `p_max_pu` is excluded
    * for upstream's own reason instead of by omission from a hardcoded `IN`.
    *
    * ==Inactive capacity counts as curtailed==
    *
    * Nothing masks by investment period. An asset that does not exist yet has `p = 0` and a
    * `p_nom_opt` that is already its full capacity, so its whole potential is reported as
    * curtailment in the periods before it is built. Upstream does the same -- there is no
    * `active_assets` term in `curtailment` -- and `investment-periods-expansion` is the
    * fixture that shows it rather than a judgement about what the number ought to mean.
    */
  def curtailment(groupBy: String = "carrier"): Map[String, Double] =
    // `GREATEST(..., 0)` is `clip(lower=0)`. DuckDB has no `clip`, and `CASE WHEN` would say
    // the same thing in three more lines.
    val term =
      "GREATEST(pu.value * e.nominal - f.value, 0) * s.w_generators"
    grouped(s"""
      |SELECT f.component            AS component,
      |       d.${dimension(groupBy)} AS grp$periodSelect,
      |       ROUND(SUM($term), 5)    AS value
      |FROM fact f
      |-- The membership rule, as a join: a component with no `p_max_pu` has no such rows and
      |-- drops out, which is what `@pass_empty_series_if_keyerror` does upstream.
      |JOIN fact pu
      |  ON  pu.component = f.component AND pu.entity = f.entity
      |  AND pu.snapshot  = f.snapshot  AND pu.quantity = 'p_max_pu'
      |JOIN entity e ON e.component = f.component AND e.entity = f.entity
      |JOIN dimensions d
      |  ON d.component = f.component AND d.entity = f.entity AND d.port = 0
      |JOIN snapshot s ON s.snapshot = f.snapshot
      |-- The dispatch series, under both its names: `p` for a one-port and `p0` for a
      |-- controllable branch, which is the same variable PyPSA stores twice -- `assign_solution`
      |-- writes a link's `p` and its `p0` from one solution array.
      |WHERE f.quantity IN ('p', 'p0')
      |GROUP BY f.component, grp$periodGroup
      |HAVING ROUND(SUM($term), 5) <> 0
      |""".stripMargin)

  /** Signed flow through the branches that carry power between buses of one carrier.
    *
    * `p0 * w_generators`, summed, at port 0 only -- `at_port="bus0"` is this metric's own
    * default and not the usual `None`, so a branch is counted once at the end it leaves and
    * the receiving port's efficiency never enters. Which branches count is the
    * `transmission_branch` view; see [[StatsSchema]] for why same-carrier is PyPSA's own
    * definition of transmission.
    *
    * ==Signed, and that is the whole difference from [[supply]]==
    *
    * `supply` keeps the positive part of each port's injection, so a line that sends 25.7 MWh
    * one way and 3,821 MWh the other reports their sum. This reports their '''difference''':
    * `ac-dc-meshed`'s `Line|0` comes out at -3,795.26789, which is 25.717 - 3,820.985, and
    * `inactive` reports `Line|AC` at -715 beside a `Transformer|-` at +420. A metric that
    * took the absolute value would agree with upstream on every radial network and on nothing
    * meshed.
    *
    * So the sign is the direction the flow took on balance, and a group of several lines can
    * cancel to a zero that `drop_zero` then removes -- which is why `ac-dc-txvolume` reports
    * `Line|AC` and `statistics-dimensions` reports its one `Link|tie` at +240 rather than a
    * magnitude.
    */
  def transmission(groupBy: String = "carrier"): Map[String, Double] =
    grouped(s"""
      |SELECT f.component            AS component,
      |       d.${dimension(groupBy)} AS grp$periodSelect,
      |       ROUND(SUM(f.value * s.w_generators), 5) AS value
      |FROM fact f
      |JOIN transmission_branch tb
      |  ON tb.component = f.component AND tb.entity = f.entity
      |JOIN dimensions d
      |  ON d.component = f.component AND d.entity = f.entity AND d.port = 0
      |JOIN snapshot s ON s.snapshot = f.snapshot
      |WHERE f.quantity = 'p0'
      |GROUP BY f.component, grp$periodGroup
      |HAVING ROUND(SUM(f.value * s.w_generators), 5) <> 0
      |""".stripMargin)

  /** Net energy at every bus the component touches, in MWh, by `groupBy`.
    *
    * PyPSA's `energy_balance` with `direction="both"`, which is the metric [[supply]] and
    * [[withdrawal]] are the two halves of. All three are one `SUM` over the `injection` view
    * weighted by `w_generators`; this one applies no filter at all, `supply` keeps the
    * positive rows and `withdrawal` negates the negative ones.
    *
    * '''Signed, and the sign is the whole content.''' A generator contributes positively, a
    * load negatively, a branch negatively at the end it leaves and positively at the end it
    * reaches. Summing the whole metric over every group of a network recovers zero to solver
    * tolerance, because that is the bus balance.
    *
    * This is the first metric here that reports `Load`, and `Load` is the reason three of the
    * six that followed it needed a schema change rather than a query -- see `quantitiesOf`
    * for what a load contributes and why its `p` is an input.
    */
  def energyBalance(groupBy: String = "carrier"): Map[String, Double] =
    balance(groupBy, filter = "", term = "i.value")

  /** Energy taken OUT at every bus the component touches, in MWh, by `groupBy`.
    *
    * `-p.clip(upper=0)` summed, which is the negative part of [[energyBalance]] reported as a
    * positive number. A load is the obvious contributor and not the only one: a storage unit
    * charging withdraws, a store filling withdraws, and a branch withdraws at `bus0` whatever
    * it delivers at `bus1`.
    *
    * '''Not''' `energy_balance` negated. A group containing both a generator and a load would
    * net to the difference under [[energyBalance]] and reports the load's whole consumption
    * here, because the clip happens per `(entity, port, snapshot)` and not per group.
    */
  def withdrawal(groupBy: String = "carrier"): Map[String, Double] =
    balance(groupBy, filter = "WHERE i.value < 0", term = "-i.value")

  /** The shared body of the three directions of PyPSA's `energy_balance`.
    *
    * `filter` and `term` are interpolated, and as with [[capital]]'s `cost` that is safe only
    * because the three call sites are the only ones and each passes a literal.
    */
  private def balance(groupBy: String, filter: String, term: String): Map[String, Double] =
    grouped(s"""
      |SELECT i.component                      AS component,
      |       d.${dimension(groupBy)}           AS grp$periodSelect,
      |       ROUND(SUM(($term) * s.w_generators), 5) AS value
      |FROM injection i
      |JOIN dimensions d
      |  ON d.component = i.component AND d.entity = i.entity AND d.port = i.port
      |JOIN snapshot s USING (snapshot)
      |$filter
      |GROUP BY i.component, grp$periodGroup
      |HAVING ROUND(SUM(($term) * s.w_generators), 5) <> 0
      |""".stripMargin)

  /** Mean utilisation of the chosen capacity, per unit, by `groupBy`.
    *
    * A ratio rather than a sum, and the first metric here that is one:
    *
    * {{{
    * capacity_factor = mean_t |operation|  /  optimal_capacity
    * }}}
    *
    * Three things about it are upstream's and none of them is what the name suggests.
    *
    * ==The mean is weighted, and the weights are normalised PER PERIOD==
    *
    * `_aggregate_timeseries(..., agg="mean")` does not average: it divides the weighting
    * column by its own sum and then sums. On a multi-period index that division is
    * `weights.groupby(level=0).transform(lambda w: w / w.sum())` -- per period, so each
    * period's factor is a mean over that period's snapshots alone rather than over the
    * horizon. `w_generators` again, as everywhere an energy is involved.
    *
    * ==The numerator is `get_operation`, which is a different series per component==
    *
    * `p` for a one-port and a controllable branch, `p0` for a passive one, and '''`e` for a
    * `Store`''' -- the energy LEVEL. So a store's capacity factor is its mean state of charge
    * against `e_nom_opt`, not a utilisation of any power rating. See `operation` in
    * [[StatsSchema]].
    *
    * ==A `Load` reports NaN, and that is the answer rather than a gap==
    *
    * Upstream divides by `optimal_capacity.reindex(df.index)`, and a load has no `p_nom` for
    * `optimal_capacity` to report, so the reindex supplies NaN and the division propagates
    * it. Every golden records `capacity_factor` for its loads as a NaN, 1,291 of them, and
    * this reproduces that rather than dropping the group -- a dropped group would be a group
    * PyPSA has and this does not, which `StatsSuite` compares on.
    *
    * The division is on already-rounded, already-zero-dropped operands and the quotient is
    * NOT re-rounded, which is why this is the one metric here whose values carry more than
    * five decimals.
    */
  def capacityFactor(groupBy: String = "carrier"): Map[String, Double] =
    val capacity = optimalCapacity(groupBy)
    // The per-period normaliser. `COALESCE(period, '')` rather than `period`, because a flat
    // index has one NULL period and `JOIN ... USING (period)` on a NULL matches nothing.
    val mean = grouped(s"""
      |WITH span AS (
      |  SELECT COALESCE(period, '') AS period_key, SUM(w_generators) AS total
      |  FROM snapshot
      |  GROUP BY COALESCE(period, '')
      |)
      |SELECT o.component                      AS component,
      |       d.${dimension(groupBy)}           AS grp$periodSelect,
      |       ROUND(SUM(o.value * s.w_generators / span.total), 5) AS value
      |FROM operation o
      |JOIN dimensions d
      |  ON d.component = o.component AND d.entity = o.entity AND d.port = 0
      |JOIN snapshot s ON s.snapshot = o.snapshot
      |JOIN span ON span.period_key = COALESCE(s.period, '')
      |GROUP BY o.component, grp$periodGroup
      |HAVING ROUND(SUM(o.value * s.w_generators / span.total), 5) <> 0
      |""".stripMargin)
    // `reindex(df.index)`, so a group the capacity has and the operation does not is dropped
    // rather than reported as a zero -- and one the operation has and the capacity does not
    // is a NaN rather than an absence.
    mean.map((key, value) => key -> value / capacity.getOrElse(key, Double.NaN))

  /** Net revenue of each component, in currency, by `groupBy`.
    *
    * Every port's injection priced at the bus it injects into, weighted and summed:
    *
    * {{{
    * revenue = SUM over ports and snapshots of
    *           sign * p_port * marginal_price[bus_of_port] * w_objective
    * }}}
    *
    * So a generator earns, a load pays, and a branch pays at the end it leaves and earns at
    * the end it reaches -- which makes the signed quantity being priced exactly the
    * `injection` view [[energyBalance]] sums. Upstream writes it as
    * `sign * dynamic[f"p{port}"]` times the price frame reindexed onto that port's buses, and
    * the two expressions are the same one.
    *
    * ==`w_objective` alone, where [[energyBalance]] takes `w_generators`==
    *
    * `_aggregate_timeseries(revenue, n.snapshot_weightings.objective)`, and the period
    * weighting is '''not''' applied on top -- unlike [[opex]], which multiplies by
    * `p_objective` as well. That is not an inconsistency in this port: `assign_solution`
    * divides `buses_t.marginal_price` by the snapshot weighting '''times''' the period's, so
    * the stored price is already net of both, and multiplying only the snapshot half back in
    * leaves a revenue that is discounted exactly once. `LopfResult.marginalPrice` divides by
    * the same product, so the arithmetic matches term for term.
    *
    * ==It is the first metric here whose input is a DUAL==
    *
    * A nodal price is a dual variable and a dual face is far more common than a primal one:
    * any bus whose marginal unit sits at a bound has an interval of valid prices. The
    * generator's determinacy probe covers it the same way it covers a degenerate dispatch --
    * it solves twice and records the groups that moved -- so a price two solvers disagree
    * about is skipped rather than compared.
    */
  def revenue(groupBy: String = "carrier"): Map[String, Double] =
    revenueOver(groupBy, mean = false)

  /** [[revenue]], summed over the horizon or averaged over it.
    *
    * `mean` is `groupby_time`, and the two callers want different values of it: `revenue`
    * defaults to `"sum"` and [[marketValue]] passes its own default of `"mean"` down. The
    * averaging is `_aggregate_timeseries`' own -- divide the weighting column by its sum and
    * then sum, per period on a multi-period index -- and is the same shape
    * [[capacityFactor]] needs, over `w_objective` rather than `w_generators`.
    */
  private def revenueOver(groupBy: String, mean: Boolean): Map[String, Double] =
    val term =
      if mean then "i.value * pr.value * s.w_objective / span.total"
      else "i.value * pr.value * s.w_objective"
    grouped(s"""
      |WITH span AS (
      |  SELECT COALESCE(period, '') AS period_key, SUM(w_objective) AS total
      |  FROM snapshot
      |  GROUP BY COALESCE(period, '')
      |)
      |SELECT i.component                      AS component,
      |       d.${dimension(groupBy)}           AS grp$periodSelect,
      |       ROUND(SUM($term), 5)              AS value
      |FROM injection i
      |JOIN port po
      |  ON po.component = i.component AND po.entity = i.entity AND po.port = i.port
      |JOIN price pr ON pr.bus = po.bus AND pr.snapshot = i.snapshot
      |JOIN dimensions d
      |  ON d.component = i.component AND d.entity = i.entity AND d.port = i.port
      |JOIN snapshot s ON s.snapshot = i.snapshot
      |JOIN span ON span.period_key = COALESCE(s.period, '')
      |GROUP BY i.component, grp$periodGroup
      |HAVING ROUND(SUM($term), 5) <> 0
      |""".stripMargin)

  /** Revenue per unit operated, in currency per MWh, by `groupBy`.
    *
    * A ratio of two '''means''', not of two sums, and that is `market_value`'s own
    * `groupby_time` default of `"mean"` -- which it passes down to `revenue` as well:
    *
    * {{{
    * rev   = revenue(groupby_time="mean", at_port="all")       # objective weighting
    * denom = |get_operation| averaged, at_port=[0],
    *         drop_zero=False, round=None                       # generators weighting
    * df    = rev / denom
    * }}}
    *
    * ==Two weighting columns, each normalised by its own sum==
    *
    * The numerator divides by the period's total `w_objective` and the denominator by its
    * total `w_generators`, so where those two columns differ the ratio is '''not''' the ratio
    * of the sums. `operational-limit` is the fixture that says so: `generators` is 3.0 against
    * `objective` at 1.0, so the two totals are 12 and 4 and the sums-of-products ratio is a
    * third of the answer -- 4.1667 against upstream's 12.5. `energy-budget` is the same
    * mistake at a factor of two.
    *
    * That factor is the only thing wrong with the sums reading, which is why it is worth
    * naming: `revenue` on its own defaults to `"sum"` and agrees with upstream exactly, so a
    * port that read `market_value` as `revenue / operation` gets the numerator right and the
    * metric wrong.
    *
    * ==The asymmetry is where the NaNs and the infinities come from==
    *
    * `revenue` counts every port and the denominator counts port 0 only, so under a grouping
    * read off a BUS the numerator has keys the denominator does not -- a branch's `bus1`
    * appears in `rev` and never in `denom` -- and the division leaves NaN there. Measured:
    * 1,112 NaN groups across the goldens, more than any other metric here produces, and an
    * answer rather than a gap.
    *
    * The denominator is also neither rounded nor zero-dropped, which is why it can be exactly
    * zero: a component that never operated has a revenue of nothing over an operation of
    * nothing. `0.0 / 0.0` is NaN in Scala as in numpy and `x / 0.0` is an infinity in both --
    * `ac-dc-txvolume` records a `+inf` group -- so the arithmetic carries upstream's own
    * answer without a branch for it.
    */
  def marketValue(groupBy: String = "carrier"): Map[String, Double] =
    val earned = revenueOver(groupBy, mean = true)
    // `drop_zero=False, round=None`, so no `HAVING` and no `ROUND` -- the only query here
    // without either, and deliberately: a zero denominator is what makes a group NaN.
    val operated = grouped(s"""
      |WITH span AS (
      |  SELECT COALESCE(period, '') AS period_key, SUM(w_generators) AS total
      |  FROM snapshot
      |  GROUP BY COALESCE(period, '')
      |)
      |SELECT o.component                      AS component,
      |       d.${dimension(groupBy)}           AS grp$periodSelect,
      |       SUM(o.value * s.w_generators / span.total) AS value
      |FROM operation o
      |JOIN dimensions d
      |  ON d.component = o.component AND d.entity = o.entity AND d.port = 0
      |JOIN snapshot s ON s.snapshot = o.snapshot
      |JOIN span ON span.period_key = COALESCE(s.period, '')
      |GROUP BY o.component, grp$periodGroup
      |""".stripMargin)
    // The UNION of the two key sets, because `rev / denom` is a pandas division and that
    // aligns on the union: a group only one side has comes back as NaN, in either direction.
    // Reindexing onto `rev` alone would drop a component that operated and earned exactly
    // nothing -- its revenue rounds to zero and `drop_zero` removes it -- where upstream
    // reports NaN for it.
    //
    // Upstream's `if denom.empty: rev[:] = np.nan` needs no counterpart: with an empty
    // denominator every key divides by a missing value and is already NaN.
    (earned.keySet ++ operated.keySet)
      .map(key =>
        key -> earned.getOrElse(key, Double.NaN) / operated.getOrElse(key, Double.NaN))
      .toMap

  /** The load-weighted average marginal price at each group of buses, in currency per MWh.
    *
    * The one metric keyed by '''bus''' rather than by component, which is why it has its own
    * key shape and its own grouping view -- see `bus_dimensions` in [[StatsSchema]]. Upstream
    * says so in its own signature: `prices` takes neither `components` nor `at_port`, because
    * "prices are only defined for buses".
    *
    * {{{
    * price[bus]  = SUM_t w_objective * load[t, bus] * price[t, bus]
    *             / SUM_t w_objective * load[t, bus]
    * price[group] = SUM_bus b[bus] * price[bus] / SUM_bus b[bus]      with b the denominator
    * }}}
    *
    * ==The default weighting is the LOAD, not the time==
    *
    * `weighting="load"`, and the load is `withdrawal(groupby="bus", groupby_time=False)` --
    * raw megawatts per bus per snapshot, with no snapshot weighting of its own, because the
    * weighting enters once on each side of the division.
    *
    * A bus that withdraws nothing '''anywhere''' is given a weight of 1, not 0:
    * `weights.reindex(prices.columns, axis=1, fill_value=1)`, so such a bus gets a flat time
    * average rather than a `0/0`. The reindex is per BUS and not per cell, so a bus that
    * withdraws SOMETIMES keeps its own zeros at the quiet snapshots -- see `bus_load_weight`
    * in [[StatsSchema]], which exists because a `COALESCE(..., 1.0)` reads like the first rule
    * and quietly applies it to the second case too.
    *
    * ==The snapshot weighting is an equivalent mutant, and the fixture that would kill it is
    * skipped==
    *
    * `w_objective` multiplies both sides of the division, so it cancels wherever it is the
    * same at every snapshot -- and dropping it fails nothing, measured. Exactly one golden
    * varies that column across snapshots, `link-delay-wrap` at 1.0 and 3.0, and it is one of
    * the networks `StatsSuite` skips because its nodal prices are a different vertex of the
    * dual. So the term is upstream's and is untested here, named rather than counted.
    *
    * ==There is no period level, and that is not an omission==
    *
    * Every other metric here reports per period on a multi-period network. `prices` does not:
    * it is a single weighted average over the whole index, so `tx-cost-periods` records one
    * number per bus across both 2030 and 2040. Upstream's aggregation is a matrix product
    * against the whole weighting column with no `groupby(level=0)` anywhere in it.
    *
    * ==`bus` is refused, by PyPSA and so by this==
    *
    * `prices` groups by a static BUS column, and there is no `bus` column on `buses.csv`:
    * upstream raises `ValueError: Grouping prices by 'bus' is not supported. Use 'name',
    * 'bus_carrier' or a static bus attribute.` Every golden records it, and `busDimension`
    * refuses the same name rather than grouping each bus under itself -- which would be a
    * plausible answer to a question upstream declines.
    */
  def prices(groupBy: String = "carrier"): Map[String, Double] =
    val column = busDimension(groupBy)
    query(s"""
      |WITH weighted AS (
      |  SELECT pr.bus                                  AS bus,
      |         SUM(s.w_objective * w.value * pr.value) AS numerator,
      |         SUM(s.w_objective * w.value)            AS denominator
      |  FROM price pr
      |  JOIN snapshot s ON s.snapshot = pr.snapshot
      |  JOIN bus_load_weight w ON w.bus = pr.bus AND w.snapshot = pr.snapshot
      |  GROUP BY pr.bus
      |)
      |SELECT bd.$column          AS grp,
      |       SUM(numerator)      AS numerator,
      |       SUM(denominator)    AS denominator
      |FROM weighted
      |JOIN bus_dimensions bd ON bd.name = weighted.bus
      |GROUP BY bd.$column
      |""".stripMargin)
      .map { row =>
        def number(column: String): Double = row(column) match
          case null                => 0.0
          case n: java.lang.Number => n.doubleValue
          case other =>
            throw new IllegalStateException(s"prices returned a non-numeric $column: $other")
        val group = Option(row("grp")).map(_.toString).getOrElse("")
        // Divided HERE and not in SQL, which is the one thing about this query worth a
        // comment. DuckDB returns NULL for a division by zero and numpy returns a NaN or an
        // infinity, and the difference is not cosmetic: a bus nothing withdraws from has a
        // denominator of exactly zero and upstream reports NaN for it -- `link-delay`'s bus
        // `a` is the case -- while a NULL would reach `drop_zero` as "no value" and vanish.
        //
        // Rounding and the zero-drop are applied here for the same reason, in the order
        // `_apply_option_kwargs` applies them: round to five, then drop what is exactly zero.
        // `rint` rather than DuckDB's `ROUND` because it is numpy's half-to-even and because
        // it passes a NaN through.
        group -> math.rint(number("numerator") / number("denominator") * 1e5) / 1e5
      }
      .filter((_, value) => value != 0.0)
      .toMap

  /** A grouping dimension of a BUS, validated against the `bus_dimensions` view.
    *
    * The same injection guard [[dimension]] is, over a different set of names: `bus` is NOT
    * one of them, because upstream reads a static column of `buses.csv` and there is no `bus`
    * column on it. `component` is absent for the same reason -- a bus is not a component
    * here, and nothing is keyed by one.
    *
    * `country` is refused where the buses carry none, exactly as [[dimension]] refuses it and
    * for the same reason: PyPSA's own grouper raises on such a network.
    */
  private def busDimension(groupBy: String): String =
    if !Stats.BusDimensions.contains(groupBy) then
      throw new IllegalArgumentException(
        s"no bus grouping dimension '$groupBy'; PyPSA's `prices` groups by " +
          s"${Stats.BusDimensions.toSeq.sorted.mkString(", ")} and raises on anything else, " +
          "because it reads a static column of the bus table"
      )
    else if groupBy == "country" && !hasCountry then
      throw new IllegalArgumentException(
        "this network's buses carry no `country` column, and PyPSA's own grouper raises " +
          "AttributeError on such a network rather than grouping it under one empty country"
      )
    else groupBy

  /** The shared body of the four metrics that are a single sum over `capital_share`.
    *
    * `cost` is an expression over `entity`, interpolated -- which is safe only because the
    * three call sites are the only ones and each passes a literal. It is not a parameter in
    * the sense `groupBy` is, and it is deliberately `private`.
    *
    * No join to `fact` or `snapshot`: a capacity is one number for the whole horizon. That is
    * what makes these three the first metrics here whose period axis comes from
    * `capital_share` rather than from the snapshot index.
    */
  private def capital(groupBy: String, cost: String, weighted: Boolean = true): Map[String, Double] =
    // The period weighting is a factor on a COST and not on a capacity. `capex` carries it
    // and the three capacity metrics do not -- there is no `has_investment_periods` branch in
    // `optimal_capacity` upstream, and `investment-periods-expansion` reports 160 and 200 MW
    // against weightings of 1.0 and 0.5. Passing it through unconditionally is wrong by 2x on
    // that fixture's second period and right on every flat-index network.
    val term = if weighted then s"($cost) * cs.weight" else s"($cost)"
    grouped(s"""
      |SELECT e.component            AS component,
      |       d.${dimension(groupBy)} AS grp$capitalPeriodSelect,
      |       ROUND(SUM($term), 5)    AS value
      |FROM entity e
      |JOIN dimensions d
      |  ON d.component = e.component AND d.entity = e.entity AND d.port = 0
      |-- Fully qualified rather than `USING`, because `dimensions` now carries a `component`
      |-- of its own and a `USING` join against two tables that both have one is ambiguous.
      |JOIN capital_share cs ON cs.component = e.component AND cs.entity = e.entity
      |GROUP BY e.component, grp$capitalPeriodGroup
      |HAVING ROUND(SUM($term), 5) <> 0
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

  /** Every grouping dimension `prices` accepts, which is a different set.
    *
    * `prices` is keyed by bus and groups by a static column of `buses.csv`, so `bus` is not
    * among them -- upstream raises `ValueError` on it, and every golden records that. Nor is
    * `component`: a bus is not one. See [[Stats.busDimension]].
    */
  val BusDimensions: Set[String] =
    Set("carrier", "bus_carrier", "country", "location", "unit", "name")

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
      // The third is a list rather than a flag because the refusal names them, and because
      // `generate_goldens.py` records upstream's own message naming them too -- six
      // generators on `ac-dc-meshed`, one store on `store-bank`.
      val unpriceable = Expansion.nominalAttribute.keys.toSeq.sorted.flatMap { component =>
        n.table(component).toSeq.flatMap { table =>
          table.ids
            .filter(id => Expansion.overnightCost(n, table, id).isEmpty)
            .map(id => s"$component '$id'")
        }
      }
      new Stats(conn, n.isMultiPeriod,
                n.table("Bus").exists(_.static.contains("country")), unpriceable)
    catch
      case e: Throwable =>
        conn.close()
        throw e

  /** Everything the schema holds.
    *
    * Two mechanisms, and the reason is now size alone rather than a gap in the driver.
    *
    * `fact` goes through `DuckDBAppender`, because that is where the rows are: `scigrid-de`
    * is 1,423 generators and 852 lines over 24 snapshots, so ~55k rows, and row-by-row JDBC
    * `INSERT` is the one shape of this slow enough to notice.
    *
    * The dimension tables go through a batched `PreparedStatement`. They are hundreds of rows,
    * so the appender buys nothing there, and they carry NULLs that matter: `carrier`, `bus`,
    * `marginal_cost`, `sign` and the rest are legitimately absent on components that do not
    * declare them. Appending a sentinel instead would make "no carrier" and "carrier named
    * empty string" the same row, and would put a 0.0 where a component has no `capital_cost`
    * at all, which any `SUM` would then count.
    *
    * ==The driver gap this used to rest on is closed, and the split outlived it==
    *
    * Until the pin moved to 1.3.2.1 the second paragraph gave a harder reason: `DuckDBAppender`
    * had '''no `appendNull`''' and no boxed overload either, so a nullable column could not go
    * through it at all. Checked with `javap` against both jars rather than assumed: 1.3.1.0 has
    * neither, and 1.3.2.1 added `appendNull()` and seven `append(java.lang.Boolean)`-style
    * overloads that take one.
    *
    * So the appender could carry the dimensions now. They stay on the prepared statement
    * because the size argument always did the work on its own — the gap decided which
    * mechanism was POSSIBLE, not which was right — and this note says so rather than citing a
    * limitation a reader would not find in the jar.
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

    // One row per (bus, snapshot): the dual of that bus's balance.
    //
    // Loaded here rather than from `fact` because it is keyed by BUS and `fact` is keyed by
    // entity, and because `prices` groups buses rather than components -- see `bus_dimensions`
    // in [[StatsSchema]]. `marginalPrice` throws on a bus with no balance row, which is a
    // network this module has never seen; it is left to throw rather than coalesced, because a
    // price of zero where there is no row is a number nobody could check.
    n.table("Bus").foreach { buses =>
      insert("price", 3)(
        for
          bus <- buses.ids
          t   <- n.snapshots.indices
        yield Seq(bus, t, result.marginalPrice(bus, t))
      )
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
    val modelled = n.tables.values.filter(t => quantitiesOf(t).nonEmpty).toSeq

    modelled.foreach { table =>
      val component = table.spec.name
      val branch = Role.of(table.spec) match
        case Role.PassiveBranch | Role.ControllableBranch => true
        case _                                            => false
      // `bus0` for a branch, which is PyPSA's own placement and the asymmetry
      // `TechCapacityLimit` reproduces: a branch counts at the end it leaves.
      val busAttr = if branch then "bus0" else "bus"
      insert("entity", 12)(table.ids.map { id =>
        Seq(
          component,
          id,
          carrierOf(table, busAttr, id),
          if declares(table, busAttr) then table.string(busAttr, id) else null,
          // NULL where the component has no capacity attribute at all, which is `Load` and
          // only `Load`. `LopfResult.capacity` reads `Expansion.nominalAttribute` unguarded
          // and threw `key not found: Load` the moment loads were loaded -- loudly, which is
          // the right direction, and the guard belongs here because a load genuinely has no
          // capacity rather than a capacity of zero. It is what makes `optimal_capacity`
          // report no load group, which is in turn why `capacity_factor` reports NaN for one.
          if Expansion.nominalAttribute.contains(component) then
            Double.box(result.capacity(component, id))
          else null,
          Expansion.nominalAttribute.get(component).map(table.float(_, id)).orNull,
          Expansion.isExtendable(table, id),
          if declares(table, "marginal_cost") then table.float("marginal_cost", id) else null,
          // `Expansion.capitalCost`, not the raw column: upstream's `capex` reads the
          // `capital_cost` PROPERTY, which annuitises an `overnight_cost` and leaves out
          // `fom_cost`. Reading the column agreed on every golden for as long as the LOPF
          // refused an annuitised cost, and then reported 9,999,900 against 16,189.99 on
          // `annuitised-capital`, whose `both` generator sets a `capital_cost` of 99,999
          // that PyPSA ignores.
          if declares(table, "capital_cost") || declares(table, "overnight_cost") then
            Expansion.capitalCost(n, table, id)
          else null,
          if declares(table, "fom_cost") then table.float("fom_cost", id) else null,
          // NULL where upstream refuses to produce one. `Stats.overnightCost` refuses the
          // same networks by name; the row still loads, because a network whose `capex` is
          // perfectly comparable must not fail to load because one other metric is undefined
          // on it.
          if Expansion.nominalAttribute.contains(component) then
            Expansion.overnightCost(n, table, id).map(Double.box).orNull
          else null,
          // NULL for a branch and the declared `sign` for a one-port, which is the shape
          // `injection` reads as "is this a branch". PyPSA's own test is `c in
          // branch_components` and its own fall-back is `static.get("sign", 1.0)`, so a
          // one-port that declares no `sign` gets 1.0 here rather than a NULL that would
          // take it out of every energy balance.
          if branch then null
          else if declares(table, "sign") then Double.box(table.float("sign", id))
          else Double.box(1.0),
        )
      })

      // One row per port the network actually gives this entity. `Topology.branchPorts` is
      // the same enumeration `Lopf`'s balance walks, which is the point: a port counted here
      // and not there -- or there and not here -- is a metric disagreeing with the model it
      // reports on. A one-port has `bus` and nothing else.
      insert("port", 4)(
        if branch then
          Topology
            .branchPorts(table)
            .zipWithIndex
            .flatMap { (column, port) =>
              table.ids
                .filter(id => declares(table, column) && table.string(column, id).nonEmpty)
                .map(id => Seq(component, id, port, table.string(column, id)))
            }
        else
          table.ids
            .filter(id => declares(table, busAttr) && table.string(busAttr, id).nonEmpty)
            .map(id => Seq(component, id, 0, table.string(busAttr, id)))
      )

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
          quantitiesOf(table).foreach { quantity =>
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
  private def quantitiesOf(table: ComponentTable): Seq[String] =
    val spec = table.spec
    Role.of(spec) match
    case Role.PassiveBranch      => Seq("p0")
    // `p_max_pu` for `curtailment`, which is the headroom above the dispatch. A passive
    // branch has `s_max_pu` instead and no `p_max_pu` at all, so upstream's `curtailment`
    // raises a KeyError on one and reports nothing -- `inactive` and `standard-types` record
    // no `Line` or `Transformer` group for it, where `transmission` reports both.
    // One `efficiency<port>` per RECEIVING port, from the same enumeration `Lopf` walks.
    // `efficiency1` is PyPSA's plain `efficiency` and `efficiency2` and beyond are its
    // numbered ones, so `injection` can join `'efficiency' || port` and cover a branch with
    // any number of them. It read a single `efficiency` before, which is the two-port
    // assumption spelled as a quantity name.
    case Role.ControllableBranch =>
      Seq("p0", "marginal_cost", "p_max_pu") ++
        Topology.branchPorts(table).indices.drop(1).map(port => s"efficiency$port")
    case Role.Bus | Role.None    => Seq.empty
    case Role.Attached =>
      spec.name match
        case "Generator" => Seq("p", "marginal_cost", "p_max_pu")
        case "StorageUnit" =>
          Seq("p", "soc", "charge", "discharge", "spill",
              "marginal_cost", "marginal_cost_storage", "spill_cost", "p_max_pu")
        case "Store" => Seq("e", "p", "marginal_cost", "marginal_cost_storage")
        // Consumption. It has no solved series -- there is no load variable -- and yet it
        // has a `p`, because `assign_solution` writes one:
        // `_set_dynamic_data(n, "Load", "p", get_switchable_as_dense("Load", "p_set"))`.
        // So `p` here is the INPUT `p_set`, resolved per snapshot, and `entity.sign` of -1
        // is what turns it into a withdrawal.
        case "Load" => Seq("p")
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
      // A load has no variable, so its `p` is its own `p_set` -- which is what
      // `assign_solution` assigns, and it does NOT mask it by the activity window.
      //
      // So an inactive load still reports its full consumption to every metric here, while
      // `Lopf` leaves it out of the balance it is inactive in. That is upstream's own
      // asymmetry and it is reproduced rather than reconciled; it is also UNEXERCISED, since
      // no `loads.csv` in the goldens carries a `build_year`, a `lifetime` or an `active`
      // column. Read off `optimize.py:1281` rather than measured, and said so here.
      case "p" if component == "Load" => table.valueAt("p_set", id, t)
      case "p" | "p0"     => result.dispatch(component, id, t)
      case "soc"          => result.stateOfCharge(id, t)
      case "charge"       => result.charging(id, t)
      case "discharge"    => result.discharging(id, t)
      case "spill"        => result.spill(id, t)
      case "e"            => result.energy(id, t)
      // The input cost series. `valueAt` is what resolves a static column against a
      // time-varying one, the way every LOPF builder resolves it -- and `operational-limit`
      // is the fixture that needs it: its run-of-river bids 1/2/3/4 across four snapshots.
      // `efficiency` scales what a branch's receiving port injects, and it is `static or
      // series` upstream, so it belongs beside the other input series rather than on
      // `entity`. A passive branch has no such attribute and `injection` coalesces to 1.0.
      // `Topology.portEfficiency`, not `table.valueAt`, and the difference is one its own
      // comment warns about: `efficiency2` and beyond are NOT schema attributes, so a
      // multi-port link carries them as custom columns -- and a custom column can arrive as a
      // series with no static counterpart, which is the normal export shape when the static
      // value sits at its 1.0 default. Reading only `spec` and `static` makes such a port
      // LOSSLESS, putting power at the receiving bus the network never produced. One helper,
      // shared with the balance that has to agree with it.
      case Efficiency(port) =>
        Topology.portEfficiency(table, id, s"bus$port", t)
      case "marginal_cost" | "marginal_cost_storage" | "spill_cost" | "p_max_pu" =>
        if declares(table, quantity) then table.valueAt(quantity, id, t) else 0.0
      case other =>
        throw new IllegalStateException(s"no accessor for quantity '$other' on $component")

  /** `efficiency3` as the port number 3, so `valueOf` can match a whole family at once.
    *
    * An extractor rather than a `startsWith` and a `substring`, because the thing that must
    * not happen is `efficiency` matching with no port: port 1 is stored as `efficiency1` and a
    * bare one would be a quantity nothing writes and nothing could resolve.
    */
  private object Efficiency:
    def unapply(quantity: String): Option[Int] =
      if quantity.startsWith("efficiency") then quantity.drop("efficiency".length).toIntOption
      else None

  /** Whether a table carries an attribute at all, declared or present.
    *
    * The same test the LOPF builders use: a schema-declared attribute with no column still
    * has a default, and a column present without a declaration is still readable.
    */
  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
