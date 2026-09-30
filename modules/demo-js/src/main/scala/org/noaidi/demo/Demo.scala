package org.noaidi.demo

import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

import org.scalajs.dom
import org.scalajs.dom.document

import org.noaidi.network.*
import org.noaidi.lopf.{BidLadder, HydroOps, Lopf, LopfResult, Stability}
import org.noaidi.prima.{Pdhg, PdhgParams, SolveStatus}

/** The whole port running in a browser: a `Network`, the real `Lopf`, and Prima.
  *
  * The earlier version of this page built a linear program by hand with `LpBuilder`, which
  * proved that `prima-core` linked but said nothing about the modelling layer. This one
  * builds an actual [[org.noaidi.network.Network]] — buses, lines, generators, a reservoir,
  * loads, snapshot weightings — hands it to [[Lopf.build]], and reads the answer back through
  * [[LopfResult]]. Every row in the problem is emitted by the same code the JVM runs,
  * including the two optional families the sliders switch on.
  *
  * `java.time` was the only thing `network-lopf` needed and `scala-java-time` provides it, so
  * `HydroOps` computes its calendar day and ISO week here exactly as it does on a server.
  * That is worth saying because it is the one part of this that could have required forking a
  * source file, and it did not.
  */
object Demo:

  private val Start = java.time.LocalDateTime.of(2023, 1, 2, 0, 0)   // a Monday

  private val Zones = IndexedSeq("A", "B", "C")

  private final case class Inputs(
      hours: Int,
      scrMin: Double,
      windCapacity: Double,
      hydroFloor: Double,
      ladderWidth: Double,
      lineRating: Double,
  )

  private def windProfile(t: Int): Double =
    val daily = math.sin(t * 2 * math.Pi / 24.0) * 0.18
    val spell = math.sin(t * 2 * math.Pi / 71.0) * 0.42
    math.max(0.0, 0.45 + daily + spell)

  private def loadAt(zone: String, t: Int): Double = zone match
    case "B" => 420.0 + 90.0 * math.sin((t - 7) * 2 * math.Pi / 24.0)
    case "C" => 380.0 + 70.0 * math.cos((t - 5) * 2 * math.Pi / 24.0)
    case _   => 0.0

  // ---------------------------------------------------------------- building the network

  private def floats(values: Double*): Column = Column.Floats(IArray(values*))
  private def strings(values: String*): Column = Column.Strings(IArray(values*))
  private def bools(values: Boolean*): Column = Column.Bools(IArray(values*))

  private def series(hours: Int, entity: String, f: Int => Double): TimeSeries =
    TimeSeries(IndexedSeq(entity), IArray.tabulate(hours)(t => IArray(f(t))))

  private def table(schema: Schema, component: String, ids: IndexedSeq[String],
                    static: (String, Column)*)(series: (String, TimeSeries)*): ComponentTable =
    ComponentTable(schema(component), ids, ListMap(static*), ListMap(series*))

  /** The network the sliders describe: three buses in a triangle, one reservoir, some wind.
    *
    * A triangle because every line's removal leaves it connected, which is what makes each
    * one a credible contingency — the same reason `sclopf-families` is one.
    */
  private def network(schema: Schema, in: Inputs): Network =
    val Hours = in.hours
    val labels = IndexedSeq.tabulate(Hours)(t =>
      Start.plusHours(t.toLong).format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    Network(
      name = "demo",
      schema = schema,
      snapshots = labels,
      snapshotWeightings = ListMap(
        "objective" -> IArray.fill(Hours)(1.0),
        "stores" -> IArray.fill(Hours)(1.0),
        "generators" -> IArray.fill(Hours)(1.0)),
      tables = ListMap(
        "Bus" -> table(schema, "Bus", Zones,
          "v_nom" -> floats(380.0, 380.0, 380.0),
          "carrier" -> strings("AC", "AC", "AC"))(),
        "Line" -> table(schema, "Line", IndexedSeq("AB", "BC", "AC"),
          "bus0" -> strings("A", "B", "A"),
          "bus1" -> strings("B", "C", "C"),
          "x" -> floats(0.10, 0.10, 0.12),
          "r" -> floats(0.0, 0.0, 0.0),
          "s_nom" -> floats(in.lineRating, in.lineRating, in.lineRating))(),
        "StorageUnit" -> table(schema, "StorageUnit", IndexedSeq("A hydro"),
          "bus" -> strings("A"),
          "carrier" -> strings("hydro"),
          "p_nom" -> floats(900.0),
          "max_hours" -> floats(60.0),
          "marginal_cost" -> floats(12.0),
          "spill_cost" -> floats(0.1),
          "state_of_charge_initial" -> floats(0.6 * 900.0 * 60.0),
          "cyclic_state_of_charge" -> bools(false))(
          "inflow" -> series(Hours, "A hydro", _ => 260.0)),
        "Generator" -> table(schema, "Generator",
          IndexedSeq("C wind", "B gas", "C peaker"),
          "bus" -> strings("C", "B", "C"),
          "carrier" -> strings("wind_onshore", "gas", "gas"),
          "p_nom" -> floats(in.windCapacity, 500.0, 400.0),
          "marginal_cost" -> floats(0.0, 85.0, 190.0))(
          "p_max_pu" -> series(Hours, "C wind", windProfile)),
        "Load" -> table(schema, "Load", IndexedSeq("lb", "lc"),
          "bus" -> strings("B", "C"))(
          "p_set" -> TimeSeries(IndexedSeq("lb", "lc"),
            IArray.tabulate(Hours)(t => IArray(loadAt("B", t), loadAt("C", t))))),
      ),
    )

  /** NordPSA's technology data, trimmed to the classes this network uses. */
  private def stability(in: Inputs): Stability.Config =
    if in.scrMin <= 0.0 then Stability.off
    else
      Stability.Config(
        tech = Map(
          "hydro_res" -> Stability.Tech(Stability.Mode.Commit, inertiaSeconds = 3.0,
            cosPhi = 0.9, subtransientReactance = 0.22, minStableFraction = 0.3),
          "gas" -> Stability.Tech(Stability.Mode.Commit, inertiaSeconds = 5.0,
            cosPhi = 0.85, subtransientReactance = 0.16, minStableFraction = 0.4),
          "ibr_wind" -> Stability.Tech(Stability.Mode.Ibr, converterWeight = 1.0),
        ),
        mapping = Map(
          "StorageUnit:hydro" -> "hydro_res",
          "Generator:gas" -> "gas",
          "Generator:wind_onshore" -> "ibr_wind"),
        transformerReactance = 0.12,
        scrMin = in.scrMin,
      )

  private final case class Solution(
      objective: Double, status: SolveStatus, millis: Double, iterations: Int,
      columns: Int, rows: Int, hours: Int,
      wind: Array[Double], hydro: Array[Double], gas: Array[Double],
      curtailed: Double, flows: Array[Double], rating: Double)

  private def solve(schema: Schema, in: Inputs): Solution =
    val Hours = in.hours
    val n = network(schema, in)
    val families = Lopf.Families(
      hydro =
        if in.hydroFloor > 0.0 then HydroOps.Config(minHourlyFraction = in.hydroFloor)
        else HydroOps.off,
      ladder =
        if in.ladderWidth > 0.0 then BidLadder.Config(tiers = 3, width = in.ladderWidth)
        else BidLadder.off,
      stability = stability(in),
    )
    val started = dom.window.performance.now()
    val result = Lopf.solve(n, families,
      Pdhg.Solver(PdhgParams(epsAbs = 1e-7, epsRel = 1e-7, maxIterations = 100_000)))
    val elapsed = dom.window.performance.now() - started

    val available = Array.tabulate(Hours)(t => in.windCapacity * windProfile(t))
    val wind = Array.tabulate(Hours)(t => result.dispatch("Generator", "C wind", t))
    Solution(
      objective = result.objective,
      status = result.status,
      millis = elapsed,
      iterations = result.solution.iterations,
      hours = Hours,
      columns = result.model.problem.numVariables,
      rows = result.model.problem.numConstraints,
      wind = wind,
      hydro = Array.tabulate(Hours)(t => result.discharging("A hydro", t)),
      gas = Array.tabulate(Hours)(t =>
        result.dispatch("Generator", "B gas", t) + result.dispatch("Generator", "C peaker", t)),
      curtailed = (0 until Hours).map(t => math.max(0.0, available(t) - wind(t))).sum,
      flows = Array.tabulate(Hours)(t => math.abs(result.dispatch("Line", "AB", t))),
      rating = in.lineRating,
    )

  // ------------------------------------------------------------------------------ drawing

  private val W = 900.0
  private val H = 250.0
  private val Pad = 40.0

  private def area(values: Array[Double], base: Array[Double], scale: Double,
                   colour: String): String =
    val Hours = values.length
    val step = (W - 2 * Pad) / (Hours - 1).toDouble
    val up = (0 until Hours).map(i =>
      s"${Pad + i * step},${H - Pad - (base(i) + values(i)) * scale}").mkString(" ")
    val down = (Hours - 1 to 0 by -1).map(i =>
      s"${Pad + i * step},${H - Pad - base(i) * scale}").mkString(" ")
    s"""<polygon points="$up $down" fill="$colour" />"""

  private def chart(s: Solution): String =
    val Hours = s.hours
    val peak  = (0 until Hours).map(i => s.wind(i) + s.hydro(i) + s.gas(i)).max
    val scale = (H - 2 * Pad) / math.max(peak, 1.0)
    val zero  = Array.fill(Hours)(0.0)
    val afterWind  = Array.tabulate(Hours)(i => s.wind(i))
    val afterHydro = Array.tabulate(Hours)(i => s.wind(i) + s.hydro(i))
    val grid = (0 to 4).map { k =>
      val y = H - Pad - k * (H - 2 * Pad) / 4.0
      s"""<line x1="$Pad" y1="$y" x2="${W - Pad}" y2="$y" stroke="#e6e6e6"/>""" +
        s"""<text x="${Pad - 6}" y="${y + 4}" text-anchor="end" font-size="11" fill="#666">""" +
        s"""${(k * peak / 4.0).round}</text>"""
    }.mkString
    val days = (0 to Hours / 24).map { d =>
      val x = Pad + d * 24 * (W - 2 * Pad) / (Hours - 1).toDouble
      s"""<text x="$x" y="${H - Pad + 16}" text-anchor="middle" font-size="11" fill="#666">""" +
        s"""d$d</text>"""
    }.mkString
    s"""<svg viewBox="0 0 $W $H" width="100%" role="img">$grid""" +
      area(s.wind, zero, scale, "#7fb2dd") +
      area(s.hydro, afterWind, scale, "#1f6fb2") +
      area(s.gas, afterHydro, scale, "#c98f5a") +
      s"""$days<text x="$Pad" y="18" font-size="12" fill="#444">MW</text></svg>"""

  private def render(schema: Schema, in: Inputs): Unit =
    try
      val s = solve(schema, in)
      document.getElementById("chart").innerHTML = chart(s)
      val loaded = (0 until s.hours).count(t => s.flows(t) > 0.999 * s.rating)
      document.getElementById("readout").innerHTML =
        s"""<b>${(s.millis).round} ms</b> &middot; ${s.status} &middot;
           |${s.columns} kolumner, ${s.rows} rader &middot;
           |<b>${s.iterations}</b> iterationer &middot;
           |kostnad <b>${(s.objective / 1000.0).round} k&euro;</b> &middot;
           |spilld vind <b>${(s.curtailed / 1000.0 * 10).round / 10.0} GWh</b> &middot;
           |AB vid taket <b>$loaded h</b>""".stripMargin
    catch
      case e: Lopf.UnsupportedNetwork =>
        document.getElementById("readout").innerHTML =
          s"""<span style="color:#b3261e">Lopf avvisade nätet: ${e.getMessage}</span>"""

  def main(args: Array[String]): Unit =
    def value(id: String): Double =
      document.getElementById(id).asInstanceOf[dom.html.Input].value.toDouble
    def current(): Inputs =
      Inputs(hours = value("hours").toInt, scrMin = value("scr"), windCapacity = value("wind"),
             hydroFloor = value("floor"), ladderWidth = value("ladder"),
             lineRating = value("line"))

    dom.fetch("schema.json").flatMap(_.text()).onComplete {
      case Success(text) =>
        val schema = Schema.fromJson(text)
        document.getElementById("schema").textContent =
          s"Schema.fromJson: ${schema.components.size} komponenttyper, " +
            s"${schema.attributeCount} attribut — parsat i webbläsaren av samma kod som på JVM:en"
        Seq("hours", "scr", "wind", "floor", "ladder", "line").foreach { id =>
          val element = document.getElementById(id).asInstanceOf[dom.html.Input]
          element.addEventListener("input", _ => {
            document.getElementById(s"$id-value").textContent = element.value
            render(schema, current())
          })
        }
        render(schema, current())
      case Failure(error) =>
        document.getElementById("schema").textContent =
          s"Schema.fromJson misslyckades: ${error.getMessage}"
    }
