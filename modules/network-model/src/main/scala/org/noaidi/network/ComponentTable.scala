package org.noaidi.network

import scala.collection.immutable.ListMap

/** A typed column of static values, one per entity. */
enum Column:
  case Floats(values: IArray[Double])
  case Ints(values: IArray[Int])
  case Strings(values: IArray[String])
  case Bools(values: IArray[Boolean])

  def length: Int = this match
    case Floats(v)  => v.length
    case Ints(v)    => v.length
    case Strings(v) => v.length
    case Bools(v)   => v.length

  def valueType: AttributeType = this match
    case _: Floats  => AttributeType.Float
    case _: Ints    => AttributeType.Int
    case _: Strings => AttributeType.Str
    case _: Bools   => AttributeType.Bool

  /** Rendered value at `row`, for writing and for diagnostics. */
  def text(row: Int): String = this match
    case Floats(v)  => v(row).toString
    case Ints(v)    => v(row).toString
    case Strings(v) => v(row)
    case Bools(v)   => v(row).toString

/** Per-snapshot values for one attribute, for the entities that actually vary.
  *
  * The columns here are a *subset* of the table's entities, and that is the
  * whole point: PyPSA stores a static value for every entity and a time series
  * only for those whose value changes. A generator table with six rows may have
  * three `p_max_pu` columns. Representing this as a dense snapshot-by-entity
  * matrix would produce a correct network and a file that does not match, which
  * is the difference between equivalence and interchangeability.
  */
final case class TimeSeries(
    /** Entity ids carrying an override, in file order. */
    entities: IndexedSeq[String],
    /** `values(snapshot)(entityPosition)`. */
    values: IArray[IArray[Double]],
):
  def snapshotCount: Int = values.length
  def entityCount: Int   = entities.length

  private val positionOf: Map[String, Int] = entities.zipWithIndex.toMap

  /** The override for `entity` at `snapshot`, if that entity varies. */
  def get(entity: String, snapshot: Int): Option[Double] =
    positionOf.get(entity).map(values(snapshot)(_))

  def covers(entity: String): Boolean = positionOf.contains(entity)

/** One component type's data: the entities, their static values, and whatever
  * per-snapshot overrides exist.
  *
  * Static columns are keyed by attribute name and hold one value per entity in
  * `ids` order. `series` holds overrides keyed by attribute name.
  */
final case class ComponentTable(
    spec: ComponentSpec,
    ids: IndexedSeq[String],
    static: ListMap[String, Column],
    series: ListMap[String, TimeSeries],
):
  def size: Int = ids.length

  private val rowOf: Map[String, Int] = ids.zipWithIndex.toMap

  def row(id: String): Int =
    rowOf.getOrElse(id, throw new NoSuchElementException(s"${spec.listName} has no entity '$id'"))

  def has(id: String): Boolean = rowOf.contains(id)

  /** Static value of a float attribute, falling back to the schema default when
    * the column is absent — which is the normal case, since PyPSA omits columns
    * left at their default.
    */
  def float(attribute: String, id: String): Double =
    static.get(attribute) match
      case Some(Column.Floats(v)) => v(row(id))
      case Some(other) =>
        throw new IllegalArgumentException(s"$attribute is ${other.valueType}, not a float")
      case None => defaultFloat(attribute)

  def string(attribute: String, id: String): String =
    static.get(attribute) match
      case Some(Column.Strings(v)) => v(row(id))
      case Some(other) =>
        throw new IllegalArgumentException(s"$attribute is ${other.valueType}, not a string")
      case None => spec.require(attribute).defaultText.getOrElse("")

  def bool(attribute: String, id: String): Boolean =
    static.get(attribute) match
      case Some(Column.Bools(v)) => v(row(id))
      case Some(other) =>
        throw new IllegalArgumentException(s"$attribute is ${other.valueType}, not a boolean")
      case None => spec.require(attribute).defaultText.exists(d => d == "true" || d == "True")

  def int(attribute: String, id: String): Int =
    static.get(attribute) match
      case Some(Column.Ints(v)) => v(row(id))
      case Some(other) =>
        throw new IllegalArgumentException(s"$attribute is ${other.valueType}, not an int")
      case None => spec.require(attribute).defaultText.flatMap(_.toIntOption).getOrElse(0)

  /** The effective value of a possibly-varying float attribute at one snapshot.
    *
    * This is the resolution order that makes the hybrid representation usable:
    * a per-snapshot override if this entity has one, otherwise its static
    * value, otherwise the schema default. Physics code should go through here
    * rather than reaching into `series` and `static` separately, because
    * "absent" means three different things at those three levels.
    */
  def valueAt(attribute: String, id: String, snapshot: Int): Double =
    series.get(attribute).flatMap(_.get(id, snapshot)).getOrElse(float(attribute, id))

  private def defaultFloat(attribute: String): Double =
    spec.require(attribute).defaultText match
      case Some("inf")  => Double.PositiveInfinity
      case Some("-inf") => Double.NegativeInfinity
      case Some("nan")  => Double.NaN
      case Some(text)   => text.toDoubleOption.getOrElse(Double.NaN)
      case None         => Double.NaN

object ComponentTable:
  def empty(spec: ComponentSpec): ComponentTable =
    ComponentTable(spec, IndexedSeq.empty, ListMap.empty, ListMap.empty)

/** A power system network: snapshots plus one table per component type present.
  *
  * Immutable, as the brief requires — every mutation returns a new value. The
  * component tables are held in a map rather than as named fields because the
  * set of component types is schema-driven, not fixed.
  */
final case class Network(
    name: String,
    schema: Schema,
    snapshots: IndexedSeq[String],
    tables: ListMap[String, ComponentTable],
):
  def snapshotCount: Int = snapshots.length

  /** Table for a component type, by name or by PyPSA's plural list name. */
  def table(component: String): Option[ComponentTable] =
    tables.get(component).orElse {
      schema.get(component).flatMap(spec => tables.get(spec.name))
    }

  def require(component: String): ComponentTable =
    table(component).getOrElse(
      throw new NoSuchElementException(s"network '$name' has no $component")
    )

  /** Component types actually present, in insertion order. */
  def presentComponents: IndexedSeq[String] = tables.keys.toIndexedSeq

  def withTable(t: ComponentTable): Network = copy(tables = tables.updated(t.spec.name, t))

  def entityCount: Int = tables.values.map(_.size).sum

object Network:
  def empty(name: String, schema: Schema): Network =
    Network(name, schema, IndexedSeq.empty, ListMap.empty)
