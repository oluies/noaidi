package org.noaidi.network

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import SchemaSweepSuite.*

/** Every input attribute PyPSA declares, accounted for or the build fails.
  *
  * This is the sweep that found `active`, `phase_shift`, `ramp_limit_up`,
  * `ramp_limit_down`, `e_sum_max`, `e_sum_min`, multi-investment periods and
  * `Link.delay` — enumerate the attributes the schema declares as input,
  * subtract the ones the sources ever name, and look hard at what is left. Six
  * confirmed silent wrong answers came out of it, the largest under-pricing an
  * objective by 23,280 while reporting `Optimal`.
  *
  * '''It was a throwaway grep, run twice.''' Exactly the position
  * [[org.noaidi.lopf.GapRefusalSuite]] was written to fix one level down: an
  * audit that proved the state of the tree on one afternoon and left nothing
  * behind. Re-running it by hand is not something anyone will remember to do
  * when PyPSA 1.3 adds a column, and an attribute nobody has looked at is
  * precisely the failure mode that produced all six.
  *
  * So the sweep is the test, and its residue is committed as [[ledger]] — one
  * line per attribute, with the reason it cannot change an answer. An attribute
  * that is neither named by the sources nor ruled on here fails the build.
  *
  * ==The three rules==
  *
  *   - '''Unaccounted fails.''' An input attribute that the sweep cannot see and
  *     the ledger does not name is drift: either upstream added it, or it was
  *     always there and nobody looked.
  *   - '''A stale ruling fails.''' If the sources start naming a ledgered
  *     attribute, the ruling is now a lie about the code and must be deleted in
  *     the change that implemented it — the same deliberate deletion
  *     `GapRefusalSuite` asks for when a gap is closed.
  *   - '''A ruling on nothing fails.''' An entry naming a component or attribute
  *     the schema no longer has means upstream removed it, and the line should
  *     go with it.
  *
  * ==What it does not do==
  *
  * It does not check that an attribute the sources *name* is handled
  * '''correctly''', only that some code names it. That is what the goldens are
  * for, and being named is a far weaker claim than being right.
  *
  * It matches attribute names, not `(component, attribute)` pairs, because the
  * sources refer to attributes by bare name — `table.float("x", id)` reads a
  * Line's reactance and there is nothing in the call to say so. One attribute is
  * masked by that today: `Bus.x` is a map coordinate and is invisible to this
  * sweep because `Line.x` is a reactance, so it is ruled on in prose beside
  * `Bus.y` rather than mechanically. `ShuntImpedance` is masked wholesale for the
  * same reason — every one of its attributes shares a name with a component that
  * is modelled — and it is refused outright by `Lopf.rejectUnhandled`, which is
  * what makes that survivable.
  *
  * Two further imprecisions follow from matching text rather than meaning, and
  * both can decide a rule. [[quoted]] is '''every''' identifier-shaped string
  * literal in the main sources — error-message fragments, JSON keys, HDF5 paths
  * — not only literals in an accessor position, so an unrelated one can mask an
  * attribute as named. Quoting `"v_ang"` anywhere would fail open on
  * `Line.v_ang_min` and `v_ang_max` and fail their rulings as stale in the same
  * run. [[interpolated]] compounds it by taking a full cross-product, so it
  * manufactures names no code contains; that one can only ever mask, never
  * report. Narrowing `quoted` to accessor call sites would remove most of the
  * surface and is the obvious move if either misfires — neither has yet, and a
  * narrower match trades this for spurious unaccounted attributes, which is
  * noisier and no safer.
  *
  * A ruling's *reason* is prose and nothing checks it. The rules above check that
  * a reason exists and still applies to a real attribute; whether it is true is a
  * review question, which is why the ledger is a diff rather than a suppression
  * file.
  */
class SchemaSweepSuite extends munit.FunSuite:

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  /** The port's own sources. Read as text from disk rather than reached through
    * the classpath: the sweep is over what the code *says*, and the modules above
    * this one are not on it.
    */
  private def sources: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_SOURCES", "modules"))

  private lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  private lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  /** The modules that make up the port. Prima is deliberately absent: it is an LP
    * solver and knows nothing about PyPSA's component model, so a hit there would
    * be a coincidence rather than a use.
    */
  private val ported = Seq("network-model", "network-lopf", "network-pf", "network-io")

  private lazy val sourceFiles: IndexedSeq[Path] =
    ported.toIndexedSeq.flatMap { module =>
      val root = sources.resolve(module).resolve("src").resolve("main").resolve("scala")
      if !Files.isDirectory(root) then IndexedSeq.empty
      else
        Using.resource(Files.walk(root)) { walk =>
          walk.iterator.asScala.filter(_.getFileName.toString.endsWith(".scala")).toIndexedSeq
        }
    }

  private lazy val text: String = sourceFiles.map(Files.readString).mkString("\n")

  /** Attribute names the sources quote outright. */
  private lazy val quoted: Set[String] =
    """"([A-Za-z][A-Za-z0-9_]*)"""".r.findAllMatchIn(text).map(_.group(1)).toSet

  /** Suffixes the sources append to an interpolated attribute name.
    *
    * `Expansion` reads a component's capacity bounds as `s"${attribute}_max"`
    * where `attribute` is `p_nom`, `s_nom` or `e_nom` — so `p_nom_max` is read by
    * code that never writes it down. A literal sweep reports twenty-four such
    * attributes as unhandled, and burying them in the ledger would be filing the
    * mechanism's blind spot as a human judgement.
    */
  private lazy val suffixes: Set[String] =
    """\$\{[A-Za-z][A-Za-z0-9_]*\}(_[A-Za-z0-9_]+)""".r.findAllMatchIn(text).map(_.group(1)).toSet

  /** The stems those suffixes get appended to.
    *
    * Read out of the map that binds them rather than guessed at from shape:
    * `Expansion.nominalAttribute` maps every expandable component to `p_nom`,
    * `s_nom` or `e_nom`, and its values are the only things the `${attribute}` in
    * the interpolations above is ever bound to. Matching quoted names ending
    * `_nom` instead would readmit `v_nom` and `f_nom`, which the sources quote
    * and no code ever interpolates.
    *
    * Restricted, where this used to be every quoted name. A full cross-product
    * manufactures names no code contains, and unlike [[quoted]]'s imprecision it
    * can only ever mask an attribute, never report a spurious one -- so it is
    * invisible until the week upstream adds a name it happens to manufacture.
    * PyPSA 1.3.0 was that week: it added `Transformer.phase_shift_min` and
    * `phase_shift_max`, the port quotes `"phase_shift"` because the linear flow
    * reads it, and the sweep called both accounted for while nothing in the tree
    * named either. They belong to the one 1.3.0 feature that moves an answer this
    * port already computes, which is the sweep failing in exactly the case it
    * exists for.
    */
  private lazy val stems: Set[String] =
    """->\s*"([A-Za-z][A-Za-z0-9_]*_nom)"""".r.findAllMatchIn(text).map(_.group(1)).toSet

  /** Names the sources build by appending a known suffix to a stem they quote. */
  private lazy val interpolated: Set[String] =
    for stem <- stems; suffix <- suffixes yield stem + suffix

  private def named(attribute: String): Boolean =
    quoted.contains(attribute) || interpolated.contains(attribute)

  /** Whether the sources name the attribute a `Component.attribute` key points at. */
  private def namedByKey(key: String): Boolean = named(attributeOf(key))

  /** Every input attribute, as `Component.attribute`. */
  private lazy val inputs: IndexedSeq[String] =
    schema.components.flatMap(c => c.inputAttributes.map(a => s"${c.name}.${a.name}"))

  // The sweep is a search over text, and a search that finds nothing looks
  // exactly like a search that has nothing to find. A tree that failed to be
  // located would otherwise report every attribute as unhandled -- or, with the
  // ledger absorbing the ones it names, report a suspiciously tidy result.
  test("the sweep actually read the port and the schema") {
    assume(available, "goldens missing")
    // Printed, not merely asserted. The drift workflow runs this suite against a
    // schema generated from a newer PyPSA, and if the override failed to take it
    // would read the pinned one and pass -- which is indistinguishable from
    // finding no drift. The workflow greps this line to tell those apart.
    //
    // On its own line, and before anything that touches `schema`. Folding the
    // path into a message that also reports `inputs.size` would force the parse
    // first, and `AttributeType.parse` throws on a type it cannot classify --
    // so a genuinely new upstream type would kill the suite before it printed,
    // and the workflow would blame a failed sbt override for what is the loudest
    // drift signal there is.
    println(s"schema sweep: reading ${goldens.resolve("schema.json")}")
    println(s"schema sweep: ${sourceFiles.size} source files under $sources")
    assert(sourceFiles.sizeIs >= 20, s"only ${sourceFiles.size} source files under $sources")
    println(s"schema sweep: ${inputs.size} input attributes")
    assert(inputs.sizeIs >= 300, s"only ${inputs.size} input attributes in the schema")
    assert(quoted.sizeIs >= 100, s"only ${quoted.size} quoted names in the sources")
    assertEquals(
      suffixes,
      Set("_extendable", "_max", "_min", "_mod"),
      "the set of interpolated attribute suffixes moved; the resolution above needs rechecking",
    )
    // The other half of the same resolution, and the half that was missing.
    // Suffixes were pinned here from the start while the stems were left as
    // every quoted name, so the cross-product could grow without this test
    // noticing -- which is how `phase_shift_min` and `phase_shift_max` came to
    // be manufactured.
    assertEquals(
      stems,
      Set("e_nom", "p_nom", "s_nom"),
      "the set of interpolated attribute stems moved; the resolution above needs rechecking",
    )
  }

  test("every input attribute is named by the port or ruled on here") {
    assume(available, "goldens missing")
    val ruled       = ledger.map(_.key).toSet
    val unaccounted = inputs.filterNot(namedByKey).filterNot(ruled.contains)
    assert(
      unaccounted.isEmpty,
      s"${unaccounted.size} input attribute(s) that no source names and no ruling covers.\n" +
        "Each is a possible silent wrong answer: read it, decide, and either handle it, " +
        "refuse it, or add a ruling saying why it cannot change an answer.\n  " +
        unaccounted.mkString("\n  "),
    )
  }

  test("no ruling covers an attribute the port now names") {
    assume(available, "goldens missing")
    val stale = ledger.filter(r => named(r.attribute))
    assert(
      stale.isEmpty,
      s"${stale.size} ruling(s) whose attribute the sources now name. If it was implemented or " +
        "refused, delete the ruling in that change rather than leaving it to assert the opposite.\n  " +
        stale.map(_.key).mkString("\n  "),
    )
  }

  test("no ruling covers an attribute the schema does not declare") {
    assume(available, "goldens missing")
    val declared = inputs.toSet
    val orphans  = ledger.map(_.key).filterNot(declared.contains)
    assert(
      orphans.isEmpty,
      s"${orphans.size} ruling(s) naming something the pinned schema has no input attribute for. " +
        "Upstream removed it, or the key is misspelt.\n  " + orphans.mkString("\n  "),
    )
  }

  test("no attribute is ruled on twice") {
    val duplicates = ledger.groupBy(_.key).collect { case (k, rs) if rs.sizeIs > 1 => k }
    assert(duplicates.isEmpty, s"duplicate ruling(s): ${duplicates.mkString(", ")}")
  }

object SchemaSweepSuite:

  /** Why an attribute the sources never name cannot change an answer. */
  enum Verdict:
    /** The component itself is refused, so none of its attributes can be
      * silently dropped — the network is.
      */
    case ComponentRefused

    /** PyPSA reads it only on a multi-investment-period network, and something
      * refuses that reading before it can matter.
      *
      * Not `Periods.reject` refusing every multi-period network, which is what
      * this used to say: `Lopf` models multi-period dispatch, and `Periods`
      * refuses only the parts whose formulation differs. The one entry left here
      * rests on `Expansion.reject` instead, which refuses annuitised capital
      * cost on every network, single- or multi-period. Each entry states its own
      * grounds; this verdict only records that the reading is multi-period.
      */
    case MultiPeriodOnly

    /** PyPSA reads it only under a flag this port refuses, so the refusal is
      * reached before the attribute can be.
      *
      * Narrower than [[ComponentRefused]], which rests on a whole table being
      * unhandled, and a different kind of claim from [[Inert]], which is about
      * PyPSA rather than about this port. This one has a moving part: it holds
      * exactly as long as the refusal does, so a line under this verdict names
      * the refusal carrying it and must go when that refusal goes -- the same
      * deliberate deletion `GapRefusalSuite` asks for when a gap closes.
      */
    case GatedByRefusal

    /** PyPSA's own optimiser and power flow never read it, so no value of it can
      * move an answer this port is checked against.
      */
    case Inert

    /** Read, but under a name that arrives in the data rather than in the source. */
    case NamedIndirectly

    /** Carries no physics: presentation, provenance, or library bookkeeping. */
    case Descriptive

  import Verdict.*

  /** The bare attribute name out of a `Component.attribute` key. */
  def attributeOf(key: String): String = key.substring(key.indexOf('.') + 1)

  final case class Ruling(key: String, verdict: Verdict, because: String):
    def attribute: String = attributeOf(key)

  private def ruled(verdict: Verdict, because: String)(keys: String*): Vector[Ruling] =
    keys.toVector.map(Ruling(_, verdict, because))

  /** The sweep's residue, one line per attribute.
    *
    * The reason each entry is here matters more than how many there are, and a
    * count written down beside the entries is a second place for it to go stale
    * — `NOTES` recounts it when the sweep moves. A ruling is a claim that the
    * attribute cannot change an answer — not that it is unimportant, and not
    * that nobody got round to it. When that stops being true the line is the
    * thing to delete.
    */
  val ledger: Vector[Ruling] =
    // ---- Read only behind a refusal ------------------------------------------
    //
    // Maintenance scheduling, new in PyPSA 1.3.0. `Commitment.reject` refuses any
    // entity with `maintainable` set, on any component declaring it, so the three
    // attributes that describe the outages -- how many, how long, and what the
    // entity can still do during one -- are unreachable rather than ignored.
    //
    // `maintainable` itself is not here: the refusal names it, and a ruling on an
    // attribute the port names fails as stale.
    ruled(
      GatedByRefusal,
      "read only when maintainable is set, which Commitment.reject refuses",
    )(
      "Generator.maintenance_duration",
      "Generator.maintenance_events",
      "Generator.maintenance_pu",
      "Link.maintenance_duration",
      "Link.maintenance_events",
      "Link.maintenance_pu",
    ) ++
    // ---- Whole components this port refuses -----------------------------------
    //
    // `Lopf.rejectUnhandled` throws on any table outside its handled set, so a
    // network carrying processes is refused rather than solved without them. The
    // attributes below are therefore unreachable, not ignored -- which is the
    // distinction that made `investment_periods.csv` a bug and makes this not one.
    ruled(
      ComponentRefused,
      "Process is not modelled; Lopf.rejectUnhandled refuses any network that carries one",
    )(
      // `Process.build_year` and `Process.lifetime` were here and cannot be:
      // `Periods` quotes both bare names for the components it *does* model, and
      // this sweep matches bare names, so a ruling on them would go stale the
      // moment multi-period was implemented. Masked exactly as `Bus.x` is by
      // `Line.x`, and safe for the same reason the rest of this group is --
      // `Lopf.rejectUnhandled` refuses any network carrying a Process at all.
      "Process.cyclic_delay0",
      "Process.cyclic_delay1",
      "Process.delay0",
      "Process.delay1",
      "Process.discount_rate",
      "Process.maintenance_duration",
      "Process.maintenance_events",
      "Process.maintenance_pu",
      "Process.p_nom_set",
      "Process.rate0",
      "Process.rate1",
      "Process.terrain_factor",
    ) ++
      // ---- Multi-period only ---------------------------------------------------
      //
      // `build_year` and `lifetime` used to be ruled here, on the grounds that a
      // multi-period network was refused outright. `Periods` reads both now, so
      // those eighteen entries are gone rather than reworded -- and
      // `Carrier.max_growth`, `max_relative_growth` and
      // `GlobalConstraint.investment_period` with them, each now named by the
      // refusal that replaced the blanket one.
      //
      // `investment_period` is the one worth recording. It was ruled *safe*
      // because the whole network was refused; narrowing that refusal turned a
      // ledger entry into a live gap -- a cap meant for one period applied to the
      // whole horizon -- and the sweep is what surfaced it, which is the job.
      //
      // `discount_rate` stays. It is only read when an `overnight_cost` is
      // annuitised, and `Expansion.reject` refuses that on every network,
      // single- or multi-period.
      ruled(
        MultiPeriodOnly,
        "annuitises overnight_cost, which Expansion.reject refuses on every network",
      )(
        "Generator.discount_rate",
        "Line.discount_rate",
        "Link.discount_rate",
        "StorageUnit.discount_rate",
        "Store.discount_rate",
        "Transformer.discount_rate",
      ) ++
      // ---- Inert in PyPSA itself -----------------------------------------------
      //
      // The strongest form of ruling available: the attribute gets zero hits in
      // PyPSA's own optimiser, so there is no value of it that makes PyPSA and
      // this port disagree. Each was checked against the pinned install rather
      // than inferred from the documentation.
      ruled(
        Inert,
        "a target capacity for PyPSA's own heuristics; its optimiser never reads it",
      )(
        "Generator.p_nom_set",
        "Line.s_nom_set",
        "Link.p_nom_set",
        "StorageUnit.p_nom_set",
        "Store.e_nom_set",
        "Transformer.s_nom_set",
      ) ++
      ruled(
        Inert,
        "scales a length-based capital cost in PyPSA's cost helpers, not in the LP it builds",
      )("Line.terrain_factor", "Link.terrain_factor") ++
      ruled(
        Inert,
        "a voltage-angle bound PyPSA's linear optimiser does not constrain on",
      )("Line.v_ang_min", "Line.v_ang_max", "Transformer.v_ang_min", "Transformer.v_ang_max") ++
      ruled(
        Inert,
        "a desired voltage band; neither the LOPF nor the Newton-Raphson solve bounds on it, " +
          "which reads v_mag_pu_set instead",
      )("Bus.v_mag_pu_min", "Bus.v_mag_pu_max") ++
      ruled(
        Inert,
        "weights a generator in PyPSA's network-clustering helpers, not in any optimisation",
      )("Generator.weight") ++
      // `Link.cyclic_delay` was ruled inert here, on the grounds that only a
      // non-zero `delay` can observe it and `Lopf.rejectDelayedLinks` refused
      // those. `Delays` implements both, so the ruling is gone rather than
      // reworded -- the sources name the attribute now, which is what the
      // stale-ruling rule below is for.
      //
      // The type library's expansion produces r, x, g, b, s_nom, tap_ratio and
      // phase_shift, and `standard-types`, `transformer-levels` and
      // `transformer-taps` agree with PyPSA on every one. These four feed none of
      // them: the winding voltages are documentation of what the type is for, and
      // the tap bounds are a range PyPSA does not clamp `tap_position` to either.
      ruled(
        Inert,
        "not read by the transformer-type expansion, which the standard-types goldens pin",
      )(
        "TransformerType.v_nom_0",
        "TransformerType.v_nom_1",
        "TransformerType.tap_min",
        "TransformerType.tap_max",
      ) ++
      // ---- Read under a name that comes from the data --------------------------
      //
      // A primary-energy constraint names the carrier column it charges in its own
      // `carrier_attribute` field, and `Lopf` reads whatever it says. So the
      // commonest emissions cap in PyPSA works without `co2_emissions` appearing
      // anywhere in this port -- and would keep working if a network charged some
      // other carrier attribute instead.
      ruled(
        NamedIndirectly,
        "read through GlobalConstraint.carrier_attribute, which holds the column name as data",
      )("Carrier.co2_emissions") ++
      // ---- Descriptive ----------------------------------------------------------
      //
      // Presentation, provenance and library bookkeeping. `Bus.x` belongs here too
      // and cannot be written down: this sweep matches bare attribute names, and
      // `Line.x` is a reactance the sources quote constantly, so a ruling on
      // `Bus.x` would fail the stale-ruling rule the moment it was added.
      ruled(Descriptive, "map coordinate, for plotting")("Bus.y") ++
      ruled(Descriptive, "a free-text place name")("Bus.location") ++
      ruled(Descriptive, "plot colour and display label")("Carrier.color", "Carrier.nice_name") ++
      ruled(
        Descriptive,
        "geometry bookkeeping: which component a shape belongs to and its index within it",
      )("Shape.component", "Shape.idx") ++
      ruled(
        Descriptive,
        "line-type catalogue metadata: conductor geometry, thermal rating and where the row came from",
      )(
        "LineType.cross_section",
        "LineType.i_nom",
        "LineType.mounting",
        "LineType.references",
      ) ++
      ruled(Descriptive, "where the transformer-type row came from")("TransformerType.references")
