package org.noaidi.network

import java.nio.file.{Files, Path}

/** Writer for PyPSA's CSV directory format.
  *
  * The reader can be lenient; the writer cannot — round-tripping means producing
  * the file PyPSA would have produced.
  *
  * `buses.csv` carries six columns out of Bus's nineteen attributes, and the
  * obvious explanation is wrong: PyPSA does not omit columns whose values happen
  * to equal the default, it omits columns that were never '''set'''. Those differ.
  * The reference network has buses at `x = 0.0, y = 0.0` — which is the default —
  * and PyPSA writes them anyway, because they were assigned. Filtering on value
  * dropped them, and the round-trip caught it.
  *
  * Since the reader records exactly the columns the input file carried, the
  * writer preserves them rather than recomputing which ones matter. That is both
  * simpler and the only thing that can be correct: provenance is not recoverable
  * from values. A table built in code should therefore carry only the columns it
  * means to write — see [[minimal]] for dropping all-default columns
  * deliberately.
  */
object CsvWriter:

  def write(network: Network, directory: Path): Unit =
    Files.createDirectories(directory)
    writeSnapshots(network, directory)
    writeInvestmentPeriods(network, directory)
    network.tables.values.foreach { table =>
      writeStatic(table, directory)
      writeSeries(table, network, directory)
    }

  private def writeSnapshots(network: Network, directory: Path): Unit =
    if network.snapshots.nonEmpty then
      // `,snapshot,objective,stores,generators`: PyPSA writes a blank index
      // header, then the label, then whatever weightings the network carries.
      //
      // A multi-period network's index is two columns rather than one --
      // `,period,timestep,...` and no `snapshot` at all -- because its snapshots
      // are `(period, timestep)` pairs and the timestep half repeats across
      // periods. Writing a single `snapshot` column for one would produce a file
      // that reads back with the periods gone.
      val kinds = network.snapshotWeightings.keys.toIndexedSeq
      val index = if network.isMultiPeriod then IndexedSeq("period", "timestep")
                  else IndexedSeq("snapshot")
      val header = ("" +: index ++: kinds).map(escape).mkString(",")
      val rows = network.snapshots.indices.map { i =>
        val label =
          if network.isMultiPeriod then IndexedSeq(network.snapshotPeriods(i), network.snapshots(i))
          else IndexedSeq(network.snapshots(i))
        val cells = i.toString +: label ++: kinds.map(k => render(network.weighting(k, i)))
        cells.map(escape).mkString(",")
      }
      Files.write(directory.resolve("snapshots.csv"), asBytes(header +: rows))

  /** `investment_periods.csv`, which only a multi-period network has.
    *
    * Written from the same fields the reader fills, so the file survives a
    * round trip. Before periods were modelled the reader took the labels and
    * dropped the weightings, which made this file unwritable without inventing
    * numbers -- so it was not written at all, and a round-tripped multi-period
    * network silently lost its periods.
    */
  private def writeInvestmentPeriods(network: Network, directory: Path): Unit =
    if network.investmentPeriods.nonEmpty then
      val kinds  = network.investmentPeriodWeightings.keys.toIndexedSeq
      val header = ("period" +: kinds).map(escape).mkString(",")
      val rows = network.investmentPeriods.map { period =>
        val cells = period +: kinds.map(k => render(network.periodWeighting(k, period)))
        cells.map(escape).mkString(",")
      }
      Files.write(directory.resolve("investment_periods.csv"), asBytes(header +: rows))

  private def writeStatic(table: ComponentTable, directory: Path): Unit =
    if table.size == 0 then return

    // Every column the table holds, in its own order — which for a table read
    // from a file is that file's order. Deciding here which columns "matter"
    // would discard the provenance the reader captured.
    val kept = table.static.toIndexedSeq

    val header = "name" +: kept.map(_._1)
    val rows = table.ids.zipWithIndex.map { (id, row) =>
      id +: kept.map((_, col) => col.text(row))
    }
    val lines = (header +: rows).map(_.map(escape).mkString(","))
    Files.write(directory.resolve(s"${table.spec.listName}.csv"), asBytes(lines))

  private def writeSeries(table: ComponentTable, network: Network, directory: Path): Unit =
    table.series.foreach { (attribute, series) =>
      if series.entityCount > 0 then
        // PyPSA leaves the index header blank on time-series files.
        val header = "" +: series.entities
        // The index column carries the snapshot's *position*, not its label --
        // the same convention snapshots.csv uses. Writing the timestamp here
        // produces a readable file that does not match.
        val rows = network.snapshots.indices.map { s =>
          s.toString +: series.entities.indices.map(e => render(series.values(s)(e)))
        }
        val lines = (header +: rows).map(_.map(escape).mkString(","))
        Files.write(
          directory.resolve(s"${table.spec.listName}-$attribute.csv"),
          asBytes(lines),
        )
    }

  /** Drop every column whose values are all still the schema default.
    *
    * Deliberately not applied when writing a network that came from files:
    * PyPSA's rule is about whether a column was set, not what it holds. This is
    * the right thing for a table assembled in code, where there is no
    * provenance to preserve and emitting all 53 of a Generator's attributes
    * would be noise.
    *
    * NaN is compared by predicate rather than equality: a NaN default means
    * "unset", and `NaN != NaN` would make every such column look non-default.
    */
  def minimal(table: ComponentTable): ComponentTable =
    table.copy(static = table.static.filter { (name, col) =>
      table.spec.attribute(name).forall(attr => !isAllDefault(col, attr))
    })

  private[network] def isAllDefaultDoc: Unit = ()

  /** See [[minimal]]. */
  private[network] def isAllDefault(column: Column, attr: AttributeSpec): Boolean =
    attr.defaultText match
      case None => false
      case Some(default) =>
        column match
          case Column.Floats(v) =>
            val d = parseDefaultFloat(default)
            v.forall(x => x == d || (x.isNaN && d.isNaN))
          case Column.Ints(v)    => default.toIntOption.exists(d => v.forall(_ == d))
          case Column.Strings(v) => v.forall(_ == default)
          case Column.Bools(v) =>
            val d = default == "true" || default == "True"
            v.forall(_ == d)

  private def parseDefaultFloat(text: String): Double = text match
    case "inf"  => Double.PositiveInfinity
    case "-inf" => Double.NegativeInfinity
    case "nan"  => Double.NaN
    case other  => other.toDoubleOption.getOrElse(Double.NaN)

  /** Render a double the way pandas does, so the diff against PyPSA's own output
    * is about content rather than formatting.
    */
  private[network] def render(value: Double): String =
    if value.isNaN then ""
    else if value.isPosInfinity then "inf"
    else if value.isNegInfinity then "-inf"
    else if value == value.floor && value.abs < 1e15 then
      // pandas writes a whole float as "3.0", not "3". Formatted against
      // Locale.ROOT explicitly: the default locale would emit "20,0" on a
      // machine using a comma decimal separator, which breaks the CSV itself.
      String.format(java.util.Locale.ROOT, "%.1f", java.lang.Double.valueOf(value))
    else value.toString

  private def escape(field: String): String =
    if field.exists(c => c == ',' || c == '"' || c == '\n') then
      "\"" + field.replace("\"", "\"\"") + "\""
    else field

  private def asBytes(lines: Seq[String]): Array[Byte] =
    (lines.mkString("\n") + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
