package org.noaidi.network

import java.nio.file.{Files, Path, Paths}

/** The shipped standard type library, and the expansion that uses it.
  *
  * Two separate concerns, and both matter:
  *
  *   - the library this module ships as a resource has to be the pinned PyPSA's,
  *     which nothing else can check because no network export contains it;
  *   - the arithmetic that turns a type name into an impedance has to be PyPSA's.
  *
  * The second is gated against real flows in `network-pf` and `network-lopf`.
  * What is here is the first, plus the cases those golden comparisons cannot
  * reach — a `num_parallel` that is not 1, an unknown type name, a network
  * carrying a type of its own.
  */
class StandardTypesSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def golden(listName: String): Path =
    goldens.resolve("standard_types").resolve(s"$listName.csv")

  Seq("LineType" -> "line_types", "TransformerType" -> "transformer_types").foreach {
    (component, listName) =>
      test(s"the shipped $listName match the pinned PyPSA's") {
        assume(available, "goldens missing — run reference/generate_goldens.py")
        // The whole reason the library is committed twice. The resource is what
        // this module reads at runtime, with no goldens directory in sight; the
        // golden is what the pinned PyPSA actually holds. A version bump that
        // changed an impedance would otherwise change every answer on a typed
        // network with nothing to notice it.
        val shipped   = StandardTypes.resource(component, schema)
        val reference = CsvReader.table(Files.readString(golden(listName)), schema(component))

        assertEquals(shipped.ids, reference.ids, s"$listName names")
        assertEquals(shipped.static.keys.toList, reference.static.keys.toList, s"$listName columns")
        assert(shipped.size > 0, s"$listName is empty")

        // Cell by cell. `Column` holds an `IArray`, so comparing two columns
        // directly compares array identities and passes for any two distinct
        // libraries -- which is how this test first "passed".
        shipped.static.foreach { (attribute, column) =>
          val expected = reference.static(attribute)
          assertEquals(column.valueType, expected.valueType, s"$listName.$attribute type")
          (column, expected) match
            case (Column.Floats(mine), Column.Floats(theirs)) =>
              mine.indices.foreach { i =>
                assertEqualsDouble(mine(i), theirs(i), 0.0, s"$listName.$attribute at ${shipped.ids(i)}")
              }
            case _ =>
              (0 until column.length).foreach { i =>
                assertEquals(
                  column.text(i),
                  expected.text(i),
                  s"$listName.$attribute at ${shipped.ids(i)}",
                )
              }
        }
      }
  }

  test("a line takes its impedance from the type, scaled by length and parallel circuits") {
    assume(available, "goldens missing")
    // Against the library's own numbers rather than against a transcription:
    // `Al/St 240/40 4-bundle 380.0` is 0.03 ohm/km resistance, 0.246 reactance
    // and 13.8 nF/km. Two circuits over 80 km therefore give x = 0.246*80/2 =
    // 9.84 and b = 2*pi*1e-9*50*13.8*80*2 -- the series impedance divided by the
    // parallel count and the shunt multiplied by it.
    val n = expandedFixture(
      "name,bus0,bus1,type,length,num_parallel,s_nom\n" +
        "l,A,B,Al/St 240/40 4-bundle 380.0,80.0,2.0,1000.0\n"
    )
    val lines = n.require("Line")

    assertEqualsDouble(lines.float("r", "l"), 0.03 * 80.0 / 2.0, 1e-12, "r")
    assertEqualsDouble(lines.float("x", "l"), 0.246 * 80.0 / 2.0, 1e-12, "x")
    assertEqualsDouble(
      lines.float("b", "l"),
      2.0 * math.Pi * 1e-9 * 50.0 * 13.8 * 80.0 * 2.0,
      1e-18,
      "b",
    )
  }

  test("parallel circuits divide the series impedance and multiply the shunt") {
    assume(available, "goldens missing")
    // The direction is the point. One circuit against four, same type and
    // length: a transposed `num_parallel` would give x four times too large and
    // b four times too small, and both would still be finite, plausible numbers.
    val n = expandedFixture(
      "name,bus0,bus1,type,length,num_parallel,s_nom\n" +
        "one,A,B,Al/St 240/40 4-bundle 380.0,80.0,1.0,1000.0\n" +
        "four,A,B,Al/St 240/40 4-bundle 380.0,80.0,4.0,1000.0\n"
    )
    val lines = n.require("Line")

    assertEqualsDouble(lines.float("x", "one") / lines.float("x", "four"), 4.0, 1e-12, "series")
    assertEqualsDouble(lines.float("b", "four") / lines.float("b", "one"), 4.0, 1e-12, "shunt")
  }

  test("a transformer's percentages become impedance, and its rating comes from the type") {
    assume(available, "goldens missing")
    // `160 MVA 380/110 kV`: vsc 12.2%, vscr 0.25%, pfe 60 kW, i0 0.06%.
    // r = 0.0025, x = sqrt(0.122^2 - 0.0025^2), g = 60/(1000*160),
    // b = -sqrt(0.0006^2 - g^2). s_nom is the type's 160 and is NOT scaled by
    // num_parallel, which is surprising enough to be worth pinning.
    val n = expandedTransformerFixture(
      "name,bus0,bus1,type,num_parallel,model\n" +
        "t,A,B,160 MVA 380/110 kV,2.0,pi\n"
    )
    val t = n.require("Transformer")

    val r = 0.25 / 100.0
    val x = math.sqrt((12.2 / 100.0) * (12.2 / 100.0) - r * r)
    val g = 60.0 / (1000.0 * 160.0)
    val b = -math.sqrt((0.06 / 100.0) * (0.06 / 100.0) - g * g)

    assertEqualsDouble(t.float("r", "t"), r / 2.0, 1e-15, "r")
    assertEqualsDouble(t.float("x", "t"), x / 2.0, 1e-15, "x")
    assertEqualsDouble(t.float("g", "t"), g * 2.0, 1e-18, "g")
    assertEqualsDouble(t.float("b", "t"), b * 2.0, 1e-18, "b")
    assertEqualsDouble(t.float("s_nom", "t"), 160.0, 0.0, "s_nom is the type's, unscaled")
    assertEqualsDouble(t.float("tap_ratio", "t"), 1.0, 0.0, "tap_ratio at the neutral position")
  }

  test("x is formed from the unscaled r, not the divided one") {
    assume(available, "goldens missing")
    // The trap inside the transformer conversion. PyPSA computes
    // x = sqrt((vsc/100)^2 - r^2) with the *undivided* r and only then divides
    // both by num_parallel. Doing it the other way is wrong by num_parallel^2
    // under the root -- which for this type is a difference of about 5e-9 in x,
    // far too small to notice in a flow comparison and exactly the kind of thing
    // that stays wrong for years.
    val n = expandedTransformerFixture(
      "name,bus0,bus1,type,num_parallel,model\n" +
        "t,A,B,160 MVA 380/110 kV,4.0,pi\n"
    )
    val r       = 0.25 / 100.0
    val correct = math.sqrt((12.2 / 100.0) * (12.2 / 100.0) - r * r) / 4.0
    val wrong   = math.sqrt((12.2 / 100.0) * (12.2 / 100.0) - (r / 4.0) * (r / 4.0)) / 4.0
    assert(correct != wrong, "the fixture cannot distinguish the two orderings")

    val actual = n.require("Transformer").float("x", "t")
    assertEqualsDouble(actual, correct, 1e-18, "x")
    assert(math.abs(actual - wrong) > 1e-12, s"x matches the transposed formula too ($actual)")
  }

  test("an untyped branch alongside a typed one keeps its own impedance") {
    assume(available, "goldens missing")
    // Expansion rewrites whole columns, so the rows it does not own have to be
    // carried through unchanged. A network mixing the two is the normal case --
    // `scigrid-de` types every line and types none of its transformers.
    val n = expandedFixture(
      "name,bus0,bus1,type,length,num_parallel,x,r,s_nom\n" +
        "typed,A,B,Al/St 240/40 4-bundle 380.0,80.0,1.0,0.0,0.0,1000.0\n" +
        "plain,A,B,,0.0,1.0,0.4,0.05,1000.0\n"
    )
    val lines = n.require("Line")

    assertEqualsDouble(lines.float("x", "plain"), 0.4, 0.0, "untyped x")
    assertEqualsDouble(lines.float("r", "plain"), 0.05, 0.0, "untyped r")
    assertEqualsDouble(lines.float("x", "typed"), 0.246 * 80.0, 1e-12, "typed x")
  }

  test("a type the network defines itself overrides the shipped one") {
    assume(available, "goldens missing")
    // Why PyPSA writes a `line_types.csv` at all: the export drops exactly the
    // rows a fresh Network was born with, so a file that exists carries the
    // user's own. Reading only the shipped library would silently use the
    // library's impedance for a name the network redefined.
    val dir = fixture(
      "name,bus0,bus1,type,length,num_parallel,s_nom\n" +
        "l,A,B,Al/St 240/40 4-bundle 380.0,80.0,1.0,1000.0\n"
    )
    Files.writeString(
      dir.resolve("line_types.csv"),
      "name,f_nom,r_per_length,x_per_length,c_per_length\n" +
        "Al/St 240/40 4-bundle 380.0,50.0,1.0,2.0,0.0\n",
    )
    val n = StandardTypes.expand(CsvReader.read(dir, schema, "override"))

    assertEqualsDouble(n.require("Line").float("x", "l"), 2.0 * 80.0, 1e-12, "the network's own")
  }

  test("a type name that is in neither is named, with the nearest matches") {
    assume(available, "goldens missing")
    val dir = fixture(
      "name,bus0,bus1,type,length,num_parallel,s_nom\n" +
        "l,A,B,Al/St 240/40 4-bundle 381.0,80.0,1.0,1000.0\n"
    )
    val failure = intercept[StandardTypes.UnknownType] {
      StandardTypes.expand(CsvReader.read(dir, schema, "unknown"))
    }
    assert(failure.getMessage.contains("Al/St 240/40 4-bundle 381.0"), failure.getMessage)
    // The nearest match, because a type name is long and a typo in one is not
    // visible in a bare "unknown type" message.
    assert(failure.getMessage.contains("Al/St 240/40 4-bundle 380.0"), failure.getMessage)
  }

  test("a network with no typed branch is returned untouched") {
    assume(available, "goldens missing")
    // The fast path, and it is not only an optimisation: expansion appends
    // columns, so running it on an untyped network would change what a writer
    // exports for every fixture in the repository.
    val n        = CsvReader.read(goldens.resolve("networks").resolve("ac-dc-meshed"), schema, "m")
    val expanded = StandardTypes.expand(n)
    assert(expanded eq n, "an untyped network was rebuilt rather than returned as-is")
  }

  test("expansion is idempotent") {
    assume(available, "goldens missing")
    // `Lopf.solve` expands and then calls `Lopf.build`, which expands again. That
    // is only safe because the second pass reads the same `type` column and
    // derives the same numbers rather than compounding them.
    val once  = expandedFixture(
      "name,bus0,bus1,type,length,num_parallel,s_nom\n" +
        "l,A,B,Al/St 240/40 4-bundle 380.0,80.0,2.0,1000.0\n"
    )
    val twice = StandardTypes.expand(once)
    Seq("r", "x", "b").foreach { attribute =>
      assertEqualsDouble(
        twice.require("Line").float(attribute, "l"),
        once.require("Line").float(attribute, "l"),
        0.0,
        attribute,
      )
    }
  }

  /** A two-bus network directory with the given `lines.csv`. */
  private def fixture(lines: String): Path =
    val dir = Files.createTempDirectory("noaidi-types-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nA,380.0,AC\nB,380.0,AC\n")
    Files.writeString(dir.resolve("lines.csv"), lines)
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    dir

  private def expandedFixture(lines: String): Network =
    StandardTypes.expand(CsvReader.read(fixture(lines), schema, "types"))

  private def expandedTransformerFixture(transformers: String): Network =
    val dir = Files.createTempDirectory("noaidi-types-")
    temporaries += dir
    Files.writeString(dir.resolve("buses.csv"), "name,v_nom,carrier\nA,380.0,AC\nB,110.0,AC\n")
    Files.writeString(dir.resolve("transformers.csv"), transformers)
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    StandardTypes.expand(CsvReader.read(dir, schema, "types"))

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }
