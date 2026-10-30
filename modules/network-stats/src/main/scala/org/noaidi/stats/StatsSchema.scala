package org.noaidi.stats

/** The star schema a solved result is loaded into, as DDL.
  *
  * Two long fact tables and six dimensions, with eight views over them. Long rather than wide
  * because the quantities differ by component — a generator has one series, a storage unit has
  * five — so a wide table is a sparse column per component per quantity and every query has to
  * know which of them exist for the components it touches.
  *
  * The second fact table is `price`, and it is separate rather than a `quantity` in `fact`
  * because it is keyed by BUS: a nodal price belongs to the bus rather than to anything
  * attached to it, and `prices` groups buses rather than components.
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

    // The dual of each bus balance, one row per (bus, snapshot).
    //
    // The first DUAL quantity in this schema, and the last three metrics all read it:
    // `revenue` prices an injection with it, `market_value` divides that by what the asset
    // operated, and `prices` reports it on its own.
    //
    // `LopfResult.marginalPrice` already divides by `Periods.objectiveWeight` -- the
    // snapshot's `objective` weighting times its period's -- because PyPSA divides its own
    // `buses_t.marginal_price` by exactly that in `assign_solution`. So what is stored here is
    // a price per unit of energy, and a metric that sums it back up multiplies the weighting
    // in again.
    //
    // ==It is the one number here that can be a different point of the same optimum==
    //
    // A nodal price is a dual variable, and a dual face is far more common than a primal one:
    // every bus whose marginal unit is at a bound has an interval of valid prices. NOTES
    // records this for the LOPF, and `StatsSuite` inherits the determinacy measurement that
    // covers it -- the generator's probe solves twice and compares, so a price group that two
    // solvers disagree about is skipped rather than compared.
    """CREATE TABLE price (
      |  bus      VARCHAR NOT NULL,
      |  snapshot INTEGER NOT NULL,
      |  value    DOUBLE  NOT NULL,
      |  PRIMARY KEY (bus, snapshot)
      |)""".stripMargin,

    // One row per (component, entity, port): the bus that port attaches to.
    //
    // A TABLE now, loaded from `Topology.branchPorts`, where it used to be a view over
    // `entity.bus` and `entity.bus1`. That view could only ever carry two ports, and a view is
    // the wrong shape for a fact the network decides: a link may have `bus2` and beyond, and
    // `Lopf` has always built them -- `branchPorts` walks every `busN` column and the balance
    // reads `efficiency2` beside `efficiency`.
    //
    // This module refused such a network by name instead, which was the honest thing to do
    // while no golden had one: two ports silently carrying three is a supply short by whatever
    // the third injects. `multi-port-link` is the golden that ended that, and it is not a
    // marginal shape -- its electrolyser's waste heat is 95 of a 323 `supply` group, and
    // zeroing `efficiency2` moves the objective from 7,580 to 12,900.
    //
    // A one-port has port 0 and nothing else. A branch has port 0 at `bus0` and a receiving
    // port per further `busN`, which is what lets `supply` attribute a flow to every bus that
    // RECEIVES it while `opex` and the capacity metrics stay at port 0.
    """CREATE TABLE port (
      |  component VARCHAR NOT NULL,
      |  entity    VARCHAR NOT NULL,
      |  port      INTEGER NOT NULL,
      |  bus       VARCHAR NOT NULL,
      |  PRIMARY KEY (component, entity, port)
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
    // ==The receiving port's efficiency is joined by PORT NUMBER==
    //
    // PyPSA names them `efficiency` for `bus1` and `efficiency<i>` for later ports, and this
    // stores each under `efficiency<port>` in `fact` -- so the join is
    // `'efficiency' || i.port` and one query covers a branch with any number of ports rather
    // than one `LEFT JOIN` per port. A passive branch declares none and `COALESCE(..., 1.0)`
    // makes it lossless, which is what the linear model does with it.
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
      |SELECT f.component, f.entity, f.snapshot, p.port AS port,
      |       f.value * COALESCE(eff.value, 1.0) AS value
      |FROM fact f
      |JOIN port p ON p.component = f.component AND p.entity = f.entity AND p.port > 0
      |LEFT JOIN fact eff
      |  ON  eff.component = f.component AND eff.entity = f.entity
      |  AND eff.snapshot  = f.snapshot
      |  AND eff.quantity  = 'efficiency' || CAST(p.port AS VARCHAR)
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
    // ==Any two ports of the same carrier, not just `bus0` and `bus1`==
    //
    // Driven off `port` rather than off `entity.bus`/`entity.bus1`, which is what a branch with
    // three of them needs: upstream maps EVERY `bus*` column through `buses.carrier` and keeps
    // a branch where more than one end matches the same value, so a three-port link whose
    // second and third ports share a carrier is transmission even though its first does not.
    // Measured on `multi-port-link`, whose three ports are AC, H2 and heat: no two match, and
    // upstream reports no transmission group for it at all.
    """CREATE VIEW transmission_branch AS
      |SELECT DISTINCT a.component, a.entity
      |FROM port a
      |JOIN port b
      |  ON b.component = a.component AND b.entity = a.entity AND b.port > a.port
      |JOIN bus ba ON ba.name = a.bus
      |JOIN bus bb ON bb.name = b.bus
      |WHERE COALESCE(ba.carrier, '') = COALESCE(bb.carrier, '')""".stripMargin,

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

    // Every grouping dimension of a BUS, which is not the same view as `dimensions`.
    //
    // `prices` is the one metric keyed by bus rather than by component, and upstream groups it
    // by reading a column of `n.c.buses.static` directly:
    //
    //     col = "carrier" if key == "bus_carrier" else key
    //
    // So `bus_carrier` and `carrier` are the SAME grouping here -- both the bus's own carrier
    // -- `name` is the bus's name, and anything else must be a static bus column or upstream
    // raises `ValueError: Grouping prices by 'bus' is not supported`. Which is why `bus` is
    // absent below: there is no `bus` column on `buses.csv`, and every golden records that
    // refusal.
    //
    // ==And the carrier is RAW, not the nice name==
    //
    // `prices` ends with `self._apply_option_kwargs(df, nice_names=False)`, with a comment
    // upstream saying to add it "once integrated in function". Every other metric here maps a
    // carrier through `nice_name` and this one does not: `statistics-dimensions` sets
    // `nice_name` on both its carriers and the golden records `prices|carrier` as `AC` and
    // `H2` rather than `Alternating current` and `H2`. So this view reads the column, where
    // `dimensions` reads the chain.
    """CREATE VIEW bus_dimensions AS
      |SELECT b.name                     AS name,
      |       COALESCE(b.carrier, '')    AS carrier,
      |       COALESCE(b.carrier, '')    AS bus_carrier,
      |       COALESCE(b.country, '')    AS country,
      |       COALESCE(b.location, '')   AS location,
      |       COALESCE(b.unit, '')       AS unit
      |FROM bus b""".stripMargin,

    // How much is withdrawn at each bus at each snapshot, in MW and unweighted.
    //
    // This is `withdrawal(groupby="bus", groupby_time=False)` -- the weight `prices` averages
    // with under its default `weighting="load"`. Unweighted because `groupby_time=False`
    // returns the raw series: the snapshot weighting enters once, in `prices` itself, on both
    // the numerator and the denominator.
    //
    // ==A bus appears because something ATTACHES to it, not because it withdraws==
    //
    // The clip is inside the `SUM` and there is no `WHERE`, so a bus with a component port on
    // it has a row at every snapshot even when that row is zero. Upstream computes
    // `-p.clip(upper=0)` for every component and then groups, which produces zeros rather
    // than absences, and the distinction decides an answer: `link-delay`'s bus `a` carries a
    // generator and a link that never run, so upstream's weight for it is zero at every
    // snapshot and its price comes out `0 / 0` -- a NaN, which the golden records. Filtering
    // on `i.value < 0` instead made that bus look like one nothing attaches to, which gets a
    // weight of 1 and a price of 0.
    """CREATE VIEW bus_withdrawal AS
      |SELECT p.bus AS bus, i.snapshot AS snapshot, SUM(GREATEST(-i.value, 0)) AS value
      |FROM injection i
      |JOIN port p ON p.component = i.component AND p.entity = i.entity AND p.port = i.port
      |GROUP BY p.bus, i.snapshot""".stripMargin,

    // The weight `prices` averages with, dense over every (bus, snapshot).
    //
    // `prices` defaults to `weighting="load"` and its weight is
    // `withdrawal(groupby="bus", groupby_time=False)` reindexed onto the price frame's columns
    // with `fill_value=1`. That reindex is '''per bus, not per cell''', and the difference is
    // the whole reason this view exists rather than a `COALESCE` at the call site:
    //
    //   - a bus NOTHING ATTACHES TO is absent from the frame, so every one of its snapshots
    //     gets a weight of 1 -- which makes its price a flat time average rather than a NaN
    //   - a bus something attaches to is present even where that something never runs, so its
    //     zeros stay zeros and its price can be a `0 / 0`
    //
    // A `COALESCE(w.value, 1.0)` reads like the first rule and silently applies it to the
    // second case too, handing a quiet hour the same weight as a busy one.
    //
    // ==And the fixture that shows it is skipped, which is worth knowing==
    //
    // The per-cell reading was load-bearing when it was written: it reported `link-delay`'s
    // `prices|bus_carrier` as 86.54 against upstream's 90, because that bus withdraws 100 MW
    // at one snapshot and nothing at the other three, so a fill of 1 at the quiet ones drags
    // the average down. `link-delay` is now skipped -- its nodal prices are a different vertex
    // of the dual -- and the mutant survives. Kept because it is upstream's rule and because
    // the reason it is untested is a property of this port's duals rather than of the rule.
    """CREATE VIEW bus_load_weight AS
      |SELECT b.name     AS bus,
      |       s.snapshot AS snapshot,
      |       CASE
      |         WHEN NOT EXISTS (SELECT 1 FROM bus_withdrawal w WHERE w.bus = b.name) THEN 1.0
      |         ELSE COALESCE((SELECT w.value FROM bus_withdrawal w
      |                        WHERE w.bus = b.name AND w.snapshot = s.snapshot), 0.0)
      |       END        AS value
      |FROM bus b
      |CROSS JOIN snapshot s""".stripMargin,
  )
