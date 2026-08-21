package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema}
import org.noaidi.prima.PdhgParams

/** Every documented gap, asserted to actually refuse.
  *
  * This suite exists because an audit found that one did not. Multi-investment
  * periods sat on the list of known gaps in `NOTES.md` for months while
  * `investment_periods.csv` was skipped by the reader and read by nothing, so a
  * multi-period network was solved as a single period and returned 2,000 where
  * PyPSA said 17,000 — `Optimal`, no diagnostic. The prose said "not
  * implemented" and the code said nothing at all.
  *
  * '''A gap is only honest if it is loud.''' The other refusals were then
  * audited by hand and every one of them fired — but that audit was a throwaway
  * script, so it proved the state of the tree on one afternoon and left nothing
  * behind. Deleting a refusal would have gone unnoticed exactly as the first one
  * did. This is that audit made permanent.
  *
  * ==What it does not do==
  *
  * It does not check that a refusal is *correct*, only that it exists and names
  * the thing it refuses. Whether the gap is real — whether PyPSA has an answer
  * this port declines to compute, and whether that answer differs — belongs with
  * the fixture that measures it, and several of these have one. What this
  * catches is a refusal quietly disappearing.
  *
  * A gap being implemented is not a failure of this suite; it is a reason to
  * delete a case from it, deliberately, in the change that implements it. Three
  * cases were removed that way when the AC transformer model was written.
  */
class GapRefusalSuite extends munit.FunSuite, CsvFixtures:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  private def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      scala.util.Using.resource(Files.list(dir))(_.forEach(Files.deleteIfExists(_)))
      Files.deleteIfExists(dir)
    }

  /** A copy of a golden network's directory, for the mutations below. */
  private def copyOf(name: String): Path =
    val dir = Files.createTempDirectory("noaidi-gap-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve(name)
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    dir

  /** A golden network with one file added, for a component it does not carry. */
  private def withExtraFile(name: String, file: String, content: String): Network =
    val dir = copyOf(name)
    Files.writeString(dir.resolve(file), content)
    CsvReader.read(dir, schema, name)

  /** A golden network with several files added or rewritten at once.
    *
    * [[withExtraFile]] takes one, which is enough for a component the fixture
    * does not carry and not enough for a branch: `investment-periods` is a
    * single bus, so a line needs both a second bus and the line itself before it
    * is anything but a dangling reference.
    */
  private def withFiles(name: String, files: (String, String)*): Network =
    val dir = copyOf(name)
    files.foreach((file, content) => Files.writeString(dir.resolve(file), content))
    CsvReader.read(dir, schema, name)

  /** A copy of a golden network with one file rewritten, read back through the
    * real parse path.
    */
  private def mutate(name: String, file: String, edit: String => String): Network =
    val dir    = copyOf(name)
    val target = dir.resolve(file)
    val before = Files.readString(target)
    val after  = edit(before)
    assertNotEquals(after, before, s"the edit to $file changed nothing")
    Files.writeString(target, after)
    CsvReader.read(dir, schema, name)

  /** One gap: a network that exercises it, and a word its refusal must contain.
    *
    * Built through `CsvReader` rather than assembled in memory, for the reason
    * the other suites give: a hand-built table can express states the reader
    * never produces, so a test built that way can pass while the real path stays
    * broken.
    */
  private def refuses(gap: String, word: String)(build: => Network): Unit =
    test(s"gap: $gap is refused") {
      assume(available, "goldens missing")
      val failure = intercept[Lopf.UnsupportedNetwork](Lopf.build(build))
      assert(
        failure.getMessage.toLowerCase.contains(word.toLowerCase),
        s"refused, but the message does not mention '$word': ${failure.getMessage}",
      )
    }

  // The three PyPSA 1.3.0 arrived with. Each is inert at its default, which is
  // what let the pin move without any existing fixture noticing, and each errs
  // cheap when ignored -- the direction this port refuses on sight.
  refuses("a maintainable generator", "maintainable") {
    mutate("ac-dc-meshed", "generators.csv", setColumn(_, "maintainable", "True"))
  }
  refuses("a maintainable link", "maintainable") {
    mutate("ac-dc-meshed", "links.csv", setColumn(_, "maintainable", "True"))
  }
  // `min < max` is the range, exactly as `define_phase_shift_variables` tests it.
  refuses("an optimisable phase shift range", "phase_shift_min") {
    mutate("transformer-taps", "transformers.csv", setColumn(_, "phase_shift_min", "-15.0"))
  }
  // An unbounded range, which the first version of this refusal let through.
  // It read both bounds via `Branches.optional`, whose "non-finite means absent"
  // rule maps `inf` to 0.0 -- so `0.0 < 0.0` was false, the network passed, and
  // PyPSA's own `min < max` was satisfied and made the variable. Reverting to
  // `Branches.optional` fails this case and nothing else.
  refuses("an unbounded optimisable phase shift", "phase_shift_max") {
    mutate("transformer-taps", "transformers.csv", setColumn(_, "phase_shift_max", "inf"))
  }

  test("an inverted phase shift range is not refused") {
    assume(available, "goldens missing")
    // The other half of `min < max`, and a case where refusing would turn an
    // agreement into an error. `check_phase_shift_bounds` reports `min > max` as
    // a likely mistake and PyPSA then holds the shift fixed at `phase_shift`,
    // which is exactly what this model does with it -- so the two agree on the
    // answer and there is nothing to refuse.
    //
    // NaN is the same case arithmetically: every comparison against it is false,
    // in Scala as in pandas, so a half-written pair creates no variable there and
    // refuses here only if it would.
    val n = mutate(
      "transformer-taps",
      "transformers.csv",
      before => setColumn(setColumn(before, "phase_shift_min", "5.0"), "phase_shift_max", "-5.0"),
    )
    Lopf.build(n): Unit
  }

  // Capacity expansion: the two forms of capital cost this model does not price.
  refuses("annuitised overnight_cost", "overnight_cost") {
    mutate("ac-dc-meshed", "generators.csv", setColumn(_, "overnight_cost", "1000.0"))
  }
  refuses("modular capacity (p_nom_mod)", "p_nom_mod") {
    mutate("ac-dc-meshed", "generators.csv", setColumn(_, "p_nom_mod", "50.0"))
  }
  refuses("modular capacity (s_nom_mod)", "s_nom_mod") {
    mutate("ac-dc-meshed", "lines.csv", setColumn(_, "s_nom_mod", "50.0"))
  }
  refuses("modular capacity (e_nom_mod)", "e_nom_mod") {
    mutate("store-bank", "stores.csv", setColumn(_, "e_nom_mod", "10.0"))
  }

  // Set points that pin a variable this model leaves free. `state_of_charge_set`
  // is deliberately absent: it is implemented, not refused.
  Seq("p_set", "p_dispatch_set", "p_store_set").foreach { attribute =>
    refuses(s"StorageUnit $attribute", attribute) {
      mutate("storage-cycle", "storage_units.csv", setColumn(_, attribute, "1.0"))
    }
  }
  Seq("p_set", "e_set").foreach { attribute =>
    refuses(s"Store $attribute", attribute) {
      mutate("store-bank", "stores.csv", setColumn(_, attribute, "1.0"))
    }
  }

  // Global constraints: PyPSA dispatches on `type` to entirely different
  // builders, so the wrong one is a different constraint wearing the same
  // right-hand side.
  refuses("GlobalConstraint type other than primary_energy", "type") {
    mutate("ac-dc-co2", "global_constraints.csv", setColumn(_, "type", "operational_limit"))
  }
  refuses("GlobalConstraint sense other than <=", "sense") {
    mutate("ac-dc-co2", "global_constraints.csv", setColumn(_, "sense", ">="))
  }

  // Whole features, refused as networks rather than as attributes.
  refuses("committable units", "committable")(network("unit-commitment"))

  // `multi-investment periods` was here as one blanket refusal. `Lopf` models
  // multi-period dispatch now, so the case is gone and what replaces it is the
  // narrower set: the parts whose *formulation* differs rather than merely their
  // weighting. Each is a mutation of the one multi-period fixture, so none of
  // them is unreachable-by-construction.
  refuses("capacity expansion across investment periods", "extendable") {
    mutate("investment-periods", "generators.csv", setColumn(_, "p_nom_extendable", "True"))
  }
  refuses("Carrier max_growth between periods", "max_growth") {
    withExtraFile("investment-periods", "carriers.csv", "name,max_growth\nAC,100.0\n")
  }
  refuses("per-period storage cycling", "cyclic_state_of_charge_per_period") {
    withExtraFile(
      "investment-periods",
      "storage_units.csv",
      "name,bus,p_nom,max_hours,cyclic_state_of_charge_per_period\ns,b,10.0,4.0,True\n",
    )
  }
  refuses("a snapshot in an undeclared period", "does not declare") {
    mutate("investment-periods", "snapshots.csv",
           setColumn(_, "period", (i, p) => if i == "3" then "2050" else p))
  }
  // The whole-horizon row families. `Lopf` masks a partly-built asset's columns
  // by pinning them to zero, which is enough for a bound and not enough for a
  // row whose shape or right-hand side depends on which assets exist.
  refuses("a line built partway through the horizon", "cycle basis") {
    withFiles(
      "investment-periods",
      "buses.csv" ->
        "name,v_nom,control,generator,sub_network\nb,110.0,Slack,new,0\nb2,110.0,PQ,,0\n",
      "lines.csv" ->
        ("name,bus0,bus1,x,r,s_nom,build_year,lifetime\n" +
          "l0,b,b2,0.1,0.01,100.0,0,inf\n" +
          "l1,b,b2,0.2,0.02,100.0,2040,30.0\n"),
    )
  }
  refuses("a ramp-limited unit built partway through the horizon", "ramp-limited") {
    withFiles(
      "investment-periods",
      "generators.csv" ->
        ("name,bus,control,p_nom,marginal_cost,build_year,lifetime,ramp_limit_up\n" +
          "new,b,Slack,200.0,5.0,2040,30.0,0.5\n" +
          "old,b,PQ,200.0,80.0,0,inf,1.0\n"),
    )
  }
  // A partly-built storage unit or store is *not* here. Three cases were, on the
  // grounds that pinning its columns to zero left the energy-balance rows saying
  // something PyPSA does not say -- which was true, and the answer was to emit
  // the rows over the asset's active snapshots rather than to refuse the network
  // that exposes it. `LopfSuite` carries what replaced them.
  refuses("a period label that is not a year", "is not a year") {
    withFiles(
      "investment-periods",
      "investment_periods.csv" -> "period,objective,years\n2030,1.0,10\n2040-Q1,1.0,10\n",
      "snapshots.csv" ->
        (",period,timestep,objective,stores,generators\n" +
          "0,2030,0,1.0,1.0,1.0\n" +
          "1,2030,1,1.0,1.0,1.0\n" +
          "2,2040-Q1,0,1.0,1.0,1.0\n" +
          "3,2040-Q1,1,1.0,1.0,1.0\n"),
    )
  }

  // `Link delay` was here. `Delays` implements it, so the case is gone rather
  // than reworded -- the same way three transformer cases went when the AC model
  // was written. What replaced it is a golden comparison on `link-delay` and
  // `link-delay-wrap`, plus the two invalid-delay refusals in `LopfSuite`, which
  // are PyPSA parity rather than a gap.

  test("gap: security-constrained expansion of the transmission is refused") {
    assume(available, "goldens missing")
    // Not a `Lopf` refusal: extendable *generation* under SCLOPF is fine and
    // deliberately allowed, so this one belongs to `Sclopf` and names the branch.
    val failure = intercept[Sclopf.UnsupportedNetwork](Sclopf.build(network("ac-dc-meshed")))
    assert(failure.getMessage.contains("extendable"), failure.getMessage)
  }
