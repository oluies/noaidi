# `network-stats`: DuckDB as a consumer of solved results

Written as a sketch, and now partly built: `modules/network-stats` carries the five tables
and two of the nineteen metrics. It exists to make one architectural claim concrete:
**DuckDB belongs downstream of the solve, not underneath the network.**

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
`<` in a throwaway script while `significant` keeps `> GroupFloor`, which is the kind of
boundary a pinned list catches and a sentence about magnitudes does not.

### What is still not covered

- **The other metrics.** Each is a query, and the corrections above are the argument for
  adding them against measured goldens a few at a time rather than in one pass. Three of the
  seventeen are done; see *The capital metrics* below for what they cost.
- **A third port.** `bus2` and beyond are refused rather than dropped; see *Branches in
  `supply`* below.
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

Nine metrics: `system_cost`, `withdrawal`, `transmission`, `energy_balance`,
`curtailment`, `capacity_factor`, `revenue`, `market_value`, `prices`.

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

A branch with `bus2` is **refused**. `Lopf` builds one — `efficiency2` is read there — and
this schema carries two ports, so such a network would be short by whatever the third port
injects. No golden has one, so this refuses a shape it has never seen, deliberately.

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

So the series and capacity blocks carry the verdict alone, and the objective gap between the
two solves went the same way. The per-**group** deviations in the `statistics` block stay,
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

## What this deliberately does not do

- **Back the network.** `Network` stays as it is. Nothing in `network-model`,
  `network-lopf` or `network-pf` learns about DuckDB, and the cross-build is untouched.
- **Replace `results_drift.py`.** That compares two *golden* directories and is Python next
  to the generator that writes them. It could be rewritten in SQL; it has no reason to be.
- **Read `.h5` or `.nc`.** DuckDB reads neither, per NOTES.
- **Become a query interface for the solver.** If a future caller wants predicate pushdown
  into an LP build, that is a different design and this is not its first step.
