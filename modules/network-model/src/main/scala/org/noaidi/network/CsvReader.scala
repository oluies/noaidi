package org.noaidi.network

import java.nio.file.{Files, Path}
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

/** Reader for PyPSA's CSV directory format.
  *
  * The layout, as PyPSA writes it:
  *
  * {{{
  *   snapshots.csv                  the snapshot index
  *   buses.csv                      static attributes, one row per entity
  *   generators.csv
  *   generators-p_max_pu.csv        per-snapshot overrides for one attribute
  *   loads-p_set.csv
  * }}}
  *
  * Two properties of the format drive the design, and both were established by
  * inspecting what PyPSA actually writes rather than from its documentation:
  *
  *   - '''Columns left at their default are omitted.''' `buses.csv` on the
  *     reference network carries six columns out of Bus's nineteen attributes.
  *     So a missing column is not missing data, and the reader must not invent
  *     one — resolution falls through to the schema default at access time.
  *   - '''Time series cover only the entities that vary.''' A six-generator
  *     network can have three `p_max_pu` columns. The file name encodes which
  *     attribute it belongs to, as `<list_name>-<attribute>.csv`.
  */
object CsvReader:

  final class MalformedNetwork(message: String) extends RuntimeException(message)

  def read(directory: Path, schema: Schema, name: String = ""): Network =
    if !Files.isDirectory(directory) then
      throw new MalformedNetwork(s"$directory is not a directory")

    val files = Files.list(directory).iterator.asScala.toIndexedSeq.map(_.getFileName.toString).sorted
    val csvs  = files.filter(_.endsWith(".csv"))

    val (snapshots, weightings) = readSnapshots(directory.resolve("snapshots.csv"))

    // Split `generators.csv` from `generators-p_max_pu.csv`. Component list
    // names contain underscores but not hyphens, and PyPSA uses the hyphen
    // precisely as this separator.
    val staticFiles = csvs.filterNot(_.contains("-")).filterNot(reserved.contains)
    val seriesFiles = csvs.filter(f => f.contains("-") && !reserved.contains(f))

    val seriesByComponent = seriesFiles.groupBy { f =>
      val stem = f.stripSuffix(".csv")
      stem.substring(0, stem.indexOf('-'))
    }

    val tables = staticFiles.flatMap { file =>
      val listName = file.stripSuffix(".csv")
      schema.get(listName).map { spec =>
        val table = readStatic(directory.resolve(file), spec)
        val withSeries = seriesByComponent.getOrElse(listName, IndexedSeq.empty).foldLeft(table) {
          (acc, seriesFile) =>
            val stem      = seriesFile.stripSuffix(".csv")
            val attribute = stem.substring(stem.indexOf('-') + 1)
            val ts        = readSeries(directory.resolve(seriesFile), snapshots.length, attribute, spec)
            acc.copy(series = acc.series.updated(attribute, ts))
        }
        withSeries
      }
    }

    Network(
      name = if name.nonEmpty then name else directory.getFileName.toString,
      schema = schema,
      snapshots = snapshots,
      tables = ListMap.from(tables.map(t => t.spec.name -> t)),
      snapshotWeightings = weightings,
    )

  /** Files that are not component tables. */
  private val reserved = Set("snapshots.csv", "network.csv", "investment_periods.csv")

  /** Snapshot labels and their weightings.
    *
    * The file is `,snapshot,objective,stores,generators`: a blank index column,
    * the label, then the weightings. Taking the first field would read the
    * positional index rather than the timestamp, which happens to look
    * plausible and is wrong.
    */
  private def readSnapshots(path: Path): (IndexedSeq[String], ListMap[String, IArray[Double]]) =
    if !Files.exists(path) then (IndexedSeq.empty, ListMap.empty)
    else
      val rows = parse(path)
      if rows.isEmpty then (IndexedSeq.empty, ListMap.empty)
      else
        val header = rows.head
        val body   = rows.tail
        val labelAt = header.indexOf("snapshot") match
          case -1 => if header.length > 1 then 1 else 0
          case i  => i
        val labels = body.map(r => if labelAt < r.length then r(labelAt) else "")

        val weightings = header.zipWithIndex
          .filter((name, i) => i != labelAt && name.nonEmpty)
          .map { (name, i) =>
            name -> IArray.from(body.map { r =>
              if i < r.length then r(i).trim.toDoubleOption.getOrElse(1.0) else 1.0
            })
          }
        (labels, ListMap.from(weightings))

  private def readStatic(path: Path, spec: ComponentSpec): ComponentTable =
    val rows = parse(path)
    if rows.isEmpty then return ComponentTable.empty(spec)

    val header = rows.head
    val body   = rows.tail
    if header.isEmpty then return ComponentTable.empty(spec)

    // PyPSA writes the entity id in the first column, headed "name".
    val ids = body.map(_.headOption.getOrElse(""))

    val columns = header.zipWithIndex.tail.map { (columnName, index) =>
      val cells = body.map(r => if index < r.length then r(index) else "")
      // A column with no schema entry is a custom attribute, not an error.
      // PyPSA lets users add their own and the reference network does exactly
      // that -- `buses.csv` carries a `country` column that is nowhere in the
      // component metadata. Dropping unknown columns silently loses data on
      // every round-trip, so the type is inferred from the values instead.
      val col = spec.attribute(columnName) match
        case Some(attr) => column(attr, cells, columnName, spec)
        case None       => inferColumn(cells)
      columnName -> col
    }

    ComponentTable(spec, ids, ListMap.from(columns), ListMap.empty)

  private def column(
      attr: AttributeSpec,
      cells: IndexedSeq[String],
      columnName: String,
      spec: ComponentSpec,
  ): Column =
    attr.valueType match
      case AttributeType.Float =>
        Column.Floats(IArray.from(cells.map(parseFloat(_, columnName, spec))))
      case AttributeType.Int =>
        // PyPSA writes integers through pandas, which may render them as "3.0".
        Column.Ints(IArray.from(cells.map { c =>
          val t = c.trim
          t.toIntOption.orElse(t.toDoubleOption.map(_.toInt)).getOrElse(0)
        }))
      case AttributeType.Bool =>
        Column.Bools(IArray.from(cells.map { c =>
          val t = c.trim.toLowerCase
          t == "true" || t == "1" || t == "1.0"
        }))
      case AttributeType.Str | AttributeType.Geometry =>
        Column.Strings(IArray.from(cells.map(_.trim)))

  /** Type of a column the schema does not describe, from its values.
    *
    * Conservative: a column is only numeric or boolean if every value is, so a
    * mixed column stays textual and survives the round-trip verbatim.
    */
  private def inferColumn(cells: IndexedSeq[String]): Column =
    val trimmed = cells.map(_.trim)
    val present = trimmed.filter(_.nonEmpty)
    if present.nonEmpty && present.forall(c => c == "True" || c == "False") then
      Column.Bools(IArray.from(trimmed.map(_ == "True")))
    else if present.nonEmpty && present.forall(_.toDoubleOption.isDefined) then
      Column.Floats(IArray.from(trimmed.map(c => if c.isEmpty then Double.NaN else c.toDouble)))
    else Column.Strings(IArray.from(trimmed))

  private def parseFloat(cell: String, columnName: String, spec: ComponentSpec): Double =
    val t = cell.trim
    if t.isEmpty then Double.NaN
    else
      t.toDoubleOption.getOrElse {
        t.toLowerCase match
          case "inf" | "+inf" | "infinity"  => Double.PositiveInfinity
          case "-inf" | "-infinity"         => Double.NegativeInfinity
          case "nan" | "" | "na"            => Double.NaN
          case _ =>
            throw new MalformedNetwork(
              s"${spec.listName}.$columnName: '$cell' is not a number"
            )
      }

  private def readSeries(
      path: Path,
      snapshotCount: Int,
      attribute: String,
      spec: ComponentSpec,
  ): TimeSeries =
    val rows = parse(path)
    if rows.isEmpty then return TimeSeries(IndexedSeq.empty, IArray.empty)

    // The header's first field is the snapshot index, which is blank in PyPSA's
    // output; the rest are the entities that carry an override.
    val entities = rows.head.tail.map(_.trim)
    val body     = rows.tail

    if body.length != snapshotCount then
      throw new MalformedNetwork(
        s"${spec.listName}-$attribute has ${body.length} rows but the network has " +
          s"$snapshotCount snapshots"
      )

    val values = IArray.from(body.map { r =>
      IArray.from(entities.indices.map { i =>
        val cell = if i + 1 < r.length then r(i + 1) else ""
        parseFloat(cell, attribute, spec)
      })
    })
    TimeSeries(entities, values)

  /** Minimal CSV parse: quoted fields with doubled quotes, no embedded newlines.
    *
    * PyPSA writes through pandas, which quotes any field containing a comma —
    * entity names like "Manchester Wind" do not need it, but bus names in real
    * datasets can. Embedded newlines do not occur in this format and are not
    * handled, so a file containing one fails loudly on field count rather than
    * silently misaligning.
    */
  private def parse(path: Path): IndexedSeq[IndexedSeq[String]] =
    if !Files.exists(path) then IndexedSeq.empty
    else
      Files
        .readAllLines(path)
        .asScala
        .toIndexedSeq
        .filter(_.nonEmpty)
        .map(splitLine)

  private[network] def splitLine(line: String): IndexedSeq[String] =
    val out     = IndexedSeq.newBuilder[String]
    val field   = StringBuilder()
    var quoted  = false
    var i       = 0
    while i < line.length do
      val c = line.charAt(i)
      if quoted then
        if c == '"' then
          if i + 1 < line.length && line.charAt(i + 1) == '"' then
            field.append('"')
            i += 1
          else quoted = false
        else field.append(c)
      else if c == '"' then quoted = true
      else if c == ',' then
        out += field.toString
        field.clear()
      else field.append(c)
      i += 1
    out += field.toString
    out.result()
