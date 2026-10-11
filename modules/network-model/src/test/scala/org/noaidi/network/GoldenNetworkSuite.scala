package org.noaidi.network

import java.nio.file.{Files, Path, Paths}

/** The store, read against the pinned PyPSA's own output.
  *
  * Checking the reader against hand-written CSV would only confirm it agrees
  * with my reading of the format. These assertions come from `manifest.json`,
  * which the generator wrote by inspecting the loaded network — so the counts,
  * column sets and time-series coverage are PyPSA's, not mine.
  */
class GoldenNetworkSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))

  private lazy val schema: Schema = Schema.fromFile(goldens.resolve("schema.json"))

  private lazy val manifest: ujson.Value =
    ujson.read(Files.readString(goldens.resolve("manifest.json")))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  test("the schema covers every component type PyPSA knows") {
    assume(available, "goldens missing — run reference/generate_goldens.py")
    // Both numbers come from the pinned install's registry. If a version bump
    // changes them, this failing is the signal to look at the diff.
    assertEquals(schema.components.size, 16)
    // 422 until PyPSA 1.3.0, which added 32: maintenance scheduling on Generator,
    // Link and Process, piecewise cost breakpoints, and an optimisable phase
    // shift range on Transformer.
    assertEquals(schema.attributeCount, 454)
  }

  /** A golden network directory with one file rewritten, for the reader refusals below. */
  private def withFile(name: String, file: String, content: String)(check: Path => Unit): Unit =
    val source = goldens.resolve("networks").resolve(name)
    val out    = Files.createTempDirectory(s"noaidi-csv-$name-")
    try
      scala.util.Using.resource(Files.list(source)) { paths =>
        paths.forEach(p => Files.copy(p, out.resolve(p.getFileName)))
      }
      Files.writeString(out.resolve(file), content)
      check(out)
    finally
      scala.util.Using.resource(Files.walk(out)) { paths =>
        paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
      }

  test("a decimal comma is refused rather than read as a field separator") {
    assume(available, "goldens missing")
    // `0,25` is what a spreadsheet in a European locale writes for 0.25, and no
    // comma-splitting reader can tell it from a separator. Measured against pandas 3.0.5 on
    // this exact text, which is why the refusal is a deliberate divergence rather than a
    // shared diagnosis:
    //
    //   truth    marginal_cost = 0.25
    //   pandas   marginal_cost = 25     <- `g` becomes the index and all three columns shift
    //   noaidi   marginal_cost = 0      <- read positionally, the trailing field dropped
    //
    // So `pd.read_csv` accepts the file and is off by a factor of 100, and this reader
    // accepted it and was off by the whole value. Neither raised.
    val text = "name,bus,p_nom,marginal_cost\ng,b,100.0,0,25\n"
    val spec = schema.components.find(_.name == "Generator").get
    val failure = intercept[CsvReader.MalformedNetwork](CsvReader.table(text, spec))

    // The line, the counts and the cause. Each is asserted separately because each is a
    // different way for the message to be useless: a reader sent to the wrong line, a count
    // that does not say which side is wrong, or a correct refusal with no hint at all.
    assert(failure.getMessage.contains("line 2"), failure.getMessage)
    assert(failure.getMessage.contains("5 fields"), failure.getMessage)
    assert(failure.getMessage.contains("header has 4"), failure.getMessage)
    assert(failure.getMessage.contains("0.25"), failure.getMessage)
    assert(failure.getMessage.contains("decimal comma"), failure.getMessage)
  }

  test("a decimal comma in a weighting is refused, and names the file") {
    assume(available, "goldens missing")
    // `snapshots.csv` rather than a component table, because that is where the gap was
    // found: `parseWeighting` refuses a cell it cannot parse, and a decimal comma never
    // reaches it -- the row simply gains a column, `1` parses as the weighting and the `0`
    // of `0,25` is dropped. The check has to sit in `parse`, above every reader, for this
    // file to be covered at all.
    val text = ",snapshot,objective,stores,generators\n0,now,0,25,1.0,1.0\n"
    withFile("ac-dc-meshed", "snapshots.csv", text) { directory =>
      val failure =
        intercept[CsvReader.MalformedNetwork](CsvReader.read(directory, schema, "comma"))
      assert(failure.getMessage.contains("snapshots.csv"), failure.getMessage)
      assert(failure.getMessage.contains("decimal comma"), failure.getMessage)
    }
  }

  test("a short row is not refused, because pandas pads one with NaN") {
    assume(available, "goldens missing")
    // The other half, and the one that keeps this from being an over-refusal. `pd.read_csv`
    // pads a short row with NaN -- measured -- and a NaN cell is exactly how this reader
    // already spells "not set for this entity", so both sides fall through to the schema
    // default and there is nothing to refuse. Asserting the value rather than just the
    // absence of an exception: "not refused" would also pass if the row were dropped.
    val text = "name,bus,p_nom,marginal_cost\ng,b,100.0\n"
    val spec = schema.components.find(_.name == "Generator").get
    val table = CsvReader.table(text, spec)
    assertEquals(table.ids, IndexedSeq("g"))
    assertEquals(table.float("p_nom", "g"), 100.0)
    assertEquals(table.float("marginal_cost", "g"), 0.0)
  }

  test("a quoted comma is one field and is not refused") {
    assume(available, "goldens missing")
    // The check counts the fields `splitLine` produced, not the commas in the line, and this
    // is what says so. PyPSA writes through pandas, which quotes any field containing a
    // comma, and real datasets have bus names that need it -- a check built on a naive split
    // would refuse that file, which is the opposite mistake and a louder one.
    val text = "name,bus,p_nom\n\"g, large\",b,100.0\n"
    val spec = schema.components.find(_.name == "Generator").get
    val table = CsvReader.table(text, spec)
    assertEquals(table.ids, IndexedSeq("g, large"))
    assertEquals(table.float("p_nom", "g, large"), 100.0)
  }

  test("a piecewise cost curve is refused by the CSV reader, from PyPSA's own export") {
    assume(available, "goldens missing")
    // Against `unsupported/piecewise-cost`, written by `export_to_csv_folder` and
    // `export_to_netcdf` rather than by hand. That distinction is the whole reason
    // the fixture exists: the first version of this refusal matched a
    // `_piecewise` suffix read out of PyPSA's Excel sheet-name table, and the
    // hand-written CSV in the test agreed with it and passed, while
    // `_CSVExporter.save_piecewise` writes `<list>-<attr>-pw.csv`.
    //
    // The CSV half only, and named that way after a review caught the name
    // claiming both. `network-model` does not depend on `network-io`, so
    // `NetCdfReader` is not on this suite's classpath and deleting its guard left
    // this case green -- while `GapRefusalSuite`'s doc cited it, by that name, as
    // one of the two covering the gap. The netCDF half is
    // `NetCdfReaderSuite`'s "a piecewise cost curve is refused rather than read as
    // a static column"; the two spellings share nothing beyond the exception type,
    // `generators-marginal_cost-pw.csv` against a `generators_pw_marginal_cost`
    // variable.
    val directory = goldens.resolve("unsupported").resolve("piecewise-cost")
    assert(Files.isDirectory(directory), s"no unsupported fixture at $directory")
    assert(
      Files.exists(directory.resolve("generators-marginal_cost-pw.csv")),
      "the fixture does not carry the CSV file this refusal is keyed to",
    )

    val fromCsv =
      intercept[UnsupportedNetworkFile](CsvReader.read(directory, schema, "piecewise-cost"))
    assert(
      fromCsv.getMessage.contains("piecewise"),
      s"refused, but not as a piecewise curve: ${fromCsv.getMessage}",
    )
    // Not a row count. Read as a time series the file fails on its header or its
    // length, and both messages blame the snapshots for a file that is indexed by
    // breakpoint -- which is the diagnostic this refusal exists to replace.
    assert(
      !fromCsv.getMessage.contains("snapshots"),
      s"refused as a malformed time series rather than as a curve: ${fromCsv.getMessage}",
    )
  }

  test("a piecewise-capable attribute is still read as static-or-series") {
    assume(available, "goldens missing")
    // PyPSA 1.3.0 widened nine attributes to `static or piecewise or series`, and
    // the shape they carry is unchanged: a static value with per-entity snapshot
    // overrides. Reading the new word as a third shape -- or failing to match the
    // string at all and falling back to `varying` -- is how the overrides would
    // go missing on a network that sets them.
    val generator = schema("Generator")
    assertEquals(generator.require("marginal_cost").variability, Variability.StaticOrSeries)
    assertEquals(generator.require("efficiency").variability, Variability.StaticOrSeries)
    assertEquals(generator.require("marginal_cost").valueType, AttributeType.Float)
    // And the type string really is the new one, read from the file rather than
    // through `AttributeSpec`, which keeps the parsed shape and not the words.
    // Without this the assertions above would keep passing against a schema whose
    // `marginal_cost` had gone back to plain `static or series`, and the parse
    // they are checking would no longer be exercised by anything.
    val declared = ujson
      .read(Files.readString(goldens.resolve("schema.json")))("Generator")("attributes")
    assertEquals(declared("marginal_cost")("type").str, "static or piecewise or series")
    assertEquals(declared("efficiency")("type").str, "static or piecewise or series")
  }

  test("attribute variability is read as three distinct cases") {
    assume(available, "goldens missing")
    val generator = schema("Generator")

    // Static: one value per entity, never a series.
    assertEquals(generator.require("bus").variability, Variability.Static)
    // Static-or-series: an input that may vary, stored as a static value plus
    // per-entity overrides. This is the case that would be lost by treating
    // "varying" as a single boolean.
    assertEquals(generator.require("p_max_pu").variability, Variability.StaticOrSeries)
    assertEquals(generator.require("marginal_cost").variability, Variability.StaticOrSeries)
    // Series: an output, computed rather than read.
    assertEquals(generator.require("p").variability, Variability.Series)
    assert(generator.require("p").isOutput)
    assert(!generator.require("p_max_pu").isOutput)
  }

  test("defaults survive as PyPSA renders them, including the non-finite ones") {
    assume(available, "goldens missing")
    val generator = schema("Generator")
    assertEquals(generator.require("p_nom").defaultText, Some("0"))
    assertEquals(generator.require("p_max_pu").defaultText, Some("1"))
    // An unset capacity limit is infinite, and a default that silently became
    // zero would turn an unconstrained generator into a disabled one.
    assertEquals(generator.require("p_nom_max").defaultText, Some("inf"))
  }

  /** Every golden network, from the manifest rather than from a list here.
    *
    * The list this replaces had gone stale four fixtures in a row --
    * `transformer-levels`, `lodf-mesh`, `standard-types` and `scigrid-de` were
    * all absent, so the largest network in the repository and the only one whose
    * impedance comes from a type name were never loaded at this layer at all. A
    * hardcoded list fails by omission, which is the failure mode that leaves no
    * trace: the suite stays green and the coverage quietly shrinks.
    *
    * The manifest is the authority for which networks exist, so a fixture added
    * to `generate_goldens.py` is covered here without anyone remembering to.
    *
    * The multi-period networks are included. They used to be excluded on the
    * manifest's `multi_period` flag, because `Network` held a flat list of
    * snapshot labels and a `(period, timestep)` index read back as duplicates.
    * `Network` carries both halves now, so the exclusion went with the
    * limitation — the flag stays in the manifest as a description of the
    * fixture, and nothing reads it here.
    */
  private lazy val goldenNetworks: List[String] =
    manifest("networks").obj.keys.toList.sorted

  goldenNetworks.foreach { name =>
    test(s"$name loads with the component counts PyPSA reported") {
      assume(available, "goldens missing")
      val n        = network(name)
      val expected = manifest("networks")(name)

      // Values, not just the count. Comparing sizes alone is what let a reader
      // that returned the positional index instead of the timestamp pass: the
      // count was right and every assertion was satisfied.
      val expectedSnapshots = expected("snapshots").arr.map(_.str).toIndexedSeq
      assertEquals(n.snapshotCount, expectedSnapshots.size, "snapshot count")
      // Compared verbatim. The manifest used to report the ISO form while the CSV
      // writes "2015-01-01 00:00:00", so this normalised the separator — but the
      // manifest now records the label as the file spells it, which it has to,
      // because a snapshot label need not be a timestamp at all. `ac-pf-pv` uses
      // plain integers, and rendering those as JSON numbers described the
      // in-memory index rather than the file.
      // Through `snapshotLabel` rather than `snapshots` directly, because a
      // multi-period index has two halves and the manifest records the pair as
      // Python prints it. On a flat index this is the label unchanged, so the
      // check is no weaker for the twenty-one networks that have one.
      assertEquals(
        n.snapshots.indices.map(n.snapshotLabel).toIndexedSeq,
        expectedSnapshots,
        s"$name: snapshot labels",
      )

      expected("components").obj.foreach { (componentName, info) =>
        val table = n.table(componentName)
        assert(table.isDefined, s"$name: $componentName was not loaded")
        assertEquals(
          table.get.size,
          info("count").num.toInt,
          s"$name: $componentName entity count",
        )

        // The column set the export actually carried. This is the assertion that
        // would have caught four silently-dropped custom columns -- `country` on
        // buses, three on carriers -- which the previous version never read.
        val exported = info("exported_columns").arr.map(_.str).toSet - "name"
        assertEquals(
          table.get.static.keySet,
          exported,
          s"$name: $componentName column set",
        )
      }
    }

    test(s"$name carries time series only for the entities that vary") {
      assume(available, "goldens missing")
      val n        = network(name)
      val expected = manifest("networks")(name)

      expected("components").obj.foreach { (componentName, info) =>
        val table = n.require(componentName)
        info("varying").obj.foreach { (attribute, entitiesJson) =>
          val entities = entitiesJson.arr.map(_.str).toIndexedSeq
          val series   = table.series.get(attribute)
          assert(series.isDefined, s"$name: $componentName.$attribute has no series")
          assertEquals(
            series.get.entities,
            entities,
            s"$name: $componentName.$attribute covers the wrong entities",
          )
          assertEquals(series.get.snapshotCount, n.snapshotCount)
        }
      }
    }
  }

  test("a varying attribute resolves per entity, falling back to the static value") {
    assume(available, "goldens missing")
    val n          = network("ac-dc-meshed")
    val generators = n.require("Generator")
    val varying    = generators.series("p_max_pu")

    // The point of the hybrid representation: some entities have overrides and
    // the rest do not, and both must resolve through the same accessor.
    assert(varying.entityCount < generators.size, "every generator varies; the fixture lost its point")

    generators.ids.foreach { id =>
      val resolved = generators.valueAt("p_max_pu", id, 0)
      if varying.covers(id) then
        assertEquals(resolved, varying.get(id, 0).get, s"$id should resolve to its override")
      else
        // No override: the static value, or the schema default when the column
        // was omitted from the file entirely.
        assertEquals(resolved, generators.float("p_max_pu", id), s"$id should fall back")
        assert(resolved.isFinite, s"$id resolved to $resolved")
    }
  }

  test("custom columns outside the schema are preserved") {
    assume(available, "goldens missing")
    // PyPSA's component model is extensible and the reference network uses it:
    // buses.csv carries `country`, which appears nowhere in Bus's 19 schema
    // attributes. Dropping it would lose data on every round-trip.
    val buses = network("ac-dc-meshed").require("Bus")
    assert(schema("Bus").attribute("country").isEmpty, "country is unexpectedly in the schema")
    assert(buses.static.contains("country"), "the custom column was dropped")
    assertEquals(buses.string("country", "London"), "UK")

    val carriers = network("ac-dc-meshed").require("Carrier")
    Seq("marginal_cost", "efficiency", "capital_cost").foreach { c =>
      assert(carriers.static.contains(c), s"carriers.$c was dropped")
    }
  }

  test("a blank cell resolves to the schema default, not to zero") {
    assume(available, "goldens missing")
    // carriers.csv leaves cells empty for entities that do not set an
    // extensible attribute, and PyPSA's own importer fills the default there.
    // Reading a blank efficiency as NaN would turn 1.0 into no value at all.
    val generators = network("ac-dc-meshed").require("Generator")
    generators.ids.foreach { id =>
      assert(
        generators.float("efficiency", id).isFinite,
        s"$id has a non-finite efficiency",
      )
    }
  }

  test("an omitted column resolves to the schema default rather than failing") {
    assume(available, "goldens missing")
    val buses = network("ac-dc-meshed").require("Bus")
    // buses.csv carries six columns out of Bus's nineteen attributes, so most
    // reads go through the default path. That is the normal case, not an edge.
    assert(buses.static.size < schema("Bus").attributes.size)
    assertEquals(buses.float("v_mag_pu_set", buses.ids.head), 1.0)
    assertEquals(buses.string("type", buses.ids.head), "")
  }

  test("entity ids and static values match the file") {
    assume(available, "goldens missing")
    val buses = network("ac-dc-meshed").require("Bus")
    assert(buses.ids.contains("London"), s"got ${buses.ids}")
    // v_nom is written in buses.csv, so this reads a real column rather than a
    // default.
    assert(buses.float("v_nom", "London") > 0.0)
    assertEquals(buses.string("carrier", "London"), "AC")
  }

  test("CSV fields are unquoted and doubled quotes collapse") {
    val q = '"'
    // a,"b,c","say ""hi""",
    val line   = s"a,${q}b,c${q},${q}say ${q}${q}hi${q}${q}${q},"
    val parsed = CsvReader.splitLine(line)
    assertEquals(parsed, IndexedSeq("a", "b,c", s"say ${q}hi${q}", ""))
  }
