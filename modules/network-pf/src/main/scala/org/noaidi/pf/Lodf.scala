package org.noaidi.pf

import org.noaidi.network.*

/** Line outage distribution factors for one sub-network.
  *
  * `factor(affected, outage)` is how much of the outaged branch's pre-outage flow
  * reappears on another branch:
  *
  * {{{
  * f_affected^after = f_affected^before + factor(affected, outage) · f_outage^before
  * }}}
  *
  * which is what makes N-1 security expressible as ordinary linear constraints on
  * the flows a dispatch model already has, with no post-contingency variables.
  */
final class Lodf private[pf] (
    val branches: IndexedSeq[(String, String)],
    private val values: IArray[Double],
):
  val size: Int = branches.length
  private val indexOf: Map[(String, String), Int] = branches.zipWithIndex.toMap

  def factor(affected: (String, String), outage: (String, String)): Double =
    (indexOf.get(affected), indexOf.get(outage)) match
      case (Some(i), Some(j)) => values(i * size + j)
      // Branches in different sub-networks do not affect each other, which is
      // the physically correct answer rather than a missing entry.
      case _ => 0.0

  def contains(branch: (String, String)): Boolean = indexOf.contains(branch)

object Lodf:

  final class Unsupported(message: String) extends RuntimeException(message)

  /** Compute the factors for a sub-network.
    *
    * Follows PyPSA's construction exactly, because the point is to agree with it:
    *
    *   1. `H[l, b]` is the branch susceptance times the incidence — the flow on
    *      `l` per radian at bus `b`.
    *   2. `B⁻¹` is the bus susceptance matrix '''with the slack row and column
    *      struck out''', inverted, with zeros put back for the slack. That is the
    *      same reduced system [[LinearPowerFlow]] solves, so the factorisation is
    *      reused across the columns rather than a matrix being inverted.
    *   3. `PTDF = H · B⁻¹`, then `branchPTDF = PTDF · K` with `K` the
    *      bus-by-branch incidence, giving branch-to-branch sensitivities.
    *   4. `LODF[l,o] = branchPTDF[l,o] / (1 − branchPTDF[o,o])`, and `−1` on the
    *      diagonal, since an outaged branch carries nothing afterwards.
    *
    * The denominator is where a contingency stops making sense: `branchPTDF[o,o]`
    * reaching 1 means removing `o` disconnects the network, so there is no
    * post-outage flow to distribute. That is a '''bridge''', and it is refused
    * rather than allowed to produce an infinity — the reference network's
    * Bremen–Frankfurt line is exactly such a branch.
    */
  def of(network: Network, sub: SubNetwork): Lodf =
    val slack = Slack.of(network, sub).bus
    val free  = sub.buses.filterNot(_ == slack)
    val index = free.zipWithIndex.toMap
    val n     = free.length

    val edges = passiveEdges(network, sub)
    val m     = edges.length

    if m == 0 then return new Lodf(IndexedSeq.empty, IArray.empty)

    val susceptance = edges.map(e => susceptanceOf(network, sub, e))

    // The reduced susceptance matrix, assembled exactly as the linear flow does.
    val b = new Array[Double](n * n)
    edges.zip(susceptance).foreach { (e, y) =>
      (index.get(e.bus0), index.get(e.bus1)) match
        case (Some(i), Some(j)) =>
          b(i * n + i) += y
          b(j * n + j) += y
          b(i * n + j) -= y
          b(j * n + i) -= y
        case (Some(i), None) => b(i * n + i) += y
        case (None, Some(j)) => b(j * n + j) += y
        case (None, None)    => ()
    }

    val factorisation =
      try Cholesky.factor(n, IArray.unsafeFromArray(b))
      catch
        case e: Cholesky.NotPositiveDefinite =>
          throw new Unsupported(
            s"sub-network ${sub.buses.mkString("{", ", ", "}")} has no unique power flow, so its " +
              s"outage factors are undefined: ${e.getMessage}"
          )

    // B inverse, one column per free bus. Solving against the shared
    // factorisation rather than inverting: same result, and the O(n^3) work
    // happens once.
    val inverse = Array.ofDim[Double](n, n)
    free.indices.foreach { c =>
      val unit = Array.fill(n)(0.0)
      unit(c) = 1.0
      val column = factorisation.solve(IArray.unsafeFromArray(unit))
      free.indices.foreach(r => inverse(r)(c) = column(r))
    }

    // PTDF[l, freeBus]: the slack's column is zero by construction, so it is
    // simply left out of the sum below.
    val ptdf = Array.ofDim[Double](m, n)
    edges.zip(susceptance).zipWithIndex.foreach { case ((e, y), l) =>
      val from = index.get(e.bus0)
      val to   = index.get(e.bus1)
      free.indices.foreach { c =>
        var v = 0.0
        from.foreach(i => v += y * inverse(i)(c))
        to.foreach(j => v -= y * inverse(j)(c))
        ptdf(l)(c) = v
      }
    }

    // branchPTDF = PTDF * K, which for a branch is the difference of its two
    // endpoint columns.
    val branchPtdf = Array.ofDim[Double](m, m)
    edges.zipWithIndex.foreach { (o, j) =>
      val from = index.get(o.bus0)
      val to   = index.get(o.bus1)
      (0 until m).foreach { l =>
        var v = 0.0
        from.foreach(i => v += ptdf(l)(i))
        to.foreach(i => v -= ptdf(l)(i))
        branchPtdf(l)(j) = v
      }
    }

    val lodf = new Array[Double](m * m)
    (0 until m).foreach { j =>
      val self = branchPtdf(j)(j)
      val denominator = 1.0 - self
      // A branch whose removal disconnects the network takes the whole flow with
      // it, and `1 - branchPTDF[o,o]` goes to zero. Producing an infinity here
      // would put one into a constraint coefficient and the LP would come back
      // infeasible with no indication why.
      if math.abs(denominator) < 1e-9 then
        throw new Unsupported(
          s"${edges(j).component} '${edges(j).id}' is a bridge: removing it disconnects the " +
            "sub-network, so there is no post-outage flow to redistribute and it is not a " +
            "credible contingency"
        )
      (0 until m).foreach { l =>
        lodf(l * m + j) = if l == j then -1.0 else branchPtdf(l)(j) / denominator
      }
    }

    new Lodf(edges.map(e => (e.component, e.id)), IArray.unsafeFromArray(lodf))

  private final case class Edge(component: String, id: String, bus0: String, bus1: String)

  private def passiveEdges(network: Network, sub: SubNetwork): IndexedSeq[Edge] =
    val members = sub.buses.toSet
    network.tables.values.toIndexedSeq.flatMap { table =>
      if Role.of(table.spec) != Role.PassiveBranch then IndexedSeq.empty
      else
        table.ids.flatMap { id =>
          val bus0 = table.string("bus0", id)
          val bus1 = table.string("bus1", id)
          if members.contains(bus0) && members.contains(bus1) then
            Some(Edge(table.spec.name, id, bus0, bus1))
          else None
        }
    }

  /** Per-unit susceptance, on the same convention as the linear flow.
    *
    * Reactance for AC and resistance for DC, scaled by `v_nom²`. The scale
    * cancels out of the factors themselves — they are ratios of flows — but
    * getting the AC/DC split wrong does not cancel, since the reference DC lines
    * carry `x = 0`.
    */
  private def susceptanceOf(network: Network, sub: SubNetwork, edge: Edge): Double =
    val table = network.require(edge.component)
    val z     = if sub.carrier == "DC" then table.float("r", edge.id) else table.float("x", edge.id)
    val vNom  = network.require("Bus").float("v_nom", edge.bus0)
    if !(z > 0.0) then
      throw new Unsupported(
        s"${edge.component} '${edge.id}' has ${if sub.carrier == "DC" then "r" else "x"} = $z in a " +
          s"${sub.carrier} sub-network, so it has no susceptance"
      )
    if !(vNom > 0.0) then
      throw new Unsupported(s"bus '${edge.bus0}' has v_nom = $vNom, so there is no per-unit base")
    vNom * vNom / z
