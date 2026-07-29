package org.noaidi.network

/** How an attribute's value is stored.
  *
  * Read off the pinned PyPSA install rather than chosen here, because the port
  * has to reproduce its files and not merely model the same physics.
  */
enum AttributeType:
  case Float, Int, Str, Bool, Geometry

object AttributeType:
  /** The storage type, from PyPSA's `type` field.
    *
    * That field mixes two concerns: for most attributes it names the element
    * type, but for the time-varying ones it names the *shape* instead —
    * `series`, `static or series`, `static`. Those are all numeric (their
    * defaults and units confirm it: MW, currency/MWh, radians), so they map to
    * float here and [[Variability]] reads the shape out of the same string.
    */
  def parse(s: String): AttributeType = s.trim.toLowerCase match
    case "float"            => Float
    case "int"              => Int
    case "string"           => Str
    case "boolean" | "bool" => Bool
    case "geometry"         => Geometry
    // Shape rather than element type; always numeric.
    case "series" | "static or series" | "static" => Float
    // PyPSA occasionally qualifies a type ("float or None"); the leading word
    // is the storage class and the rest is documentation.
    case other if other.startsWith("float")  => Float
    case other if other.startsWith("int")    => Int
    case other if other.startsWith("string") => Str
    case other if other.startsWith("bool")   => Bool
    case other if other.startsWith("static") || other.startsWith("series") => Float
    case _ =>
      throw new IllegalArgumentException(s"unknown PyPSA attribute type '$s'")

/** Whether an attribute can differ between snapshots.
  *
  * The three cases are genuinely different and collapsing them loses fidelity:
  *
  *   - [[Static]] never varies. One value per entity.
  *   - [[StaticOrSeries]] is an *input* that may vary. PyPSA stores a static
  *     value for every entity and a time series only for those that actually
  *     vary — so `p_max_pu` on a six-generator network may have three columns.
  *     Modelling this as a dense matrix would still give a correct network and
  *     the wrong files.
  *   - [[Series]] is an *output*, written per snapshot for every entity.
  */
enum Variability:
  case Static, StaticOrSeries, Series

  def mayVary: Boolean = this != Static

object Variability:
  def parse(pypsaType: String, varying: Boolean): Variability =
    val t = pypsaType.trim.toLowerCase
    if t == "series" then Series
    else if t.startsWith("static or series") || varying then StaticOrSeries
    else Static

/** One attribute of one component type. */
final case class AttributeSpec(
    name: String,
    valueType: AttributeType,
    variability: Variability,
    unit: String,
    /** Default as PyPSA renders it, unparsed.
      *
      * Kept as text because the default's meaning depends on the type and on
      * PyPSA's own conventions — an empty string for a required reference, `inf`
      * for an unset bound — and reinterpreting it here would be guessing.
      */
    defaultText: Option[String],
    status: String,
):
  /** Output attributes are computed, never read from an input file. */
  def isOutput: Boolean = variability == Variability.Series || status.toLowerCase.contains("output")

/** One component type — buses, generators, lines — and its attributes. */
final case class ComponentSpec(
    name: String,
    listName: String,
    description: String,
    attributes: IndexedSeq[AttributeSpec],
):
  private val byName: Map[String, AttributeSpec] = attributes.map(a => a.name -> a).toMap

  def attribute(name: String): Option[AttributeSpec] = byName.get(name)

  def require(name: String): AttributeSpec =
    byName.getOrElse(name, throw new NoSuchElementException(s"$listName has no attribute '$name'"))

  /** Attributes that can appear in an input file. */
  def inputAttributes: IndexedSeq[AttributeSpec] = attributes.filterNot(_.isOutput)

  /** Attributes that may carry a per-snapshot time series. */
  def varyingAttributes: IndexedSeq[AttributeSpec] = attributes.filter(_.variability.mayVary)

/** The whole component model.
  *
  * Deliberately data rather than a set of case classes. PyPSA's model is
  * dynamic — attributes come from metadata and users add their own — so a fixed
  * Scala shape would either reject valid networks or need regenerating whenever
  * upstream adds a field. Typed access is layered on top instead, in
  * [[Components]], where the physics wants it.
  */
final case class Schema(components: IndexedSeq[ComponentSpec]):
  private val byName: Map[String, ComponentSpec]     = components.map(c => c.name -> c).toMap
  private val byListName: Map[String, ComponentSpec] = components.map(c => c.listName -> c).toMap

  def apply(name: String): ComponentSpec =
    byName
      .get(name)
      .orElse(byListName.get(name))
      .getOrElse(throw new NoSuchElementException(s"no component type '$name'"))

  def get(name: String): Option[ComponentSpec] = byName.get(name).orElse(byListName.get(name))

  def componentNames: IndexedSeq[String] = components.map(_.name)

  def attributeCount: Int = components.map(_.attributes.size).sum

object Schema:

  /** Parse the schema PyPSA's own registry produced.
    *
    * The file is generated by `reference/generate_goldens.py` from the pinned
    * install. Parsing it rather than transcribing it means a version bump is a
    * reviewable diff instead of a silent divergence.
    */
  def fromJson(json: String): Schema =
    val root = ujson.read(json).obj
    val components = root.toIndexedSeq
      .sortBy(_._1)
      .map { (componentName, body) =>
        val o = body.obj
        val attrs = o("attributes").obj.toIndexedSeq
          .map { (attrName, attrBody) =>
            val a          = attrBody.obj
            val pypsaType  = a("type").str
            val varying    = a.get("varying").exists(_.bool)
            val defaultRaw = a.get("default").map(renderDefault)
            AttributeSpec(
              name = attrName,
              valueType = AttributeType.parse(pypsaType),
              variability = Variability.parse(pypsaType, varying),
              unit = a.get("unit").map(_.str).getOrElse(""),
              defaultText = defaultRaw.filter(_.nonEmpty),
              status = a.get("status").map(_.str).getOrElse(""),
            )
          }
        ComponentSpec(
          name = componentName,
          listName = o("list_name").str,
          description = o.get("description").map(_.str).getOrElse(""),
          attributes = attrs,
        )
      }
    Schema(components)

  /** The generator tags non-finite defaults rather than emitting invalid JSON. */
  private def renderDefault(v: ujson.Value): String = v match
    case ujson.Str(s)  => s
    case ujson.Num(n)  => if n == n.floor && n.abs < 1e15 then n.toLong.toString else n.toString
    case ujson.True    => "true"
    case ujson.False   => "false"
    case ujson.Null    => ""
    case o: ujson.Obj =>
      if o.value.contains("$nan") then "nan"
      else
        o.value.get("$inf") match
          case Some(ujson.Str("-")) => "-inf"
          case Some(_)              => "inf"
          case None                 => o.render()
    case other => other.render()

  def fromFile(path: java.nio.file.Path): Schema =
    fromJson(java.nio.file.Files.readString(path))
