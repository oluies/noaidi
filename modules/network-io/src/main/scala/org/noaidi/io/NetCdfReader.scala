package org.noaidi.io

import io.jhdf.HdfFile
import io.jhdf.api.Dataset
import java.nio.file.Path
import java.time.{Duration, LocalDateTime}
import java.time.format.DateTimeFormatter
import org.noaidi.network.*
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

/** Reads PyPSA's netCDF export into the same [[Network]] the CSV reader produces.
  *
  * PyPSA's netCDF is '''netCDF-4''', which is an HDF5 container — the file begins
  * with the HDF5 signature, not `CDF`. So this needs an HDF5 reader rather than a
  * netCDF-3 parser, and jhdf is a pure-Java one, which keeps the module free of
  * native libraries and of the platform-specific packaging they bring.
  *
  * ==Layout==
  *
  * Everything sits flat at the root, named by convention:
  *
  * {{{
  * <list>_i                  the entity names, e.g. buses_i
  * <list>_<attr>             a static column over <list>_i
  * <list>_t_<attr>           a time series over (snapshots, <list>_t_<attr>_i)
  * <list>_t_<attr>_i         the entities that vary, a subset of <list>_i
  * snapshots_snapshot        the snapshot labels
  * snapshots_<weighting>     objective / stores / generators weightings
  * }}}
  *
  * Component list names come from the schema rather than from splitting the
  * dataset name on underscores, which would be ambiguous — `sub_networks_carrier`
  * has an underscore in the list name and `p_nom_extendable` has two in the
  * attribute.
  *
  * ==Two conventions that are not obvious from the file==
  *
  * '''Time is CF-encoded.''' `snapshots_snapshot` holds integers with a `units`
  * attribute like `hours since 2015-01-01 00:00:00`, not timestamps. Reading the
  * raw values would give a network whose snapshots are `0, 1, 2` where the CSV
  * says `2015-01-01 00:00:00`, and the two would silently disagree. A network
  * whose snapshots are genuinely integers — `unit-commitment` — carries no
  * `units` at all, so the presence of the attribute is what distinguishes them.
  *
  * '''Booleans are bytes.''' netCDF has no boolean type, so xarray writes `int8`
  * and marks it with a `dtype = bool` attribute. Without that check a boolean
  * column reads back as an integer one, and `p_nom_extendable` stops being
  * comparable with the CSV reader's.
  */
object NetCdfReader:

  final class MalformedNetwork(message: String) extends RuntimeException(message)

  def read(file: Path, schema: Schema, name: String): Network =
    val hdf = new HdfFile(file)
    try readFrom(hdf, schema, name)
    finally hdf.close()

  private def readFrom(hdf: HdfFile, schema: Schema, name: String): Network =
    val datasets = hdf.getChildren.asScala.collect { case (n, d: Dataset) => n -> d }.toMap

    val snapshots = readSnapshots(datasets)
    // Discovered, not enumerated. `CsvReader` reads every non-label column of
    // snapshots.csv as a weighting, so hardcoding three names here would drop a
    // fourth that PyPSA adds -- and the two readers would then disagree about a
    // network neither of them is wrong about.
    val weightings = ListMap.from(
      datasets.toIndexedSeq
        .collect {
          case (n, d) if n.startsWith("snapshots_") && n != "snapshots_snapshot" =>
            n.drop("snapshots_".length) -> IArray.from(doubles(d))
        }
        .sortBy(_._1)
    )

    // Driven off the schema's list names. Splitting a dataset name on
    // underscores could not tell `sub_networks_carrier` apart from a `networks`
    // list with a `sub_carrier` attribute; the schema is what says which prefixes
    // are real component lists.
    //
    // An earlier version sorted these by descending name length to defend
    // against one list name prefixing another. No two of the schema's sixteen
    // do, and each spec filters the datasets independently with nothing
    // consumed, so the sort could not have changed the outcome either way.
    val tables = schema.components.flatMap { spec =>
      datasets.get(s"${spec.listName}_i").map { index =>
        spec.name -> readTable(spec, strings(index), datasets, snapshots.length)
      }
    }

    Network(
      name = name,
      schema = schema,
      snapshots = snapshots,
      tables = ListMap.from(tables.sortBy(_._1)),
      snapshotWeightings = weightings,
    )

  private def readTable(
      spec: ComponentSpec,
      ids: IndexedSeq[String],
      datasets: Map[String, Dataset],
      snapshotCount: Int,
  ): ComponentTable =
    val prefix = s"${spec.listName}_"

    val statics = ListMap.from(
      datasets.toIndexedSeq
        .filter((n, _) => n.startsWith(prefix))
        .flatMap { (n, d) =>
          val rest = n.drop(prefix.length)
          if rest == "i" || rest.startsWith("t_") then None
          else
            val col = column(d, rest, spec)
            // The same check the series path already had. Without it a mis-sized
            // column is stored happily and surfaces much later as an
            // ArrayIndexOutOfBoundsException naming neither file nor attribute.
            if col.length != ids.length then
              throw new MalformedNetwork(
                s"$n holds ${col.length} values but ${spec.listName}_i has ${ids.length} entities"
              )
            Some(rest -> col)
        }
        .sortBy(_._1)
    )

    val series = ListMap.from(
      datasets.toIndexedSeq
        .filter((n, _) => n.startsWith(prefix + "t_") && !n.endsWith("_i"))
        .flatMap { (n, d) =>
          val attribute = n.drop(prefix.length + 2)
          datasets.get(s"$n" + "_i").map { entityIndex =>
            val entities = strings(entityIndex)
            val values   = matrix(d)
            if values.length != snapshotCount then
              throw new MalformedNetwork(
                s"$n has ${values.length} rows but the network has $snapshotCount snapshots"
              )
            values.foreach { row =>
              if row.length != entities.length then
                throw new MalformedNetwork(
                  s"$n has a row of ${row.length} values against ${entities.length} entities"
                )
            }
            attribute -> TimeSeries(entities, IArray.from(values.map(IArray.from(_))))
          }
        }
        .sortBy(_._1)
    )

    ComponentTable(spec, ids, statics, series)

  /** Snapshot labels, decoding CF time when the file says it is time.
    *
    * Rendered as `yyyy-MM-dd HH:mm:ss`, which is how PyPSA writes them to CSV, so
    * the two readers agree on the label rather than on an ISO variant of it.
    */
  private def readSnapshots(datasets: Map[String, Dataset]): IndexedSeq[String] =
    val d = datasets.getOrElse(
      "snapshots_snapshot",
      throw new MalformedNetwork("the file has no snapshots_snapshot dataset"),
    )
    val units = attribute(d, "units")

    units match
      case None =>
        // Not a time axis at all -- PyPSA allows any index, and `unit-commitment`
        // uses plain integers.
        d.getData match
          case a: Array[String] => a.toIndexedSeq
          case _                => longs(d).map(_.toString)

      case Some(spec) =>
        val (unit, epoch) = parseUnits(spec)
        val formatter     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // Offsets are read as doubles, because CF permits fractional ones and
        // xarray emits them whenever the units are pinned rather than inferred --
        // `0.5` under `days since ...` is half a day. Truncating to a long turned
        // that into `0`, a label twelve hours out with no error, which is exactly
        // the silent disagreement this decoding exists to prevent.
        doubles(d).map { offset =>
          val nanos = offset * unit.toNanos.toDouble
          if math.abs(nanos - math.rint(nanos)) > 1e-3 then
            throw new MalformedNetwork(
              s"the snapshot offset $offset in '$spec' is not a whole number of nanoseconds"
            )
          epoch.plusNanos(math.rint(nanos).toLong).format(formatter)
        }

  /** `<unit> since <timestamp>`, the CF convention. */
  private def parseUnits(spec: String): (Duration, LocalDateTime) =
    val parts = spec.split(" since ", 2)
    if parts.length != 2 then
      throw new MalformedNetwork(s"cannot read the time units '$spec'")
    // CF allows singular and abbreviated forms as well as the plurals xarray
    // happens to write, so the word is normalised rather than matched literally.
    val word = parts(0).trim.toLowerCase.stripSuffix("s")
    val unit = word match
      case "day" | "d"                  => Duration.ofDays(1)
      case "hour" | "hr" | "h"          => Duration.ofHours(1)
      case "minute" | "min"             => Duration.ofMinutes(1)
      case "second" | "sec" | "s"       => Duration.ofSeconds(1)
      case "millisecond" | "msec" | "ms" => Duration.ofMillis(1)
      case "microsecond" | "usec" | "us" => Duration.ofNanos(1000)
      case "nanosecond" | "nsec" | "ns"  => Duration.ofNanos(1)
      case other => throw new MalformedNetwork(s"unsupported time unit '$other'")

    // A CF epoch may carry a zone offset. Stripping it rather than replacing
    // every space with `T`, which turned `2015-01-01 00:00:00 +00:00` into
    // `2015-01-01T00:00:00T+00:00` and then failed to parse something valid.
    val raw    = parts(1).trim
    val zoned  = raw.split("\\s+")
    val stamp  = if zoned.length >= 2 && !zoned(1).startsWith("+") && !zoned(1).startsWith("-")
                 then s"${zoned(0)}T${zoned(1)}"
                 else zoned(0)
    val text   = stamp.stripSuffix("Z")
    val epoch =
      try LocalDateTime.parse(if text.length == 10 then s"${text}T00:00:00" else text)
      catch case _: Exception => throw new MalformedNetwork(s"cannot read the epoch '$raw'")
    (unit, epoch)

  /** A static column, typed by the schema where it declares one.
    *
    * `CsvReader` types from the schema, and this has to agree or the two produce
    * different models of the same network. Inferring from the file's dtype alone
    * is not equivalent: pandas promotes an int column to float64 the moment it
    * holds a NaN, so a schema-declared Int can arrive as doubles and
    * `table.int(...)` would then throw. The `dtype = bool` marker is one instance
    * of that same problem, and is kept for CUSTOM columns, where the schema has
    * nothing to say.
    */
  private def column(d: Dataset, attribute: String, spec: ComponentSpec): Column =
    spec.attribute(attribute).map(_.valueType) match
      case Some(AttributeType.Float) => Column.Floats(IArray.from(doubles(d)))
      case Some(AttributeType.Int)   => Column.Ints(IArray.from(doubles(d).map(_.toInt)))
      case Some(AttributeType.Bool)  => Column.Bools(IArray.from(booleans(d)))
      case Some(AttributeType.Str)   => Column.Strings(IArray.from(strings(d)))
      // Geometry is not modelled, and an undeclared attribute is a custom column
      // the schema says nothing about; both fall back to the file's own dtype.
      case _                         => inferred(d, s"${spec.listName}.$attribute")

  private def booleans(d: Dataset): IndexedSeq[Boolean] = d.getData match
    case a: Array[Byte]    => a.map(_ != 0).toIndexedSeq
    case a: Array[Boolean] => a.toIndexedSeq
    case a: Array[Int]     => a.map(_ != 0).toIndexedSeq
    case other =>
      throw new MalformedNetwork(s"expected a boolean, got ${other.getClass.getSimpleName}")

  /** A custom column, typed from the file since the schema does not declare it. */
  private def inferred(d: Dataset, what: String): Column =
    // netCDF has no boolean, so xarray writes int8 and records the real type in a
    // `dtype` attribute. Inferring from the values would make a column of 0s and
    // 1s an integer column, which then does not compare equal to the CSV reader's.
    val declaredBool = attribute(d, "dtype").contains("bool")
    d.getData match
      case a: Array[String]  => Column.Strings(IArray.from(a))
      case a: Array[Double]  => Column.Floats(IArray.from(a))
      case a: Array[Float]   => Column.Floats(IArray.from(a.map(_.toDouble)))
      case a: Array[Byte] if declaredBool  => Column.Bools(IArray.from(a.map(_ != 0)))
      case a: Array[Byte]    => Column.Ints(IArray.from(a.map(_.toInt)))
      case a: Array[Short]   => Column.Ints(IArray.from(a.map(_.toInt)))
      case a: Array[Int]     => Column.Ints(IArray.from(a))
      case a: Array[Long]    => Column.Ints(IArray.from(a.map(_.toInt)))
      case other =>
        throw new MalformedNetwork(s"$what has an unsupported type ${other.getClass.getSimpleName}")

  private def attribute(d: Dataset, name: String): Option[String] =
    Option(d.getAttributes.get(name)).map(_.getData).map {
      case s: String        => s
      case a: Array[String] => a.mkString
      case other            => other.toString
    }

  private def strings(d: Dataset): IndexedSeq[String] = d.getData match
    case a: Array[String] => a.toIndexedSeq
    case other =>
      throw new MalformedNetwork(s"expected text, got ${other.getClass.getSimpleName}")

  private def doubles(d: Dataset): IndexedSeq[Double] = d.getData match
    case a: Array[Double] => a.toIndexedSeq
    case a: Array[Float]  => a.map(_.toDouble).toIndexedSeq
    case a: Array[Long]   => a.map(_.toDouble).toIndexedSeq
    case a: Array[Int]    => a.map(_.toDouble).toIndexedSeq
    case other =>
      throw new MalformedNetwork(s"expected numbers, got ${other.getClass.getSimpleName}")

  private def longs(d: Dataset): IndexedSeq[Long] = d.getData match
    case a: Array[Long]   => a.toIndexedSeq
    case a: Array[Int]    => a.map(_.toLong).toIndexedSeq
    case a: Array[Double] => a.map(_.toLong).toIndexedSeq
    case other =>
      throw new MalformedNetwork(s"expected integers, got ${other.getClass.getSimpleName}")

  private def matrix(d: Dataset): IndexedSeq[IndexedSeq[Double]] = d.getData match
    case a: Array[Array[Double]] => a.map(_.toIndexedSeq).toIndexedSeq
    case a: Array[Array[Float]]  => a.map(_.map(_.toDouble).toIndexedSeq).toIndexedSeq
    case other =>
      throw new MalformedNetwork(s"expected a matrix, got ${other.getClass.getSimpleName}")
