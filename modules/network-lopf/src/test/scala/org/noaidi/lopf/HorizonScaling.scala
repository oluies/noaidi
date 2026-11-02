package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.immutable.ListMap
import org.noaidi.network.{Column, CsvReader, CsvWriter, Network, Schema, TimeSeries}
import org.noaidi.prima.{Pdhg, PdhgParams, SolveStatus}

/** What a long horizon costs this solver, measured rather than extrapolated.
  *
  * The question: three years of the Nordic model at two-hourly resolution is 13,152
  * snapshots, which on `nordic-today`'s 157 columns per snapshot is about 2.06 M columns.
  * Nothing in this repository has solved an LP within two decimal orders of that, and the
  * arithmetic that would reach it from what is measured does not close.
  *
  * ==Why the one published figure does not answer it==
  *
  * `NOTES.md` records `scigrid-de` at 60,552 variables: 14,848 iterations and 12.7 s at
  * 1e-4. `nordic-today` is almost exactly the same size -- 57,305 columns -- and this
  * harness measures it at '''101,824 iterations and 37.7 s''' at the same tolerance, on
  * macOS/aarch64. Seven times the iterations at the same column count.
  *
  * So the iteration count is not a function of size, and an extrapolation anchored on the
  * one instance that happened to get published is an extrapolation from the wrong curve.
  * Even on one network the exponent is the whole answer: at 36x the horizon, iterations
  * flat in size put three years at 1e-4 in the minutes, iterations linear in size put it
  * in the days. That span is what this exists to close, and it closes it by solving a
  * ladder of horizons on the network the question is actually about.
  *
  * ==What it measures==
  *
  * Size, iterations and wall clock per rung, then the log-log slope of iterations against
  * columns -- the exponent -- and what that slope predicts for `--target` snapshots.
  *
  * Each rung is solved twice, stability off and on, because the second half of the
  * question is what those rows cost. On `nordic-today` they cost little in '''size''':
  * `thermal`, `hydro_ror` and `nuclear` all carry `p_min_pu == p_max_pu` at every
  * snapshot, so [[Stability]] reads them as `fixed` and they enter the requirement rows as
  * constants rather than as columns. The only online columns are the five hydro
  * reservoirs -- 1,825 columns and 4,015 rows on the committed 365-snapshot network,
  * against 57,305 and 5,110 -- and what the family really changes is the conditioning and
  * a must-run floor on water. Measured at the base horizon that is +12.5% iterations for
  * +3.85% on the objective. Whether the first number holds as the horizon grows is the
  * question, and it is not one to answer from an armchair.
  *
  * ==What it does not measure==
  *
  *   - '''Not a forecast.''' The ladder repeats one year of NordPSA's `today` data. Three
  *     identical years are interchangeable, so the optimum has a face rather than a point
  *     and a first-order method lands in the interior of it, which can cost iterations a
  *     real three-year profile would not. `--jitter` measures that effect rather than
  *     curing it; see below.
  *   - '''Not a resolution study.''' Subdividing a snapshot into twelve carrying the same
  *     MW values is energy-exact -- `eh` carries the hours -- so it reproduces the shape of
  *     a two-hourly model, including a reservoir whose `max_hours` spans 1,293 snapshots
  *     instead of 108, but none of its within-day variation.
  *   - '''Not a comparison.''' One solver, one host, one thread. `ScalaKernels` is scalar
  *     `while` loops by design and the solve runs on one of them.
  *
  * The subdivision being energy-exact is also the self-check: with `--jitter 0` every rung
  * has to reach `copies` times the untouched network's objective, and the report says so by
  * name. If that line fails, every row below it is measuring a different network and the
  * exponent means nothing.
  *
  * ==The inertia level, and why 150 GWs==
  *
  * Read off the fleet rather than chosen for roundness. On `nordic-today` the units
  * [[Stability]] reads as fixed carry 67.1 to 110.9 GWs of system-weighted rotational
  * energy across the year, and the five reservoirs can add 146.7 GWs more if every one of
  * them is brought fully online. A requirement of 150 GWs therefore binds at every
  * snapshot -- none meets it on constants alone -- and is feasible at every snapshot, the
  * minimum attainable being 213.7 GWs. The `m_min` of 0.3 on `hydro_res` then forces
  * between 3.5 and 7.5 GW of hydro output, which is 31 to 65 TWh a year against 167 TWh of
  * inflow: binding, and not an infeasibility dressed up as a cost. The +3.85% premium
  * above is the measurement that it binds.
  *
  * `--penalty` stays at zero for that reason. A priced slack makes every requirement row
  * satisfiable, which sounds like safety and is a '''change to the thing being measured''':
  * it adds a column per row per snapshot and puts a large coefficient in an objective whose
  * other terms are tens of euros, and a first-order method's iteration count is sensitive
  * to exactly that. A hard row that cannot be met reports `PrimalInfeasible`, which is a
  * result rather than a silent re-pricing.
  *
  * ==Running it==
  *
  * Three stages, cheapest first, because the expensive one is only worth running once the
  * cheap one says what to expect:
  *
  * {{{
  * # 1. the exponent, on resolution alone: 365 -> 1,460 snapshots, six solves
  * sbt --batch '; set networkLopf / Test / javaOptions += "-Xmx8g" ; \
  *   networkLopf / Test / runMain org.noaidi.lopf.HorizonScaling --out horizon.txt'
  *
  * # 2. push it to a genuine two-hourly year, 4,380 snapshots
  * ... HorizonScaling --hours 4,2 --time-limit 7200 --out horizon-2h.txt
  *
  * # 3. the horizon itself, at and around the target
  * ... HorizonScaling --hours 2 --copies 2,3 --time-limit 21600 --out horizon-3y.txt
  *
  * # 4. what capacity expansion does to the exponent -- the one structural gap
  * #    between this ladder and a NordPSA `expand` run
  * ... HorizonScaling --expand wind_onshore,wind_offshore,solar --out horizon-exp.txt
  * }}}
  *
  * The `;` prefix is not decoration: without it sbt 2 reads the `set` and the `runMain` as
  * one expression and fails on it. The `set` also accumulates in a long-lived sbt server,
  * exactly as `build.sbt` warns about `-Werror`, so a second run with a different heap
  * leaves both on the list -- `sbt shutdown` between heap changes.
  *
  * The heap is not optional and the harness refuses to start without enough of it.
  * `LpBuilder` holds every row as a `Seq[(Int, Double)]` until `build()`, which is boxed
  * tuples at a few million rows -- its own comment anticipates "far more on a year-long
  * network" -- and an OOM forty minutes into the last rung is the one failure that wastes a
  * whole run.
  *
  * Flags, all optional:
  *
  * {{{
  * --network   nordic-today   a directory under reference/nordpsa/networks
  * --hours     24,12,6        hours per snapshot, one rung each; must divide the base's own
  * --copies    1              how many times to repeat the horizon, one rung each
  * --tolerance 1e-4           epsAbs and epsRel, as the suite gates
  * --stability both           off | on | both
  * --inertia   150.0          systemInertiaGws, when stability is on
  * --scr-min   0.0            scrMin; off by default, see below
  * --penalty   0.0            slack price on every requirement row; see above
  * --jitter    0.0            intra-day and horizon-long price shape, see below
  * --expand    (none)         carriers to make extendable; off means dispatch only
  * --capital   60000.0        invented capital cost per MW per year for those carriers
  * --expand-max 3.0           cap on the build, as a multiple of the existing fleet
  * --export    (none)         a directory to write each rung's network into, as PyPSA CSVs
  * --target    13152          the horizon the closing prediction is made for
  * --max-iter  2000000        iteration cap per solve
  * --time-limit 1800          wall-clock cap per solve, in seconds
  * --out       (none)         a file written and flushed per row
  * }}}
  *
  * `--max-iter` is two million rather than the 500,000 the suites use, because the base
  * horizon alone takes 101,824 and the cap is meant to catch a divergence rather than to
  * end a slow convergence -- that is what `--time-limit` is for, and a rung stopped by
  * either reports its status in the table and is excluded from the fit.
  *
  * `--scr-min` is off rather than at the fixtures' 1.5 because the grid-strength row is per
  * zone and its feasibility depends on where the converters are, which no arithmetic off
  * the committed files settles. Switch it on deliberately and read the status column.
  *
  * `--expand` is off for a sharper reason: with it on the ladder measures a different
  * '''kind''' of problem rather than a harder one, and the two must not be averaged into
  * one exponent. See [[expandable]] for what the three numbers behind it are, and for why
  * the objective of an expanding rung may not be compared with a dispatching one.
  *
  * `--jitter a` multiplies every `marginal_cost` '''series''' by
  * `1 + a*(sin(2*pi*i/subdivisions) + 0.5*sin(2*pi*i/total))`: an intra-day shape, which a
  * subdivided daily profile has none of, and a horizon-long one, which is what makes
  * repeated years distinguishable. It is invented data and it is named as such. Run a
  * ladder at `0` and at `0.1` and the pair brackets how much of the iteration count is
  * degeneracy rather than size. At the base resolution the intra-day term is identically
  * zero, as it should be: a daily model has no within-day structure to shape.
  *
  * ==Handing a rung to PyPSA==
  *
  * `--export <dir>` writes every rung as a PyPSA CSV directory, one subdirectory per rung,
  * through the same `CsvWriter` whose round-trip is asserted byte-for-byte against pandas.
  * That makes a genuine head-to-head possible, which none of the numbers above is: the
  * same LP, read by `pypsa.Network`, solved by HiGHS at a stated tolerance and thread
  * count. It is the only way to tell a solver difference from a problem difference, and
  * the external anchor in NOTES exists because that distinction was about to be guessed.
  *
  * What an export '''cannot''' carry is the stability requirements. They live in an
  * `extra_functionality` callback, PyPSA stores none of it on the network, and a reader
  * gets a strictly easier problem -- which is the whole argument of
  * `docs/pypsa-and-noaidi.md` and the reason `reference/nordpsa` keeps networks and
  * answers in separate files. So an exported rung compares the '''dispatch''' halves of
  * the two implementations honestly, and says nothing about the requirement rows unless
  * the Python side is handed NordPSA's own callback as well.
  *
  * Every row is printed as its rung finishes and flushed to `--out` if given, for the
  * reason `MilpReport.Sink` gives at length: this ladder runs for hours, a pipe between two
  * JVMs is block-buffered, and a run killed by a timeout must still leave behind the rungs
  * that finished. The rungs are ordered smallest first for the same reason.
  */
object HorizonScaling:

  /** `yyyy-MM-dd HH:mm:ss`, which is the format `HydroOps` parses.
    *
    * The stretched network's labels are real timestamps rather than ordinals so the
    * calendar families stay reachable on it. `nordic-today`'s own labels are bare dates,
    * `2023-01-01`, which `HydroOps.windows` already refuses by name -- so emitting
    * timestamps makes the fixture more usable rather than less.
    *
    * The label axis is a true calendar while the data axis repeats the base profile, so on
    * a multi-copy rung the two drift by a day across a leap year. Nothing in the LP reads
    * both; a calendar family would, which is the one thing to know before switching
    * `HydroOps` on over a tiled network.
    */
  private val Formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  private val Start = LocalDateTime.of(2023, 1, 1, 0, 0)

  /** `2` rather than `2.000`, and `0.5` rather than `0.5000`.
    *
    * The resolution appears in a network's name and in every rung label, and a label is
    * what a reader diffs two runs by. `%g` on a whole number of hours spells it with three
    * trailing zeros, which makes two identical ladders look like different ones.
    */
  private def hoursLabel(hours: Double): String =
    if hours == math.rint(hours) then f"$hours%.0f" else f"$hours%s"

  // ------------------------------------------------------------------ the stretched network

  /** How many hours one of this network's snapshots stands for.
    *
    * PyPSA says this with `snapshot_weightings`, and it says it three times -- `objective`,
    * `stores` and `generators` -- which are three different jobs that happen to carry the
    * same number on an ordinary network. [[stretched]] needs one number, so it requires
    * them to agree rather than picking one: a network where they differ is expressing
    * something this harness has no basis to reinterpret.
    *
    * An absent weighting is one hour, which is PyPSA's own default and what a network with
    * no `snapshots.csv` columns means.
    */
  def baseHours(network: Network): Double =
    val values = network.snapshotWeightings.map { (kind, column) =>
      val first = column(0)
      require(
        (0 until column.length).forall(t => column(t) == first),
        s"snapshot weighting '$kind' varies across network '${network.name}', so " +
          "subdividing it would have to decide what a weighted snapshot subdivides into",
      )
      kind -> first
    }
    require(
      values.values.toSet.size <= 1,
      s"network '${network.name}' weights its snapshots differently per column -- " +
        values.map((k, v) => f"$k%s $v%.4g").mkString(", ") +
        " -- so there is no single resolution to subdivide",
    )
    values.values.headOption.getOrElse(1.0)

  /** The same network over a longer horizon at a finer resolution.
    *
    * Two operations at once, because they are the same operation: snapshot `i` takes its
    * data from source snapshot `(i / subdivisions) % base.snapshotCount`, which subdivides
    * when `subdivisions > 1` and repeats when `copies > 1`.
    *
    * '''Energy-exact, not interpolated.''' Every weighting column becomes `hours`, and
    * PyPSA's formulation multiplies by `eh` wherever energy is meant -- the storage
    * balance, the inflow, the objective -- so twelve two-hour snapshots carrying one day's
    * MW values are worth exactly what that day's single 24-hour snapshot was. That is what
    * makes the objective comparable across the ladder, and the report's replication check
    * is the measurement of it rather than the claim.
    *
    * The copies '''share''' their rows: `values(i)` is a reference to the source row, not a
    * copy, so a 36x horizon costs no more memory in the network than the base does. Safe
    * because `IArray` is immutable, and worth the sentence because the obvious
    * implementation copies.
    */
  def stretched(base: Network, copies: Int, hours: Double): Network =
    require(copies >= 1, s"copies must be at least 1, got $copies")
    require(hours > 0.0, s"hours must be positive, got $hours")
    require(
      !base.isMultiPeriod,
      s"network '${base.name}' has investment periods, and stretching it would have to " +
        "decide which period each new snapshot belongs to. That is a modelling choice, " +
        "not a tiling, so it is refused rather than guessed.",
    )
    val from         = baseHours(base)
    val subdivisions = math.round(from / hours).toInt
    require(
      subdivisions >= 1 && math.abs(subdivisions * hours - from) < 1e-9,
      f"network '${base.name}%s' weighs a snapshot at $from%.4g h, which does not divide " +
        f"into whole snapshots of $hours%.4g h: $from%.4g/$hours%.4g is ${from / hours}%.6f",
    )
    val minutes = math.round(hours * 60.0)
    require(
      math.abs(minutes - hours * 60.0) < 1e-9,
      s"$hours h is not a whole number of minutes, and the snapshot labels are timestamps",
    )

    val sources = base.snapshotCount
    val total   = sources * subdivisions * copies
    def sourceOf(i: Int): Int = (i / subdivisions) % sources

    val weightings = ListMap.from(base.snapshotWeightings.view.map { (kind, _) =>
      kind -> IArray.fill(total)(hours)
    })

    val labels = IndexedSeq.tabulate(total)(i => Start.plusMinutes(i * minutes).format(Formatter))

    val tables = ListMap.from(base.tables.view.map { (name, table) =>
      name -> table.copy(series = ListMap.from(table.series.view.map { (attribute, series) =>
        attribute -> TimeSeries(
          series.entities,
          IArray.tabulate(total)(i => series.values(sourceOf(i))),
        )
      }))
    })

    base.copy(
      name = s"${base.name}-${copies}x${hoursLabel(hours)}h",
      snapshots = labels,
      tables = tables,
      snapshotWeightings = weightings,
    )

  /** A price shape over invented data, to separate degeneracy from size.
    *
    * See the class note on `--jitter`. Applied to the `marginal_cost` series only: a
    * generator whose cost is static keeps it, which is asymmetric and is the honest limit
    * of a one-line symmetry breaker -- the alternative is manufacturing a series for every
    * generator in the network, which is a different and much larger lie.
    */
  def jittered(network: Network, amount: Double, subdivisions: Int): Network =
    if amount == 0.0 then network
    else
      val total = network.snapshotCount
      def factor(i: Int): Double =
        1.0 + amount * (math.sin(2 * math.Pi * i / subdivisions) +
          0.5 * math.sin(2 * math.Pi * i / total))
      val tables = ListMap.from(network.tables.view.map { (name, table) =>
        name -> table.copy(series = ListMap.from(table.series.view.map { (attribute, series) =>
          if attribute != "marginal_cost" then attribute -> series
          else
            attribute -> TimeSeries(
              series.entities,
              IArray.tabulate(total) { i =>
                val row = series.values(i)
                val f   = factor(i)
                IArray.tabulate(row.length)(e => row(e) * f)
              },
            )
        }))
      })
      network.copy(tables = tables)

  /** The same network with a candidate set made extendable.
    *
    * This is the one structural difference between what the ladder measures and what a
    * NordPSA `expand` run solves, and it is the difference that matters most: a dispatch
    * horizon is the same sparse block repeated, coupled only through storage, which is
    * exactly the structure the sublinear iteration exponent rests on. A capacity column is
    * not that. It appears in a row at '''every''' snapshot, so at 13,152 snapshots one such
    * column has 13,152 nonzeros, and it ties the whole horizon together in a way no
    * dispatch variable does. Whether the exponent survives that is not a question the
    * dispatch ladder can answer, and it is the question that decides whether a 17-hour
    * `expand` run is the horizon's fault or the expansion's.
    *
    * '''The three numbers here are invented''' and named as such, like `--jitter`'s. The
    * point is the column structure, not a cost-optimal build:
    *
    *   - `p_nom_min = p_nom`, so the existing fleet stays and the LP decides '''additions'''.
    *     A floor of zero would let it retire the fleet instead, which is a different study.
    *   - `p_nom_max = maxFactor * p_nom`, so the build cannot wander off to a number no
    *     Nordic plan would contain. With `p_nom_max` left at the file's `inf` the LP is
    *     still bounded -- capital is positive -- but the capacity it reaches is then set by
    *     an invented capital cost alone, which is a worse-founded number than a cap is.
    *   - one `capital_cost` for every carrier named, because a cost per carrier is three
    *     more invented numbers with no better claim behind any of them.
    *
    * A multiplicative cap cannot express a '''greenfield''' candidate -- a unit with no
    * fleet, which is what `nuclear_nordic` adds on the NordPSA side -- and a carrier whose
    * fleet is 0 MW is refused rather than given a capacity column pinned at zero. So this
    * reproduces the shape of an expansion over an existing fleet and not the shape of a
    * new-build study, and the refusal says which.
    *
    * Capital is charged on the whole capacity, existing fleet included, which is what PyPSA
    * does. So the objective of an expanding rung is '''not''' comparable to a dispatching
    * one: it carries the fleet's capital as a constant the other does not. Compare
    * iterations and wall clock across the two, never the objective.
    *
    * Generic over the tables that can carry an extendable capacity -- `Generator`,
    * `StorageUnit` and `Link` all do -- rather than special-casing generators, because the
    * code is one loop either way and `stab_batt12` is a battery candidate.
    *
    * A carrier naming nothing is refused. A flag that silently matches no component is the
    * failure this whole file is written against: the run finishes, reports a number, and
    * the number answers a question nobody asked.
    */
  def expandable(
      network: Network,
      carriers: Set[String],
      capital: Double,
      maxFactor: Double,
  ): Network =
    if carriers.isEmpty then network
    else
      require(capital > 0.0, s"--capital must be positive, got $capital")
      require(maxFactor >= 1.0, s"--expand-max must be at least 1, got $maxFactor")
      val needed =
        Seq("carrier", "p_nom", "p_nom_extendable", "p_nom_min", "p_nom_max", "capital_cost")

      val eligible = network.tables.values
        .filter(table => needed.forall(a => table.spec.attributes.exists(_.name == a)))
      val fleet = eligible
        .flatMap(table => table.ids.map(id => table.string("carrier", id) -> table.float("p_nom", id)))
        .groupMapReduce((carrier, _) => carrier)((_, capacity) => capacity)(_ + _)

      // Two refusals rather than one, because they are two different mistakes and a reader
      // needs to know which. Both were found the hard way: `--expand gas` on this network
      // ran clean, reported a number and expanded nothing, because every gas unit in it
      // has `p_nom = 0` and a multiple of zero is zero. That is the inert setting the rest
      // of this port refuses by name, reappearing in the tool written to measure it.
      val missing = carriers.filterNot(fleet.contains)
      require(
        missing.isEmpty,
        s"--expand named ${missing.toSeq.sorted.mkString(", ")}, which network " +
          s"'${network.name}' has no extendable-capable component for. Its carriers are " +
          fleet.keys.toSeq.sorted.mkString(", ") + ".",
      )
      val barren = carriers.filter(c => fleet(c) <= 0.0)
      require(
        barren.isEmpty,
        s"--expand named ${barren.toSeq.sorted.mkString(", ")}, whose fleet in network " +
          s"'${network.name}' is 0 MW. The cap here is a multiple of the existing " +
          f"capacity, and $maxFactor%.1f x 0 is 0, so those units would carry a capacity " +
          "column pinned at zero: more rows, no decision, and an objective that looks " +
          "like an answer. Name a carrier that has a fleet to extend -- this flag " +
          "expands one, and a greenfield candidate needs an absolute cap that this " +
          "harness does not offer.",
      )

      val rewritten = network.tables.toSeq.map { (name, table) =>
        val canExtend = needed.forall(a => table.spec.attributes.exists(_.name == a))
        val chosen =
          if canExtend then table.ids.map(id => carriers.contains(table.string("carrier", id)))
          else table.ids.map(_ => false)
        if !chosen.contains(true) then (name, table)
        else
          def floats(attribute: String, value: String => Double): Column =
            Column.Floats(IArray.from(table.ids.indices.map { i =>
              val id = table.ids(i)
              if chosen(i) then value(id) else table.float(attribute, id)
            }))
          val static = table.static
            .updated("p_nom_extendable", Column.Bools(IArray.from(table.ids.indices.map { i =>
              chosen(i) || table.bool("p_nom_extendable", table.ids(i))
            })))
            .updated("p_nom_min", floats("p_nom_min", id => table.float("p_nom", id)))
            .updated("p_nom_max", floats("p_nom_max", id => maxFactor * table.float("p_nom", id)))
            .updated("capital_cost", floats("capital_cost", _ => capital))
          (name, table.copy(static = static))
      }

      network.copy(tables = ListMap.from(rewritten.map((name, table) => name -> table)))

  // ----------------------------------------------------------------------------- the report

  /** Writes a line to stdout and, when asked, to a file it flushes.
    *
    * The same device as `MilpReport.Sink` and for the same reason, which that file states
    * at length: a run this long gets killed, `tee` through a pipe between two JVMs is
    * block-buffered, and a report that exists only in a buffer is a report nobody reads.
    * Not shared with it because the two live in different modules, and a test-jar
    * dependency between them costs more than eight lines.
    */
  private final class Sink(path: Option[Path]) extends AutoCloseable:
    private val writer = path.map(Files.newBufferedWriter(_))

    def line(text: String): Unit =
      println(text)
      writer.foreach { w =>
        w.write(text)
        w.newLine()
        w.flush()
      }

    def close(): Unit = writer.foreach(_.close())

  /** One rung of the ladder, before it is solved. */
  private final case class Rung(copies: Int, hours: Double, snapshots: Int):
    def label: String = s"${copies}x-${hoursLabel(hours)}h"

  private final case class Row(
      rung: String,
      stability: Boolean,
      snapshots: Int,
      columns: Int,
      constraints: Int,
      nnz: Int,
      online: Int,
      status: SolveStatus,
      objective: Double,
      relativeGap: Double,
      iterations: Int,
      restarts: Int,
      millis: Long,
      slackUsed: Double,
  ):
    def optimal: Boolean           = status == SolveStatus.Optimal
    def millisPerIteration: Double = if iterations > 0 then millis.toDouble / iterations else 0.0

  /** The table header.
    *
    * `^horizon ` is the leading token, so a job summary can find the table the way CI finds
    * `MilpReport`'s by `^instance `.
    */
  private val header =
    f"${"horizon"}%-14s ${"stab"}%4s ${"snaps"}%7s ${"columns"}%10s ${"rows"}%10s " +
      f"${"nnz"}%11s ${"online"}%7s ${"status"}%-16s ${"objective"}%18s ${"rel gap"}%9s " +
      f"${"iters"}%9s ${"restarts"}%8s ${"seconds"}%9s ${"ms/iter"}%8s ${"slack"}%10s"

  private def render(r: Row): String =
    val objective =
      if r.status == SolveStatus.PrimalInfeasible then f"${"-"}%18s" else f"${r.objective}%18.2f"
    f"${r.rung}%-14s ${if r.stability then "on" else "off"}%4s ${r.snapshots}%7d " +
      f"${r.columns}%10d ${r.constraints}%10d ${r.nnz}%11d ${r.online}%7d " +
      f"${r.status}%-16s $objective ${r.relativeGap}%9.2e ${r.iterations}%9d " +
      f"${r.restarts}%8d ${r.millis / 1000.0}%9.1f ${r.millisPerIteration}%8.2f " +
      f"${r.slackUsed}%10.1f"

  /** The least-squares slope and intercept of `log y` against `log x`.
    *
    * `None` under two points, and `None` when every point shares one `x`: a slope through
    * one column count is not a measurement. The caller prints the point count beside the
    * slope for the same reason -- three points through a curve is a slope with no error
    * bar, and saying so is cheaper than implying otherwise.
    */
  private def logFit(points: Seq[(Double, Double)]): Option[(Double, Double)] =
    if points.length < 2 then None
    else
      val xs  = points.map((x, _) => math.log(x))
      val ys  = points.map((_, y) => math.log(y))
      val mx  = xs.sum / xs.length
      val my  = ys.sum / ys.length
      val sxx = xs.map(x => (x - mx) * (x - mx)).sum
      if sxx == 0.0 then None
      else
        val sxy   = xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum
        val slope = sxy / sxx
        Some((slope, my - slope * mx))

  private def predict(fit: (Double, Double), x: Double): Double =
    math.exp(fit._2) * math.pow(x, fit._1)

  private def duration(millis: Double): String =
    if millis < 90_000 then f"${millis / 1000.0}%.0f s"
    else if millis < 5_400_000 then f"${millis / 60_000.0}%.1f min"
    else if millis < 172_800_000 then f"${millis / 3_600_000.0}%.1f h"
    else f"${millis / 86_400_000.0}%.1f days"

  // ------------------------------------------------------------------------------- the main

  private val flags = Set("network", "hours", "copies", "tolerance", "stability", "inertia",
    "scr-min", "penalty", "jitter", "expand", "capital", "expand-max", "target", "max-iter",
    "time-limit", "out", "export")

  private def parse(args: Array[String]): Map[String, String] =
    require(args.length % 2 == 0, s"expected --flag value pairs, got ${args.length} arguments")
    val pairs = args.grouped(2).map {
      case Array(flag, value) =>
        require(flag.startsWith("--"), s"expected a --flag, got '$flag'")
        val name = flag.drop(2)
        require(
          flags.contains(name),
          s"unknown flag '$flag'; known: ${flags.toSeq.sorted.mkString(", ")}",
        )
        name -> value
      case other => throw new IllegalArgumentException(other.mkString(" "))
    }.toList
    require(pairs.map(_._1).distinct.length == pairs.length, "a flag was passed twice")
    pairs.toMap

  def main(args: Array[String]): Unit =
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    val options = parse(args)
    def str(name: String, fallback: String): String = options.getOrElse(name, fallback)
    def num(name: String, fallback: Double): Double = str(name, fallback.toString).toDouble
    def int(name: String, fallback: Int): Int       = str(name, fallback.toString).toInt

    val which = str("stability", "both")
    require(Set("off", "on", "both").contains(which), s"--stability is off|on|both, got '$which'")

    val sink = new Sink(options.get("out").filter(_.nonEmpty).map(Path.of(_)))
    try
      run(
        sink = sink,
        networkName = str("network", "nordic-today"),
        hours = str("hours", "24,12,6").split(",").map(_.trim.toDouble).toIndexedSeq,
        copies = str("copies", "1").split(",").map(_.trim.toInt).toIndexedSeq,
        tolerance = num("tolerance", 1e-4),
        which = which,
        inertia = num("inertia", 150.0),
        scrMin = num("scr-min", 0.0),
        penalty = num("penalty", 0.0),
        jitter = num("jitter", 0.0),
        expand = str("expand", "").split(",").map(_.trim).filter(_.nonEmpty).toSet,
        capital = num("capital", 60000.0),
        expandMax = num("expand-max", 3.0),
        exportTo = options.get("export").filter(_.nonEmpty).map(Path.of(_)),
        target = int("target", 13152),
        maxIter = int("max-iter", 2_000_000),
        timeLimit = num("time-limit", 1800.0),
      )
    finally sink.close()

  private def run(
      sink: Sink,
      networkName: String,
      hours: IndexedSeq[Double],
      copies: IndexedSeq[Int],
      tolerance: Double,
      which: String,
      inertia: Double,
      scrMin: Double,
      penalty: Double,
      jitter: Double,
      expand: Set[String],
      capital: Double,
      expandMax: Double,
      exportTo: Option[Path],
      target: Int,
      maxIter: Int,
      timeLimit: Double,
  ): Unit =
    val p = (k: String) => sys.props.getOrElse(k, "unknown")
    sink.line(
      s"host: ${p("os.name")} ${p("os.arch")}, JVM ${p("java.vm.name")} ${p("java.vm.version")}"
    )
    sink.line(
      f"heap: ${Runtime.getRuntime.maxMemory / 1073741824.0}%.1f GiB, " +
        f"cores ${Runtime.getRuntime.availableProcessors}%d (the solve is single threaded)"
    )

    val goldens = Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))
    val nordpsa = Paths.get(sys.env.getOrElse("NOAIDI_NORDPSA", "reference/nordpsa"))
    val schema  = Schema.fromFile(goldens.resolve("schema.json"))
    val base =
      CsvReader.read(nordpsa.resolve("networks").resolve(networkName), schema, networkName)
    val resolution = baseHours(base)

    // The ladder, smallest first: a run that gets killed has banked the cheap rungs, and a
    // rung that repeats the base network outright is dropped rather than solved twice.
    val rungs = (for
      h <- hours
      c <- copies
      subdivisions = math.round(resolution / h).toInt
      if !(c == 1 && subdivisions == 1)
    yield Rung(c, h, base.snapshotCount * subdivisions * c)).sortBy(_.snapshots)

    // Before anything is read, let alone solved. The last rung is where the heap bites and
    // it is an hour in, which is the one place a check like this pays for itself. The
    // coefficient is calibrated on the measured base network -- 365 snapshots inside 4 GiB
    // with room over -- and is deliberately generous, since the cost of being wrong in this
    // direction is a refusal and in the other direction is a wasted afternoon.
    val widest = if rungs.isEmpty then base.snapshotCount else rungs.map(_.snapshots).max
    val needed = 2.0 + widest / 1500.0
    require(
      Runtime.getRuntime.maxMemory / 1073741824.0 >= needed,
      f"this ladder reaches $widest%d snapshots, which needs about $needed%.0f GiB: " +
        "`LpBuilder` holds every row as boxed tuples until `build()`. Raise the heap -- " +
        """sbt --batch '; set networkLopf / Test / javaOptions += "-Xmx12g" ; ...' -- """ +
        "rather than discovering this on the last rung.",
    )

    val reference = ujson.read(Files.readString(nordpsa.resolve("stability.json")))
    val config = StabilityReference.zoneData(reference).copy(
      systemInertiaGws = inertia,
      scrMin = scrMin,
      slackPenalty = penalty,
      scrSlackPenalty = penalty,
    )

    val settings = which match
      case "off" => IndexedSeq(false)
      case "on"  => IndexedSeq(true)
      case _     => IndexedSeq(false, true)

    sink.line(f"network: $networkName%s, ${base.snapshotCount}%d snapshots at " +
      f"${hoursLabel(resolution)}%s h as committed")
    // Named rather than counted, and with the empty case spelled out: a ladder whose only
    // rung repeats the base is a legitimate thing to ask for -- it is how the base is
    // measured on its own -- and it printed as "ladder:  ( snapshots)" until this said so.
    sink.line(
      if rungs.isEmpty then
        f"ladder: the base network alone, at tolerance $tolerance%.1e, capped at " +
          f"$maxIter%d iterations or ${timeLimit}%.0f s. Every rung asked for repeats it."
      else
        s"ladder: ${rungs.map(_.label).mkString(" ")} " +
          s"(${rungs.map(_.snapshots).mkString(", ")} snapshots) " +
          f"at tolerance $tolerance%.1e, capped at $maxIter%d iterations or ${timeLimit}%.0f s"
    )
    sink.line(f"stability: $which%s, system inertia $inertia%.1f GWs, scrMin $scrMin%.2f, " +
      f"slack price $penalty%.1f, jitter $jitter%.3f")
    exportTo.foreach(root =>
      sink.line(s"export: each rung's network as PyPSA CSVs under $root"))
    sink.line(
      if expand.isEmpty then "expansion: off -- dispatch only, no capacity column"
      else f"expansion: ${expand.toSeq.sorted.mkString(",")}%s at $capital%.0f per MW per " +
        f"year, capped at $expandMax%.1fx the existing fleet (invented numbers)"
    )
    sink.line("")
    sink.line(header)

    val params = PdhgParams(
      epsAbs = tolerance,
      epsRel = tolerance,
      maxIterations = maxIter,
      timeLimitMillis = Some(math.round(timeLimit * 1000.0)),
    )

    /** Networks already written out, so `--export` writes each one once. */
    val exported = scala.collection.mutable.Set.empty[String]

    // Built, solved and printed one at a time, never collected first: the row of a rung that
    // finished has to survive the kill that stops the next one.
    def measure(label: String, network: Network, stability: Boolean): Row =
      // Before the solve, and once per network rather than once per setting: an export is
      // worth having when the solve is what got killed, and the two settings differ in the
      // callback rather than in the network -- which is exactly what an export cannot
      // carry, so the second write would write the same bytes again. Keyed on the name
      // rather than on `!stability`, which would export nothing at all under
      // `--stability on`.
      exportTo.filter(_ => exported.add(network.name)).foreach { root =>
        val into = root.resolve(network.name)
        Files.createDirectories(into)
        CsvWriter.write(network, into)
        System.err.println(s"exported ${network.name} to $into")
      }
      System.err.println(s"solving $label, stability ${if stability then "on" else "off"}")
      val families = if stability then Lopf.Families(stability = config) else Lopf.Families.none
      val model    = Lopf.build(network, families)
      val problem  = model.problem
      val started  = System.currentTimeMillis()
      val solution = Pdhg.solve(problem, params)
      val millis   = System.currentTimeMillis() - started
      val row = Row(
        rung = label,
        stability = stability,
        snapshots = network.snapshotCount,
        columns = problem.numVariables,
        constraints = problem.numConstraints,
        nnz = problem.constraintMatrix.nnz,
        online = model.map.columns.count((key, _) => key._1 == Stability.Online),
        status = solution.status,
        objective = solution.objectiveValue,
        relativeGap =
          solution.kkt.absoluteGap / math.max(1.0, math.abs(solution.objectiveValue)),
        iterations = solution.iterations,
        restarts = solution.restarts,
        millis = millis,
        slackUsed = model.map.columns.view
          .filter((key, _) => key._1 == Stability.Slack)
          .map((_, column) => solution.primal(column))
          .sum,
      )
      sink.line(render(row))
      row

    // The committed network first, untouched: the cheapest rung, the anchor the published
    // objective is quoted against, and what the subdivision check compares to.
    val anchored = expandable(base, expand, capital, expandMax)
    val anchors  = settings.map(s => s -> measure("base", anchored, s)).toMap
    val solved  = rungs.flatMap(r =>
      val network = expandable(
        jittered(
          stretched(base, r.copies, r.hours),
          jitter,
          math.round(resolution / r.hours).toInt,
        ),
        expand,
        capital,
        expandMax,
      )
      settings.map(s => (r, s, measure(r.label, network, s)))
    )

    sink.line("")

    // The self-check, and the reason it is a line in the report rather than an assertion:
    // every number below depends on it, so a reader has to see its verdict even on a run
    // that is otherwise clean.
    if jitter != 0.0 then
      sink.line("replication check: skipped -- a jittered ladder is a different LP by " +
        "construction, so the base objective is not the one it should reach")
    else if expand.nonEmpty then
      sink.line("replication check: skipped -- with capacity a decision the capital term " +
        "is charged per horizon rather than per snapshot, so a longer rung is not " +
        "required to reach a multiple of the base objective")
    else
      if solved.isEmpty then
        sink.line("replication check: skipped -- the ladder has no rung to compare")
      else
        solved.foreach { (r, s, row) =>
          // Every rung should reach `copies` times the base objective. Subdividing is
          // energy-exact, and repeating the horizon `c` times over the same data multiplies
          // it: replicating the base's own schedule `c` times is feasible for the longer
          // problem, so its optimum cannot be dearer.
          //
          // Checked both ways round regardless, because that argument is about the two
          // *exact* optima and neither number here is one. Both sides stop at `tolerance`,
          // so each carries its own error of that order and the difference can fall either
          // side of zero -- at 1e-4 on three copies it came out 1.0e-4 dear, which is the
          // tolerance and not an arbitrage. A gap well past the band is the tiling, not
          // the solver: the one thing that would produce it is a rung whose data or
          // weightings do not repeat the base's.
          val anchor   = anchors(s)
          val expected = r.copies * anchor.objective
          val relative = math.abs(row.objective - expected) / math.max(1.0, math.abs(expected))
          val verdict =
            if !row.optimal || !anchor.optimal then "INCONCLUSIVE -- one side is not Optimal"
            else if relative <= 10.0 * tolerance then "OK"
            else "FAIL -- the stretched network is not the same problem"
          sink.line(f"replication check ${r.label}%s, stability " +
            f"${if s then "on" else "off"}%-3s: ${r.copies}%d x ${anchor.objective}%.2f = " +
            f"$expected%.2f vs ${row.objective}%.2f, relative $relative%.2e -- $verdict%s")
        }

    // What the requirements cost, per rung, where both halves solved. The one figure here
    // that is about the model rather than about the solver.
    if settings.length == 2 then
      sink.line("")
      (("base", anchors(false), anchors(true)) +: rungs.flatMap { r =>
        for
          off <- solved.find((rr, s, _) => rr == r && !s).map(_._3)
          on  <- solved.find((rr, s, _) => rr == r && s).map(_._3)
        yield (r.label, off, on)
      }).foreach { (label, off, on) =>
        if off.optimal && on.optimal then
          val premium = (on.objective - off.objective) / math.max(1.0, math.abs(off.objective))
          sink.line(f"$label%-14s stability costs ${on.objective - off.objective}%+.0f " +
            f"(${100.0 * premium}%+.2f%%) and ${on.iterations - off.iterations}%+d iterations " +
            f"(${100.0 * (on.iterations - off.iterations) / math.max(1, off.iterations)}%+.1f%%)")
        else
          sink.line(f"$label%-14s no premium -- one half of the pair did not reach Optimal")
      }

    // The exponent, which is what the whole ladder is for.
    sink.line("")
    settings.foreach { s =>
      val usable = (anchors(s) +: solved.filter((_, st, _) => st == s).map(_._3))
        .filter(r => r.optimal && r.iterations > 0)
      val label = if s then "stability on " else "stability off"
      if usable.length < 2 then
        sink.line(s"$label: fewer than two Optimal rungs, so no exponent. " +
          "Raise --time-limit or --max-iter, or loosen --tolerance.")
      else
        val perSnapshot = usable.head.columns.toDouble / usable.head.snapshots
        val iterFit     = logFit(usable.map(r => r.columns.toDouble -> r.iterations.toDouble))
        val costFit =
          logFit(usable.map(r => r.columns.toDouble -> math.max(r.millisPerIteration, 1e-6)))
        (iterFit, costFit) match
          case (Some(iter), Some(cost)) =>
            val columns      = perSnapshot * target
            val iterations   = predict(iter, columns)
            val perIteration = predict(cost, columns)
            sink.line(f"$label%s: iterations ~ columns^${iter._1}%.2f, ms/iteration ~ " +
              f"columns^${cost._1}%.2f, over ${usable.length}%d Optimal rungs " +
              f"(${usable.map(_.columns).min}%d to ${usable.map(_.columns).max}%d columns)")
            sink.line(f"$label%s: $target%d snapshots is about $columns%.0f columns -> " +
              f"$iterations%.3g iterations at $perIteration%.1f ms each = " +
              s"${duration(iterations * perIteration)}")
          case _ =>
            sink.line(s"$label: the rungs do not span two distinct column counts, so no fit")
    }

    sink.line("")
    val spent = (anchors.values.map(_.millis).sum + solved.map(_._3.millis).sum) / 1000.0
    sink.line(f"ladder cost: $spent%.0f s of solving across " +
      f"${anchors.size + solved.length}%d solves. The prediction above is a two-parameter " +
      "fit through those points and nothing more -- read the exponent, not the digits.")
