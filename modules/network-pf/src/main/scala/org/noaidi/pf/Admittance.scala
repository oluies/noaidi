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

  /** Whether this branch is declared with the T equivalent circuit. */
  private def isTModel(table: ComponentTable, id: String): Boolean =
    table.spec.attribute("model").isDefined && table.static.contains("model") &&
      table.string("model", id) == "t"

  /** Which end carries the tap: 0 for `bus0`, 1 for `bus1`, 0 by default.
    *
    * PyPSA's default is 0, and it is a property of the *transformer type* as
    * well as of the transformer, so a typed unit inherits it. Read defensively
    * because a Line declares no such attribute.
    */
  private def tapSide(table: ComponentTable, id: String): Int =
    if table.spec.attribute("tap_side").isDefined && table.static.contains("tap_side") then
      table.int("tap_side", id)
    else 0

  /** The T equivalent circuit as an equivalent pi, in per unit.
    *
    * PyPSA's `apply_transformer_t_model` runs a wye–delta conversion on the
    * three impedances `z/2`, `z/2` and `1/y` and keeps two of the three results.
    * With the first two equal the algebra collapses to a closed form, which is
    * what this computes:
    *
    * {{{
    * z' = z + y z² / 4          y' = 4 y / (z y + 4)
    * }}}
    *
    * Derived rather than transcribed, and worth showing because the general
    * `summand / z_i` form hides that `za` and `zb` are equal here: with
    * `z1 = z2 = z/2` and `z3 = 1/y`, `summand = z²/4 + z/y`, so
    * `zc = summand · y = z + y z²/4` and `za = summand · 2/z = z/2 + 2/y`,
    * whence `y' = 2/za = 4y/(zy + 4)`.
    *
    * Applied only where the shunt is non-zero. `1/y` is the third leg of the
    * wye, so a lossless, non-charging transformer has no T to convert and the
    * expression would divide by zero — PyPSA masks on exactly that condition.
    */
  private[pf] def tModelToPi(
      r: Double,
      x: Double,
      g: Double,
      b: Double,
  ): (Double, Double, Double, Double) =
    // z = r + jx, y = g + jb, computed with explicit real and imaginary parts
    // rather than a complex type, for the reason the class doc gives.
    val zzR = r * r - x * x // z²
    val zzI = 2.0 * r * x
    // y z² / 4
    val quarterR = (g * zzR - b * zzI) / 4.0
    val quarterI = (g * zzI + b * zzR) / 4.0
    val zR = r + quarterR
    val zI = x + quarterI

    // z y + 4
    val zyR = r * g - x * b + 4.0
    val zyI = r * b + x * g
    val denom = zyR * zyR + zyI * zyI
    // 4y / (zy + 4)
    val numR = 4.0 * g
    val numI = 4.0 * b
    val yR = (numR * zyR + numI * zyI) / denom
    val yI = (numI * zyR - numR * zyI) / denom

    (zR, zI, yR, yI)

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
                  sNom
                case other =>
                  throw new Unsupported(
                    s"$other '$id' is a passive branch whose per-unit base is not known here"
                  )

              val rRaw = table.float("r", id) / base
              val xRaw = table.float("x", id) / base
              val gRaw = Branches.optional(table, "g", id) * base
              val bRaw = Branches.optional(table, "b", id) * base

              // The T model, converted to its equivalent pi before anything else
              // reads the impedance. PyPSA does this in `apply_transformer_t_model`
              // ahead of `calculate_Y`, and only where the shunt is non-zero --
              // with no shunt the two models coincide and the conversion would
              // divide by zero.
              val (rPu, xPu, shuntGpu, shuntBpu) =
                if isTModel(table, id) && (gRaw != 0.0 || bRaw != 0.0) then
                  tModelToPi(rRaw, xRaw, gRaw, bRaw)
                else (rRaw, xRaw, gRaw, bRaw)

              val magnitude = rPu * rPu + xPu * xPu
              if !(magnitude > 0.0) then
                throw new Unsupported(
                  s"${table.spec.name} '$id' has r = 0 and x = 0, so its series admittance is infinite"
                )

              // 1 / (r + jx) = (r - jx) / (r² + x²)
              val yG = rPu / magnitude
              val yB = -xPu / magnitude

              val shuntG = shuntGpu / 2.0
              val shuntB = shuntBpu / 2.0

              // The ideal-transformer ratio, complex: magnitude from `tap_ratio`
              // and argument from `phase_shift`. `tap_side` decides which end
              // carries it -- 0 is bus0, 1 is bus1 -- and the other end gets 1.
              // A Line has neither attribute and so gets 1 at both ends, which
              // reduces every expression below to the plain pi model.
              val tap  = Branches.tapRatio(table, id)
              val side = tapSide(table, id)
              val tauHv = if side == 0 then tap else 1.0
              val tauLv = if side == 1 then tap else 1.0
              val phi   = math.toRadians(Branches.optional(table, "phase_shift", id))
              val cosPhi = math.cos(phi)
              val sinPhi = math.sin(phi)

              // PyPSA's `calculate_Y`, with the complex arithmetic written out:
              //
              //   Y00 = (y_se + y_sh/2) / tau_hv²      Y11 = (y_se + y_sh/2) / tau_lv²
              //   Y10 = -y_se / (tau_lv tau_hv e^{jφ})
              //   Y01 = -y_se / (tau_lv tau_hv e^{-jφ})
              //
              // The two off-diagonals differ by the *conjugate* of the phase
              // term, which is what makes Y asymmetric rather than merely
              // scaled. Everything downstream indexes `(i, k)` in the general
              // form and the Jacobian is solved by LU, so nothing needed to
              // change to carry that.
              g(i * n + i) += (yG + shuntG) / (tauHv * tauHv)
              b(i * n + i) += (yB + shuntB) / (tauHv * tauHv)
              g(k * n + k) += (yG + shuntG) / (tauLv * tauLv)
              b(k * n + k) += (yB + shuntB) / (tauLv * tauLv)

              val scale = 1.0 / (tauHv * tauLv)
              // Dividing by e^{-jφ} multiplies by cos φ + j sin φ.
              g(i * n + k) -= scale * (yG * cosPhi - yB * sinPhi)
              b(i * n + k) -= scale * (yG * sinPhi + yB * cosPhi)
              // Dividing by e^{+jφ} multiplies by cos φ − j sin φ.
              g(k * n + i) -= scale * (yG * cosPhi + yB * sinPhi)
              b(k * n + i) -= scale * (yB * cosPhi - yG * sinPhi)

            case _ => () // A branch outside this island.
        }
    }

    Admittance(buses, IArray.unsafeFromArray(g), IArray.unsafeFromArray(b))
