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

val munitVersion  = "1.3.4"
val zioVersion    = "2.1.26"
val ojalgoVersion = "55.2.0"

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
  .aggregate(primaCore, primaZio, primaOjalgo, primaValidation)
  .settings(
    name := "noaidi",
    publish / skip := true,
  )
