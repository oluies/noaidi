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
              // The per-unit base differs by component: a line is referred to
              // voltage and a transformer to its own rating. Impedance is
              // divided by the base and admittance multiplied by it, which is
              // why `base` appears on both sides below.
              val base = table.spec.name match
                case "Line" =>
                  val vNom = busTable.float("v_nom", bus0)
                  if !(vNom > 0.0) then
                    throw new Unsupported(
                      s"bus '$bus0' has v_nom = $vNom, so ${table.spec.name} '$id' has no " +
                        "per-unit base"
                    )
                  vNom * vNom
                case "Transformer" =>
                  val sNom = table.float("s_nom", id)
                  if !(sNom > 0.0) then
                    throw new Unsupported(s"Transformer '$id' has s_nom = $sNom, its per-unit base")
                  // Off-nominal taps and the T-model are refused rather than
                  // approximated. PyPSA folds `tap_ratio` into `x_pu_eff` for the
                  // *linear* models, which this module's linear counterpart
                  // follows, but the AC admittance of an off-nominal transformer
                  // is an ideal-transformer model that makes Y asymmetric -- not
                  // a scalar. And `model = "t"` with a non-zero shunt is a
                  // wye-delta conversion before Y is built at all. No golden has
                  // either, so neither is written blind.
                  val tap = Branches.tapRatio(table, id)
                  if math.abs(tap - 1.0) > 1e-12 then
                    throw new Unsupported(
                      s"Transformer '$id' has tap_ratio = $tap; an off-nominal tap changes the AC " +
                        "admittance by more than a scalar and is not modelled"
                    )
                  // Phase shift, refused here for the same reason and found the
                  // same way. PyPSA multiplies the off-diagonals by `exp(jφ)` and
                  // its conjugate, so `Y` is no longer symmetric -- not a scaling.
                  // The linear flow *does* model it (see `LinearPowerFlow`); this
                  // path silently returned the unshifted answer because nothing
                  // read the attribute, which is exactly the failure a refusal
                  // exists to prevent.
                  val shift = Branches.optional(table, "phase_shift", id)
                  if shift != 0.0 then
                    throw new Unsupported(
                      s"Transformer '$id' has phase_shift = $shift degrees; that makes Y asymmetric " +
                        "(`exp(jφ)` on one off-diagonal and its conjugate on the other) and is not " +
                        "modelled here, though the linear flow does model it"
                    )
                  val shunt = Branches.optional(table, "b", id) + Branches.optional(table, "g", id)
                  val isT   = table.static.contains("model") && table.string("model", id) == "t"
                  if isT && shunt != 0.0 then
                    throw new Unsupported(
                      s"Transformer '$id' uses the T model with a non-zero shunt, which PyPSA " +
                        "converts to an equivalent pi model before building Y; that is not " +
                        "implemented"
                    )
                  sNom
                case other =>
                  throw new Unsupported(
                    s"$other '$id' is a passive branch whose per-unit base is not known here"
                  )

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

              val shuntG = Branches.optional(table, "g", id) * base / 2.0
              val shuntB = Branches.optional(table, "b", id) * base / 2.0

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
