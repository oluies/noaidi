package org.noaidi.prima
package netlib

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** The Netlib LP corpus, fetched on demand and cached on disk.
  *
  * Netlib is the standard correctness bar for linear programming. It matters
  * here for two reasons that hand-written fixtures cannot cover: it contains
  * degenerate and badly conditioned instances nobody would think to invent, and
  * it contains a collection built specifically to be '''infeasible''' — which is
  * the direct test of the error Prima's certificate logic has made twice.
  *
  * The instances are downloaded rather than committed. They are third-party
  * test data of a few megabytes that never changes, and putting them in this
  * repository's history would buy nothing.
  */
object NetlibCorpus:

  private val baseUrl = "https://raw.githubusercontent.com/SkyLiu0/NETLIB/main"

  /** Netlib's infeasible collection, in full.
    *
    * Every one of these must be reported infeasible or inconclusive, and never
    * optimal. This is the set the suite asserts hardest on.
    */
  val infeasibleNames: Seq[String] = Seq(
    "bgdbg1", "bgetam", "bgindy", "bgprtr", "box1", "ceria3d", "chemcom",
    "cplex1", "cplex2", "ex72a", "ex73a", "forest6", "galenet", "gosh",
    "gran", "greenbea", "itest2", "itest6", "klein1", "klein2", "klein3",
    "mondou2", "pang", "pilot4i", "qual", "reactor", "refinery", "vol1",
    "woodinfe",
  )

  /** A cross-section of the feasible set, smallest first.
    *
    * Not the whole 114: the larger instances take a first-order method without
    * presolve a very long time, and a suite nobody runs is worth nothing.
    */
  val feasibleNames: Seq[String] = Seq(
    "afiro", "sc50a", "sc50b", "kb2", "adlittle", "blend", "share2b",
    "sc105", "stocfor1", "scagr7", "israel", "share1b", "beaconfd",
    "lotfi", "brandy", "e226", "sc205", "scorpion", "bandm",
  )

  private def root: Path =
    Paths.get(sys.env.getOrElse("PRIMA_NETLIB_DIR", "target/netlib"))

  /** Download `url` to `dest` unless it is already there. Returns false when
    * the fetch fails, which is how an offline machine ends up skipping rather
    * than failing.
    */
  private def ensure(url: String, dest: Path): Boolean =
    if Files.exists(dest) && Files.size(dest) > 0 then true
    else
      Try {
        Files.createDirectories(dest.getParent)
        Using.resource(URI.create(url).toURL.openStream()) { in =>
          Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Files.size(dest) > 0
      }.getOrElse(false)

  private def instancePath(kind: String, name: String): Option[Path] =
    val dest = root.resolve(kind).resolve(s"$name.mps")
    if ensure(s"$baseUrl/$kind/$name.mps", dest) then Some(dest) else None

  def feasible(name: String): Option[Path]   = instancePath("feasible", name)
  def infeasible(name: String): Option[Path] = instancePath("infeasible", name)

  /** Reference optima published with the corpus, computed by Gurobi at 1e-8.
    *
    * This is the project's first oracle independent of ojAlgo — until now every
    * cross-check ran against a solver that is itself part of this build.
    */
  lazy val referenceObjectives: Map[String, Double] =
    val dest = root.resolve("feasible_reference.csv")
    if !ensure(s"$baseUrl/feasible_gurobi_1e-8.csv", dest) then Map.empty
    else
      Try {
        val lines = Files.readAllLines(dest).asScala.toList
        val header = lines.headOption.getOrElse("").split(",", -1).map(_.trim).toIndexedSeq
        // Read by column name, not by position. A column added upstream would
        // otherwise stop every row matching and empty the whole map, taking the
        // correctness assertions with it and leaving the suite green.
        def columnOf(name: String): Int =
          val i = header.indexOf(name)
          if i < 0 then sys.error(s"reference CSV has no '$name' column; header was ${header.mkString(",")}")
          i
        val nameCol   = columnOf("name")
        val statusCol = columnOf("status")
        val objCol    = columnOf("pobj")

        lines.tail.flatMap { line =>
          val f = line.split(",", -1).map(_.trim)
          if f.length > objCol && f(statusCol) == "OPTIMAL" then
            f(objCol).toDoubleOption.map(f(nameCol) -> _)
          else None
        }.toMap
      }.getOrElse(Map.empty)

  /** Whether the corpus is reachable at all. Everything is skipped rather than
    * failed when it is not, so an offline machine gets a green build with an
    * honest message instead of a red one it cannot fix.
    */
  lazy val available: Boolean = feasible("afiro").isDefined
