# `network-stats`: DuckDB as a consumer of solved results

Written as a sketch, and now built: `modules/network-stats` carries seven tables, nine views
and all nineteen of PyPSA's `statistics` metrics. It exists to make one architectural claim
concrete: **DuckDB belongs downstream of the solve, not underneath the network.**

The sketch is kept as written, with a *What building it changed* section at the end recording
where it was wrong. Read the two together: four of the SQL snippets below are superseded, and
the corrections are listed there rather than edited in, because what a careful reading of
upstream got wrong is the part worth keeping.

## Why not underneath

The proposal this answers was to use DuckDB "instead of porting pandas et al". The
premise does not hold up, and the reasons are worth keeping because they are the reasons
this module is shaped the way it is.

- **There is no pandas port.** `network-model` is 1,870 lines, of which the dataframe-shaped
  part is `ComponentTable` (267) + `Schema` (217) + `CsvReader` (423) + `CsvWriter` (168)
  ≈ 1,075. No indexing, no alignment, no groupby, no broadcasting — the things that make
  pandas large are the things this does not have.
- **It would end the Scala.js build.** `networkModelJs` compiles the *entire* model source
  directory to JavaScript, excluding one 24-line file, and `demoJs` depends on it to build a
  `Network` and solve an LP in-page. DuckDB on the JVM is JNI; DuckDB-Wasm is a separate
  async Arrow API. No single Scala source targets both.
- **The per-column semantics are the product.** An empty cell is NaN, lowercase `nan` does
  not parse, an absent column means the schema default, NaN means "not set" for
  `state_of_charge_set` and "refuse" for a weighting, and `0,25` silently becomes two
  fields. `RoundTripSuite` asserts byte-for-byte reproduction of PyPSA's export across 36
  networks. A general CSV reader has its own answer to every one of those.
- **The build path is not relational.** `Lopf.build` walks columns and emits sparse-matrix
  rows. No joins, no group-bys, nothing to push down.
- **It does not unlock the one real format gap.** `NOTES.md` already records this: DuckDB's
  community repository has no HDF5 extension, so `.h5` stays unread either way.

## Why downstream

PyPSA's `statistics` is nineteen metrics over seven grouping dimensions:

```
capex  installed_capex  expanded_capex  overnight_cost  fom
optimal_capacity  installed_capacity  expanded_capacity
opex  system_cost  supply  withdrawal  transmission  energy_balance
curtailment  capacity_factor  revenue  market_value  prices

grouped by: carrier | bus_carrier | bus | country | location | unit | name
aggregated over time by: sum | mean | none
```

That is group-by-and-aggregate with a cross product of dimensions. Nineteen hand-written
folds, each with its own grouping parameter, is the version of this that rots. One fact
table and nineteen `SELECT`s is the version that does not — and a reader can check a metric
against PyPSA's definition by reading one query.

## Module

JVM-only. It must not be cross-built, and the comment should say so, because the next
person to add a `*Js` sibling for consistency would break the demo.

```scala
val duckdbVersion = "1.3.1.0"          // confirm against Maven Central before pinning

lazy val networkStats = project
  .in(file("modules/network-stats"))
  .dependsOn(networkLopf % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "network-stats",
    // JVM only, deliberately. DuckDB is a JNI library; DuckDB-Wasm is a different artifact
    // with an async Arrow API. There is no `networkStatsJs` and there should not be one --
    // `demoJs` links `networkModelJs`, and a native dependency anywhere in that graph ends
    // the browser demo.
    libraryDependencies += "org.duckdb" % "duckdb_jdbc" % duckdbVersion,
  )
```

## Schema

A star schema over one long fact table. Long rather than wide because the quantities differ
per component — a generator has one series, a storage unit has five — so a wide table is a
sparse column-per-component-per-quantity and every query has to know which columns exist.

```sql
-- One row per (component, entity, snapshot, quantity).
CREATE TABLE fact (
  component  VARCHAR NOT NULL,   -- 'Generator', 'Line', 'StorageUnit', ...
  entity     VARCHAR NOT NULL,
  snapshot   INTEGER NOT NULL,   -- index into Network.snapshots, not the label
  quantity   VARCHAR NOT NULL,   -- 'p' | 'p0' | 'soc' | 'charge' | 'discharge' | 'spill' | 'e'
  value      DOUBLE  NOT NULL
);

-- One row per entity: everything a grouping dimension needs.
CREATE TABLE entity (
  component  VARCHAR NOT NULL,
  entity     VARCHAR NOT NULL,
  carrier    VARCHAR,            -- NULL where the class declares none
  bus        VARCHAR,            -- bus, or bus0 for a branch (PyPSA's own choice)
  bus1       VARCHAR,            -- branches only
  country    VARCHAR,
  location   VARCHAR,
  unit       VARCHAR,
  nominal    DOUBLE,             -- p_nom_opt / s_nom_opt / e_nom_opt, the SOLVED capacity
  nominal_in DOUBLE,             -- the capacity the network arrived with
  extendable BOOLEAN,
  marginal_cost DOUBLE,          -- static only; the time-varying case lives in fact
  capital_cost  DOUBLE,
  PRIMARY KEY (component, entity)
);

-- One row per snapshot. The weightings live HERE, not multiplied into `value`.
CREATE TABLE snapshot (
  snapshot    INTEGER PRIMARY KEY,
  label       VARCHAR NOT NULL,  -- Network.snapshotLabel: '(2030, 0)' or '0'
  period      VARCHAR,           -- NULL on a flat index
  w_objective DOUBLE NOT NULL,   -- snapshot_weightings.objective
  w_stores    DOUBLE NOT NULL,   -- elapsed hours
  w_generators DOUBLE NOT NULL,
  p_objective DOUBLE NOT NULL,   -- investment_period_weightings.objective, or 1.0
  p_years     DOUBLE NOT NULL    -- investment_period_weightings.years, or 1.0
);

-- One row per bus, for bus_carrier grouping.
CREATE TABLE bus (
  name VARCHAR PRIMARY KEY,
  carrier VARCHAR,
  country VARCHAR,
  v_nom DOUBLE
);
```

### The weightings are columns, not pre-multiplied

This is the one design decision worth arguing for explicitly, because getting it wrong is
the defect this project has hit most often.

`value` holds the raw solver output. Every weighting that an aggregate needs is a column on
`snapshot`, and the query multiplies. The alternative — baking `w_stores` into a
`value` already converted to MWh — looks tidier and is how four separate bugs got in:
`objective` read where `years` belonged, `years` read where `objective` belonged, a weighting
applied on a flat index where PyPSA applies none, and a weighting applied to a period scope
where PyPSA sets it to 1.

With the factors as columns, a metric's SQL *states* which one it uses, and a reviewer can
check it against `define_*` in one reading. That is worth more than the multiplication saved.

## Loading

`DuckDBAppender` rather than `INSERT`, and a single in-memory connection per result:

```scala
object Stats:
  /** An in-memory DuckDB over one solved result. Closing it drops the database. */
  def of(result: LopfResult): Stats = ...

  // scigrid-de is the size that matters: 1423 generators x 24 snapshots plus 852 lines
  // gives ~55k fact rows. The appender does that in well under a second; `INSERT` row by
  // row over JDBC does not, and this is the only reason the appender is worth the API.
```

Shape of the API:

```scala
final class Stats private (conn: Connection) extends AutoCloseable:
  /** Raw SQL, for anything the named metrics do not cover. */
  def query(sql: String): Vector[Map[String, Any]]

  /** The named metrics, each one query. `groupBy` is a dimension column on `entity`. */
  def energyBalance(groupBy: String = "carrier"): Vector[(String, Double)]
  def capacityFactor(groupBy: String = "carrier"): Vector[(String, Double)]
  def curtailment(groupBy: String = "carrier"): Vector[(String, Double)]
  def opex(groupBy: String = "carrier"): Vector[(String, Double)]
  def capex(groupBy: String = "carrier"): Vector[(String, Double)]
  // ... the remaining fourteen
```

## Metrics as SQL

**Supply** — energy injected, in MWh. `w_stores` is the elapsed hours, so it is what turns
MW into MWh; `w_generators` is *not*, and that distinction is the fixture
`operational-limit` exists to pin.

```sql
SELECT e.carrier, SUM(f.value * s.w_stores) AS supply_mwh
FROM fact f
JOIN entity e USING (component, entity)
JOIN snapshot s USING (snapshot)
WHERE f.quantity IN ('p', 'discharge') AND f.value > 0
GROUP BY e.carrier ORDER BY supply_mwh DESC;
```

**Capacity factor** — output over what the capacity could have produced.

```sql
WITH produced AS (
  SELECT component, entity, SUM(value * s.w_stores) AS mwh
  FROM fact f JOIN snapshot s USING (snapshot)
  WHERE quantity = 'p' GROUP BY 1, 2
),
available AS (
  SELECT e.component, e.entity, e.nominal * SUM(s.w_stores) AS mwh
  FROM entity e CROSS JOIN snapshot s GROUP BY 1, 2, e.nominal
)
SELECT e.carrier, SUM(p.mwh) / NULLIF(SUM(a.mwh), 0) AS capacity_factor
FROM produced p
JOIN available a USING (component, entity)
JOIN entity e USING (component, entity)
GROUP BY e.carrier;
```

**Opex** — `w_objective` and `p_objective` both, because a cost carries the snapshot
weighting *and* the period discount. This is `Periods.objectiveWeight` in SQL, and the
query naming both is the point.

```sql
SELECT e.carrier,
       SUM(f.value * e.marginal_cost * s.w_objective * s.p_objective) AS opex
FROM fact f
JOIN entity e USING (component, entity)
JOIN snapshot s USING (snapshot)
WHERE f.quantity = 'p'
GROUP BY e.carrier;
```

**Capex** — one row per asset, not per snapshot, and the per-period factor is
`Expansion.costWeight`: the sum of `p_objective` over the periods the asset is active in.
That needs an activity relation, so a fifth table:

```sql
CREATE TABLE active (component VARCHAR, entity VARCHAR, period VARCHAR);

SELECT e.carrier,
       SUM(e.capital_cost * e.nominal * w.factor) AS capex
FROM entity e
JOIN (SELECT a.component, a.entity, SUM(DISTINCT s.p_objective) AS factor
      FROM active a JOIN snapshot s ON s.period = a.period
      GROUP BY 1, 2) w USING (component, entity)
WHERE e.extendable
GROUP BY e.carrier;
```

That last one is where SQL starts to fight back, and it is worth saying so: `SUM(DISTINCT)`
over a joined dimension is the kind of expression that is easy to get subtly wrong. If the
capex metrics read badly in SQL, they belong in Scala against `Expansion.costWeight`
directly — the module does not have to be all-or-nothing.

## Testing

The discipline is the project's existing one: **compare against PyPSA, never derive.**

1. `generate_goldens.py` grows a `statistics` block per network, recording
   `n.statistics.<metric>(groupby=...)` for each metric and each grouping it supports.
   That is a wide block, so it should be written per metric rather than as one blob, so a
   drift diff names the metric that moved.
2. `StatsSuite` loads each golden network, solves it, and asserts every metric against the
   recorded frame at the tolerances the dispatch comparisons already use.
3. Determinacy applies here too. A metric over a degenerate dispatch is a metric over one
   vertex of a face — `ac-dc-dispatch` prices and `scigrid-de` dispatch are already known
   to be that, so those networks need the same per-series `determined` treatment the
   NordPSA fixtures use rather than a blanket assertion.

The failure mode to design against: a metric that agrees because both sides are zero. Every
assertion should check the recorded value is non-trivial first, the way
`GlobalConstraintSuite` checks a cap actually binds before comparing an objective.

## What building it changed

The skeleton is now in the tree — `modules/network-stats`, with `supply` and `opex` and the
five tables below — so this section is the honest part of the sketch: what the SQL above got
wrong, measured against PyPSA rather than re-reasoned. Each was found by running the metric,
and each would have survived any amount of re-reading.

- **`supply` is weighted by `w_generators`, not `w_stores`.** The paragraph under *Metrics
  as SQL* argues the opposite — "`w_stores` is the elapsed hours, so it is what turns MW
  into MWh; `w_generators` is *not*" — and the reasoning is sound and the conclusion is
  backwards. `supply` delegates to `energy_balance`, which weights with
  `snapshot_weightings.generators` (`expressions.py:2305`). `operational-limit` is the
  fixture that settles it, as predicted, and it settles it the other way: it is the only
  golden whose three weightings differ (`generators` 3.0, the rest 1.0), and PyPSA reports
  120 MWh for a 40 MW dispatch at one snapshot. The snippet is left as written, with this
  correction beside it, because the shape of the error is the useful part.
- **`quantity IN ('p', 'discharge')` double-counts a storage unit.** A `StorageUnit`
  contributes both, so every storage figure came out at exactly 2x — 1,200 against 600 on
  `operational-limit`. It needs one quantity per component kind: `p` for `Generator` and
  `Store`, `discharge` for `StorageUnit`.
- **`opex` cannot read `marginal_cost` off `entity`.** It is time-varying upstream, and
  `operational-limit`'s run-of-river bids 1/2/3/4 across four snapshots while the static
  column holds the schema default and charges 0. So `marginal_cost` is a row in `fact`
  beside the solved series, and `opex` joins fact-to-fact.
- **`opex` multiplies each cost type by the variable PyPSA pairs it with**, not by one
  dispatch series: `marginal_cost_storage` by `soc`/`e`, `spill_cost` by `spill`, and — the
  one that bites — `marginal_cost` by `p_dispatch` for a `StorageUnit` rather than by the
  net `p`. Charging the net makes the metric **negative**: -6,668 against 25,328 on
  `storage-hvdc`.
- **Zero groups are dropped and absent carriers render `'-'`.** `drop_zero` defaults to
  true upstream and `nice_names` renders a missing carrier as a dash. Both failed on the
  group *set* rather than on a number, which is the right place to fail.
- **A `Store`'s carrier comes from its bus.** `calculate_dependent_values` rewrites an empty
  carrier from the bus on `Line`, `Link` and `Store` — `network/power_flow.py:779`, `:831`,
  `:835` — and `optimize` reaches it through `consistency_check`. `store-bank`'s stores are
  written with no carrier and PyPSA reports `Store|AC`. Generators are *not* on that list
  and keep the dash, so this cannot be done for every component.

Two things the *Testing* section planned for turned out differently.

- **Degeneracy is read from the golden, not listed in the suite.** `scigrid-de` already
  carries a `dispatch_note`, so `StatsSuite` skips on its presence. A network that becomes
  degenerate is then skipped without the suite being edited — which is the same argument as
  reading the network list from the manifest.
- **Multi-period networks are skipped, visibly and for now.** PyPSA returns a frame with a
  column per period there, so its keys carry a third part and its numbers are per period,
  while these queries sum over the horizon. Comparing them wants the queries grouped by
  period too. Detected from the key shape rather than from a list of names.

And one thing it did not plan for at all: **a statistics suite that iterates the manifest
reads goldens nothing else reads.** `ac-pf-pv` had carried a generator `p_set` for as long
as the LOPF had ignored it, and `opex` over that network is what finally compared the two —
2,650 here against 7,650 upstream. The LOPF refused it for a release and now **builds** it —
`FixedDispatch` — so `StatsSuite` stopped skipping that network and its `opex` is one of the
assertions that says the equality is right. Breadth of coverage found a correctness bug in
another module, and then confirmed its fix, which is an argument for the module that the
nineteen-metrics argument does not make.

## Every golden, every grouping

The skeleton compared two metrics on two groupings over 23 of 36 networks. It now compares
two metrics on **all seven** groupings over 36 of 39, and the three things that took are each
worth recording because none of them was in the plan above.

### Four of the seven dimensions are read off the bus, and four had nothing to compare

`carrier`, `bus` and `name` come off the component. `bus_carrier`, `country`, `location` and
`unit` map the component's bus through a column of `n.c.buses.static` — a different kind of
mistake to make, and one no fixture could catch: `location` and `unit` were carried by no
golden at all, and `country` only by `scigrid-de`, which is skipped for degeneracy. So
`statistics-dimensions` exists, with three buses, three bus carriers, two countries, two
locations and two units, arranged so every dimension splits for a component this module
covers. The Store on its hydrogen bus is what makes `unit` and `location` say anything:
without it that bus has only links attached, and branches are not covered in `supply`.

### `nice_name`, which is not a dimension and was the real gap

PyPSA's `carrier` grouper maps each carrier through `carriers.nice_name` where that is
non-empty, falls back to the raw name, and only then renders an empty carrier as `'-'`. No
golden set a `nice_name`, so a port that skipped the mapping entirely agreed with upstream on
all 38 networks — which this one did. `bus_carrier` goes through the same grouper rather than
reading the column, so it inherits the mapping too.

The chain is `COALESCE(NULLIF(COALESCE(NULLIF(nice_name, ''), carrier), ''), '-')`, and the
order matters in a way worth stating: upstream filters `nice_name[lambda ds: ds != ""]`
*before* mapping, so a carrier whose nice name is the empty string falls back to its own name
rather than to the dash. Only a component with no carrier at all becomes `'-'`.

### `country` is refused, because PyPSA raises

It is the one dimension that is not a declared attribute. The grouper reads
`n.c.buses.static.country` unguarded, so on a network whose `buses.csv` has no such column
upstream raises `AttributeError: 'DataFrame' object has no attribute 'country'` — recorded as
an error for 39 of the 41 goldens. Grouping them all under one empty country would be
answering a question PyPSA declines, so this declines it too. `StatsSuite` asserts the
refusal rather than inferring it from a missing golden block, because "no block and the module
raised" is also what a module raising for the wrong reason looks like.

### The period is the third level of the key

Eleven networks were skipped for having investment periods: upstream returns a frame with a
column per period there, so its numbers are per period and its keys carry the period as
their last level, while these queries summed over the horizon.

It is one `GROUP BY` term, added when the snapshot table has periods at all — not a
per-period variant of each metric, which for nineteen metrics would be thirty-eight queries
and is the duplication this module's whole argument is against.

### `drop_zero` is round-to-five-then-drop, and that is only as reproducible as the solver

The sketch had this as a threshold. Upstream's rule is `df.round(5)` followed by `df[df != 0]`
— `options.params.statistics.round` is 5 — so a group at 1e-7 is dropped because it rounds
away, not because it is small. The module reproduces that exactly, and the values it returns
are rounded to five decimals, because upstream's are.

What the rule is not is reproducible across solvers at that scale. On
`ac-dc-txvolume-exact` HiGHS leaves two idle generators at exactly 0.0 while Prima — a
first-order method at 1e-9 on a 60,000-variable LP — leaves them at 1.0e-5, which rounds to
0.00001 and survives. So the suite compares group *sets* above a floor, applied to both sides,
and says in the code that this is a tolerance on the solver rather than on the metric.

**The floor is absolute, at 1e-4, and getting that wrong was the instructive part.** The first
version was relative — 1e-6 of the largest group in the same metric — which happened to
suppress the artefact and would also have hidden five groups PyPSA *genuinely* reports:
`scigrid-de`'s onshore wind farms at 1e-5 to 1e-4 against a largest group of 61,728, a ratio
as low as 1.6e-10. Upstream's rule is five decimal places, so the smallest value it admits is
1e-5 **at any scale**; a floor in any other shape cannot be reasoned about against it.

Nothing separates a real 1e-5 group from a solver artefact, and the floor does not pretend to.
What makes it safe is four cases that pin what it removes rather than arguing that it is
small: exactly five golden groups across every network in the manifest, all five in the one network the
suite skips for a degenerate dispatch, against a smallest *compared* group of 0.6 — six
thousand times the floor. A future golden with a genuinely tiny group in a compared network
fails a test instead of being quietly dropped. The fifth of those five sits exactly on the
floor, and the first version of that pinned list said four: it was enumerated with a strict
`<` in a throwaway script while `significant` keeps `> floor`, which is the kind of
boundary a pinned list catches and a sentence about magnitudes does not.

### What is still not covered

- **The other metrics.** Each is a query, and the corrections above are the argument for
  adding them against measured goldens a few at a time rather than in one pass. All nineteen
  are done, in five batches; see *The capital metrics*, *The capacity metrics*, *The three
  metrics that needed no new table*, *`Load`, and the three metrics it unlocked* and *The last
  three, which read the DUAL* below for what each cost.
- ~~**A third port.** `bus2` and beyond are refused rather than dropped.~~ Built; see *A
  third port, which was refusing a shape it had never seen* below.
- **The `HAVING` clause's rounding.** Replacing `ROUND(..., 5) <> 0` with a `1e-9` threshold
  fails no test, because the suite's solver floor hides exactly the artefacts the rounding
  removes. Measured and stated rather than counted as coverage; the rounding of the returned
  *value* is separable and is asserted.

## The capital metrics, and the seventh thing the sketch had wrong

`capex`, `installed_capex` and `expanded_capex` are the first metrics here that are **not
sums over snapshots**: a capacity is one number for the whole horizon, so there is no
snapshot axis to group by. Three things came out of building them, and the first was in the
sketch above.

### `capex` is not `Expansion.costWeight`

The sketch said it was — "the per-period factor is `Expansion.costWeight`: the sum of
`p_objective` over the periods the asset is active in" — with a `SUM(DISTINCT s.p_objective)`
query to match, and a note that this is where SQL starts to fight back.

`costWeight` is one factor per asset and it is what the **objective** charges capital with.
The statistic is reported **per period**, each period carrying its own weighting:

```
capex[group, period] = SUM over assets active in that period of
                       nominal_opt * capital_cost, times that period's objective weighting
```

Summing the statistic over periods recovers `costWeight`, so the two agree about the total
and disagree about every breakdown — the shape of error a single-period fixture cannot see.
Derived from the goldens and checked on two fixtures;
`investment-periods-expansion` has weightings 1.0 and 0.5 and a generator built in 2040:

```
2030   (old 100x0 + wind 60x120)                x 1.0  =  7200
2040   (old 0 + wind 7200 + late 40x60 = 9600)  x 0.5  =  4800
```

The `SUM(DISTINCT)` the sketch worried about is not needed either. A `capital_share` view
supplies one row per `(entity, period)` an asset is charged for, with that period's weight,
and exactly one row per entity with weight 1.0 on a flat index — where the `active` table is
empty, because there is no period to be active in. Two sums over that view are the whole of
`capex` and `installed_capex`.

### `expanded_capex` reports exact zeros, and `drop_zero` does not stop it

Upstream is `capex.sub(installed_capex, fill_value=0)`, and both operands have already been
rounded and zero-dropped by then — `_apply_option_kwargs` runs inside each metric, not on the
difference. So a group whose expansion is nothing comes back as `0.0` rather than being
omitted, and on four goldens (`ac-dc-co2`, `ac-dc-dispatch`, `storage-hvdc`,
`storage-cyclic-co2`) every non-extendable group does.

This module computed it as one query over `nominal - nominal_in` with the usual
`HAVING ... <> 0`, which drops them — **and agreed with every assertion anyway**. The suite's
own floor filters a zero off both sides, so the group sets matched, and `getOrElse(key, 0.0)`
made the absent values compare equal. The agreement was accidental. It is now two queries and
a subtraction, like upstream, and `round(a) - round(b)` is not `round(a - b)` in the fifth
decimal either.

### The suite is driven by the recorded metric list

It named `supply` and `opex` inline, so three new metrics would have been recorded in every
golden, read by nothing, and reported as a pass. The metric list now comes off the goldens
like the grouping list and the network list, with a case asserting that every recorded metric
has an implementation.

### What is still not covered

Nothing, now. The nine named here when this section was written have since landed in three
batches — see *The three metrics that needed no new table*, *`Load`, and the three metrics it
unlocked* and *The last three, which read the DUAL*.

`overnight_cost` and `fom` are done, and `overnight_cost` is the first metric here that
**refuses a network**. It is the inverse of `capex`: the column where it is set, and
`capital_cost / (annuity x nyears)` where it is not — and that back-calculation raises
upstream without `discount_rate` and `lifetime`, which `discount_rate` defaults to NaN.
Every golden priced by `capital_cost` alone records an error for the metric rather than a
frame, `ac-dc-meshed` naming six generators and `store-bank` one store, so the module refuses
the same networks rather than summing over the assets it can price.

`fom` carries an unclosed hole, stated rather than counted: the only golden with a non-zero
`fom_cost` is flat-index, so the claim that the metric is unweighted is an equivalent
mutant — measured, not assumed. Closing it needs `fom_cost` on a multi-period golden, and
`tx-cost-periods` is the candidate.

## The capacity metrics, where the two "expanded" metrics disagree

`optimal_capacity`, `installed_capacity` and `expanded_capacity` are the same two sums over
`capital_share` as the capex family, without the cost factor — and with two differences that
no amount of reading the capex code would have suggested.

### The period weighting is on the cost, not on the capacity

`capex` multiplies each period by its `objective` weighting. The capacity metrics do **not**:
there is no `has_investment_periods` branch in `optimal_capacity`, and the goldens agree.
`investment-periods-expansion` has weightings 1.0 and 0.5 and reports 160 and 200 MW, which is
`old 100 + wind 60` and then `+ late 40`, nothing scaled.

Obvious once stated — a megawatt is not a cost, so there is nothing for a discount factor to
discount — and not obvious from the four metrics sharing one view. A port that passed the
weight through would be wrong by 2× on that fixture's second period and right on every
single-period network.

### `expanded_capacity` clips and `expanded_capex` does not

```
expanded_capex     = capex.sub(installed_capex, fill_value=0)
expanded_capacity  = optimal.sub(installed).where(optimal.abs() > installed.abs(), 0)
```

So on `ac-dc-meshed`, which retires gas from 150,000 MW installed to 982.03 chosen, the two
metrics report the same retirement as **-21,109,107.32** and as **0**. Both numbers are in
that network's golden. The clip reads like a safety net and is load-bearing, so it has a case
of its own asserting the disagreement.

`reindex(optimal.index)` rather than a union is the second difference: a group present only in
`installed_capacity` is dropped here where `expanded_capex` would carry it as a negative.
Reproduced, and **unexercised** — it needs an extendable whose optimum is exactly zero beside
a non-zero `p_nom`, and no golden has one.

### Capacity counts at port 0

`optimal_capacity` multiplies by `port_efficiency(n, c, port)`, and the goldens show branch
capacity counted once at `bus0`: `ac-dc-meshed`'s seven lines report 280,000 MW installed,
their plain `s_nom` sum rather than twice it, and `statistics-dimensions`' electrolyser
reports 100 MW rather than 0.7 × 100. The same `bus0` placement `opex` uses — and not the
per-port treatment `supply` needs, which is the next thing to build.

## Branches in `supply`, and the determinacy measurement that had to come first

`supply` now sums injections at **every port**, which is `energy_balance` with
`at_port="all"`. Three schema additions carry it — a `port` view giving each port its bus, an
`injection` view giving each port its signed contribution, and a `dimensions` view keyed by
port as well as entity, so a flow is grouped by the bus that *receives* it while `opex` and
the six capital metrics stay pinned at port 0.

The sign rule is the bus balance's own: `-p0` at port 0, `+p0 × efficiency` at port 1. For a
line the efficiency is 1, so the positive part sums to `|p0|` — `ac-dc-meshed`'s `Line|0` is
3,846.70123 both ways. With an efficiency that is not 1, `statistics-dimensions`'
electrolyser reports 25.0 against a 35.71429 MW flow. `efficiency` is a row in `fact` rather
than a column on `entity`, for the same reason `marginal_cost` is: it is `static or series`.

A branch with `bus2` was **refused** at this point. `Lopf` builds one — `efficiency2` is read
there — and this schema carried two ports, so such a network would be short by whatever the
third port injects. No golden had one, so it refused a shape it had never seen, deliberately.
That refusal is lifted now; see *A third port* below for the fixture that lifted it.

### Covering branches broke four goldens, none of them because of this code

`StatsSuite` had excluded `Line`, `Link` and `Transformer` from every comparison and asserted
that the excluded set was *exactly* those three — so that the day branches arrived, the
assertion would fail rather than the agreement widening in silence. It failed. Measured with
HiGHS simplex against interior point, crossover and presolve off:

```
ac-dc-co2           objective identical, generator dispatch differs by 537.96 MW
storage-hvdc        dispatch to 2e-4, line flows differ by 165.08
storage-cyclic-co2  dispatch to 1e-4, line flows differ by 136.46
ac-dc-txcost        dispatch determinate; the expansion allocation is a face — its
                    cost limit binds at 4800 and the per-line split is free
```

Two degenerate dispatches, two degenerate flow patterns, one degenerate capacity allocation.
**Not one was marked**, because until branch coverage nothing compared a branch quantity.

### Determinacy is measured per group, because every coarser rule throws away something real

`DEGENERATE_DISPATCH` is a hand-written note per **network**, which is what `scigrid-de` was
skipped under. Skipping those four whole would have cost every generator comparison they
carry, which is most of what they are for.

So `generate_goldens.py` solves every network twice and compares each metric **group by
group**, recording only the keys that moved — 1,445 metric/grouping pairs measured, and the
block is sparse, naming exactly what must not be compared. On three of the five affected
networks the undetermined groups are *only* branches, which is what let branch coverage ship.

Three things about the measurement:

- **The tolerance is per element against its own magnitude**, `1e-2 + 1e-4 × max(|a|,|b|)`,
  which is the shape the suite compares at. A tolerance scaled by the largest group in a
  metric instead called a small group determinate while the suite rejected it:
  `ac-dc-txcost`'s `capex|bus` has a 4,396 group and a 10.36 one, and the small group moves
  by 14.48 — swallowed by a metric-scaled tolerance of 1,143.
- **The floor is chosen from the measured distribution**, not picked. The worst agreement on
  anything that is a point is 7e-3, on `ac-dc-meshed`'s flows; a face moves by 14 to 538. One
  group sits in between: `storage-hvdc`'s `Generator|Gas 2` capex moves by 0.0236, 2.4× the
  floor, and is treated as a face because that direction costs one skipped group while the
  other costs a comparison that breaks on a tie-break.
- **It skips whole component kinds, not individual keys.** The generator can only name keys
  that moved between PyPSA's own two solves, so it cannot name one that *this* port produces
  and neither of PyPSA's does — and on a degenerate flow pattern that happens, because which
  lines carry anything at all is part of the face. Still far finer than a network skip.

The measurement is reproducible across two regenerations of the same PyPSA — which is what
was checked when it landed, and not enough.

**It carried a margin, and the margin had to go.** Each series and capacity verdict recorded
an `excess`: how far past the per-element tolerance the two solves were, so a reader could
see whether something missed by a hair or by 500 MW. An `excess` near zero is a difference of
two solver outputs, so it moves with any change to either. Measured across the PyPSA
1.3.0 → 1.4.0 step, three networks — `investment-periods-expansion`, `ramp-limits`,
`transformer-taps` — reported as drifted with their verdicts unchanged and nothing else moved,
the whole difference being `excess` values shifting by 7e-7 to 1.6e-2 relative. Noise in the
one tool whose job is to tell noise from signal.

So the series and capacity blocks carry the verdict alone, the objective gap between the two
solves went the same way, and in the end **`results_drift.py` stopped comparing the block at
all** — three rounds of the same lesson, each one smaller than the last.

The third round is the one with the sharpest evidence. The first drift run after the PyPSA
1.4.0 pin reported 114 differences across `ac-dc-co2`, `ac-dc-txcost`, `storage-cyclic-co2`
and `storage-hvdc` — exactly the networks the block marks degenerate, minus `scigrid-de`
which the comparator already covered — and **every reported difference was inside this
block**. Nothing in `optimize` or `statistics` had moved. The goldens are macOS/aarch64 on
Python 3.14 and the runner is Linux/x86_64 on 3.13, and the two disagree about which groups
are a tie-break.

The surviving content is the group *membership*, and that cannot be made
platform-independent, because it is a statement about the solver rather than about PyPSA's
model. So it is excluded from drift by name, and nothing is left unguarded: `StatsSuite` pins
the block's content directly, and those cases read the committed goldens, so they hold on
whatever platform runs the suite. A verdict flipping is a test failure there, which is the
right place for it — it is not news about upstream. The per-**group** deviations in the `statistics` block stay,
because those are the ones that matter and the ones that hold still: 14 to 538, three orders
above the tolerance.

Dropping the series margins first is what exposed the objective gap as the remaining churn —
and with both gone the 1.3.0 → 1.4.0 drift narrows from four networks to the **three that
really moved**, which is the report doing its job:

```
inactive                      68,407.58 -> 19,800.00   upstream fixed the Kirchhoff bug
investment-periods-expansion  constant 0.0 -> 3,900.0  upstream now appends it under
                                                       multi-invest; total_system_cost is
                                                       unchanged at 20,175
ramp-limits                   50,240.00 -> 48,640.00   a genuinely cheaper answer
```

## The three metrics that needed no new table

`system_cost`, `curtailment` and `transmission` are the batch that fits the schema as it
already stands, which is why they came before the six that do not. One is a composition of
metrics already here, one needed a single new input series, and one needed a single new view.

### `system_cost` is a union of three finished metrics, not a query

Upstream is `reduce(lambda a, b: a.add(b, fill_value=0), (capex, fom, opex))`, and each
operand has already been rounded to five decimals and zero-dropped by its own
`_apply_option_kwargs` before the addition happens. So this is `expanded_capex`'s shape
rather than `opex`'s: composed in Scala over three finished maps, because `round(a) + round(b)`
is not `round(a + b)` in the fifth decimal, and because nothing re-drops the sum — a group
whose terms cancel is reported as an exact zero.

`annuitised-capital` pins all three terms on one asset. Its `new` generator is 4,290.52586 of
capex, 750 of fom — the only non-zero `fom_cost` anywhere in the goldens — and 4,818,000 of
opex, against upstream's 4,823,040.52586.

**It is not the objective, and the gap is the one `capex` already has.** Three cost terms out
of PyPSA's own three, so `system_cost` inherits every divergence those carry: `capex` reads
the `capital_cost` *property*, which annuitises an `overnight_cost` and leaves `fom_cost`
out, and `fom` is unweighted where `Expansion.periodizedCost` weights it. A multi-period
network with a `fom_cost` therefore has a `system_cost` that is not its objective value.
That is upstream's arithmetic, and worth stating because "total system cost" reads like the
objective by name.

### `curtailment` decides its own component set, and the join is what decides it

`(p_max_pu × p_nom_opt − p).clip(lower=0)`, weighted by `generators` and summed.

The clip is an **equivalent mutant** — dropping it fails no comparison, measured — and it is
not a coincidence of the fixtures. The dispatch upper bound is `p <= p_max_pu × p_nom` and
`p_nom_opt` *is* that `p_nom`, so the difference is non-negative for any feasible solution; for
a storage unit the bound is on `p_dispatch` while `p` is `p_dispatch − p_store`, smaller still.
A charging unit therefore reports its charging as curtailment on top of its headroom, and
upstream does the same, since its own clip is just as inert there. It is reproduced because the
one thing that *would* make it fire is a `p_nom_opt` disagreeing with the bound the dispatch
was solved against — a defect this would otherwise report as a negative number rather than as a
failure.

The interesting part is **which components are in it**. Upstream wraps the body in
`@pass_empty_series_if_keyerror` and then asks for `get_switchable_as_dense(c, "p_max_pu")`,
so a component without that attribute contributes an empty series and no error. That is the
entire membership rule — a passive branch has `s_max_pu`, which is not the same name, and
`Store` and `Load` have neither.

So this query reproduces the *rule* rather than restating the list it produces: `WHERE
f.quantity IN ('p', 'p0')` admits every dispatch series, and an **inner join** to the
`p_max_pu` rows of `fact` is what drops a component. `quantitiesOf` emits those rows for
exactly the components that declare one, so a controllable branch added upstream is covered
without this SQL changing, and a one-port added without a `p_max_pu` is excluded for
upstream's own reason instead of by omission from a hardcoded `IN`. Measured: `inactive` and
`standard-types` record no `Line` or `Transformer` group here while `transmission` reports
both of them.

**Nothing masks by investment period.** An asset that does not exist yet has `p = 0` and a
`p_nom_opt` that is already its full capacity, so its whole potential is reported as
curtailment in the periods before it is built. There is no `active_assets` term in upstream's
`curtailment` either, and `investment-periods-expansion` is the golden that shows it.

**It is also the most tie-break-sensitive metric here**, which was not obvious until it
landed. It reads the *distance* between a dispatch and its own headroom, so a generator
sitting anywhere on an optimal face moves it, where `supply` and `opex` only move when the
total moves. The determinacy measurement went from five networks to seven on this metric
alone: `ac-dc-meshed` and `annuitised-capital` are determinate in every other metric and have
a generator whose curtailment moves by 0.012 between the two solves.

### `transmission` is signed, and that is the whole difference from `supply`

`p0 × generators`, summed at port 0 only — `at_port="bus0"` is this metric's own default, so a
branch is counted once at the end it leaves and the receiving port's efficiency never enters.

`supply` keeps the positive part of each port's injection, so a line that sends one way and
receives the other reports the *sum*. This reports the **difference**: `ac-dc-meshed`'s
`Line|0` is −3,795.26789, which is 25.717 − 3,820.985, and `inactive` reports `Line|AC` at
−715 beside a `Transformer|-` at +420. A metric that took the absolute value would agree with
upstream on every radial network and on nothing meshed.

Which branches count is a new view, `transmission_branch`, and it is PyPSA's own definition:
`get_transmission_branches` maps every `bus*` column through `buses.carrier` and keeps a
branch where more than one end matches the same value. A line between two AC buses is
transmission and an AC/DC converter link is not — `ac-dc-meshed` reports its seven lines and
none of its three converters, and `statistics-dimensions` reports `tie` (AC to AC) and not
`elec` (AC to H2).

A network whose `buses.csv` has **no `carrier` column at all** is therefore entirely
transmission, because `Bus.carrier` is declared with a default of `AC` and every bus gets it.
`inactive` and `standard-types` report every line *and* transformer, which is in their
goldens. The view's `COALESCE(..., '')` is not what produces that — it guards a NULL this
loader does not currently produce, and dropping it is a measured equivalent mutant. It is kept
because the failure it would admit is silent: comparing two NULLs in SQL yields NULL rather
than true, so a bus carrier that ever did arrive unset would empty the view and make every
`transmission` group disappear.

### What the mutation run says about the three

Seventeen mutants, fifteen killed. The ones worth naming are the ones that distinguish a
reading of upstream from a plausible alternative:

| mutant | killed by |
|---|---|
| `curtailment` weights with `objective` | `energy-budget`, 5 cases |
| `curtailment` forgets `e.nominal` | 39 cases |
| `curtailment` charges the installed capacity | `ac-dc-meshed`, 9 cases |
| `curtailment` admits a component with no `p_max_pu`, at 1.0 | 22 cases |
| `curtailment` drops the controllable branches | 13 cases |
| `curtailment` drops the one-ports | 39 cases |
| `transmission` takes the magnitude | 17 cases |
| `transmission` counts every branch | 7 cases |
| `transmission` weights with `objective` | `link-delay-wrap`, 1 case |
| `transmission` reads the receiving port's dimensions | 22 cases |
| `system_cost` drops the `fom` term | `annuitised-capital`, 1 case |
| `system_cost` drops the `opex` term | 39 cases |
| `system_cost` charges the installed capacity | 14 cases |
| `system_cost` intersects the three key sets instead of unioning them | 39 cases |
| `transmission_branch` compares `bus0` to itself | 7 cases |

Two survived, and both are stated above rather than counted as coverage: `curtailment`'s
`clip(lower=0)`, which cannot fire for a feasible solution, and `transmission_branch`'s
`COALESCE`, which guards a NULL this loader does not produce.

Two more were discarded as **vacuous** rather than recorded as survivors, which is worth a
line because they first read as equivalent mutants. Loosening `curtailment`'s join to a `LEFT
JOIN` on its own changes nothing — `pu.value` is then NULL, the arithmetic is NULL, `SUM`
skips it and the `HAVING` drops the group — and adding `COALESCE(pu.value, 1.0)` on its own
changes nothing either, because an inner join never produces the NULL it would catch. Only
both together admit a component with no `p_max_pu`, and that mutant is killed in 22 cases. A
mutant that does not alter the program is not evidence about the test suite.

## `Load`, and the three metrics it unlocked

`energy_balance`, `withdrawal` and `capacity_factor` take the module to sixteen of nineteen.
All three needed the same thing first — a component that had deliberately contributed nothing.

### A load's `p` is an input, and it has one because `assign_solution` writes it

`quantitiesOf` listed `Load` explicitly and returned no series for it, on the stated grounds
that consumption "enters the bus balance as a constant". True of the LOPF and false of the
statistics layer: upstream writes

```python
_set_dynamic_data(n, "Load", "p", n.get_switchable_as_dense("Load", "p_set", sns))
```

so a solved network has a `loads_t.p` and every metric over a one-port reports it. A load now
contributes one `fact` row per snapshot carrying its own `p_set`, resolved against a
time-varying column the way every other input series here is.

It is **not masked by the activity window**, and that is upstream's own asymmetry rather than
an oversight here: `get_switchable_as_dense` has no `active_assets` term, so an inactive load
still reports its full consumption to every metric while `Lopf` leaves it out of the balance
it is inactive in. Reproduced, and **unexercised** — no `loads.csv` in the goldens carries a
`build_year`, a `lifetime` or an `active` column, so this is read off `optimize.py:1281`
rather than measured, and is stated here rather than counted as coverage.

### `sign` is a column, and that is what the branch/one-port split now reads

`energy_balance` is

```python
sign = -1.0 if c in n.branch_components else n.c[c].static.get("sign", 1.0)
p    = sign * n.c[c].dynamic[f"p{port}"]
```

so the +1/-1 is an **attribute**, not a decision per component name: +1 for a generator, a
storage unit and a store, −1 for a load, and whatever a network says otherwise. `entity` now
carries it, NULL for a branch — which makes `e.sign IS NULL` upstream's own `c in
branch_components` test wherever this schema needs it, rather than a component list repeated in
two views.

### `supply`, `withdrawal` and `energy_balance` are one query with three filters

Upstream is one function with a `direction` argument, and so is this: a `SUM` over the
`injection` view weighted by `generators`, with `WHERE i.value > 0`, `WHERE i.value < 0`
negated, or no filter at all. `supply` had been its own query and is now three lines shorter.

**A storage unit injects its net `p`, not its discharge.** `injection` read `discharge` for
two releases and agreed with upstream on every golden — which is what an equivalent mutant
looks like. `supply` keeps the positive part, and a unit that charges and discharges at the
same snapshot pays round-trip losses for nothing, so no optimum in the goldens does it. The
difference stops being invisible the moment the whole signed series is reported, because
`clip(p, 0)` and `discharge` differ exactly when both halves run at once.

### `capacity_factor` is the first quotient here, and the first metric to break the probe

```
capacity_factor = mean_t |operation| / optimal_capacity
```

Three things about it are upstream's and none is what the name suggests.

**The mean is weighted and the weights are normalised per period.**
`_aggregate_timeseries(..., agg="mean")` does not average — it divides the weighting column by
its own sum and then sums, and on a multi-period index that division is
`weights.groupby(level=0).transform(lambda w: w / w.sum())`. So each period's factor is a mean
over that period's snapshots alone.

**The numerator is `get_operation`, a different series per component.** `p` for a one-port and
a controllable branch, `p0` for a passive one, and **`e` for a `Store`** — the energy *level*.
A store's capacity factor is therefore its mean state of charge against `e_nom_opt`, not a
utilisation of any power rating. Reproduced rather than corrected.

**A `Load` reports NaN, and that is the answer.** Upstream divides by
`optimal_capacity.reindex(df.index)` and a load has no `p_nom`, so the reindex supplies NaN and
the division propagates it: 1,291 NaN groups across 31 of the 41 networks, every one of them
`capacity_factor` for a load. The suite asserts them as NaNs rather than tolerating them —
`assertEqualsDouble` fails on `|NaN − NaN|`, and reporting a number where PyPSA reports NaN is
exactly the invented answer this suite exists to catch. `LopfResult.capacity` had to stop being
called for a load to get there: it reads `Expansion.nominalAttribute` unguarded and threw
`key not found: Load` the moment loads were loaded.

### The determinacy probe is calibrated in MWh, and a quotient divides that away

This is the interesting failure. `DETERMINACY_ABS` is **1e-2 and absolute**, calibrated by
measuring the worst agreement on anything that is a point — 7e-3, on `ac-dc-meshed`'s flows.
Right shape for an energy or a cost; three orders too loose for a ratio.

Measured on `storage-cyclic-co2`: its `Storage 1` is flagged under both `energy_balance|name`
and `withdrawal|name` at 1.64 and **not under `capacity_factor|name` at all**, because 1.67 MWh
over 36 weighted hours and 25 MW of capacity is 0.0019. The same vertex, reported in two units
— and `storage-hvdc` shows it again at 99.24 MWh and 0.0018. Without a rule for it, this module
would have had to either report a different number from upstream or pretend the metric agreed.

So a quotient over the dispatch **inherits the verdict of every metric over that dispatch**.
Reading one of them is not enough, and that was the first attempt: a branch contributes `-p0`
at one port and `+p0 × efficiency` at the other, so `energy_balance` nets a line to nearly
nothing and says nothing about whether its flow moved, while `transmission` — the signed sum at
one port — says so plainly. Measured, the union over the six dispatch metrics covers every kind
that disagrees on all four degenerate networks with nothing left over, where `energy_balance`
alone missed `Line` and `Link` on all four.

**Not** a union across every metric of any kind. A face is a property of the solution rather
than of a metric, so "loose anywhere means loose everywhere" is true in principle — and it
would take `ac-dc-meshed`'s generators out of `capex` and `supply` because one `curtailment`
group moved by 0.0122. The narrow rule costs only the metric with the measurement problem.

The proper fix — a determinacy tolerance in each metric's own units — has since landed, and
this patch is gone with it. See *Retuning the probe* below.

### What the mutation run says about these three

Seventeen mutants, twelve killed. The ones that matter:

| mutant | killed by |
|---|---|
| `energy_balance` keeps only the positive part | 39 cases |
| `withdrawal` negates the whole balance, not its negative part | 39 cases |
| the balance weights with `objective` | `energy-budget`, 5 cases |
| the injection ignores the `sign` column | 39 cases |
| a storage unit injects its discharge again | `operational-limit-retired`, 5 cases |
| the operation of a store is its power, not its energy | `statistics-dimensions`, 3 cases |
| the operation keeps its sign | 19 cases |
| `capacity_factor` sums instead of averaging | 37 cases |
| `capacity_factor` normalises over the horizon, not per period | `fixed-dispatch-periods` |
| `capacity_factor` divides by the installed capacity | `ac-dc-meshed`, 14 cases |
| `capacity_factor` drops a no-capacity group instead of reporting NaN | 29 cases |
| a load injects with a positive sign | 40 cases |

The second-to-last pair is the one worth dwelling on. **The storage-unit mutant is now killed,
and it was an equivalent mutant for two releases** — which is the whole argument for adding
`energy_balance` rather than stopping at `supply`.

Four survived and each is named rather than counted:

- **`capacity_factor` unioning the capacity index instead of reindexing on it.** An asset with
  a capacity and no operation has a numerator of exactly zero, which `HAVING` has already
  removed and which `drop_zero` removes from upstream's frame too, so the union adds back a
  `0 / capacity` that `StatsSuite`'s floor filters off both sides.
- **A load reading a capacity of 0 instead of none.** `optimal_capacity` sums it to zero and
  `drop_zero` removes the group either way, so NULL and 0.0 are the same answer here. What
  makes the guard load-bearing is not a mutant: without it `LopfResult.capacity` throws
  `key not found: Load`.
- **A load's `p` masked by the activity window.** Exactly the hole stated above — no
  `loads.csv` in the goldens carries a `build_year`, a `lifetime` or an `active` column, so
  the choice is read off upstream and measured to be untested.
- **The `sign` fall-back of 1.0 for a one-port that declares none.** Unreachable: `sign` is a
  declared attribute on all four of PyPSA's one-ports, so `declares` is always true and the
  fall-back exists only because `static.get("sign", 1.0)` is what upstream writes.

And one mutant was discarded as **vacuous** before it could be counted: mutating that same
unreachable fall-back instead of the live branch, which is how the load's sign first read as
untested. A mutant that does not alter the program is not evidence about the test suite.

## The last three, which read the DUAL

`revenue`, `market_value` and `prices` take the module to nineteen of nineteen. They are one
batch because they share one input that no metric before them touched: the bus marginal price.

### A nodal price is a dual variable, and that changes what can be compared

Every other metric here reads the primal. These three read the dual, and a dual face is far
more common than a primal one: any bus whose marginal unit sits at a bound has an **interval**
of valid prices, and two solvers pick different points in it while agreeing on the objective,
the dispatch and every primal metric. `prima-core/NOTES.md` has recorded this for the LOPF
since prices were built — "nodal prices agree wherever the dual is unique and are a different
point of the same optimal dual face elsewhere" — and these three are the first metrics to
inherit it.

So the suite measures it rather than assuming it. `dualAgrees` compares
`LopfResult.marginalPrice` against the golden `optimize.bus_marginal_price` cell by cell at the
same `1e-4` relative tolerance the metrics are compared at, and skips the three on a network
where they differ. **Measured, and the spread is three orders wide**: the worst disagreement is
below 1e-6 on 31 of the 39 goldens — solver tolerance, nothing more — and on the rest it is a
different vertex:

```
ramp-limits       22.31   a@4             PyPSA 40.0   this port 62.31
link-delay-wrap   15.00   b@3             PyPSA 15.0   this port  0.0
link-delay         5.00   a@0             PyPSA  5.0   this port  0.0
ac-dc-dispatch     3.87   Bremen@8        PyPSA  0.11  this port  3.98
ac-dc-txvolume     0.145  Norwich DC@8
ac-dc-co2          0.114  Norway@5        PyPSA  0.11  this port  0.22
annuitised-capital 0.0086 a@1
```

Those are not near misses and no tolerance makes them one. Measured per network rather than
pinned because a list someone maintains goes stale in the direction that looks like a pass — a
port that started getting a price wrong would keep comparing the metric over it — and the
determinacy case pins which networks the measurement rejects so it cannot start rejecting
everything either.

#### They are faces, and that had to be measured rather than assumed

The two zeros read like a *missing* dual when the list was first written, and that reading was
wrong. Two facts make them faces, and a case now asserts both:

- **Strong duality holds on all seven.** The dual gap is between 1.3e-13 and 1.8e-9 relative,
  and each objective matches PyPSA's to five decimals. Two objectives agreeing is what makes a
  dual optimal rather than merely feasible.
- **Every price that is *forced* agrees with PyPSA exactly.** None of the differing cells is at
  a bus with a generator strictly inside its bounds — except on `ac-dc-dispatch` and
  `ramp-limits`, where a CO2 multiplier of −2178.29 and the ramp multipliers enter the reduced
  cost, so an interior unit does not pin its bus price to its marginal cost there. On the two
  delay networks there is no global constraint and no ramp limit, so `price <= marginal_cost`
  is the whole condition at an idle generator and it holds at all fourteen differing cells.

So the zeros are the bottom of an interval and PyPSA's 5 and 15 are the top. `link-delay` is
about as degenerate as an LP gets: the load is 100 MW at one snapshot, `delay = 1` puts it out
of the cheap generator's reach entirely, and the answer is "run the expensive local unit once
and nothing else ever" — every other column sits at zero, so most prices are bounded above and
determined by nothing.

`LopfSuite` already made this argument for `ac-dc-dispatch`, in almost these words and for the
same reason. What was missing was the argument over the other six, which is now a case driven
by the pinned list: the skip is only honest if the dual it declines to compare is optimal.

### `revenue` is the `injection` view priced, and its weighting is the odd one

`sign * p_port * marginal_price[bus_of_port] * w_objective`, summed, at every port. The signed
quantity being priced is exactly what `energy_balance` sums, so a generator earns, a load pays,
and a branch pays at the end it leaves and earns at the end it reaches.

`w_objective` **alone**, where `energy_balance` takes `w_generators` and `opex` takes
`w_objective` times the period's. Not an inconsistency: `assign_solution` divides
`buses_t.marginal_price` by the snapshot weighting *times* the period's, so the stored price is
already net of both and multiplying only the snapshot half back in discounts the revenue exactly
once. `LopfResult.marginalPrice` divides by the same product, so the arithmetic matches term for
term.

### `market_value` is a ratio of two MEANS, which is a factor nobody would guess

```
rev   = revenue(groupby_time="mean", at_port="all")       # objective weighting
denom = |get_operation| averaged, at_port=[0]             # generators weighting
df    = rev / denom
```

`market_value`'s own `groupby_time` default is `"mean"`, and it passes that down to `revenue` as
well — so **each half is normalised by its own weighting column's sum**. Where the two columns
differ the result is not the ratio of the sums: `operational-limit` has `generators` at 3.0
against `objective` at 1.0, so the totals are 12 and 4 and the sums reading is a third of the
answer — 4.1667 against upstream's 12.5. `energy-budget` is the same mistake at a factor of two.

That factor is the *only* thing wrong with the sums reading, which is what makes it worth
naming: `revenue` on its own defaults to `"sum"` and agrees with upstream exactly, so a port
that reads `market_value` as `revenue / operation` gets the numerator right and the metric
wrong.

**The port asymmetry is where the NaNs come from.** `revenue` counts every port and the
denominator counts port 0 only, so under a grouping read off a bus the numerator has keys the
denominator does not — a branch's `bus1` appears in `rev` and never in `denom` — and the
division leaves NaN. 1,123 non-finite `market_value` groups across the goldens, against
`capacity_factor`'s 1,291, and they are answers rather than gaps.

**And some of them cannot be reproduced.** `market_value` is `0 / 0` at any group whose asset
did not operate, and whether a solved quantity is *exactly* zero is decided by the solver:
PyPSA's simplex returns an exact zero, a restarted PDHG at 1e-9 returns 1e-8 to 1e-13, so the
quotient is a plausible-looking number instead of a NaN. Measured, 17 groups across the
goldens, from 3e-5 to 6.9e12 — no threshold separates those from a real group, because `0 / 0`
can land anywhere. So the suite skips a `market_value` key whose golden value is non-finite,
per key, and pins the whole non-finite census by metric, component and kind so the skip cannot
widen. `capacity_factor`'s NaNs are *not* skipped: a load has no `p_nom` at all, which is
structural, and those are asserted.

### `prices` is keyed by bus, which is a different key, a different view and a different carrier

Upstream says so in its own signature: `prices` takes neither `components` nor `at_port`,
because "prices are only defined for buses". Three consequences:

- **Its own grouping view.** `prices` groups by a static column of `buses.csv`, so `bus_carrier`
  and `carrier` are the same grouping — both the bus's own carrier — `name` is the bus's name,
  and `bus` is **refused**: there is no `bus` column, and upstream raises
  `ValueError: Grouping prices by 'bus' is not supported`. Every golden records it.
- **The raw carrier, not the nice name.** `prices` ends with
  `_apply_option_kwargs(df, nice_names=False)`, with an upstream comment saying to add it "once
  integrated in function". `statistics-dimensions` sets `nice_name` on both its carriers and the
  golden records `prices|carrier` as `AC` and `H2` rather than `Alternating current` and `H2`.
- **No period level.** Every other metric reports per period on a multi-period index. `prices`
  is a single weighted average over the whole index — `tx-cost-periods` records one number per
  bus across both 2030 and 2040 — because upstream's aggregation is a matrix product against
  the whole weighting column with no `groupby(level=0)` in it.

**The default weighting is the LOAD**, and the fill rule is the subtle part:

```
price[bus]   = SUM_t w_objective * load[t,bus] * price[t,bus] / SUM_t w_objective * load[t,bus]
price[group] = SUM_bus numerator / SUM_bus denominator
```

`weights.reindex(prices.columns, axis=1, fill_value=1)` fills **per bus, not per cell**. A bus
nothing attaches to is absent from the withdrawal frame and gets a weight of 1 at every
snapshot — a flat time average rather than a `0/0`. A bus something attaches to is present even
where that something never runs, so its zeros stay zeros and its price *can* be a `0/0`:
`link-delay`'s bus `a` carries a generator and a link that never run, and upstream reports NaN
for it. A `COALESCE(w.value, 1.0)` reads like the first rule and silently applies it to the
second case too, which is why `bus_load_weight` is a view rather than an expression.

The division happens in **Scala**, not SQL, for a reason worth recording: DuckDB returns NULL
for a division by zero where numpy returns a NaN or an infinity, and a NULL would reach
`drop_zero` as "no value" and vanish. Rounding and the zero-drop are applied there too, in
`_apply_option_kwargs`' own order, with `rint` rather than DuckDB's `ROUND` because it is
numpy's half-to-even and because it passes a NaN through.

### The generator was mangling every `prices` key

`"|".join(str(part) for part in key)` iterates a plain string character by character, and
`prices` is the one metric whose index holds plain strings rather than `(component, group)`
tuples. Every golden recorded `prices|name` for `Bremen` as `B|r|e|m|e|n` and `prices|carrier`
for `AC` as `A|C`. The determinacy probe beside it already had the `isinstance(key, tuple)`
guard; the main capture did not, so the two are one `key_label()` now.

Named `key_label` rather than `label`, which is not fussiness: `capture_network` binds a local
`label` in a loop over sub-networks, so a module-level `label` is shadowed there and the first
attempt recorded `TypeError: 'str' object is not callable` for every statistics block on every
network.

### The determinacy probe needed the same correction three times

`DETERMINACY_ABS` is 1e-2 and **absolute**, calibrated against the worst agreement on anything
that is a point — 7e-3, on `ac-dc-meshed`'s flows. Right shape for an energy or a cost; wrong
for anything smaller, and each new metric found a different way to show it:

1. `capacity_factor` divides a dispatch by a capacity, so `storage-cyclic-co2`'s 1.67 MWh of
   loose charging arrives as 0.0019 and passes. Fixed by having a quotient inherit the verdict
   of every metric over the same dispatch.
2. A branch nets to nearly nothing in `energy_balance`, so reading one numerator was not
   enough — `transmission`, the signed sum at one port, is where a line's movement shows.
   Fixed by taking the union over all six dispatch metrics.
3. `revenue|bus_carrier` on `ac-dc-txvolume-exact` is -82.36 as the net of two terms near
   1,136,000, so the two solves differ by 0.009 — just *under* the floor — while the group is
   1.1e-4 away from upstream in relative terms. Fixed by reading the inherited verdicts at
   **every** grouping, since an aggregated group mixes entities and the probe sees the movement
   where the group is largest.

Each correction was narrow and each was measured, and all three are now gone: the general fix
— a determinacy tolerance in each metric's own units — landed, and took two of the patches with
it while turning the third into a structural property. See *Retuning the probe* below.

### The first version of the guard was behind the flag it guarded

Worth recording because it survived a full mutation run and looked like coverage. `prices` is
gated on two measurements — the dual agreement and the determinacy of its weights — so the
per-network case asserts that a network which passes both compared at least one `prices`
grouping. Correct for the half read from the golden: forcing `pricesComparable` false fails 30
cases.

Useless for the other half. The assertion itself sits behind `duals`, so forcing `duals` to
false removes the three dual metrics from **every** comparison in the file *and* the assertion
that would have noticed — measured, that mutant survived the whole suite while `compared > 0`
stayed true on the strength of the other sixteen metrics.

The second attempt was no better and is worth the same line: a case that called `dualAgrees`
directly and pinned its verdict on a sample of seven networks. It kills a broken *measurement*
and not a broken *gate* — forcing the loop's flag false still left the three metrics uncompared
on forty networks while the case passed, because the case never goes through the loop.

What works is pinning the list and asserting the measurement against it **inside the
per-network case**, on every network rather than a sample. `dualFace` names the seven, the
comparison still gates on the measurement, and `assertEquals(duals, !dualFace(name))` runs
before anything else: forcing the flag false fails on the thirty-two networks whose prices
agree, forcing it true fails on the seven, emptying the list fails, and making the measurement
itself always agree fails. An assertion behind the flag it guards is not a guard, and an
assertion that bypasses the flag is not one either.

### What the mutation run says about these three

Twenty-three mutants, nineteen killed:

| mutant | killed by |
|---|---|
| `revenue` weights with `generators` | `energy-budget`, 4 cases |
| `revenue` also applies the period weighting | `investment-periods-discounted`, 3 cases |
| `revenue` prices every port at `bus0` | `ac-dc-meshed`, 7 cases |
| `market_value` takes the ratio of the sums | 30 cases |
| `market_value` normalises the operation by the objective weighting | `energy-budget`, 4 cases |
| `market_value` counts the operation at every port | 13 cases |
| `prices` weights by time instead of by load | 15 cases |
| `prices` averages the per-bus averages instead of weighting them | 8 cases |
| `prices` reads the nice carrier name | 45 cases |
| the bus withdrawal filters instead of clipping | 19 cases |
| the price is not divided back out by the objective weighting | `storage-cycle`, 5 cases |
| the dual-agreement gate is always true | the seven pinned networks |
| the dual-agreement gate is always false | the thirty-two that agree |
| the dual-agreement measurement always agrees | the seven pinned networks |
| the pinned dual-face list is empty | the seven pinned networks |
| the prices weight gate is always false | 30 cases |

Three survived, and each one's reason is a fact about the fixtures rather than about the code:

- **`market_value` reindexing onto the revenue instead of unioning.** Every key the union adds
  is one the numerator lacks, so every one is a NaN, and the suite skips a non-finite
  `market_value` group because whether its denominator is exactly zero is a fact about the
  solver. Upstream's arithmetic is kept; the skip is a property of the suite.
- **`prices` dropping the snapshot weighting.** It multiplies both sides of the division and
  cancels wherever it is constant. Exactly one golden varies that column across snapshots —
  `link-delay-wrap` at 1.0 and 3.0 — and it is one of the networks the dual-agreement
  measurement skips.
- **The load weight filling 1 per cell rather than per bus.** This was load-bearing when it was
  written: it reported `link-delay`'s `prices|bus_carrier` as 86.54 against upstream's 90,
  because that bus withdraws 100 MW at one snapshot and nothing at the other three. `link-delay`
  is now skipped for a dual face, so the mutant survives. The rule is upstream's and the reason
  it is untested is a property of this port's duals.

### The nineteen are named in the suite, and the mechanism stays

`STATISTICS_METRICS` is PyPSA's full nineteen and so is `metrics` in the suite, so the
`pending` set is **empty** — and kept. The next upstream release is what it is for: a metric
added to `statistics` lands in every golden on the first regeneration, and the case fails
unless it is either implemented or listed. An empty set is the state that mechanism is supposed
to be in, and the case asserts it is *exactly* the difference in both directions, so an entry
cannot outlive the metric being implemented and quietly take it out of every comparison.

The set carried six names for two releases and then three, and what each one was waiting for
is the record of what the batches cost: `energy_balance`, `withdrawal`, `capacity_factor`,
`revenue` and `market_value` all report `Load`, which `quantitiesOf` gave no rows in `fact`;
`revenue`, `market_value` and `prices` need the bus marginal price, which was in no table; and
`prices` is keyed by **bus** rather than by component.

**The group floor is the thing that narrowed.** It was a single absolute value in the units of
an energy or a cost, which is what made it comparable to upstream's round-to-five rule, and two
of the metrics that landed are neither — `capacity_factor` is dimensionless and `market_value`
is a price. The smallest compared group went from `store-per-period`'s 0.6 at 6,000x the floor
to `phase-shift`'s 0.0161 at 161x to `ac-dc-dispatch`'s 0.0003846 at **3.8x** — at which point
the single floor gave way to a per-metric one. See *The floor is per metric now* below.

## Ports past the second, which were refusing a shape they had never seen

`Lopf` has always built an arbitrary number of ports — `Topology.branchPorts` walks every
`busN` column and the balance reads `efficiency2` beside `efficiency` — while this module
carried exactly two per branch and turned such a network away by name. That refusal was the
right call at the time and it had a defect built in: no golden had a `bus2`, so the two-port
limit cost nothing measurable and the refusal could not be tested against anything.

`multi-port-link` is the golden that ended that, and the shape is the ordinary one: an
electrolyser delivers hydrogen at `bus1`, its waste heat at `bus2` and more hydrogen to a second
hydrogen bus at `bus3`, which is why ports past the second exist at all. It has **four**, and
the fourth is there for a specific reason given below.

### The third port decides the answer, not just the bookkeeping

Serving one MWh of hydrogen through the link costs `10 / 0.6` = 16.67 of electricity against an
import at 15, so **on its hydrogen leg alone the link is not worth running**. Its heat
by-product pays for it: every MWh of hydrogen carries `0.25 / 0.6` = 0.4167 MWh of heat that
would otherwise cost 60.

So the link runs flat out at 100 MW and the objective is **8,850**. Zero `efficiency2` and
`efficiency3` and it never runs at all: **15,650**. A port dropped is not a term lost, it is the
decision reversed.

For the metrics, `supply` sums a branch at every port, so `Link|ely` is **400** — 240 of
hydrogen at `h`, 100 of heat at `w` and 60 of hydrogen at `x`. A two-port schema reports 240,
short by 40% of the group. `withdrawal`, `energy_balance` and `revenue` split the same way.

### The fourth port is what exercises `transmission`

Upstream's `get_transmission_branches` maps **every** `bus*` column through `buses.carrier` and
keeps a branch where more than one end matches — so a multi-port link is transmission when *any
two* of its ports share a carrier, not when its first two do. With three ports at AC, H2 and
heat no two match, the rule is unexercised, and a reader comparing only `bus0` against `bus1`
agrees with upstream on every network in the manifest.

`bus3` is a second hydrogen bus, so ports 1 and 3 match while port 0 does not, and upstream
reports `transmission|name Link|ely` at 400. The mutant that compares only the first two ports
survived until this bus existed; it does not now.

### `efficiency2` is a series with no static column, which is the shape that bites

It varies — 0.25, 0.25, 0.30, 0.20 — and that is not decoration. `efficiency2` and beyond are
not schema attributes, so a multi-port link carries them as custom columns, and
`export_to_csv_folder` omits a static column sitting at its default. This network's `links.csv`
header is `name,bus0,bus1,bus2,carrier,efficiency,p_nom` — **no `efficiency2` at all** — and
only `links-efficiency2.csv` carries the values.

A reader checking `spec` and `static` therefore sees no efficiency and treats the port as
lossless, reporting 1.0 where the network says 0.25: 400 of heat instead of 100.
`Topology.portEfficiency` is the one helper that checks all three places a value can live, and
this module now goes through it rather than through `table.valueAt`. With a scalar
`efficiency2` the mutant that bypasses it **survived**; the series form is what kills it.

### Three iterations, each forced by a measurement

1. **Infeasible.** One `p` variable feeds both by-product buses, so their outputs are locked in
   the ratio `efficiency2 / efficiency` and neither can be a hard load on its own. The first
   version set hydrogen to 30 and heat to 5, which asks the link for 50 MW of input and 12.5 of
   heat against a load of 5. PyPSA rejected it on presolve.
2. **Feasible, but a dual face.** With both by-product buses given a backup generator the
   network solves — but the second version left both backups at zero at the last snapshot,
   where the link happened to meet both loads exactly. With no marginal unit there, those two
   bus prices are an interval and the two solvers disagreed by **37.05**, which would have put
   the fixture in `dualFace` and skipped `revenue`, `market_value` and `prices` on it.
3. **Both backups running at every snapshot.** Raising the last snapshot's loads to 70 and 30
   puts both at 10 MW and pins both prices. With the fourth port and its own backup added,
   series agree to 6.8e-14, the objective to 7e-12 and the nodal prices to **5.0e-9**, so all
   nineteen metrics are compared here.

### What the schema change was

`port` became a **table** loaded from `Topology.branchPorts`, where it had been a view over
`entity.bus` and `entity.bus1`. A view can only carry the columns it names, and the number of
ports is a fact the network decides. `entity.bus1` is gone with it.

Two views generalised as a consequence:

- `injection` joins the receiving port's efficiency **by port number** —
  `'efficiency' || i.port` against a `fact` quantity named `efficiency1`, `efficiency2`, … — so
  one query covers a branch with any number of ports instead of one `LEFT JOIN` per port.
  `efficiency1` is PyPSA's plain `efficiency`; a single `efficiency` was the two-port assumption
  spelled as a quantity name.
- `transmission_branch` compares **any two** ports for a shared bus carrier rather than `bus0`
  against `bus1`, which is upstream's actual rule: `get_transmission_branches` maps every `bus*`
  column and keeps a branch where more than one end matches.

### What the mutation run says

Six mutants, six killed, which is the whole point of having built the fixture before the
feature:

| mutant | killed by |
|---|---|
| the `port` table carries only two ports | 2 cases |
| the injection reads only port 1 | 2 cases |
| only the first receiving port gets an efficiency | 2 cases |
| the efficiency is read off `static` rather than through `Topology` | 2 cases |
| `transmission` compares only the first two ports | 2 cases |
| a receiving port with no efficiency row is lossless at 0 rather than 1 | 18 cases |

Two of those needed the fixture to grow to kill them, and both are recorded above because the
first version did not: the `static` read survived a scalar `efficiency2`, and the `transmission`
comparison survived until the link had two ports of one carrier.

## Retuning the probe, and what it made unnecessary

Three narrow patches had accumulated in `StatsSuite`, one per batch of metrics, and each
borrowed a determinacy verdict from a *different* metric: a quotient from its dispatch, then
from six dispatch metrics because a branch nets to nothing in `energy_balance`, then across
every grouping as well. Three patches for one cause is a diagnosis, and the cause was the
probe's tolerance.

### The probe now uses the consumer's own rule

`DETERMINACY_ABS` is 1e-2 and **absolute**, calibrated honestly: across all 39 networks the
worst agreement on anything that is a point is 7e-3, on `ac-dc-meshed`'s flows where the LP is
60,000 variables, while a face differs by 14 to 538. That is the right floor for the recorded
**series**, which are all megawatts and megawatt-hours — one unit family, one calibration.

The statistics are nineteen metrics in four unit families, and a floor measured in MWh is
meaningless for three of them:

| family | metrics | typical magnitude |
|---|---|---|
| energies and costs | `supply`, `opex`, `capex`, `revenue`, … | thousands to millions |
| capacities | `optimal_capacity` and its two | tens to hundreds of thousands |
| dimensionless | `capacity_factor` | between 0 and 1 |
| prices | `market_value`, `prices` | single digits to hundreds |

So the statistics probe now tests `|a - b| <= 1e-4 * max(1, |a|, |b|)` — **exactly** what
`StatsSuite` asserts each group to. That identity is the whole argument: the probe exists to
answer one question, *could the consuming comparison fail for a reason that is not the port's
fault*, and a probe using the consumer's own rule answers it. Any other rule answers a
different question and the gap is paid in silent skips or false failures.

The series keep `DETERMINACY_ABS`, because its calibration is sound for them.

What the old comment got right is still right: per **element** and against its own magnitude. A
tolerance scaled by the largest group in a frame would call a small group determinate while the
suite rejected it — `ac-dc-txcost`'s `capex|bus` has a 4,396 group and a 10.36 one. The
`max(1, ...)` is where the absolute floor went, and it is 1e-4 below unit magnitude.

### Three patches become one rule, and the rule is structural

Two of the three are simply gone: the retuned probe sees a face in a dimensionless group and in
a load-weighted price directly, so `capacity_factor` no longer borrows from its dispatch and
`prices` no longer borrows from `withdrawal`.

What is left is not a patch but a property. Three metrics — `energy_balance`, `revenue`,
`market_value` — sum a **signed** quantity over **both** ports, so a branch contributes `-p0`
at one end and `+p0 × efficiency` at the other and the group is the difference of two numbers
orders of magnitude bigger than itself. `ac-dc-txvolume-exact`'s `revenue|bus_carrier` has
`Link|DC` at −82.36 as the net of two terms near 1,136,000: agreement to 8e-9 on the terms is
1.1e-4 on the net. Those three read their *own* verdict at every grouping, because a coarse
group mixes the entities whose finer groups moved.

No other metric can cancel: `supply`, `withdrawal` and `curtailment` clip to one sign, the
capital metrics and `transmission` sum at one port, `capacity_factor` takes an absolute value.
`energy_balance` is in the set on structure rather than on need — it costs 41 comparisons to
include and passes without it, which is the price of saying what is true instead of what is
minimal.

### What it cost, measured

Comparison breadth, counted as golden groups actually compared across every network, metric and
grouping:

```
shipped on main  (old probe + three borrowed-verdict patches)   12,608
this change      (retuned probe + the structural rule)          12,455   -1.2%
```

For reference, applying the all-groupings rule to *every* metric rather than the three that can
cancel costs 11,923, or −5.4%. The 153 comparisons this does give up were comparisons against
groups where PyPSA's own two solves differ by more than the tolerance — they passed because
this port happened to land near upstream's simplex vertex, not because the two agreed.

### The floor is now bounded on both sides

The floor was 1e-4 at this point, one value for all nineteen metrics, and the natural question
is why not 1e-5, which is PyPSA's own round-to-five resolution. Measured, by setting it there
and reading the failure: at 1e-5
`ac-dc-txcost`'s `revenue|bus_carrier` reports `Line|AC` at −2e-05 and `Line|DC` at 1e-05 here,
where upstream reports neither. Those are the residue of a cancellation — the AC lines earn and
pay nearly 20.6 million each way — so they are this solver's zero at 1e-9 and not groups.

Above it, the smallest group PyPSA reports in any compared network is `ac-dc-dispatch`'s
`market_value` of 0.0003846. So the usable band is about `[2e-4, 3.8e-4]` and the floor sits at
the bottom of it — narrow, and narrow because the metrics approaching from above are ratios
while the artefacts approaching from below are cancellations. Both arrived with the last two
batches. The next thing to give would be the floor's shape rather than its value.

### The floor is per metric now, and the band I quoted was wrong

The previous section ends by saying the usable band is about `[2e-4, 3.8e-4]` and that the
floor sits at the bottom of it. Both halves are wrong. The cancellation residue that bounds it
from below is **2e-05**, not 2e-4, so the band was `[2e-5, 3.8e-4]` and a floor of 1e-4 sat in
the middle rather than at the bottom. An arithmetic slip in a sentence about arithmetic.

What is right is that a single floor was the wrong shape, and fixing the shape dissolves the
band rather than widening it.

#### Only five metrics can turn a solver epsilon into a group

Measured, by setting every floor to zero and reading what fails:

| metric | largest artefact | smallest real group (all networks) | floor |
|---|---|---|---|
| `prices` | none, but reachable | 0.10223 | 1e-4 |
| `revenue` | 4e-05 | 1e-05 (`scigrid-de`) | 1e-4 |
| `supply` | 1e-05 | 1e-05 (`scigrid-de`) | 1e-4 |
| `energy_balance` | 1e-05 | 1e-05 (`scigrid-de`) | 1e-4 |
| `withdrawal` | 1e-05 | 0.87578 | 1e-4 |
| `curtailment` | none | 0.34042 | 1e-4 |
| `market_value` | none | 4.95805e-06 | **0** |
| `capacity_factor` | none | 2.79015e-06 | **0** |
| `optimal_capacity`, `installed_capacity` | none | 0.00028 | **0** |
| the other ten | none | 0.6 to 13,764.8 | **0** |

Two mechanisms produce the artefacts, and between them they explain exactly which metrics are
in the first group:

- **A clip.** `supply`, `withdrawal` and `curtailment` keep one side of a quantity, so a port
  whose injection is `-1e-11` here and exactly `0` upstream becomes a group here and none
  there. The boundary makes it, not the magnitude.
- **A cancellation.** `energy_balance` and `revenue` sum a signed quantity over both ports, so
  a branch's two ends nearly annihilate and what survives is the epsilon times the scale of
  the terms — `revenue` reaches 4e-05 because its terms carry a price.
- **A dual epsilon, amplified.** `prices` averages a *dual*, and the gate that admits a network
  at all tolerates `1e-4 · max(1, |want|)` — 1e-4 absolute at a cheap bus. A bus priced at
  exactly zero upstream and 5e-5 here passes that gate and lands in the key-set comparison. No
  artefact exists today, so this one is floored on a reachable mechanism rather than a measured
  residue, and its smallest real group anywhere is 0.10223 so the floor costs nothing.
  It is also the one metric neither census case can see, being keyed by bus — left unfloored it
  would read as a side effect of a name's absence from a set rather than as a decision.

Everything else reads a static column, sums at one port, or takes an absolute value, and
produces no artefact at all. `market_value` is the one that cancels and still needs no floor,
because its `0/0` groups are removed by the non-finite skip instead.

#### What it buys, and the one thing it cannot do

The gain is on the side that was tightest. `market_value` had a 3.8× margin and now has no
floor, so its real groups down to 4.96e-06 are checked for **presence** and not only for value
— and the same for `capacity_factor` at 2.79e-06 and the two capacity metrics at 0.00028. The
hidden non-zero census drops from 40 groups to 13; the 27 recovered are `capacity_factor`'s
thirteen and `market_value`'s fourteen, which a floor placed for a residue in `revenue` had
been suppressing for nothing.

The honest part is the second column of the table. For `supply`, `energy_balance` and `revenue`
the smallest real group upstream reports is the **same magnitude** as the artefact, so no floor
separates them and none ever will. The only reason 1e-4 is safe for those three is that every
one of those groups is in `scigrid-de`, the one network this suite skips for a degenerate
dispatch. That is a weaker claim than a margin, and it is the true one.

So the floor's shape is fixed and its remaining weakness is now stated rather than averaged
away: fourteen metrics need no floor, two need one and have room, and three have a floor that
works by accident of which network their smallest groups live in.

## The ramp multipliers, and the identity they complete

`ramp-limits` was the one network in the dual-face list whose differing prices could not be
accounted for, only bounded: bus `a` reads −42.3074 and 62.3074 where PyPSA reads −20 and 40,
a difference of ∓22.3074 at consecutive snapshots.

`LopfResult.rampPrice` exposes them now — `mu_ramp_limit_up` and `mu_ramp_limit_down` upstream,
and **not** divided by the snapshot weighting the way `marginalPrice` is, which is PyPSA's own
asymmetry: `post_processing` divides `buses_t.marginal_price` by the objective weighting and
leaves every `mu_*` alone. `LpBuilder.rowCount` is what makes it possible, and `Ramps.constrain`
takes a recorder rather than a defaulted one, because a default of "record nothing" is how a
new emission site ends up unrecorded with no diagnostic.

They complete the reduced cost of a ramped generator, and `LopfSuite` now asserts the identity
at every interior generator and snapshot on that fixture:

```
cost × w_objective − λ − μ_up(t) + μ_up(t+1) − μ_down(t) + μ_down(t+1) = 0
```

It holds at all 17 of them. And it closes the account: `flex` is interior at `t4` with a cost of
40 against a bus price of 62.3074, and `μ_up(t4)` is **−22.3074** exactly. Same identity,
different split between the price and the multiplier — which is what a dual face in a ramp
constraint looks like, and why the earlier case "a generator strictly inside its bounds pins its
bus price" is true only where no ramp row touches that generator.

The signs were read off the solved model rather than derived, which is the reason the assertion
earns its place: a sign error in a ramp row or in the dual recovery leaves the primal and the
objective untouched and would show up nowhere else.

## What this deliberately does not do

- **Back the network.** `Network` stays as it is. Nothing in `network-model`,
  `network-lopf` or `network-pf` learns about DuckDB, and the cross-build is untouched.
- **Replace `results_drift.py`.** That compares two *golden* directories and is Python next
  to the generator that writes them. It could be rewritten in SQL; it has no reason to be.
- **Read `.h5` or `.nc`.** DuckDB reads neither, per NOTES.
- **Become a query interface for the solver.** If a future caller wants predicate pushdown
  into an LP build, that is a different design and this is not its first step.
