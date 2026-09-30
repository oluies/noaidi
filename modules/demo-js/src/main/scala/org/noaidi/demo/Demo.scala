package org.noaidi.demo

import org.scalajs.dom
import org.scalajs.dom.document
import org.noaidi.prima.{LpProblem, Pdhg, PdhgParams, SolveStatus}
import org.noaidi.network.Schema

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

/** Prima and the network model, running in a browser.
  *
  * The point of the demo is the one question a Scala.js port has to answer, which is not
  * "does it compile" but "is it fast enough to put behind a slider". So it solves the
  * formulation this repository's last constraint family ported — a short-circuit-ratio floor
  * over a linearised commitment — and reports how long the solve took.
  *
  * {{{
  * balance      pw(t) + ph(t) + pg(t)            = load(t)
  * online       p(t) − u(t)                     <= 0
  * min stable   p(t) − m_min · u(t)             >= 0
  * grid strength s_h·u_h(t) + s_g·u_g(t) − scr·pw(t) >= 0
  * }}}
  *
  * Every row is the one `Stability.scala` emits, at one bus instead of six. Moving the SCR
  * slider does what it does in the real model: the cheap synchronous plant is brought online
  * and made to produce, and where that is not enough the wind is curtailed.
  *
  * The network-model half is smaller but it is the half that would have been a surprise:
  * `Schema.fromJson` parses PyPSA's component schema here exactly as it does on the JVM.
  * `Schema.fromFile` and `CsvReader.read` are the only `java.nio.file` in that module and
  * the linker drops them as unreachable, which is the right answer for a browser anyway.
  */
object Demo:

  private val Hours = 168

  /** One zone, one week, three sources -- the smallest thing that still has the shape. */
  private final case class Inputs(
      scrMin: Double,
      windCapacity: Double,
      hydroMinStable: Double,
      gasCost: Double,
  )

  // Wind that actually goes somewhere over a week, and a load with a daily shape. Fixed
  // rather than random, so moving a slider changes one thing.
  private def windProfile(t: Int): Double =
    val daily  = math.sin(t * 2 * math.Pi / 24.0) * 0.18
    val spell  = math.sin(t * 2 * math.Pi / 71.0) * 0.42
    math.max(0.0, 0.45 + daily + spell)

  private def load(t: Int): Double =
    900.0 + 260.0 * math.sin((t - 7) * 2 * math.Pi / 24.0)

  private val HydroCapacity = 900.0
  private val GasCapacity   = 700.0
  private val HydroCost     = 12.0
  private val HydroStiffness = 3.27      // s_coef for hydro_res in NordPSA's zones.yaml
  private val GasStiffness   = 4.20      // s_coef for gas
  private val GasMinStable   = 0.40

  private final case class Solution(
      objective: Double,
      status: SolveStatus,
      millis: Double,
      columns: Int,
      rows: Int,
      wind: Array[Double],
      hydro: Array[Double],
      gas: Array[Double],
      curtailed: Double,
  )

  private def solve(in: Inputs): Solution =
    val builder = LpProblem.builder(0)

    // Columns first, so every row below can name them. `addVariable` returns the index.
    val pw = Array.fill(Hours)(0)
    val ph = Array.fill(Hours)(0)
    val pg = Array.fill(Hours)(0)
    val uh = Array.fill(Hours)(0)
    val ug = Array.fill(Hours)(0)
    var t = 0
    while t < Hours do
      pw(t) = builder.addVariable(0.0, in.windCapacity * windProfile(t), 0.0)
      ph(t) = builder.addVariable(0.0, HydroCapacity, HydroCost)
      pg(t) = builder.addVariable(0.0, GasCapacity, in.gasCost)
      uh(t) = builder.addVariable(0.0, HydroCapacity, 0.0)
      ug(t) = builder.addVariable(0.0, GasCapacity, 0.0)
      t += 1

    // Equalities before inequalities, which is the ordering the whole repository turns on.
    t = 0
    while t < Hours do
      builder.equalityConstraint(Seq(pw(t) -> 1.0, ph(t) -> 1.0, pg(t) -> 1.0), load(t))
      t += 1
    t = 0
    while t < Hours do
      builder.lessThan(Seq(ph(t) -> 1.0, uh(t) -> -1.0), 0.0)
      builder.greaterThan(Seq(ph(t) -> 1.0, uh(t) -> -in.hydroMinStable), 0.0)
      builder.lessThan(Seq(pg(t) -> 1.0, ug(t) -> -1.0), 0.0)
      builder.greaterThan(Seq(pg(t) -> 1.0, ug(t) -> -GasMinStable), 0.0)
      if in.scrMin > 0.0 then
        builder.greaterThan(
          Seq(uh(t) -> HydroStiffness, ug(t) -> GasStiffness, pw(t) -> -in.scrMin), 0.0)
      t += 1

    val (problem, _) = builder.build()
    val started = dom.window.performance.now()
    val result  = Pdhg.Solver(PdhgParams(epsAbs = 1e-8, epsRel = 1e-8, maxIterations = 200_000))
      .solve(problem)
    val elapsed = dom.window.performance.now() - started

    val windOut = Array.tabulate(Hours)(i => result.primal(pw(i)))
    val available = Array.tabulate(Hours)(i => in.windCapacity * windProfile(i))
    Solution(
      objective = result.objectiveValue,
      status = result.status,
      millis = elapsed,
      columns = problem.numVariables,
      rows = problem.numConstraints,
      wind = windOut,
      hydro = Array.tabulate(Hours)(i => result.primal(ph(i))),
      gas = Array.tabulate(Hours)(i => result.primal(pg(i))),
      curtailed = (0 until Hours).map(i => available(i) - windOut(i)).sum,
    )

  // ------------------------------------------------------------------------------ drawing

  private val W = 900.0
  private val H = 260.0
  private val Pad = 36.0

  private def area(values: Array[Double], base: Array[Double], scale: Double,
                   colour: String): String =
    val step = (W - 2 * Pad) / (Hours - 1).toDouble
    val up = (0 until Hours).map { i =>
      s"${Pad + i * step},${H - Pad - (base(i) + values(i)) * scale}"
    }.mkString(" ")
    val down = (Hours - 1 to 0 by -1).map { i =>
      s"${Pad + i * step},${H - Pad - base(i) * scale}"
    }.mkString(" ")
    s"""<polygon points="$up $down" fill="$colour" />"""

  private def chart(s: Solution): String =
    val peak  = (0 until Hours).map(i => s.wind(i) + s.hydro(i) + s.gas(i)).max
    val scale = (H - 2 * Pad) / math.max(peak, 1.0)
    val zero  = Array.fill(Hours)(0.0)
    val afterWind = Array.tabulate(Hours)(i => s.wind(i))
    val afterHydro = Array.tabulate(Hours)(i => s.wind(i) + s.hydro(i))
    val grid = (0 to 4).map { k =>
      val y = H - Pad - k * (H - 2 * Pad) / 4.0
      val v = k * peak / 4.0
      s"""<line x1="$Pad" y1="$y" x2="${W - Pad}" y2="$y" stroke="#e6e6e6"/>""" +
        s"""<text x="${Pad - 6}" y="${y + 4}" text-anchor="end" font-size="11"
           |fill="#666">${v.round}</text>""".stripMargin
    }.mkString
    val days = (0 to 7).map { d =>
      val x = Pad + d * 24 * (W - 2 * Pad) / (Hours - 1).toDouble
      s"""<text x="$x" y="${H - Pad + 16}" text-anchor="middle" font-size="11"
         |fill="#666">d$d</text>""".stripMargin
    }.mkString
    s"""<svg viewBox="0 0 $W $H" width="100%" role="img">
       |$grid
       |${area(s.wind, zero, scale, "#7fb2dd")}
       |${area(s.hydro, afterWind, scale, "#1f6fb2")}
       |${area(s.gas, afterHydro, scale, "#c98f5a")}
       |$days
       |<text x="$Pad" y="18" font-size="12" fill="#444">MW</text>
       |</svg>""".stripMargin

  private def render(in: Inputs): Unit =
    val s = solve(in)
    document.getElementById("chart").innerHTML = chart(s)
    val status = if s.status == SolveStatus.Optimal then "optimal" else s.status.toString
    document.getElementById("readout").innerHTML =
      s"""<b>${(s.millis * 10).round / 10.0} ms</b> &middot; $status &middot;
         |${s.columns} kolumner, ${s.rows} rader &middot;
         |kostnad <b>${(s.objective / 1000.0).round} k&euro;</b> &middot;
         |spilld vind <b>${(s.curtailed / 1000.0 * 10).round / 10.0} GWh</b>""".stripMargin

  private def slider(id: String, read: dom.html.Input => Double)(using
      get: () => Inputs): Unit =
    val element = document.getElementById(id).asInstanceOf[dom.html.Input]
    element.addEventListener("input", _ => {
      document.getElementById(s"$id-value").textContent = element.value
      render(get())
    })

  def main(args: Array[String]): Unit =
    val ids = Seq("scr", "wind", "mmin", "gas")
    def value(id: String): Double =
      document.getElementById(id).asInstanceOf[dom.html.Input].value.toDouble
    def current(): Inputs =
      Inputs(scrMin = value("scr"), windCapacity = value("wind"),
             hydroMinStable = value("mmin"), gasCost = value("gas"))
    given (() => Inputs) = current
    ids.foreach(id => slider(id, _.value.toDouble))

    // The network-model half. `Schema.fromJson` is the same parser the JVM build uses; the
    // schema is fetched rather than read from a path, which is the only difference a browser
    // forces -- and the reason `Schema.fromFile` linking out as unreachable is the right
    // outcome rather than a gap.
    //
    // Written through `Thenable.Implicits` rather than by chaining `then` by hand, which is
    // how the first version did it and why the panel silently stayed on its placeholder: the
    // casts needed to make the chain typecheck also swallowed the failure.
    dom.fetch("schema.json").flatMap(_.text()).onComplete {
      case Success(text) =>
        val schema = Schema.fromJson(text)
        document.getElementById("schema").textContent =
          s"Schema.fromJson: ${schema.components.size} komponenttyper, " +
            s"${schema.attributeCount} attribut — parsat i webbläsaren av samma kod som på JVM:en"
      case Failure(error) =>
        document.getElementById("schema").textContent =
          s"Schema.fromJson misslyckades: ${error.getMessage}"
    }

    render(current())
