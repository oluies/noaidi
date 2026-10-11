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

    """CREATE TABLE bus (
      |  name    VARCHAR PRIMARY KEY,
      |  carrier VARCHAR,
      |  v_nom   DOUBLE
      |)""".stripMargin,

    // Which periods an asset exists in, for the per-period capital factor that
    // `Expansion.costWeight` computes. Empty on a flat index: there is no period to be
    // active in, and `costWeight` is 1.0 there.
    """CREATE TABLE active (
      |  component VARCHAR NOT NULL,
      |  entity    VARCHAR NOT NULL,
      |  period    VARCHAR NOT NULL
      |)""".stripMargin,
  )
