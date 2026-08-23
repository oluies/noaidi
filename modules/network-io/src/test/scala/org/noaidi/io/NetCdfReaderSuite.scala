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

  /** Every golden network, from the manifest rather than from a list here.
    *
    * Stale by four fixtures when this replaced it, including `scigrid-de` — 71
    * datasets and 1.7 MB against the 28 of a three-bus network, and the only one
    * exercising the reader at any scale.
    *
    * The multi-period ones are no longer excluded: the reader decodes PyPSA's
    * `period`/`timestep` index and `Network` carries it, so `investment-periods`
    * runs through the whole sweep like any other. The paragraph that used to sit
    * here still described a `multi_period` skip that the `.keys` below never
    * performed.
    */
  private lazy val networks: List[String] =
    ujson
      .read(Files.readString(goldens.resolve("manifest.json")))("networks")
      .obj
      .keys
      .toList
      .sorted

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

    test(s"netCDF and CSV agree on the investment periods of $name") {
      assume(available, "goldens missing")
      // The three fields the reader gained when multi-period networks entered
      // this sweep, and the only ones the comparisons above do not reach. Left
      // uncompared, a wrong dataset prefix returns an empty weighting map and
      // `periodWeighting("years", …)` falls back to 1.0 -- which mis-scales
      // every global constraint by the period length, ten-fold on this fixture,
      // with nothing here failing. The fallback is right for a network that
      // declares no weighting and silent for one whose weighting was not found.
      val binary = fromNetCdf(name)
      val text   = fromCsv(name)
      assertEquals(binary.snapshotPeriods, text.snapshotPeriods, s"$name: snapshot periods")
      assertEquals(binary.investmentPeriods, text.investmentPeriods, s"$name: investment periods")
      assertEquals(
        binary.investmentPeriodWeightings.keySet,
        text.investmentPeriodWeightings.keySet,
        s"$name: period weighting columns",
      )
      text.investmentPeriodWeightings.foreach { (column, values) =>
        assertEquals(
          binary.investmentPeriodWeightings(column).toSeq,
          values.toSeq,
          s"$name: period weighting $column",
        )
      }
    }
  }

  test("the period weightings are read, not defaulted") {
    assume(available, "goldens missing")
    // `objective` is 1.0 in both periods of the fixture, so only `years`
    // discriminates a real read from the 1.0 fallback. Pinned here rather than
    // left to the sweep above, which would pass on two empty maps.
    val n = fromNetCdf("investment-periods")
    assertEquals(n.investmentPeriods, IndexedSeq("2030", "2040"))
    assertEqualsDouble(n.periodWeighting("years", "2030"), 10.0, 0.0)
    assertEqualsDouble(n.periodWeighting("years", "2040"), 10.0, 0.0)
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

  test("a piecewise cost curve is refused rather than read as a static column") {
    assume(available, "goldens missing")
    // The other half of the refusal `GoldenNetworkSuite` pins on the CSV side.
    // Both readers feed `Lopf`, and this guard used to sit there -- one site
    // covering both -- so moving it into the readers is only equivalent if both
    // of them got one.
    //
    // The file is a real `export_to_netcdf`, not a hand-built dataset. PyPSA
    // writes the curve as `generators_pw_marginal_cost` over its own `breakpoint`
    // dimension; `readTable` skips only `<list>_i` and `<list>_t_*`, so without
    // this it would take that variable -- and the two index variables beside it --
    // for static columns, and fail on a length check that blames `generators_i`'s
    // entity count.
    val file = goldens.resolve("unsupported").resolve("piecewise-cost.nc")
    assume(Files.exists(file), s"no unsupported netCDF fixture at $file")

    val failure =
      intercept[UnsupportedNetworkFile](NetCdfReader.read(file, schema, "piecewise-cost"))
    assert(
      failure.getMessage.contains("piecewise"),
      s"refused, but not as a piecewise curve: ${failure.getMessage}",
    )
    assert(
      !failure.getMessage.contains("entities"),
      s"refused by the entity-count check rather than as a curve: ${failure.getMessage}",
    )
    // The message names what holds the curve, not what indexes it. PyPSA writes
    // `generators_pw_marginal_cost` beside `..._i` and `..._attr_i`, and the
    // fixture carries all three -- so reporting the pair as things that "hold
    // piecewise cost curves" describes the file wrongly, and nothing above would
    // notice: both assertions pass either way.
    assert(
      failure.getMessage.contains("generators_pw_marginal_cost"),
      s"the message does not name the curve: ${failure.getMessage}",
    )
    Seq("generators_pw_marginal_cost_i", "generators_pw_marginal_cost_attr_i").foreach { index =>
      assert(
        !failure.getMessage.contains(index),
        s"the message reports $index, which indexes the curve rather than holding it: " +
          failure.getMessage,
      )
    }
  }

  test("an index-only piecewise family is still refused, and named") {
    assume(available, "goldens missing")
    // A curve whose *only* datasets are its indices. The guard detects on the
    // whole `_pw_` family and reports the value variables, which are two
    // questions -- answering both with one filter meant the refusal fired only
    // when a non-`_i` sibling was present, so a renamed value variable or a
    // partial export would carry the indices back into the static-column length
    // check the guard exists to replace.
    //
    // Synthetic, and in `unsupported/` rather than `binary/malformed/`:
    // `save_piecewise` always writes the value variable, so nothing PyPSA
    // produces reaches this branch, and `binary/malformed` is the directory three
    // separate docs point at to define what `MalformedNetwork` is for. A file
    // raising the other type would make it a counterexample to its own boundary.
    //
    // Its own case rather than a second half of the one above, which reads a real
    // export and says so. One name, one file: sharing them meant an unrelated
    // failure in the message assertions masked a regression in the filter.
    val file = goldens.resolve("unsupported").resolve("piecewise-index-only.nc")
    assume(Files.exists(file), s"no index-only fixture at $file")

    val failure =
      intercept[UnsupportedNetworkFile](NetCdfReader.read(file, schema, "piecewise-index-only"))
    assert(
      failure.getMessage.contains("piecewise"),
      s"an index-only curve was not refused as one: ${failure.getMessage}",
    )
    // The fallback, which was the part left unpinned: with no value variable to
    // name, the message reports the family rather than nothing. Dropping it makes
    // `names.mkString(", ")` render an empty sequence, and the refusal names no
    // dataset at all while still containing the word "piecewise".
    assert(
      failure.getMessage.contains("generators_pw_marginal_cost_i"),
      s"the refusal names no dataset: ${failure.getMessage}",
    )
  }
