// Prima -- a first-order (restarted PDHG / PDLP) linear programming solver for the JVM.
//
// Layout mirrors the migration brief: a dependency-free numeric core, an effectful
// ZIO facade at the edge, and solver backends kept behind one interface so the
// modeling layer never names a solver.

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "org.noaidi"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Cyfra is published for Scala 3.6.4; 3.7.4 reads that TASTy fine and keeps the
// rest of the ecosystem (ZIO, MUnit) on well-supported ground.
//
// sbt 2 already defaults to -deprecation -feature -unchecked -Wunused:all
// -Wvalue-discard, so scalacOptions stays empty rather than setting them twice.

val munitVersion  = "1.3.5"
val zioVersion    = "2.1.26"
val ojalgoVersion = "57.1.1"

// Note for CI and for anyone reading test output: under sbt 2 the `test` task is
// incremental and will happily report success having run nothing. Use `testFull`
// whenever the result is meant to prove anything.
lazy val commonSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
)

// The numeric core: pure, immutable, zero third-party dependencies. Everything
// that could later be staged to a GPU or to hardware lives behind `Kernels`.
lazy val primaCore = project
  .in(file("modules/prima-core"))
  .settings(commonSettings)
  .settings(
    name := "prima-core",
  )

// Effect boundary. Solver runs, cancellation and device interaction are ZIO
// effects; the core stays effect-free so it can be called from anywhere.
lazy val primaZio = project
  .in(file("modules/prima-zio"))
  .dependsOn(primaCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "prima-zio",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
    ),
  )

// ojAlgo backend: pure-JVM simplex/interior-point. Doubles as the correctness
// oracle Prima's own results are checked against.
lazy val primaOjalgo = project
  .in(file("modules/prima-ojalgo"))
  .dependsOn(primaCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "prima-ojalgo",
    libraryDependencies += "org.ojalgo" % "ojalgo" % ojalgoVersion,
  )

// MPS reader. Pure parsing, no third-party dependencies, so it is aggregated
// and runs in CI like the rest. It exists to reach the standard LP test
// corpora — Netlib above all — which no amount of hand-written fixtures
// substitutes for.
lazy val primaMps = project
  .in(file("modules/prima-mps"))
  .dependsOn(primaCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "prima-mps",
  )

// GPU backend spike. Cyfra compiles a Scala 3 DSL to SPIR-V and runs it on
// Vulkan, which on macOS means MoltenVK translating to Metal.
//
// Not aggregated into the root project: it needs a working Vulkan loader and an
// ICD at runtime, which no CI runner is guaranteed to have, and Cyfra is
// LGPL-2.1 where the rest of this build is Apache-2.0. Keeping it a separate,
// opt-in module contains both.
val cyfraVersion = "0.1.0-RC1"
val lwjglVersion = "3.4.0"

// The module is configured for macOS on Apple Silicon, which is where the spike
// was run. Everything host-specific is gated on these so that another platform
// gets a build that compiles and simply has no Vulkan wiring, rather than one
// that fails at runtime in a way that looks like a driver problem.
val isMacArm =
  sys.props.get("os.name").exists(_.toLowerCase.startsWith("mac")) &&
    sys.props.get("os.arch").contains("aarch64")
val homebrewLib = "/opt/homebrew/lib"
val moltenVkIcd = file("/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json")

lazy val primaCyfra = project
  .in(file("modules/prima-cyfra"))
  .dependsOn(primaCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "prima-cyfra",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "io.computenode" %% "cyfra-core"    % cyfraVersion,
      "io.computenode" %% "cyfra-dsl"     % cyfraVersion,
      "io.computenode" %% "cyfra-runtime" % cyfraVersion,
    ),
    // LWJGL resolves its native bindings by classifier and Cyfra declares only
    // the Java side, so the host's classifier has to be added here. Gated on
    // the actual host: hardcoding `natives-macos-arm64` would leave a Linux
    // build with no usable natives, failing at runtime with an
    // UnsatisfiedLinkError that reads as a missing driver rather than a build
    // misconfiguration. Cyfra pulls the Linux natives transitively already.
    libraryDependencies ++= (
      if isMacArm then
        Seq("lwjgl", "lwjgl-vma").map(lib => "org.lwjgl" % lib % lwjglVersion classifier "natives-macos-arm64")
      else Seq.empty
    ),
    Test / fork := true,
    // Only point the loader at a specific ICD when that ICD actually exists.
    // Setting VK_ICD_FILENAMES to a missing path makes the Vulkan loader skip
    // normal driver discovery rather than fall back to it, so an unconditional
    // value would break machines that are otherwise perfectly capable.
    Test / envVars ++= (
      if moltenVkIcd.exists then Map("VK_ICD_FILENAMES" -> moltenVkIcd.getAbsolutePath) else Map.empty
    ),
    // Prepended, not overwritten, so an inherited value survives.
    Test / envVars ++= (
      if isMacArm && file(homebrewLib).isDirectory then
        Map(
          "DYLD_LIBRARY_PATH" ->
            (homebrewLib +: sys.env.get("DYLD_LIBRARY_PATH").filter(_.nonEmpty).toSeq).mkString(":")
        )
      else Map.empty
    ),
    // Deliberately no -Dorg.lwjgl.librarypath: LWJGL ships its own natives and
    // pointing it at Homebrew's makes it report a version mismatch. Only the
    // Vulkan loader and ICD come from outside, via the env vars above.
  )

// Netlib LP corpus. Not aggregated: the suite downloads its instances on first
// run and skips itself when they are absent and cannot be fetched, so it needs
// network access once and no CI runner is obliged to have it.
//
// The download lives in the suite rather than in an sbt task deliberately.
// sbt 2 caches task results in a machine-wide action cache that `clean` does
// not clear, so a fetch task that failed once would keep replaying its failure;
// and a corpus that provisions itself keeps the module self-contained.
lazy val primaNetlib = project
  .in(file("modules/prima-netlib"))
  .dependsOn(primaCore % "compile->compile;test->test", primaMps)
  .settings(commonSettings)
  .settings(
    name := "prima-netlib",
    publish / skip := true,
    Test / fork := true,
    Test / envVars += "PRIMA_NETLIB_DIR" ->
      ((ThisBuild / baseDirectory).value / "target" / "netlib").getAbsolutePath,
  )

// L0: the network data model.
//
// PyPSA's component model is dynamic — types and attributes come from metadata,
// and users add their own — so the store is schema-driven rather than a fixed
// set of case classes. The schema is read from the pinned PyPSA install's own
// registry (reference/goldens/schema.json), which is why upickle is here.
val upickleVersion = "4.4.3"
val jhdfVersion    = "0.13.0"

// Where the network modules find the goldens and the port's own sources.
//
// `NOAIDI_SOURCES` is for `SchemaSweepSuite`, which searches the port as text
// rather than through the classpath: the modules above network-model are not on
// it, and the question it asks is what the code says rather than what it exports.
//
// Neither is read from the ambient environment, and the reason is worth writing
// down because the obvious `sys.env.getOrElse` here compiles, reads correctly,
// and does nothing. sbt 2's thin client does not share its environment with the
// build server, so a variable exported before `sbt` never reaches this file --
// it silently keeps the default and a run meant to test a different schema
// quietly tests the pinned one. The PyPSA drift workflow overrides the path with
// an sbt `set` command instead, which travels to the server with the rest of the
// command line.
def referenceEnv(base: File): Map[String, String] = Map(
  "NOAIDI_GOLDENS" -> (base / "reference" / "goldens").getAbsolutePath,
  "NOAIDI_SOURCES" -> (base / "modules").getAbsolutePath,
)

lazy val networkModel = project
  .in(file("modules/network-model"))
  .settings(commonSettings)
  .settings(
    name := "network-model",
    libraryDependencies += "com.lihaoyi" %% "upickle" % upickleVersion,
    // The goldens are the schema's source of truth, so tests read them from the
    // repository rather than from a copy under test resources.
    Test / envVars ++= referenceEnv((ThisBuild / baseDirectory).value),
    Test / fork := true,
  )

// L2: linear optimal power flow. The first module that makes the port *do*
// something -- it turns a Network into an LpProblem, hands it to Prima, and maps
// the solution back.
lazy val networkLopf = project
  .in(file("modules/network-lopf"))
  // Depends on network-pf for the outage factors SCLOPF needs. Those are a
  // power-flow sensitivity, not an optimisation concept, so they belong there --
  // and having the security-constrained model import them is what keeps one
  // definition of susceptance and slack across both layers.
  .dependsOn(networkModel % "compile->compile;test->test", networkPf, primaCore)
  .settings(commonSettings)
  .settings(
    name := "network-lopf",
    Test / fork := true,
    Test / envVars ++= referenceEnv((ThisBuild / baseDirectory).value),
  )

// L2: linear power flow. Deliberately independent of Prima -- LPF is a linear
// *solve*, not an optimisation, so dragging in an LP solver would misrepresent
// the problem and couple two layers that have no reason to meet.
lazy val networkPf = project
  .in(file("modules/network-pf"))
  .dependsOn(networkModel % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "network-pf",
    Test / fork := true,
    Test / envVars ++= referenceEnv((ThisBuild / baseDirectory).value),
  )

// L1: PyPSA's binary formats. Both netCDF-4 and PyPSA's .h5 are HDF5
// containers, so one pure-Java HDF5 reader serves both -- jhdf is MIT, which
// sits fine alongside this build's Apache-2.0.
lazy val networkIo = project
  .in(file("modules/network-io"))
  .dependsOn(networkModel % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "network-io",
    libraryDependencies += "io.jhdf" % "jhdf" % jhdfVersion,
    Test / fork := true,
    Test / envVars ++= referenceEnv((ThisBuild / baseDirectory).value),
  )

// Cross-backend validation: Prima vs ojAlgo on a ladder of LP instances.
lazy val primaValidation = project
  .in(file("modules/prima-validation"))
  .dependsOn(primaCore % "compile->compile;test->test", primaOjalgo)
  .settings(commonSettings)
  .settings(
    name := "prima-validation",
    publish / skip := true,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    primaCore,
    primaZio,
    primaOjalgo,
    primaMps,
    primaValidation,
    networkModel,
    networkLopf,
    networkPf,
    networkIo,
  )
  .settings(
    name := "noaidi",
    publish / skip := true,
  )
