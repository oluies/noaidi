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
