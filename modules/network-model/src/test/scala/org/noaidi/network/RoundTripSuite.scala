package org.noaidi.network

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Read a golden network, write it back, and compare against what PyPSA wrote.
  *
  * This is L0's stated deliverable — a network container that round-trips its
  * data — and the only honest way to check it. Comparing the writer against my
  * own reader would confirm the two agree with each other; comparing against
  * PyPSA's files confirms they agree with PyPSA.
  *
  * The comparison is on parsed values rather than bytes. Byte equality would
  * also assert pandas' float formatting, column ordering and line endings,
  * which are not properties the port needs to reproduce — and asserting them
  * would make the suite fail on a pandas upgrade that changed none of the data.
  * What must match is the set of files, the set of columns in each, and every
  * value.
  */
class RoundTripSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def csvFiles(dir: Path): Set[String] =
    Files.list(dir).iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".csv")).toSet

  private def parseCsv(path: Path): (IndexedSeq[String], IndexedSeq[IndexedSeq[String]]) =
    val rows = Files.readAllLines(path).asScala.toIndexedSeq.filter(_.nonEmpty).map(CsvReader.splitLine)
    if rows.isEmpty then (IndexedSeq.empty, IndexedSeq.empty) else (rows.head, rows.tail)

  /** Compare two CSV files by content, tolerating float formatting differences. */
  private def sameContent(mine: Path, theirs: Path, hint: String): Unit =
    val (myHeader, myRows)       = parseCsv(mine)
    val (theirHeader, theirRows) = parseCsv(theirs)

    assertEquals(myHeader.toSet, theirHeader.toSet, s"$hint: column sets differ")
    assertEquals(myRows.length, theirRows.length, s"$hint: row count differs")

    // Compare by column name, since ordering is not a property worth asserting.
    val myIndex    = myHeader.zipWithIndex.toMap
    val theirIndex = theirHeader.zipWithIndex.toMap

    myRows.indices.foreach { r =>
      theirHeader.foreach { column =>
        val a = myRows(r)(myIndex(column))
        val b = theirRows(r)(theirIndex(column))
        // Numeric where both parse as numbers, textual otherwise: "3.0" and "3"
        // are the same value written two ways.
        (a.toDoubleOption, b.toDoubleOption) match
          case (Some(x), Some(y)) =>
            assert(
              x == y || (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-12 * math.max(1.0, math.abs(y)),
              s"$hint row $r column '$column': $a vs $b",
            )
          case _ =>
            assertEquals(a, b, s"$hint row $r column '$column'")
      }
    }

  List("ac-dc-meshed", "storage-hvdc").foreach { name =>
    test(s"$name round-trips to the same files PyPSA wrote") {
      assume(available, "goldens missing — run reference/generate_goldens.py")
      val source = goldens.resolve("networks").resolve(name)
      val out    = Files.createTempDirectory(s"noaidi-$name-")

      try
        val network = CsvReader.read(source, schema, name)
        CsvWriter.write(network, out)

        val theirs = csvFiles(source)
        val mine   = csvFiles(out)

        // PyPSA writes network.csv and the JSON sidecars too; those are metadata
        // the model does not yet carry, so they are excluded rather than
        // silently counted as a mismatch.
        val metadata = Set("network.csv")
        assertEquals(
          mine,
          theirs -- metadata,
          s"$name: different files written",
        )

        (theirs -- metadata).foreach { file =>
          sameContent(out.resolve(file), source.resolve(file), s"$name/$file")
        }
      finally
        Files.walk(out).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
    }

    test(s"$name survives a second round-trip unchanged") {
      assume(available, "goldens missing")
      // Reading our own output must give the same network. A writer that loses
      // a column and a reader that defaults it would pass the comparison above
      // while quietly dropping data on every save.
      val source = goldens.resolve("networks").resolve(name)
      val first  = Files.createTempDirectory(s"noaidi-$name-1-")
      val second = Files.createTempDirectory(s"noaidi-$name-2-")

      try
        val original = CsvReader.read(source, schema, name)
        CsvWriter.write(original, first)
        val reloaded = CsvReader.read(first, schema, name)
        CsvWriter.write(reloaded, second)

        assertEquals(reloaded.snapshots, original.snapshots)
        assertEquals(reloaded.presentComponents.toSet, original.presentComponents.toSet)
        original.presentComponents.foreach { c =>
          val a = original.require(c)
          val b = reloaded.require(c)
          assertEquals(b.ids, a.ids, s"$name/$c: entity ids changed")
          assertEquals(b.series.keySet, a.series.keySet, s"$name/$c: time series changed")
        }

        assertEquals(csvFiles(second), csvFiles(first), s"$name: file set is not stable")
        csvFiles(first).foreach { f =>
          sameContent(second.resolve(f), first.resolve(f), s"$name/$f (second pass)")
        }
      finally
        List(first, second).foreach { d =>
          Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
        }
    }
  }

  test("a column left entirely at its default is omitted") {
    assume(available, "goldens missing")
    // The rule the whole writer hangs on. Bus has nineteen attributes and
    // PyPSA's buses.csv carries six; emitting the rest would be a valid network
    // and the wrong file.
    val spec = schema("Bus")
    val attr = spec.require("v_mag_pu_set") // default 1.0
    assert(CsvWriter.isAllDefault(Column.Floats(IArray(1.0, 1.0, 1.0)), attr))
    assert(!CsvWriter.isAllDefault(Column.Floats(IArray(1.0, 1.05, 1.0)), attr))
  }

  test("a NaN default is recognised rather than treated as always-differing") {
    assume(available, "goldens missing")
    // NaN != NaN, so a naive comparison would write every column whose default
    // is unset — which is most of the optional numeric ones.
    val attr = schema("Generator").require("p_init") // default nan
    assertEquals(attr.defaultText, Some("nan"))
    assert(CsvWriter.isAllDefault(Column.Floats(IArray(Double.NaN, Double.NaN)), attr))
    assert(!CsvWriter.isAllDefault(Column.Floats(IArray(Double.NaN, 0.0)), attr))
  }

  test("whole floats render the way pandas writes them") {
    assertEquals(CsvWriter.render(3.0), "3.0")
    assertEquals(CsvWriter.render(-220.0), "-220.0")
    assertEquals(CsvWriter.render(0.5), "0.5")
    assertEquals(CsvWriter.render(Double.PositiveInfinity), "inf")
    assertEquals(CsvWriter.render(Double.NaN), "")
  }
