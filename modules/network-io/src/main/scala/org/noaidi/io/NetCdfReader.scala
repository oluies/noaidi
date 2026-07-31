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
    val weightings = ListMap.from(
      Seq("objective", "stores", "generators").flatMap { column =>
        datasets.get(s"snapshots_$column").map(d => column -> IArray.from(doubles(d)))
      }
    )

    // Driven off the schema's list names, longest first: `sub_networks_carrier`
    // must not be read as the `networks` list, and only the schema knows which
    // prefixes are real.
    val byLength = schema.components.sortBy(-_.listName.length)

    val tables = byLength.flatMap { spec =>
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
          else Some(rest -> column(d, s"${spec.listName}.$rest"))
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
        longs(d).map(offset => epoch.plus(unit.multipliedBy(offset)).format(formatter))

  /** `<unit> since <timestamp>`, the CF convention. */
  private def parseUnits(spec: String): (Duration, LocalDateTime) =
    val parts = spec.split(" since ", 2)
    if parts.length != 2 then
      throw new MalformedNetwork(s"cannot read the time units '$spec'")
    val unit = parts(0).trim.toLowerCase match
      case "days"                     => Duration.ofDays(1)
      case "hours"                    => Duration.ofHours(1)
      case "minutes"                  => Duration.ofMinutes(1)
      case "seconds"                  => Duration.ofSeconds(1)
      case "milliseconds"             => Duration.ofMillis(1)
      case "microseconds"             => Duration.ofNanos(1000)
      case "nanoseconds"              => Duration.ofNanos(1)
      case other                      => throw new MalformedNetwork(s"unsupported time unit '$other'")
    val text  = parts(1).trim.replace(' ', 'T')
    val epoch =
      try LocalDateTime.parse(if text.length == 10 then s"${text}T00:00:00" else text)
      catch case e: Exception => throw new MalformedNetwork(s"cannot read the epoch '${parts(1)}'")
    (unit, epoch)

  /** A static column, typed as the file says rather than as the values look. */
  private def column(d: Dataset, what: String): Column =
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
