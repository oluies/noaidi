package org.noaidi.prima
package mps

import scala.collection.mutable
import scala.io.Source
import scala.util.Using

/** A problem read from an MPS file, with the names kept.
  *
  * Names are not needed to solve anything, but a corpus instance that
  * disagrees with its published optimum is far easier to investigate when the
  * offending row can be named.
  */
final case class MpsInstance(
    name: String,
    problem: LpProblem,
    translation: RowTranslation,
    rowNames: IndexedSeq[String],
    columnNames: IndexedSeq[String],
    integerColumns: Set[Int],
):
  /** MPS can express integer variables, which this solver cannot yet handle.
    * Callers should check rather than silently solve the relaxation.
    */
  def isMixedInteger: Boolean = integerColumns.nonEmpty

  def columnIndex(name: String): Option[Int] = columnNames.indexOf(name) match
    case -1 => None
    case i  => Some(i)

final class MpsParseException(message: String) extends RuntimeException(message)

/** A reader for the MPS format, covering the linear-programming subset.
  *
  * MPS is old, loosely specified, and full of conventions that differ between
  * implementations. Every such choice below is spelled out rather than left to
  * the reader of the code, because a silently different convention does not
  * produce a parse error — it produces a different LP that solves cleanly to
  * the wrong answer, which is the worst possible failure for a test corpus.
  *
  * Deliberate decisions:
  *
  *   - '''Tokens are whitespace-separated''', not read from fixed columns. The
  *     specification puts fields at columns 2-3, 5-12, 15-22 and so on, which
  *     matters only for names containing spaces. No Netlib instance has one,
  *     and free-format files are common enough that column-based parsing would
  *     reject files every other reader accepts.
  *   - '''The first `N` row is the objective'''; later `N` rows are free rows
  *     and are dropped. They constrain nothing, and passing them on would
  *     produce rows unbounded on both sides.
  *   - '''An `RHS` entry on the objective row is negated''' to give the
  *     objective constant. This is the usual convention and the one Netlib
  *     assumes, but it is not universal — some writers mean it unnegated.
  *   - '''`MI` sets only the lower bound''' to negative infinity, leaving the
  *     upper bound alone. Some older readers also force the upper bound to
  *     zero.
  *   - '''A negative `UP` bound on a variable still at its default lower bound
  *     of zero also drives the lower bound to negative infinity.''' Without
  *     this the variable would have an empty domain. The rule is widely
  *     implemented and surprising enough to be worth naming.
  */
object MpsReader:

  private enum Sense:
    case Less, Greater, Equal

  private final class RowSpec(val name: String, val sense: Sense):
    var rhs: Double             = 0.0
    var range: Option[Double]   = None
    val entries                 = mutable.ArrayBuffer.empty[(Int, Double)]

  private final class ColSpec(val name: String, val index: Int):
    var lower: Double      = 0.0
    var upper: Double      = Double.PositiveInfinity
    var objective: Double  = 0.0
    var isInteger: Boolean = false
    // Whether a bound entry set the lower bound, as opposed to it still being
    // the implicit zero. The negative-UP rule below depends on the difference.
    var lowerExplicit: Boolean = false

  def fromFile(path: java.nio.file.Path): MpsInstance =
    Using.resource(Source.fromFile(path.toFile))(s => fromLines(s.getLines(), path.getFileName.toString))

  def fromString(content: String, fallbackName: String = "inline"): MpsInstance =
    fromLines(content.linesIterator, fallbackName)

  def fromLines(lines: Iterator[String], fallbackName: String): MpsInstance =
    var problemName   = fallbackName
    var objectiveName = ""
    var objConstant   = 0.0
    var section       = ""
    var integerBlock  = false

    val rows        = mutable.LinkedHashMap.empty[String, RowSpec]
    val cols        = mutable.LinkedHashMap.empty[String, ColSpec]
    val freeRows    = mutable.Set.empty[String]

    def column(name: String): ColSpec =
      cols.getOrElseUpdate(name, new ColSpec(name, cols.size))

    def requireRow(name: String, context: String): Option[RowSpec] =
      if freeRows.contains(name) then None
      else if name == objectiveName then None
      else
        rows.get(name) match
          case Some(r) => Some(r)
          case None    => throw MpsParseException(s"$context refers to unknown row '$name'")

    lines.foreach { rawLine =>
      // A line is a comment if it starts with `*`, and blank lines are ignored.
      val line = rawLine.replace('\t', ' ')
      if line.trim.nonEmpty && !line.startsWith("*") then
        val isHeader = !line.startsWith(" ")
        val tokens   = line.trim.split("\\s+").toIndexedSeq

        if isHeader then
          section = tokens.head.toUpperCase
          section match
            case "NAME"     => if tokens.length > 1 then problemName = tokens(1)
            case "OBJSENSE" =>
              // The sense may sit on the header line or on an indented line
              // below it; both forms occur in the wild. Only `MAX` changes
              // anything, and it is rejected rather than silently solved with
              // the wrong sign.
              if tokens.length > 1 then checkObjSense(tokens(1))
            case _ => ()
        else
          section match
            case "ROWS" =>
              if tokens.length < 2 then throw MpsParseException(s"malformed ROWS entry: '$line'")
              val kind = tokens(0).toUpperCase
              val name = tokens(1)
              kind match
                case "N" =>
                  // First N row is the objective; the rest are free rows that
                  // constrain nothing.
                  if objectiveName.isEmpty then objectiveName = name else freeRows += name
                case "L" => rows(name) = new RowSpec(name, Sense.Less)
                case "G" => rows(name) = new RowSpec(name, Sense.Greater)
                case "E" => rows(name) = new RowSpec(name, Sense.Equal)
                case other => throw MpsParseException(s"unknown row type '$other' for row '$name'")

            case "COLUMNS" =>
              if tokens.contains("'MARKER'") then
                // Integrality markers bracket integer columns.
                if tokens.exists(_.equalsIgnoreCase("'INTORG'")) then integerBlock = true
                else if tokens.exists(_.equalsIgnoreCase("'INTEND'")) then integerBlock = false
              else
                if tokens.length < 3 then throw MpsParseException(s"malformed COLUMNS entry: '$line'")
                val col = column(tokens(0))
                if integerBlock then col.isInteger = true
                pairs(tokens.tail, line, "COLUMNS").foreach { (rowName, value) =>
                  if rowName == objectiveName then col.objective += value
                  else if !freeRows.contains(rowName) then
                    requireRow(rowName, "COLUMNS").foreach(_.entries += ((col.index, value)))
                }

            case "RHS" =>
              // The leading set name is optional. Entries are (row, value)
              // pairs, so an odd token count means a name is present and an
              // even one means it is not.
              val fields = dropSetName(tokens)
              pairs(fields, line, "RHS").foreach { (rowName, value) =>
                if rowName == objectiveName then objConstant = -value
                else if !freeRows.contains(rowName) then
                  requireRow(rowName, "RHS").foreach(_.rhs = value)
              }

            case "RANGES" =>
              val fields = dropSetName(tokens)
              pairs(fields, line, "RANGES").foreach { (rowName, value) =>
                requireRow(rowName, "RANGES").foreach(_.range = Some(value))
              }

            case "BOUNDS" =>
              if tokens.length < 3 then throw MpsParseException(s"malformed BOUNDS entry: '$line'")
              val kind = tokens(0).toUpperCase
              // `FR`, `MI`, `PL` and `BV` take no value, so the column name is
              // the last token in those cases and the second-to-last otherwise.
              val valueless = Set("FR", "MI", "PL", "BV")
              val colName   = if valueless.contains(kind) then tokens.last else tokens(tokens.length - 2)
              val col       = column(colName)
              def value: Double =
                parseNumber(tokens.last, line)

              kind match
                case "UP" =>
                  col.upper = value
                  // A negative upper bound on a variable still at the default
                  // lower bound of zero would leave an empty domain, so the
                  // lower bound goes to negative infinity.
                  if value < 0.0 && !col.lowerExplicit then col.lower = Double.NegativeInfinity
                case "LO" =>
                  col.lower = value
                  col.lowerExplicit = true
                case "FX" =>
                  col.lower = value
                  col.upper = value
                  col.lowerExplicit = true
                case "FR" =>
                  col.lower = Double.NegativeInfinity
                  col.upper = Double.PositiveInfinity
                  col.lowerExplicit = true
                case "MI" =>
                  col.lower = Double.NegativeInfinity
                  col.lowerExplicit = true
                case "PL" => col.upper = Double.PositiveInfinity
                case "BV" =>
                  col.lower = 0.0
                  col.upper = 1.0
                  col.isInteger = true
                  col.lowerExplicit = true
                case "LI" =>
                  col.lower = value
                  col.isInteger = true
                  col.lowerExplicit = true
                case "UI" =>
                  col.upper = value
                  col.isInteger = true
                case other => throw MpsParseException(s"unknown bound type '$other' on '$colName'")

            // The indented continuation of an OBJSENSE header.
            case "OBJSENSE"    => checkObjSense(tokens.head)
            case "ENDATA" | "" => ()
            case other         => throw MpsParseException(s"unexpected data in section '$other'")
    }

    if objectiveName.isEmpty then throw MpsParseException("no objective row (an N row) was declared")

    build(problemName, objConstant, rows.values.toIndexedSeq, cols.values.toIndexedSeq)

  private def checkObjSense(token: String): Unit =
    val sense = token.toUpperCase
    if sense.startsWith("MAX") then
      throw MpsParseException("OBJSENSE MAX is not supported; the solver minimises")

  /** Drop the optional leading set name from an RHS or RANGES line.
    *
    * The name is present in most files and absent in some. Since the payload is
    * (row, value) pairs, parity settles it: an odd count carries a name.
    */
  private def dropSetName(tokens: IndexedSeq[String]): IndexedSeq[String] =
    if tokens.length % 2 == 1 then tokens.tail else tokens

  /** Split trailing tokens into (name, value) pairs. */
  private def pairs(fields: Seq[String], line: String, section: String): Seq[(String, Double)] =
    if fields.length % 2 != 0 then
      throw MpsParseException(s"$section entry has an unpaired field: '$line'")
    fields.grouped(2).map(p => (p(0), parseNumber(p(1), line))).toSeq

  private def parseNumber(token: String, line: String): Double =
    token.toDoubleOption.getOrElse(
      throw MpsParseException(s"'$token' is not a number, in: '$line'")
    )

  private def build(
      name: String,
      objectiveConstant: Double,
      rows: IndexedSeq[RowSpec],
      cols: IndexedSeq[ColSpec],
  ): MpsInstance =
    val builder = LpProblem.builder(cols.length)
    builder.objectiveOffset(objectiveConstant)

    cols.foreach { c =>
      builder.objectiveCoefficient(c.index, c.objective)
      if c.lower > c.upper then
        throw MpsParseException(s"column '${c.name}' has empty bounds [${c.lower}, ${c.upper}]")
      builder.bounds(c.index, c.lower, c.upper)
    }

    rows.foreach { r =>
      val (lo, hi) = bracket(r)
      builder.constraint(r.entries.toSeq, lo, hi)
    }

    val (problem, translation) = builder.build()
    MpsInstance(
      name = name,
      problem = problem,
      translation = translation,
      rowNames = rows.map(_.name),
      columnNames = cols.map(_.name),
      integerColumns = cols.filter(_.isInteger).map(_.index).toSet,
    )

  /** Turn a row's sense, right-hand side and optional range into two-sided
    * bounds.
    *
    * The RANGES semantics are the least intuitive part of the format and differ
    * by row type. For `G` the range extends upwards from the right-hand side,
    * for `L` downwards, and for `E` the direction is taken from the '''sign of
    * the range value''' — which is the only place in MPS where a sign carries
    * that meaning.
    */
  private def bracket(r: RowSpec): (Double, Double) =
    r.range match
      case None =>
        r.sense match
          case Sense.Less    => (Double.NegativeInfinity, r.rhs)
          case Sense.Greater => (r.rhs, Double.PositiveInfinity)
          case Sense.Equal   => (r.rhs, r.rhs)
      case Some(range) =>
        val width = math.abs(range)
        r.sense match
          case Sense.Greater => (r.rhs, r.rhs + width)
          case Sense.Less    => (r.rhs - width, r.rhs)
          case Sense.Equal   => if range >= 0.0 then (r.rhs, r.rhs + width) else (r.rhs - width, r.rhs)

end MpsReader
