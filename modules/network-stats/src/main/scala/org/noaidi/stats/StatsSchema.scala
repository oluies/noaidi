package org.noaidi.stats

/** The star schema a solved result is loaded into, as DDL.
  *
  * One long fact table and four dimensions. Long rather than wide because the quantities
  * differ by component — a generator has one series, a storage unit has five — so a wide
  * table is a sparse column per component per quantity and every query has to know which of
  * them exist for the components it touches.
  *
  * ==The weightings are columns, not pre-multiplied into `value`==
  *
  * This is the one decision here worth defending, because getting it wrong is the defect
  * this project has hit most often. `fact.value` holds raw solver output; every weighting an
  * aggregate needs is a column on [[snapshot]] and the query multiplies.
  *
  * Baking the factors in — storing a `value` already multiplied by whichever weighting looked
  * like "the elapsed hours" — reads tidier and is how four separate divergences got in:
  * `objective` read where `years` belonged, `years` read where `objective` belonged, both
  * applied on a flat index where PyPSA applies neither, and one applied to a period-scoped
  * row where PyPSA sets it to 1.
  *
  * "Looked like" is load-bearing. `w_stores` '''is''' the elapsed hours the storage balance
  * uses, and `Stats.supply` nonetheless weights with `w_generators`, because that is what
  * `energy_balance` reads. A schema that had pre-multiplied the obvious one would have put
  * that error below every metric instead of inside one query.
  *
  * With the factors as columns a metric's SQL '''states''' which one it uses, so a reader can
  * check it against the matching `define_*` in `pypsa/optimization/` in a single reading.
  * That is worth more than the multiplication it saves.
  *
  * ==`snapshot` is an index, not a label==
  *
  * Every accessor in `LopfResult` is keyed by the snapshot's position, and a multi-period
  * network's label is a `(period, timestep)` pair that is not unique in either half. The
  * label is carried alongside for reporting; nothing joins on it.
  */
private[stats] object StatsSchema:

  /** Every table, in dependency order. */
  val ddl: Seq[String] = Seq(
    // One row per (component, entity, snapshot, quantity). `quantity` names the series:
    // 'p' for a generator or a storage unit's net injection, 'p0' for a branch's flow,
    // 'soc' / 'charge' / 'discharge' / 'spill' for a storage unit's four, 'e' for a store's
    // energy and 'p' for its signed power.
    """CREATE TABLE fact (
      |  component VARCHAR NOT NULL,
      |  entity    VARCHAR NOT NULL,
      |  snapshot  INTEGER NOT NULL,
      |  quantity  VARCHAR NOT NULL,
      |  value     DOUBLE  NOT NULL
      |)""".stripMargin,

    // One row per entity, carrying everything a grouping dimension needs. `bus` is the
    // component's own bus, or `bus0` for a branch -- which is PyPSA's choice and the same
    // asymmetry `TechCapacityLimit` reproduces, so a branch counts at the end it leaves.
    //
    // `sign` is the one column here that is NOT a dimension or a cost: it is the +1/-1
    // multiplier `energy_balance` applies to a one-port's own series, and it is NULL for a
    // branch -- which is what makes `e.sign IS NULL` upstream's `c in branch_components` test
    // wherever this schema needs it. See `injection`.
    """CREATE TABLE entity (
      |  component     VARCHAR NOT NULL,
      |  entity        VARCHAR NOT NULL,
      |  carrier       VARCHAR,
      |  bus           VARCHAR,
      |  bus1          VARCHAR,
      |  nominal       DOUBLE,
      |  nominal_in    DOUBLE,
      |  extendable    BOOLEAN,
      |  marginal_cost DOUBLE,
      |  capital_cost  DOUBLE,
      |  fom_cost      DOUBLE,
      |  overnight_cost DOUBLE,
      |  sign          DOUBLE,
      |  PRIMARY KEY (component, entity)
      |)""".stripMargin,

    // One row per snapshot. `w_*` are the three snapshot weighting columns and `p_*` the two
    // investment-period ones, each defaulting to 1.0 exactly as `Network` defaults them --
    // so a query multiplies unconditionally and a flat index needs no special case.
    """CREATE TABLE snapshot (
      |  snapshot     INTEGER PRIMARY KEY,
      |  label        VARCHAR NOT NULL,
      |  period       VARCHAR,
      |  w_objective  DOUBLE NOT NULL,
      |  w_stores     DOUBLE NOT NULL,
      |  w_generators DOUBLE NOT NULL,
      |  p_objective  DOUBLE NOT NULL,
      |  p_years      DOUBLE NOT NULL
      |)""".stripMargin,

    // One row per bus. `carrier`, `country`, `location` and `unit` are here rather than on
    // `entity` because four of PyPSA's seven grouping dimensions are read off the component's
    // BUS and not off the component -- `grouping.py`'s `bus_carrier`, `country`, `location`
    // and `unit` all map `n.c[c].static[bus]` through a column of `n.c.buses.static`.
    //
    // `country` is nullable for a reason that is not uniformity: it is NOT a declared PyPSA
    // attribute, only a column real datasets add, and the grouper reaches for it unguarded.
    // On a network without one upstream raises
    // `AttributeError: 'DataFrame' object has no attribute 'country'`, which is why
    // `Stats.dimension` refuses that grouping rather than grouping everything under the empty
    // string. `location` and `unit` are declared, default to the empty string, and group as
    // it.
    """CREATE TABLE bus (
      |  name     VARCHAR PRIMARY KEY,
      |  carrier  VARCHAR,
      |  v_nom    DOUBLE,
      |  country  VARCHAR,
      |  location VARCHAR,
      |  unit     VARCHAR
      |)""".stripMargin,

    // `carriers.csv`, for one column of it. `nice_name` is what PyPSA's `carrier` grouper
    // maps through before it renders anything, so a carrier named `wind` with a nice name of
    // `Wind power` groups as the latter -- and a port that read the raw column agreed with
    // upstream on every golden until `statistics-dimensions` set one.
    """CREATE TABLE carrier (
      |  name      VARCHAR PRIMARY KEY,
      |  nice_name VARCHAR
      |)""".stripMargin,

    // Which periods an asset exists in, for the per-period capital factor that
    // `Expansion.costWeight` computes. Empty on a flat index: there is no period to be
    // active in, and `costWeight` is 1.0 there.
    """CREATE TABLE active (
      |  component VARCHAR NOT NULL,
      |  entity    VARCHAR NOT NULL,
      |  period    VARCHAR NOT NULL
      |)""".stripMargin,

    // One row per (entity, period) an asset is charged capital for, with that period's
    // weighting -- and exactly one row per entity on a flat index.
    //
    // The three capital metrics are the first here that are NOT sums over snapshots. A
    // capacity is one number for the whole horizon, so `capex` has no snapshot axis to group
    // by, and yet upstream reports it PER PERIOD on a multi-period network. This view is what
    // supplies that axis:
    //
    //     capex[group, period] = SUM over assets active in that period of
    //                            nominal_opt * capital_cost, times that period's
    //                            objective weighting
    //
    // Derived from the goldens rather than from the formula, and checked on two fixtures.
    // `investment-periods-expansion` has weightings 1.0 and 0.5 and three generators, one
    // built in 2040:
    //
    //     2030   (old 100x0 + wind 60x120)                 x 1.0  =  7200
    //     2040   (old 0 + wind 7200 + late 40x60 = 9600)   x 0.5  =  4800
    //
    // ==This is not `Expansion.costWeight`, and the design note said it was==
    //
    // `costWeight` is the SUM of the objective weightings over the periods an asset is active
    // in, and it is what the objective charges capital with -- one factor per asset. The
    // statistic is per period with that period's own weighting. Summing the statistic over
    // periods recovers `costWeight`, so the two agree about the total and disagree about
    // every breakdown, which is the shape of error that passes a one-period fixture.
    //
    // The `active` table is empty on a flat index -- there is no period to be active in -- so
    // the second branch is what covers that case, with a weight of 1.0 and a NULL period. The
    // `COUNT(*) = 0` guard rather than a Scala branch keeps one query shape for both, which
    // matters because the alternative is three metrics times two shapes.
    """CREATE VIEW capital_share AS
      |SELECT a.component, a.entity, a.period, w.p_objective AS weight
      |FROM active a
      |JOIN (SELECT DISTINCT period, p_objective FROM snapshot) w USING (period)
      |UNION ALL
      |SELECT e.component, e.entity, NULL AS period, 1.0 AS weight
      |FROM entity e
      |WHERE (SELECT COUNT(*) FROM active) = 0""".stripMargin,

    // One row per (entity, port): the bus that port attaches to.
    //
    // A one-port has port 0 and nothing else. A branch has port 0 at `bus0` and port 1 at
    // `bus1`, which is what lets `supply` attribute a flow to the bus that RECEIVES it while
    // `opex` and the capacity metrics stay at port 0. Measured, not assumed: on
    // `statistics-dimensions`, `supply` groups the tie line under bus `b` and the
    // electrolyser under bus `h` -- their `bus1` -- while `opex` groups both under `a`.
    //
    // Two ports and no more. A link with `bus2` is a shape `Lopf` builds -- `efficiency2` is
    // read there -- and one this module would silently drop a third of, so `Stats.of` refuses
    // such a network by name rather than loading it. No golden has one.
    """CREATE VIEW port AS
      |SELECT component, entity, 0 AS port, bus AS bus FROM entity WHERE bus IS NOT NULL
      |UNION ALL
      |SELECT component, entity, 1 AS port, bus1 AS bus FROM entity WHERE bus1 IS NOT NULL""".stripMargin,

    // Signed injection into a bus, one row per (entity, port, snapshot).
    //
    // This is PyPSA's `energy_balance`, and [[Stats.supply]], [[Stats.withdrawal]] and
    // [[Stats.energyBalance]] are its three directions -- the positive part, the negative part
    // negated, and the whole thing. Upstream is one function with a `direction` argument over
    // exactly this expression:
    //
    //     sign = -1.0 if c in branch_components else static.get("sign", 1.0)
    //     p    = sign * dynamic[f"p{port}"]
    //
    // ==`sign` is a column, not a constant per component==
    //
    // It is +1 for a generator, a storage unit and a store and -1 for a LOAD, and upstream
    // reads it off `static` rather than deciding by component name -- so a one-port with an
    // unusual sign is honoured. `entity.sign` carries it for the one-ports and is NULL for a
    // branch, which makes `e.sign IS NOT NULL` exactly upstream's own `c in
    // branch_components` test rather than a list repeated here.
    //
    // ==The branch sign rule is the bus balance's own==
    //
    // Port 0 sends, so its injection is `-p0`; port 1 receives `p0` scaled by that port's
    // efficiency. Upstream writes both as `-p{port}` because it stores a link's `p1` already
    // negated and scaled -- `assign_solution` sets `p1 = -p * efficiency` -- so `-p1` is
    // `+p * efficiency`, which is what the second branch below computes from `p0`.
    //
    // For a line the efficiency is 1, so the two ports contribute `-p0` and `+p0` and the
    // positive part sums to `|p0|` -- which is exactly what the goldens show. Measured on
    // `ac-dc-meshed`:
    //
    //     Line 0   sum |p0| = 3846.70123   golden supply|name Line|0 = 3846.70123
    //     Line 1   sum |p0| = 7200.23445   golden supply|name Line|1 = 7200.23445
    //
    // And with an efficiency that is not 1, on `statistics-dimensions`: the electrolyser
    // carries 35.71429 MW at the first snapshot and its receiving port reports 25.0, which is
    // 0.7 times that. A port efficiency read as 1 would have reported 35.71429 and agreed
    // with upstream on every other network, because every other branch in the goldens either
    // has efficiency 1 or never runs.
    //
    // `efficiency` is a row in `fact` rather than a column on `entity` for the same reason
    // `marginal_cost` is: it is `static or series` upstream. `COALESCE(..., 1.0)` covers the
    // passive branches, which have no such attribute at all.
    //
    // ==A storage unit injects its NET `p`, not its discharge==
    //
    // This read `discharge` for two releases and agreed with upstream on every golden, which
    // is what an equivalent mutant looks like: `supply` keeps the positive part, and a unit
    // that charges and discharges at the same snapshot is paying round-trip losses for
    // nothing, so no optimum here does it. `energy_balance` is not a positive part, so the
    // difference stops being invisible the moment the whole signed series is reported --
    // `clip(p, 0)` and `discharge` differ exactly when both halves run at once.
    """CREATE VIEW injection AS
      |SELECT f.component, f.entity, f.snapshot, 0 AS port,
      |       e.sign * f.value AS value
      |FROM fact f
      |JOIN entity e ON e.component = f.component AND e.entity = f.entity
      |WHERE f.quantity = 'p' AND e.sign IS NOT NULL
      |UNION ALL
      |SELECT f.component, f.entity, f.snapshot, 0 AS port, -f.value AS value
      |FROM fact f
      |WHERE f.quantity = 'p0'
      |UNION ALL
      |SELECT f.component, f.entity, f.snapshot, 1 AS port,
      |       f.value * COALESCE(eff.value, 1.0) AS value
      |FROM fact f
      |LEFT JOIN fact eff
      |  ON  eff.component = f.component AND eff.entity = f.entity
      |  AND eff.snapshot  = f.snapshot  AND eff.quantity = 'efficiency'
      |WHERE f.quantity = 'p0'""".stripMargin,

    // The one series PyPSA calls a component's OPERATION, unsigned.
    //
    // `get_operation` in `statistics/expressions.py:39`, which is its own little dispatch and
    // not the same choice `injection` makes:
    //
    //     passive branch  ->  p0      (there is no `p` to read)
    //     Store           ->  e       (the ENERGY LEVEL, not the power)
    //     anything else   ->  p       (a one-port, or a link, whose `p` IS its `p0`)
    //
    // Two metrics read it and both divide by it: [[Stats.capacityFactor]] takes its weighted
    // MEAN over the horizon against `optimal_capacity`, and `market_value` will take its
    // weighted sum as the denominator of a revenue.
    //
    // The `Store` row is the one worth pausing on. A store's capacity is an ENERGY, `e_nom`,
    // so a capacity factor over its power would be dividing MW by MWh; upstream divides the
    // mean energy LEVEL by the energy capacity, which is a state of charge rather than a
    // utilisation. Reproduced rather than corrected.
    //
    // `e.sign IS NULL` is the branch test, as in `injection`, so a controllable branch reads
    // `p0` -- which is the same number upstream's `p` branch returns for a link, since
    // `assign_solution` writes both from one solution array.
    """CREATE VIEW operation AS
      |SELECT f.component, f.entity, f.snapshot, ABS(f.value) AS value
      |FROM fact f
      |JOIN entity e ON e.component = f.component AND e.entity = f.entity
      |WHERE (f.quantity = 'p0' AND e.sign IS NULL)
      |   OR (f.quantity = 'e'  AND f.component = 'Store')
      |   OR (f.quantity = 'p'  AND e.sign IS NOT NULL AND f.component <> 'Store')""".stripMargin,

    // Branches that carry power between two buses of the SAME carrier, which is PyPSA's own
    // definition of transmission -- `get_transmission_branches` maps every `bus*` column
    // through `buses.carrier` and keeps a branch where more than one end matches the same
    // carrier value.
    //
    // So a line between two AC buses is transmission and an AC/DC converter link is not.
    // Measured: `ac-dc-meshed` reports its seven lines and none of its three converters, and
    // `statistics-dimensions` reports `tie` (AC to AC) and not `elec` (AC to H2).
    //
    // ==A network with no `carrier` column is all transmission, and that is not the coalesce==
    //
    // PyPSA declares `Bus.carrier` with a default of `AC`, so a `buses.csv` without the column
    // gives every bus the same carrier and upstream then matches them all to each other:
    // `inactive` and `standard-types` report every line AND transformer as transmission, which
    // is in their goldens. `ComponentTable.string` falls back to the declared default too, so
    // this view reaches the same answer by the same route.
    //
    // The `COALESCE(..., '')` therefore guards a NULL this loader does not currently produce,
    // and dropping it is an EQUIVALENT MUTANT -- measured, not assumed. It is kept because the
    // failure it prevents is silent rather than loud: comparing two NULLs in SQL yields NULL
    // rather than true, so a bus carrier that ever did arrive unset would make the whole view
    // empty and every `transmission` group simply disappear.
    """CREATE VIEW transmission_branch AS
      |SELECT e.component, e.entity
      |FROM entity e
      |JOIN bus b0 ON b0.name = e.bus
      |JOIN bus b1 ON b1.name = e.bus1
      |WHERE e.bus1 IS NOT NULL
      |  AND COALESCE(b0.carrier, '') = COALESCE(b1.carrier, '')""".stripMargin,

    // Every grouping dimension, resolved once per entity AND PORT.
    //
    // A view rather than seven expressions repeated in nineteen queries, which is the shape
    // this module exists to avoid: a metric names a dimension by column, and the joins and
    // the `nice_name` fall-back live in one place. `Stats.dimension` validates the name
    // against this view, so an unknown grouper is refused rather than interpolated.
    //
    // Keyed by port as well, because four of the seven dimensions come from a BUS and a
    // branch has two. `supply` joins on the injection's own port; `opex` and the four
    // capital metrics pin `port = 0`, which is where upstream places them. The entity-level
    // dimensions -- `carrier`, `name`, `component` -- are the same at every port, and come
    // off `entity` rather than off `port` so that is visible in the SQL.
    //
    // ==The carrier chain is three steps and every one of them is PyPSA's==
    //
    // `nice_name` where it is set, else the carrier's own name, else a dash. Upstream writes
    // it as `_map_with_multiindex(...).fillna(carrier).replace("", "-")` over a `nice_name`
    // column already filtered by `[lambda ds: ds != ""]` -- so a carrier with an EMPTY nice
    // name falls back to its own name rather than to the dash, and only a component with no
    // carrier at all becomes the dash. Two nested `NULLIF`s in that order are that
    // expression.
    //
    // `bus_carrier` goes through the same chain against the bus's carrier, because the
    // grouper calls `self.carrier(n, "Bus")` rather than reading the column. `location` and
    // `unit` do NOT: they have no dash rendering, so an unset one groups as the empty string,
    // which is what the goldens record.
    """CREATE VIEW dimensions AS
      |SELECT p.component AS component,
      |       p.entity    AS entity,
      |       p.port      AS port,
      |       COALESCE(NULLIF(COALESCE(NULLIF(c.nice_name, ''), e.carrier), ''), '-')
      |         AS carrier,
      |       COALESCE(NULLIF(COALESCE(NULLIF(bc.nice_name, ''), b.carrier), ''), '-')
      |         AS bus_carrier,
      |       p.bus                     AS bus,
      |       COALESCE(b.country, '')   AS country,
      |       COALESCE(b.location, '')  AS location,
      |       COALESCE(b.unit, '')      AS unit,
      |       e.entity                  AS name,
      |       e.component               AS component_group
      |FROM port p
      |JOIN entity e USING (component, entity)
      |LEFT JOIN bus b      ON b.name = p.bus
      |LEFT JOIN carrier c  ON c.name = e.carrier
      |LEFT JOIN carrier bc ON bc.name = b.carrier""".stripMargin,
  )
