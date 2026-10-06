package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.{CsvReader, Network}

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
  *
  * ==The overlap with `LopfSuite` is deliberate==
  *
  * Several cases here restate an assertion `LopfSuite` already makes — `Store`'s
  * `e_set`/`p_set`, the three `StorageUnit` set points, the `unit-commitment`
  * refusal. That is the point rather than an oversight: those live in `LopfSuite`
  * beside the behaviour they qualify, where a failure says which feature broke,
  * and they live here as one list that can be read against `NOTES.md`'s gap list
  * in a single pass. Deleting either copy costs one of those two readings, and
  * the audit is only worth having if it is complete.
  *
  * ==One documented gap is not here==
  *
  * Piecewise cost curves. Every case in this suite goes through `Lopf.build`, and
  * that gap is refused a layer below it: a curve is a *file*, so `CsvReader` and
  * `NetCdfReader` turn it away before a model is built and neither raises
  * `Lopf.UnsupportedNetwork`. It takes two cases rather than one, because the two
  * readers live in modules that cannot see each other: `GoldenNetworkSuite`'s "a
  * piecewise cost curve is refused by the CSV reader" and `NetCdfReaderSuite`'s
  * "a piecewise cost curve is refused rather than read as a static column", each
  * against a real PyPSA export in `goldens/unsupported/`. Named here so that the
  * gap list in NOTES and this suite can still be read against each other.
  */
class GapRefusalSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-gap-"

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
  // right-hand side. This test has now been rewritten twice as the types it named
  // got built -- first `operational_limit`, then `tech_capacity_expansion_limit` --
  // and all five that `global_constraints.py` dispatches on are built, so there is
  // no real type left to name.
  //
  // It is still worth a test, and the type it names is deliberately one PyPSA does
  // not have. `co2_budget` is the shape of the mistake: a plausible name for a cap
  // on emissions, which a reader would expect to behave like `primary_energy` and
  // which a port that fell through to a default would build as whatever its last
  // case happened to be. The refusal has to name the string it did not recognise,
  // because the value is almost always a typo for one of the five.
  refuses("GlobalConstraint type that is not implemented", "co2_budget") {
    mutate("ac-dc-co2", "global_constraints.csv", setColumn(_, "type", "co2_budget"))
  }
  // `>=` and `==` are implemented now, so the gap that remains is a sense PyPSA
  // does not write at all. Keeping a test for the *implemented* senses here would
  // put it in the wrong file; `GlobalConstraintSuite` compares those against PyPSA.
  refuses("GlobalConstraint sense PyPSA does not write", "sense") {
    mutate("ac-dc-co2", "global_constraints.csv", setColumn(_, "sense", "<"))
  }

  // Whole features, refused as networks rather than as attributes.
  refuses("committable units", "committable")(network("unit-commitment"))

  // `multi-investment periods` was here as one blanket refusal. `Lopf` models
  // multi-period dispatch now, so the case is gone and what replaces it is the
  // narrower set: the parts whose *formulation* differs rather than merely their
  // weighting. Each is a mutation of the one multi-period fixture, so none of
  // them is unreachable-by-construction.
  // Capacity expansion across periods was here too, and is not any more: PyPSA
  // keeps one capacity variable per asset and changes only its objective
  // coefficient, so it was a weighting and three masks rather than a different
  // formulation. `investment-periods-expansion` is the fixture, and `LopfSuite`
  // compares it. What is still refused on top of expansion is refused on every
  // network, with or without periods -- `overnight_cost` and `p_nom_mod`, above --
  // but the composition is checked here rather than assumed, since "refused
  // elsewhere" is the reasoning this suite exists to stop trusting.
  refuses("an annuitised overnight_cost on a multi-period network", "overnight_cost") {
    mutate("investment-periods-expansion", "generators.csv",
           setColumn(_, "overnight_cost", (id, c) => if id == "wind" then "1000.0" else c))
  }
  refuses("a modular capacity on a multi-period network", "p_nom_mod") {
    mutate("investment-periods-expansion", "generators.csv",
           setColumn(_, "p_nom_mod", (id, m) => if id == "wind" then "10.0" else m))
  }
  // `max_growth` itself is built now -- see `growth-limit` and `GrowthLimit` -- so what is
  // left to refuse is a relative growth rate that cannot be a coefficient. PyPSA clips the
  // column at zero and does nothing else with it, so an infinite one reaches linopy as an
  // infinite coefficient; here it would reach `LpBuilder` as one, and an infinity in the
  // constraint matrix surfaces hundreds of rows away as an anonymous failure.
  refuses("an infinite Carrier max_relative_growth", "max_relative_growth") {
    mutate("growth-limit", "carriers.csv",
           setColumn(_, "max_relative_growth", (id, v) => if id == "wind" then "inf" else v))
  }
  // Per-period storage cycling was here and is built now -- `storage-per-period`
  // and `store-per-period` are the fixtures, and `LopfSuite` compares all four
  // readings of the four flags.
  //
  // What replaces it is the one place a per-period flag changes a row's SHAPE
  // rather than which snapshot its chain reaches back to, and the case only became
  // reachable when those flags stopped being refused. PyPSA's
  // `define_operational_limit` splits non-cyclic assets in two: `sus_continuous`
  // takes one final level over the whole horizon, while `sus_per_period` takes the
  // final level of every period and sums them against a per-period weighting. An
  // asset restarting from its initial level each period depletes once per period,
  // so one term is the wrong number of terms rather than the wrong coefficient.
  refuses("a per-period initial level under an operational limit",
          "state_of_charge_initial_per_period") {
    withFiles(
      "storage-per-period",
      "storage_units.csv" ->
        ("name,bus,carrier,p_nom,max_hours,marginal_cost,marginal_cost_storage," +
          "state_of_charge_initial,state_of_charge_initial_per_period\n" +
          "su,b,hydro,40.0,2.0,1.0,0.01,50.0,True\n"),
      "carriers.csv"         -> "name\nhydro\n",
      // `years` of 1 in both periods, because PyPSA's OTHER refusal here --
      // NotImplementedError for a continuous depletion across periods weighted
      // anything but 1 -- fires first otherwise, and this case is about the flag.
      "investment_periods.csv" -> "period,objective,years\n2030,1.0,1\n2040,1.0,1\n",
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "budget,operational_limit,hydro,<=,500.0\n"),
    )
  }
  // A non-finite weighting in `investment_periods.csv` reaches the objective, the nodal
  // prices and three constraint families, and `ComponentTable.periodWeighting` has no filter
  // of its own. Refused because PyPSA does not: measured on `tx-cost-periods`, an `objective`
  // weighting of NaN gives `ok/optimal` with an objective of 11,500 and an infinite one gives
  // `ok/unknown` with 0.0 -- a solve reporting Optimal on a number nobody computed.
  //
  // `NaN` and `Infinity`, not `nan` and `inf`, and the spelling is the test rather than
  // pedantry. `CsvReader` reads these two columns with `toDoubleOption.getOrElse(1.0)`, and
  // `Double.parseDouble` accepts only Java's exact spellings -- so `nan` does not parse, falls
  // back to 1.0, and reaches the builder as "no discounting" rather than as a refusal. The
  // first version of these tests used the lowercase forms and passed nothing: the refusal was
  // never reached, and `Lopf.build` solved an undiscounted network. The reachable paths for a
  // genuinely non-finite weighting are these spellings and `NetCdfReader`, which reads the
  // column as raw doubles with no spelling to get wrong.
  refuses("a NaN investment-period objective weighting", "objective weighting") {
    mutate("tx-cost-periods", "investment_periods.csv",
           setColumn(_, "objective", (p, w) => if p == "2040" then "NaN" else w))
  }
  refuses("an infinite investment-period years weighting", "years weighting") {
    mutate("tx-cost-periods", "investment_periods.csv",
           setColumn(_, "years", (p, w) => if p == "2040" then "Infinity" else w))
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
