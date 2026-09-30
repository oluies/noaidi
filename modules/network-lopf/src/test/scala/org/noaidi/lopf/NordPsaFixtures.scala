package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network}

/** The `reference/nordpsa` fixtures, for the suites that read them.
  *
  * One definition rather than one per suite, which is the rule [[CsvFixtures]] states
  * and this file exists because a review caught the second copy of it: `HydroOpsSuite`
  * had its own `root`, its own network loader and its own shallow directory copy, and
  * the copy had already dropped a guard the shared one carries.
  *
  * The path comes from the environment because `Test / fork` does not run from the
  * repository root, so a relative default resolves to nothing and every test that
  * needs a fixture reports as '''skipped''' rather than failed -- a green run over an
  * empty assumption. `referenceEnv` in build.sbt supplies it absolute.
  */
trait NordPsaFixtures extends CsvFixtures:

  protected def root: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_NORDPSA", "reference/nordpsa"))

  /** Whether the schema and one named reference file are both present. */
  protected def hasReference(file: String): Boolean =
    available && Files.exists(root.resolve(file))

  /** One of the exported NordPSA networks. */
  protected def variant(key: String): Network =
    CsvReader.read(root.resolve("networks").resolve(key), schema, key)

  /** The directory one exported network was read from, for a copy-and-edit. */
  protected def variantDir(key: String): Path =
    root.resolve("networks").resolve(key)

  /** A reference file, as parsed JSON. */
  protected def referenceJson(file: String): ujson.Value =
    ujson.read(Files.readString(root.resolve(file)))

  /** NordPSA's technology table, as a reference file records it.
    *
    * Here rather than in one suite because two read it, and because it is the part of the
    * stability configuration most expensive to get wrong by hand: fourteen classes times
    * eight numbers, and a single wrong digit makes a suite agree with a PyPSA run that
    * answered a different question. It lives in `config/zones.yaml`, which no network export
    * can carry, so both sides have to read it from the same place or drift.
    */
  protected def stabilityTech(reference: ujson.Value): Map[String, Stability.Tech] =
    reference("tech").obj.map { (name, t) =>
      val mode = Stability.Mode.parse(t("mode").str).getOrElse(
        throw new IllegalArgumentException(
          s"the reference file gives $name an unknown mode '${t("mode").str}'"))
      def num(key: String, fallback: Double): Double =
        t.obj.get(key).filterNot(_.isNull).map(_.num).getOrElse(fallback)
      name -> Stability.Tech(
        mode = mode,
        inertiaSeconds = num("H", 0.0),
        cosPhi = num("cos_phi", 1.0),
        subtransientReactance = num("xd2", Double.NaN),
        minStableFraction = num("m_min", 0.0),
        availability = num("avail", 1.0),
        converterWeight = num("ibr_w", 0.0),
        shortCircuitPerUnit = num("sk_pu", 0.0),
      )
    }.toMap

  /** The zone half of `zones.yaml`: what the units are, not what a run asks for. */
  protected def stabilityZoneData(reference: ujson.Value): Stability.Config =
    val z = reference("zone_data")
    Stability.Config(
      tech = stabilityTech(reference),
      mapping = z("mapping").obj.map((k, v) => k -> v.str).toMap,
      nameOverrides = z("name_overrides").obj.map((k, v) => k -> v.str).toMap,
      transformerReactance = z("x_t").num,
      syncWeight = z("sync_weight").obj.map((k, v) => k -> v.num).toMap,
      scrExempt = z("scr_exempt").arr.map(_.str).toSet,
    )
