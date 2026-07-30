package org.noaidi.pf

import org.noaidi.network.*

/** The bus admittance matrix of one sub-network, `Y = G + jB`.
  *
  * Stored as two real `n × n` row-major arrays rather than as a complex type,
  * because every consumer — the mismatch equations and all four Jacobian blocks —
  * wants the real and imaginary parts separately. A `Complex` wrapper would be
  * unwrapped at every use.
  */
final case class Admittance(buses: IndexedSeq[String], g: IArray[Double], b: IArray[Double]):
  val size: Int = buses.length
  private val indexOf: Map[String, Int] = buses.zipWithIndex.toMap

  def conductance(i: Int, k: Int): Double = g(i * size + k)
  def susceptance(i: Int, k: Int): Double = b(i * size + k)
  def index(bus: String): Int             = indexOf(bus)

object Admittance:

  /** The network uses a branch this admittance model does not cover. */
  final class Unsupported(message: String) extends RuntimeException(message)

  /** Build `Y` for a sub-network from its passive branches.
    *
    * The per-unit conventions are PyPSA's, and were read off its own `Y` rather
    * than from documentation — a three-bus network's matrix reproduced to six
    * significant figures is a much stronger check than a formula that looks
    * right:
    *
    *   - `r_pu = r / v_nom²` and `x_pu = x / v_nom²`, at PyPSA's default 1 MVA
    *     base. Impedance is '''divided''' by `v_nom²`.
    *   - `g_pu = g · v_nom²` and `b_pu = b · v_nom²`. Admittance is
    *     '''multiplied''' by it. Getting this backwards is the mistake that reads
    *     as plausible, since both are "per unit".
    *   - The shunt term is split, half at each end: a branch contributes
    *     `(g_pu + j·b_pu) / 2` to each of its two diagonal entries. That is the pi
    *     model, and dropping the halving doubles the charging current.
    *
    * The series admittance is `1 / (r_pu + j·x_pu)`, so unlike the linear flow
    * this needs '''both''' r and x and cannot fall back to one of them. A branch
    * with neither is refused.
    */
  def of(network: Network, sub: SubNetwork): Admittance =
    val buses = sub.buses
    val n     = buses.length
    val index = buses.zipWithIndex.toMap

    val g = new Array[Double](n * n)
    val b = new Array[Double](n * n)

    val busTable = network.require("Bus")

    network.tables.values.foreach { table =>
      if Role.of(table.spec) == Role.PassiveBranch then
        table.ids.foreach { id =>
          val bus0 = table.string("bus0", id)
          val bus1 = table.string("bus1", id)
          (index.get(bus0), index.get(bus1)) match
            case (Some(i), Some(k)) =>
              val vNom = busTable.float("v_nom", bus0)
              if !(vNom > 0.0) then
                throw new Unsupported(
                  s"bus '$bus0' has v_nom = $vNom, so ${table.spec.name} '$id' has no per-unit base"
                )
              val base = vNom * vNom

              val rPu = table.float("r", id) / base
              val xPu = table.float("x", id) / base
              val magnitude = rPu * rPu + xPu * xPu
              if !(magnitude > 0.0) then
                throw new Unsupported(
                  s"${table.spec.name} '$id' has r = 0 and x = 0, so its series admittance is infinite"
                )

              // 1 / (r + jx) = (r - jx) / (r² + x²)
              val yG = rPu / magnitude
              val yB = -xPu / magnitude

              val shuntG = optional(table, "g", id) * base / 2.0
              val shuntB = optional(table, "b", id) * base / 2.0

              g(i * n + i) += yG + shuntG
              b(i * n + i) += yB + shuntB
              g(k * n + k) += yG + shuntG
              b(k * n + k) += yB + shuntB

              g(i * n + k) -= yG
              b(i * n + k) -= yB
              g(k * n + i) -= yG
              b(k * n + i) -= yB

            case _ => () // A branch outside this island.
        }
    }

    Admittance(buses, IArray.unsafeFromArray(g), IArray.unsafeFromArray(b))

  /** A branch attribute that may be absent from the file, read as its default.
    *
    * `g` is omitted from every reference export because it sits at zero, and `b`
    * is omitted wherever no line is charging. Reading a missing column as NaN
    * would poison the whole matrix.
    */
  private def optional(table: ComponentTable, attribute: String, id: String): Double =
    if table.spec.attribute(attribute).isEmpty && !table.static.contains(attribute) then 0.0
    else
      val value = table.float(attribute, id)
      if value.isFinite then value else 0.0
