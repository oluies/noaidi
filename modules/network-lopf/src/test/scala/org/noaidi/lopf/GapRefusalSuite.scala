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

  /** A copy of a golden network with one file rewritten, read back through the
    * real parse path.
    */
  private def mutate(name: String, file: String, edit: String => String): Network =
    val dir = Files.createTempDirectory("noaidi-gap-")
    temporaries += dir
    val source = goldens.resolve("networks").resolve(name)
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
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
  refuses("multi-investment periods", "investment period")(network("investment-periods"))
  refuses("committable units", "committable")(network("unit-commitment"))
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
