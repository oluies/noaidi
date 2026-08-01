package org.noaidi.network

import scala.collection.immutable.ListMap

/** Impedance that exists nowhere in a network's files, only in a type name.
  *
  * A `Line` or `Transformer` may set `type` instead of `r`, `x`, `b` and `g`,
  * naming a row in PyPSA's standard type library. That is not an edge case:
  * every one of `scigrid-de`'s 852 lines does it, carrying `x = 0` and
  * `type = "Al/St 240/40 4-bundle 380.0"`, and the impedance follows from the
  * type's per-length values scaled by `length` and `num_parallel`.
  *
  * ==Why the library ships with this module==
  *
  * `export_to_csv_folder` writes no type library at all. It drops exactly the
  * rows a fresh `Network()` was born with — `export_standard_types` defaults to
  * false — on the correct grounds that they are library data rather than network
  * data, and PyPSA's own reader repopulates them from the installed package. A
  * reader outside Python has nothing to repopulate from, so this module carries
  * its own copy of the pinned library as a resource. `StandardTypesSuite` holds
  * that copy to the goldens, so a PyPSA bump that changes an impedance fails a
  * comparison rather than silently changing every answer on a typed network.
  *
  * A network that carries its own `line_types.csv` — which is what PyPSA exports
  * when a user has added a type of their own — overrides the library row by row.
  *
  * ==When to apply it==
  *
  * PyPSA expands types inside `calculate_dependent_values`, at the start of
  * every power flow and every optimisation, not at import. This follows that:
  * [[expand]] is called by the physics entry points, and readers leave the files
  * as they found them. The distinction is not cosmetic — expanding at read time
  * would put computed columns into a round-tripped export that PyPSA's own
  * export does not have.
  *
  * The result is therefore for solving, not for writing back.
  */
object StandardTypes:

  /** A `type` naming a row that is in neither the network nor the library. */
  final class UnknownType(message: String) extends RuntimeException(message)

  /** The network with every typed branch's impedance filled in.
    *
    * Returns the network unchanged — without touching the classpath — when no
    * line or transformer names a type, which is every fixture but one.
    *
    * Values already present are '''overridden''', matching PyPSA, which warns
    * and overrides. Setting `type` is how a user asks for the library's values;
    * having a stale `x` silently win would be the surprising reading.
    */
  def expand(network: Network): Network =
    val lines        = typed(network, "Line")
    val transformers = typed(network, "Transformer")
    if lines.isEmpty && transformers.isEmpty then network
    else
      val afterLines = lines.map(t => network.withTable(expandLines(network, t))).getOrElse(network)
      transformers
        .map(t => afterLines.withTable(expandTransformers(afterLines, t)))
        .getOrElse(afterLines)

  /** The table for `component`, if it has any entity naming a type. */
  private def typed(network: Network, component: String): Option[ComponentTable] =
    network.table(component).filter { table =>
      table.static.get("type") match
        case Some(Column.Strings(values)) => values.exists(_.trim.nonEmpty)
        case _                            => false
    }

  // -- Lines ---------------------------------------------------------------

  /** `r` and `x` per length divided by the parallel count, `b` multiplied by it.
    *
    * PyPSA's arithmetic exactly:
    * {{{
    * r = r_per_length · length / num_parallel
    * x = x_per_length · length / num_parallel
    * b = 2π · 1e-9 · f_nom · c_per_length · length · num_parallel
    * }}}
    *
    * The asymmetry is the point. Circuits in parallel divide the series
    * impedance and multiply the shunt admittance, and at the default
    * `num_parallel = 1` — which every other fixture uses — getting it backwards
    * is invisible.
    */
  private def expandLines(network: Network, table: ComponentTable): ComponentTable =
    val library = lineLibrary(network)
    val derived = Array.fill(3)(new Array[Double](table.size))
    table.ids.zipWithIndex.foreach { (id, row) =>
      val name = table.string("type", id).trim
      if name.nonEmpty then
        val t            = library.getOrElse(name, throw unknown("Line", id, name, library.keySet))
        val length       = table.float("length", id)
        val numParallel  = table.float("num_parallel", id)
        if !(numParallel > 0.0) then
          throw new UnknownType(
            s"Line '$id' has num_parallel = $numParallel, which its type's impedance is divided by"
          )
        derived(0)(row) = t.rPerLength * length / numParallel
        derived(1)(row) = t.xPerLength * length / numParallel
        derived(2)(row) = 2.0 * math.Pi * 1e-9 * t.fNom * t.cPerLength * length * numParallel
      else
        derived(0)(row) = table.float("r", id)
        derived(1)(row) = table.float("x", id)
        derived(2)(row) = table.float("b", id)
    }
    write(table, Seq("r" -> derived(0), "x" -> derived(1), "b" -> derived(2)))

  // -- Transformers --------------------------------------------------------

  /** The short-circuit and no-load percentages a transformer type is specified by.
    *
    * {{{
    * r = vscr / 100                       g = pfe / (1000 · s_nom)
    * x = sqrt((vsc/100)² − r²)            b = −sqrt((i0/100)² − g²)
    * }}}
    *
    * then `r` and `x` divided by `num_parallel` and `b` and `g` multiplied by it,
    * as for a line. `r` and `g` above are the '''undivided''' values: PyPSA forms
    * `x` and `b` from them before scaling, and reusing the scaled ones would be
    * wrong by `num_parallel²` inside the square roots.
    *
    * The clamp under the `b` root is PyPSA's, kept with its reason: several of
    * the pandapower-derived types have `i0² < g²`, which is not physical and
    * would otherwise be a NaN threaded through the admittance matrix.
    *
    * `s_nom` comes from the type and is '''not''' scaled — a network with two
    * 160 MVA units in parallel is rated 160 MVA by PyPSA, which is surprising
    * but is what it does, so it is what this does.
    */
  private def expandTransformers(network: Network, table: ComponentTable): ComponentTable =
    val library = transformerLibrary(network)
    val keys    = Seq("r", "x", "g", "b", "s_nom", "tap_ratio", "phase_shift")
    val derived = keys.map(_ => new Array[Double](table.size))
    val sides   = new Array[Int](table.size)

    table.ids.zipWithIndex.foreach { (id, row) =>
      val name = table.string("type", id).trim
      if name.nonEmpty then
        val t = library.getOrElse(name, throw unknown("Transformer", id, name, library.keySet))
        val numParallel = table.float("num_parallel", id)
        if !(numParallel > 0.0) then
          throw new UnknownType(
            s"Transformer '$id' has num_parallel = $numParallel, which its type's impedance is " +
              "divided by"
          )
        if !(t.sNom > 0.0) then
          throw new UnknownType(s"transformer type '$name' has s_nom = ${t.sNom}")

        val r = t.vscr / 100.0
        val x = math.sqrt(math.max(0.0, (t.vsc / 100.0) * (t.vsc / 100.0) - r * r))
        val g = t.pfe / (1000.0 * t.sNom)
        val b = -math.sqrt(math.max(0.0, (t.i0 / 100.0) * (t.i0 / 100.0) - g * g))

        derived(0)(row) = r / numParallel
        derived(1)(row) = x / numParallel
        derived(2)(row) = g * numParallel
        derived(3)(row) = b * numParallel
        derived(4)(row) = t.sNom
        derived(5)(row) =
          1.0 + (tapPosition(table, id) - t.tapNeutral) * (t.tapStep / 100.0)
        derived(6)(row) = t.phaseShift
        sides(row) = t.tapSide
      else
        keys.zipWithIndex.foreach((key, i) => derived(i)(row) = table.float(key, id))
        sides(row) = table.int("tap_side", id)
    }

    val withFloats = write(table, keys.zip(derived))
    withFloats.copy(static =
      withFloats.static.updated("tap_side", Column.Ints(IArray.unsafeFromArray(sides)))
    )

  /** `tap_position` as a number, whichever way the column arrived.
    *
    * The schema types it as an int, but a network that never set it has no
    * column at all and a netCDF export can carry it as a float — and
    * `ComponentTable.float` throws on an int column rather than widening, by
    * design. This is the one place that has to accept either.
    */
  private def tapPosition(table: ComponentTable, id: String): Double =
    table.static.get("tap_position") match
      case Some(Column.Ints(values))   => values(table.row(id)).toDouble
      case Some(Column.Floats(values)) => values(table.row(id))
      case Some(other) =>
        throw new UnknownType(s"tap_position is ${other.valueType}, which is not a tap position")
      case None => table.spec.attribute("tap_position").flatMap(_.defaultText).flatMap(_.toDoubleOption).getOrElse(0.0)

  // -- The library ---------------------------------------------------------

  private final case class LineSpec(
      fNom: Double,
      rPerLength: Double,
      xPerLength: Double,
      cPerLength: Double,
  )

  private final case class TransformerSpec(
      sNom: Double,
      vsc: Double,
      vscr: Double,
      pfe: Double,
      i0: Double,
      phaseShift: Double,
      tapSide: Int,
      tapNeutral: Int,
      tapStep: Double,
  )

  /** Type names shipped with this build, for diagnostics and for the drift test. */
  def lineTypeNames(schema: Schema): IndexedSeq[String]        = resource("LineType", schema).ids
  def transformerTypeNames(schema: Schema): IndexedSeq[String] = resource("TransformerType", schema).ids

  /** The shipped library as a component table, parsed against the caller's schema. */
  private[network] def resource(component: String, schema: Schema): ComponentTable =
    val spec = schema(component)
    val path = s"/org/noaidi/network/standard_types/${spec.listName}.csv"
    val text = Option(getClass.getResourceAsStream(path))
      .map(stream => scala.util.Using.resource(stream)(s => new String(s.readAllBytes, "UTF-8")))
      .getOrElse(throw new IllegalStateException(s"the standard type library $path is missing"))
    CsvReader.table(text, spec)

  private def lineLibrary(network: Network): Map[String, LineSpec] =
    merged(network, "LineType").map { (name, table) =>
      name -> LineSpec(
        fNom = table.float("f_nom", name),
        rPerLength = table.float("r_per_length", name),
        xPerLength = table.float("x_per_length", name),
        cPerLength = table.float("c_per_length", name),
      )
    }

  private def transformerLibrary(network: Network): Map[String, TransformerSpec] =
    merged(network, "TransformerType").map { (name, table) =>
      name -> TransformerSpec(
        sNom = table.float("s_nom", name),
        vsc = table.float("vsc", name),
        vscr = table.float("vscr", name),
        pfe = table.float("pfe", name),
        i0 = table.float("i0", name),
        phaseShift = table.float("phase_shift", name),
        tapSide = table.int("tap_side", name),
        tapNeutral = table.int("tap_neutral", name),
        tapStep = table.float("tap_step", name),
      )
    }

  /** Every type name in scope, mapped to the table that defines it.
    *
    * The shipped library first, then whatever the network carries — so a network
    * that exports a type of its own wins, which is the only reason PyPSA writes
    * one out at all.
    */
  private def merged(network: Network, component: String): Map[String, ComponentTable] =
    val shipped = resource(component, network.schema)
    val own     = network.table(component)
    val base    = shipped.ids.map(_ -> shipped).toMap
    own.fold(base)(t => base ++ t.ids.map(_ -> t))

  private def unknown(
      component: String,
      id: String,
      name: String,
      known: Set[String],
  ): UnknownType =
    // Named, not counted: a typo in a type name is the likely cause and the
    // nearest match is what identifies it.
    val nearest = known.toIndexedSeq.sortBy(candidate => distance(name, candidate)).take(3)
    new UnknownType(
      s"$component '$id' has type '$name', which is in neither the network's own types nor the " +
        s"${known.size} shipped with this build; nearest are ${nearest.mkString("'", "', '", "'")}"
    )

  /** Levenshtein distance, for the "did you mean" in [[unknown]] only. */
  private def distance(a: String, b: String): Int =
    val previous = Array.tabulate(b.length + 1)(identity)
    val current  = new Array[Int](b.length + 1)
    var i = 0
    while i < a.length do
      current(0) = i + 1
      var j = 0
      while j < b.length do
        val substitution = previous(j) + (if a.charAt(i) == b.charAt(j) then 0 else 1)
        current(j + 1) = math.min(math.min(current(j) + 1, previous(j + 1) + 1), substitution)
        j += 1
      Array.copy(current, 0, previous, 0, current.length)
      i += 1
    previous(b.length)

  /** Replace or append float columns, leaving the rest of the table alone.
    *
    * Appending matters for the common case: a network whose lines are all typed
    * has no `x` column at all, so the columns have to be created rather than
    * updated. Order is the table's own — an updated column keeps its position and
    * a new one lands at the end.
    */
  private def write(table: ComponentTable, columns: Seq[(String, Array[Double])]): ComponentTable =
    val updated = columns.foldLeft(table.static) { case (acc, (name, values)) =>
      acc.updated(name, Column.Floats(IArray.unsafeFromArray(values)))
    }
    table.copy(static = ListMap.from(updated))
