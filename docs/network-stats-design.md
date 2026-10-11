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
2,650 here against 7,650 upstream. The LOPF now refuses it; see NOTES. Breadth of coverage
found a correctness bug in another module, which is an argument for the module that the
nineteen-metrics argument does not make.

## What this deliberately does not do

- **Back the network.** `Network` stays as it is. Nothing in `network-model`,
  `network-lopf` or `network-pf` learns about DuckDB, and the cross-build is untouched.
- **Replace `results_drift.py`.** That compares two *golden* directories and is Python next
  to the generator that writes them. It could be rewritten in SQL; it has no reason to be.
- **Read `.h5` or `.nc`.** DuckDB reads neither, per NOTES.
- **Become a query interface for the solver.** If a future caller wants predicate pushdown
  into an LP build, that is a different design and this is not its first step.
