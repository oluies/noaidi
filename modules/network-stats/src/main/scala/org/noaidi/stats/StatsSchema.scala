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

    // Every grouping dimension, resolved once per entity.
    //
    // A view rather than seven expressions repeated in nineteen queries, which is the shape
    // this module exists to avoid: a metric names a dimension by column, and the joins and
    // the `nice_name` fall-back live in one place. `Stats.dimension` validates the name
    // against this view, so an unknown grouper is refused rather than interpolated.
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
      |SELECT e.component AS component,
      |       e.entity    AS entity,
      |       COALESCE(NULLIF(COALESCE(NULLIF(c.nice_name, ''), e.carrier), ''), '-')
      |         AS carrier,
      |       COALESCE(NULLIF(COALESCE(NULLIF(bc.nice_name, ''), b.carrier), ''), '-')
      |         AS bus_carrier,
      |       e.bus                     AS bus,
      |       COALESCE(b.country, '')   AS country,
      |       COALESCE(b.location, '')  AS location,
      |       COALESCE(b.unit, '')      AS unit,
      |       e.entity                  AS name,
      |       e.component               AS component_group
      |FROM entity e
      |LEFT JOIN bus b      ON b.name = e.bus
      |LEFT JOIN carrier c  ON c.name = e.carrier
      |LEFT JOIN carrier bc ON bc.name = b.carrier""".stripMargin,
  )
