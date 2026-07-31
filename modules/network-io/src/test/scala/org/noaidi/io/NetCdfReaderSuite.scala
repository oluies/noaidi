package org.noaidi.io

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.*

/** The netCDF reader against the CSV reader, on the same networks.
  *
  * PyPSA writes both artefacts from one network, so a reader for either must
  * produce the same model. That is a far stronger check than reading a binary
  * blob and asserting it matches itself: the two formats share no code here and
  * almost nothing on PyPSA's side, so agreeing means both decode the network
  * rather than merely round-tripping a representation.
  */
class NetCdfReaderSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  // The binary directory too, not just the schema: a checkout with the CSV
  // goldens but no `binary/` should skip rather than fail.
  private lazy val available: Boolean =
    Files.exists(goldens.resolve("schema.json")) && Files.isDirectory(goldens.resolve("binary"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private val networks = List(
    "ac-dc-meshed", "ac-dc-dispatch", "ac-dc-co2", "ac-pf-pv", "unit-commitment",
    "sclopf-triangle", "storage-hvdc",
  )

  private def fromCsv(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private def fromNetCdf(name: String): Network =
    NetCdfReader.read(goldens.resolve("binary").resolve(s"$name.nc"), schema, name)

  networks.foreach { name =>
    test(s"netCDF and CSV agree on the snapshots of $name") {
      assume(available, "goldens missing — run reference/generate_goldens.py")
      // The CF-decoding check. The raw file holds 0, 1, 2 with a `units`
      // attribute; reading those verbatim would give integer snapshots where the
      // CSV says timestamps, and every downstream comparison would drift.
      assertEquals(fromNetCdf(name).snapshots, fromCsv(name).snapshots, name)
    }

    test(s"netCDF and CSV agree on the component tables of $name") {
      assume(available, "goldens missing")
      val binary = fromNetCdf(name)
      val text   = fromCsv(name)

      assertEquals(binary.tables.keySet, text.tables.keySet, s"$name: component set")
      text.tables.foreach { (component, expected) =>
        val actual = binary.tables(component)
        assertEquals(actual.ids, expected.ids, s"$name: $component entities")
        assertEquals(
          actual.static.keySet,
          expected.static.keySet,
          s"$name: $component column set",
        )

        expected.static.foreach { (attribute, col) =>
          val mine = actual.static(attribute)
          // Types are compared only where the schema declares one. For an
          // undeclared column the type is inferred from the values, and an
          // all-empty column is genuinely ambiguous: `SubNetwork.obj` is PyPSA's
          // in-memory object handle rather than network data, and each format
          // writes its own placeholder for it -- an empty string in CSV, NaN in
          // netCDF. Both render to the same empty text, which is what the value
          // comparison below checks. Demanding the inferred types match would be
          // asserting agreement about a placeholder neither format gives meaning.
          if expected.spec.attribute(attribute).isDefined then
            assertEquals(
              mine.getClass.getSimpleName,
              col.getClass.getSimpleName,
              s"$name: $component.$attribute type",
            )
          expected.ids.indices.foreach { row =>
            (col, mine) match
              case (Column.Floats(a), Column.Floats(b)) =>
                // NaN is a real value here (an unset bound), so it has to compare
                // equal to itself rather than fail the way NaN normally does.
                assert(
                  a(row) == b(row) || (a(row).isNaN && b(row).isNaN),
                  s"$name: $component.$attribute row $row: ${b(row)} against ${a(row)}",
                )
              case _ =>
                assertEquals(
                  mine.text(row),
                  col.text(row),
                  s"$name: $component.$attribute row $row",
                )
          }
        }
      }
    }

    test(s"netCDF and CSV agree on the time series of $name") {
      assume(available, "goldens missing")
      // Including *which* entities vary, not just the values. PyPSA stores a
      // series only for the entities whose value changes, and a reader that
      // materialised a dense matrix would produce a correct network and a file
      // that does not match.
      val binary = fromNetCdf(name)
      val text   = fromCsv(name)

      text.tables.foreach { (component, expected) =>
        val actual = binary.tables(component)
        assertEquals(actual.series.keySet, expected.series.keySet, s"$name: $component series set")
        expected.series.foreach { (attribute, series) =>
          val mine = actual.series(attribute)
          assertEquals(mine.entities, series.entities, s"$name: $component.$attribute entities")
          assertEquals(mine.snapshotCount, series.snapshotCount)
          series.entities.foreach { entity =>
            (0 until series.snapshotCount).foreach { t =>
              assertEqualsDouble(
                mine.get(entity, t).get,
                series.get(entity, t).get,
                1e-12,
                s"$name: $component.$attribute $entity at $t",
              )
            }
          }
        }
      }
    }

    test(s"netCDF and CSV agree on the snapshot weightings of $name") {
      assume(available, "goldens missing")
      val binary = fromNetCdf(name)
      val text   = fromCsv(name)
      assertEquals(binary.snapshotWeightings.keySet, text.snapshotWeightings.keySet, name)
      text.snapshotWeightings.foreach { (column, values) =>
        assertEquals(binary.snapshotWeightings(column).toSeq, values.toSeq, s"$name: $column")
      }
    }
  }

  test("a boolean column stays boolean rather than becoming an integer") {
    assume(available, "goldens missing")
    // netCDF has no boolean type: xarray writes int8 and records the real type
    // in a `dtype` attribute. Inferring from the values would make a column of
    // 0s and 1s an integer column, and it would stop comparing equal to the CSV
    // reader's -- so this pins the case rather than leaving it to the sweep above.
    val n = fromNetCdf("ac-dc-meshed")
    val generators = n.require("Generator")
    assert(generators.static.contains("p_nom_extendable"), "the fixture lost its boolean column")
    generators.static("p_nom_extendable") match
      case Column.Bools(_) => ()
      case other           => fail(s"p_nom_extendable came back as ${other.getClass.getSimpleName}")
    assert(generators.bool("p_nom_extendable", "Manchester Wind"))
  }

  test("an integer snapshot index is not mistaken for a time axis") {
    assume(available, "goldens missing")
    // `unit-commitment` indexes by plain integers and carries no `units`
    // attribute, which is what distinguishes it from a CF time axis holding the
    // same small integers.
    assertEquals(fromNetCdf("unit-commitment").snapshots, IndexedSeq("0", "1", "2", "3", "4", "5", "6", "7"))
  }

  test("a malformed file is refused by name, not by accident") {
    assume(available, "goldens missing")
    // Committed fixtures rather than files built here. The previous version of
    // this test copied a valid file, asserted it read -- already covered by the
    // sweep above -- and then intercepted a bare Exception from opening a path
    // that does not exist, which is jhdf's file-open failure and would have
    // passed for a NullPointerException.
    //
    // Building them in the suite is no better: it would need the reference venv,
    // which CI does not have, so the test would skip exactly where it matters.
    val malformed = goldens.resolve("binary").resolve("malformed")
    assume(Files.isDirectory(malformed), "malformed fixtures missing — regenerate the goldens")

    Seq(
      "no-snapshots.nc"  -> "snapshots_snapshot",
      "short-column.nc"  -> "entities",
      "bad-time-unit.nc" -> "fortnights",
    ).foreach { (file, expected) =>
      val failure = intercept[NetCdfReader.MalformedNetwork](
        NetCdfReader.read(malformed.resolve(file), schema, file)
      )
      assert(
        failure.getMessage.contains(expected),
        s"$file: expected a message naming '$expected', got: ${failure.getMessage}",
      )
    }
  }

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach(p => if Files.exists(p) then Files.delete(p))
