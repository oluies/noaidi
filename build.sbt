// Prima -- a first-order (restarted PDHG / PDLP) linear programming solver for the JVM.
//
// Layout mirrors the migration brief: a dependency-free numeric core, an effectful
// ZIO facade at the edge, and solver backends kept behind one interface so the
// modeling layer never names a solver.

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "org.noaidi"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Cyfra is published for Scala 3.6.4; 3.9.0 reads that TASTy fine -- a later
// compiler reading older TASTy is the supported direction -- and keeps the rest
// of the ecosystem (ZIO, MUnit) on well-supported ground. The `primaCyfra` CI
// step is what pins this: it is the only thing that compiles against those
// artifacts, so a TASTy version this compiler refused would fail there and
// nowhere else.
//
// -deprecation and -feature rather than the compiler's defaults, which report
// both only as a count: "there were 3 deprecation warnings; re-run with
// -deprecation for details". That is how five calls to a method the standard
// library documents as able to crash your program sat here as an unnamed number
// until a compiler bump raised the count. A warning nobody can act on without
// re-running the build is a warning nobody reads.
//
// -Werror is not set here; ci.yml adds it with a `set` command on the three gated
// steps of its `test` job, which the JDK matrix runs twice. Not on every step that
// compiles: the kernel-split and validation-report jobs invoke sbt without it, so this
// is where the gate is rather than a claim about coverage.
//
// What is written down below is only what has been measured, because the mechanism
// behind this has now had two wrong explanations in this file and both read as
// confidently as this one. On sbt 2.0.9, with an isolated cache:
//
//   cold server, `CI=true sbt`             sys.env.get("CI") == Some(true)
//   a server already running               the value it started with, whatever the
//                                          current client's environment says
//   server killed, fresh cache, CI unset   None
//
// So `sys.env` in a build file is not unreadable -- a cold server does inherit the
// client's environment, and CI cold-starts one per job, so an env-var gate would in
// fact have worked there. What it is not is *stable* across invocations: the server
// keeps the evaluation it loaded with, so the same command in the same directory
// answers differently depending on what started the server. A gate whose value depends
// on that is one nobody can reproduce, which is reason enough to pass the flag on the
// command line instead. `referenceEnv` further down reaches the same *conclusion* for
// the goldens path, by the same route ci.yml takes here; its mechanism is corrected
// below rather than cited, since the blanket version it used to give is the one these
// measurements disprove.
//
// The other half was self-inflicted, and is kept because the symptom reads exactly like
// a build defect: each `set ThisBuild / scalacOptions += "-Werror"` accumulates in that
// same long-lived server, and a session's worth had stacked up -- `show
// primaCore/Compile/scalacOptions` printed the flag fourteen times. `show` is the
// diagnostic; killing the server is the cure. A flag that is *on* when nothing asked
// for it fails builds rather than passing them, so the next person to meet it will be
// debugging a compile error with a misleading cause.
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

val munitVersion  = "1.3.6"
val zioVersion    = "2.1.26"
val ojalgoVersion = "57.3.1"
val scalajsDomVersion = "2.8.1"
val scalaJavaTimeVersion = "2.6.0"

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
    // `VectorKernels` uses `jdk.incubator.vector`. Scala compiles against an
    // incubator module with no flag, but the JVM does not resolve one at run
    // time without being told, so loading that class raises
    // `NoClassDefFoundError` otherwise. Only the *tests* fork a JVM here, and
    // only they load it -- `Pdhg.solve` constructs `ScalaKernels` -- so nothing
    // a downstream caller does needs this flag unless it asks for that backend.
    //
    // `Test / fork` is required for `javaOptions` to reach anything: without a
    // forked JVM sbt ignores them, and the suite would fail on a flag the build
    // appears to set.
    Test / fork := true,
    Test / javaOptions += "--add-modules=jdk.incubator.vector",
  )

// ---------------------------------------------------------------------------- Scala.js
//
// The same sources as `primaCore` and `networkModel`, compiled to JavaScript. Not a
// `crossProject`: that would move every source file into a `.jvm`/`.js` layout and touch
// every module that depends on these two, which is a large change to make in order to
// answer a question. These projects point `unmanagedSourceDirectories` at the existing
// sources instead, so there is exactly one copy of the code and the JVM build is untouched.
//
// What has to be excluded, and it is one file: `kernels/VectorKernels.scala` uses
// `jdk.incubator.vector`. It already sits behind the `Kernels` trait with `ScalaKernels`
// as the fallback, and nothing inside `prima-core` constructs it -- a caller opts in -- so
// the linker would drop it anyway. It is excluded explicitly so that a future reference to
// it fails here rather than as a confusing link error.
//
// Nothing else in either module needed changing. `Unsafe` is two `asInstanceOf` casts,
// there are no threads and no `Future`, and the only `java.nio.file` in `network-model` is
// in `Schema.fromFile` and `CsvReader.read`, which the Scala.js linker drops as
// unreachable -- a browser fetches over HTTP rather than reading a path.
lazy val scalaJsSettings = Seq(
  // `%%%` is not available in the sbt 2 build of sbt-scalajs, so the platform suffix is
  // written out. `_sjs1_3` is exactly what `%%%` would have produced: Scala.js 1.x,
  // Scala 3.
  libraryDependencies += "org.scalameta" % s"munit_sjs1_3" % munitVersion % Test,
  // The JVM build fails a run that executed nothing; the JS build has the same hazard and
  // the same answer.
  Test / testOptions += Tests.Argument("+l"),
)

lazy val primaCoreJs = project
  .in(file("modules/prima-core-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(scalaJsSettings)
  .settings(
    name := "prima-core-js",
    Compile / unmanagedSourceDirectories := Seq((primaCore / Compile / scalaSource).value),
    Compile / unmanagedSources / excludeFilter := HiddenFileFilter || "VectorKernels.scala",
  )

lazy val networkModelJs = project
  .in(file("modules/network-model-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(scalaJsSettings)
  .settings(
    name := "network-model-js",
    libraryDependencies += "com.lihaoyi" % "upickle_sjs1_3" % upickleVersion,
    Compile / unmanagedSourceDirectories := Seq((networkModel / Compile / scalaSource).value),
    // `StandardTypeLibrary` reads a classpath resource, which a browser has none of. It is
    // the only file in the module that has to differ, which is why it is a file.
    Compile / unmanagedSources / excludeFilter :=
      HiddenFileFilter || "StandardTypeLibrary.scala",
    // Its replacement embeds the same CSVs, generated from `src/main/resources` at build
    // time. Generated rather than committed so there is still one copy of the data: a line
    // type added to the library reaches both platforms or neither, and the drift test in
    // `network-model` keeps the JVM side honest about the file's contents.
    Compile / sourceGenerators += Def.task {
      val resources = (networkModel / Compile / resourceDirectory).value /
        "org" / "noaidi" / "network" / "standard_types"
      val target = (Compile / sourceManaged).value /
        "org" / "noaidi" / "network" / "StandardTypeLibrary.scala"
      val entries = IO.listFiles(resources)
        .filter(_.getName.endsWith(".csv"))
        .sortBy(_.getName)
        .map { file =>
          val name = file.getName.stripSuffix(".csv")
          // A triple-quoted literal would break on any CSV containing one; escaping is
          // cheap and cannot.
          val escaped = IO.read(file)
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "")
          s"""    case "$name" => "$escaped""""
        }
      val body =
        s"""package org.noaidi.network
           |
           |// Generated by build.sbt from modules/network-model/src/main/resources. Do not
           |// edit: the JVM build reads those same files from the classpath, and this exists
           |// only because a browser has no classpath to read them from.
           |private[network] object StandardTypeLibrary:
           |  def text(listName: String): String = listName match
           |${entries.mkString("\n")}
           |    case other =>
           |      throw new IllegalStateException(s"no standard type library for $$other")
           |""".stripMargin
      IO.write(target, body)
      Seq(target)
    }.taskValue,
  )

lazy val networkPfJs = project
  .in(file("modules/network-pf-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(networkModelJs)
  .settings(scalaJsSettings)
  .settings(
    name := "network-pf-js",
    Compile / unmanagedSourceDirectories := Seq((networkPf / Compile / scalaSource).value),
  )

// The module that makes the port do something, and the only one that needed a library
// rather than an exclusion: `java.time` is confined to `HydroOps.scala` -- eight uses of
// `LocalDate`, `DayOfWeek`, `DateTimeFormatter` and `TemporalAdjusters`, for the calendar
// day and ISO week the reservoir limits are measured over -- and `scala-java-time` is a
// reimplementation of exactly that for Scala.js. No source changes.
lazy val networkLopfJs = project
  .in(file("modules/network-lopf-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(primaCoreJs, networkModelJs, networkPfJs)
  .settings(scalaJsSettings)
  .settings(
    name := "network-lopf-js",
    libraryDependencies += "io.github.cquiroz" % "scala-java-time_sjs1_3" % scalaJavaTimeVersion,
    Compile / unmanagedSourceDirectories := Seq((networkLopf / Compile / scalaSource).value),
  )

lazy val primaModelJs = project
  .in(file("modules/prima-model-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(primaCoreJs)
  .settings(scalaJsSettings)
  .settings(
    name := "prima-model-js",
    Compile / unmanagedSourceDirectories := Seq((primaModel / Compile / scalaSource).value),
  )

// `MpsReader.fromFile` is the only `java.nio.file` here and `fromString` is beside it, so
// the linker drops the one a browser cannot use and keeps the one it can.
lazy val primaMpsJs = project
  .in(file("modules/prima-mps-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(primaCoreJs)
  .settings(scalaJsSettings)
  .settings(
    name := "prima-mps-js",
    Compile / unmanagedSourceDirectories := Seq((primaMps / Compile / scalaSource).value),
  )

// Four modules are deliberately not here, and none of them for a language reason:
//
//   network-io      reads and writes HDF5 through `jhdf`, a Java library, and a browser has
//                   no filesystem to point it at
//   prima-ojalgo    wraps a Java solver
//   prima-ortools   wraps a native one
//   prima-cyfra     is a Vulkan backend
//   prima-netlib    and prima-validation exist to run file-based benchmark suites
//
// `prima-zio` could be ported -- ZIO cross-publishes and Scala.js implements the
// `AtomicBoolean` it uses -- and is left out because nothing in the demo is effectful and
// it would be weight for its own sake.

// The demo itself: a page that builds a network from what the sliders say, turns it into a
// linear program through the real `Lopf`, and solves it with Prima -- in the browser, with
// no server in the loop.
lazy val demoJs = project
  .in(file("modules/demo-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(primaCoreJs, networkModelJs, networkLopfJs)
  .settings(scalaJsSettings)
  .settings(
    name := "demo-js",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies += "org.scala-js" % "scalajs-dom_sjs1_3" % scalajsDomVersion,
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
val lwjglVersion = "3.4.3"

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
        Seq("lwjgl", "lwjgl-vma")
          .map(lib => ("org.lwjgl" % lib % lwjglVersion).classifier("natives-macos-arm64"))
      else Seq.empty
    ),
    // The whole org.lwjgl set has to move together. Cyfra declares lwjgl,
    // lwjgl-vma *and* lwjgl-vulkan at its own 3.4.0; only the first two are
    // named above, so raising lwjglVersion on its own reconciled those and left
    // lwjgl-vulkan behind -- a split LWJGL set, which its own docs rule out and
    // which surfaces as a NoSuchMethodError from the bindings into a
    // differently-versioned core. `primaCyfra/Test/compile` cannot see it, since
    // pure-Java bindings compile against any core; only running on a host with a
    // Vulkan stack can. Listing lwjgl-vulkan here is what keeps it tracking
    // lwjglVersion -- a further org.lwjgl module appearing in Cyfra's POM would
    // need adding to this list too.
    dependencyOverrides ++= Seq("lwjgl", "lwjgl-vma", "lwjgl-vulkan")
      .map(lib => "org.lwjgl" % lib % lwjglVersion),
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
// and does nothing useful. A build-load-time `sys.env` read is not stable across
// invocations: a cold server does inherit the client's environment, but a server
// already running answers from the environment it started with, so the same command
// in the same directory gives different answers depending on what started the
// server. A run meant to test a different schema can therefore quietly test the
// pinned one. The measurements are at the top of this file, above the
// `scalacOptions` setting.
//
// This paragraph used to say the thin client never shares its environment with the
// server. That is false on a cold start, and the correction is here rather than only
// there because a wrong mechanism with a right conclusion is the shape that spreads.
// The PyPSA drift workflow overrides the path with an sbt `set` command, which
// travels with the command line and does not depend on any of this.
def referenceEnv(base: File): Map[String, String] = Map(
  "NOAIDI_GOLDENS" -> (base / "reference" / "goldens").getAbsolutePath,
  "NOAIDI_SOURCES" -> (base / "modules").getAbsolutePath,
  // Absolute for the same reason as the two above, and the reason is worth
  // repeating rather than inferring: `Test / fork` does not run from the
  // repository root, so a relative default in the suite resolves to nothing and
  // every test that needs the fixtures reports as *skipped* rather than failed.
  // Seven of them did exactly that before this line existed, which is the quiet
  // half of the failure -- a green run over an empty assumption.
  "NOAIDI_NORDPSA" -> (base / "reference" / "nordpsa").getAbsolutePath,
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
  // ojAlgo in test scope only, and only to prove the point of the `LpSolver`
  // parameter `solve` now takes: an L2 model that can only be solved by the
  // solver it was written against is not solver-agnostic, whatever its type
  // signature says.
  .dependsOn(networkModel % "compile->compile;test->test", networkPf, primaCore, primaOjalgo % Test)
  .settings(commonSettings)
  .settings(
    name := "network-lopf",
    Test / fork := true,
    // `KernelSplit` can run the solve on `VectorKernels`, which loads the
    // incubator module. See `primaCore` for why this is needed and why it is
    // needed only by a forked test JVM.
    Test / javaOptions += "--add-modules=jdk.incubator.vector",
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

// The modeling layer: names, expressions and duals in the caller's own row
// numbering, over whichever backend `LpSolver` reaches. Depends on the core and
// on nothing else -- a model that could only be solved by the solver it was
// written against would not be a modeling layer.
lazy val primaModel = project
  .in(file("modules/prima-model"))
  .dependsOn(primaCore % "compile->compile;test->test", primaOjalgo % Test)
  .settings(commonSettings)
  .settings(
    name := "prima-model",
    Test / fork := true,
  )

val ortoolsVersion = "9.15.6755"

// `ortools-java` declares every platform's natives as runtime dependencies --
// five of them, about a hundred megabytes -- because a Maven POM has no way to
// say "whichever one this machine is". Naming the host's and excluding the rest
// is the same thing the LWJGL classifier does above, in the other direction.
val ortoolsPlatform: Option[String] =
  val os   = sys.props.getOrElse("os.name", "").toLowerCase
  val arch = sys.props.getOrElse("os.arch", "")
  if os.startsWith("mac") then Some(if arch == "aarch64" then "darwin-aarch64" else "darwin-x86-64")
  else if os.startsWith("linux") then Some(if arch == "aarch64" then "linux-aarch64" else "linux-x86-64")
  else if os.startsWith("windows") then Some("win32-x86-64")
  else None

lazy val primaOrtools = project
  .in(file("modules/prima-ortools"))
  .dependsOn(primaCore % "compile->compile;test->test", primaModel % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "prima-ortools",
    publish / skip := true,
    libraryDependencies += ("com.google.ortools" % "ortools-java" % ortoolsVersion)
      .exclude("com.google.ortools", "ortools-linux-x86-64")
      .exclude("com.google.ortools", "ortools-darwin-x86-64")
      .exclude("com.google.ortools", "ortools-win32-x86-64")
      .exclude("com.google.ortools", "ortools-linux-aarch64")
      .exclude("com.google.ortools", "ortools-darwin-aarch64"),
    // Nothing at all on an unrecognised host, so the module still compiles and
    // fails at load time with OR-Tools' own message rather than at resolution
    // with a coordinate nobody published.
    libraryDependencies ++=
      ortoolsPlatform.map(p => "com.google.ortools" % s"ortools-$p" % ortoolsVersion).toSeq,
    Test / fork := true,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    primaCore,
    primaZio,
    primaOjalgo,
    primaMps,
    primaValidation,
    primaModel,
    networkModel,
    networkLopf,
    networkPf,
    networkIo,
  )
  .settings(
    name := "noaidi",
    publish / skip := true,
  )
