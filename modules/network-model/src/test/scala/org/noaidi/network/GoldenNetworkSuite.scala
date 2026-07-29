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
    assertEquals(schema.attributeCount, 422)
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

  List("ac-dc-meshed", "storage-hvdc").foreach { name =>
    test(s"$name loads with the component counts PyPSA reported") {
      assume(available, "goldens missing")
      val n        = network(name)
      val expected = manifest("networks")(name)

      assertEquals(n.snapshotCount, expected("snapshots").arr.size, "snapshot count")

      expected("components").obj.foreach { (componentName, info) =>
        val table = n.table(componentName)
        assert(table.isDefined, s"$name: $componentName was not loaded")
        assertEquals(
          table.get.size,
          info("count").num.toInt,
          s"$name: $componentName entity count",
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
