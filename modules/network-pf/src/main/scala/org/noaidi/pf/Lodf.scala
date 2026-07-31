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
    /** Whether each outage column was validated and populated.
      *
      * When only some branches are requested as contingencies, the rest are left
      * unpopulated rather than filled with a ratio that was never checked. Reading
      * one has to fail: handing back the placeholder would be a zero that means
      * "not computed" dressed as a zero that means "no effect" -- the same hazard
      * removed from [[factor]] itself, one layer up.
      */
    private val populated: IArray[Boolean],
):
  val size: Int = branches.length
  private val indexOf: Map[(String, String), Int] = branches.zipWithIndex.toMap

  private def checked(j: Int, outage: (String, String)): Int =
    if !populated(j) then
      throw new NoSuchElementException(
        s"the outage column for $outage was not computed: it was not among the requested " +
          "contingencies, so its factors were never validated"
      )
    j

  /** The factor for two branches of this sub-network.
    *
    * Throws for a branch this `Lodf` does not know. Returning zero for an
    * unrecognised key conflated "these are in different sub-networks", which is
    * physically zero, with "you passed an id that does not exist", which is a
    * bug -- and a typo in a test then reduced a post-contingency check to
    * `|f| <= rating`, which the variable bounds already guarantee, so it passed
    * vacuously. Use [[factorOrZero]] where the cross-sub-network case is meant.
    */
  def factor(affected: (String, String), outage: (String, String)): Double =
    val i = indexOf.getOrElse(affected, throw new NoSuchElementException(s"no branch $affected here"))
    val j = indexOf.getOrElse(outage, throw new NoSuchElementException(s"no branch $outage here"))
    values(i * size + checked(j, outage))

  /** As [[factor]], but zero when either branch belongs to another sub-network.
    *
    * That is the physically correct answer: an outage cannot move flow onto a
    * branch it has no electrical path to.
    */
  def factorOrZero(affected: (String, String), outage: (String, String)): Double =
    (indexOf.get(affected), indexOf.get(outage)) match
      // A known column that was never computed still throws: only "these are in
      // different sub-networks" is a legitimate zero.
      case (Some(i), Some(j)) => values(i * size + checked(j, outage))
      case _                  => 0.0

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
  def of(network: Network, sub: SubNetwork): Lodf = of(network, sub, None)

  /** As [[of]], validating only the branches named as contingencies.
    *
    * The bridge check belongs to the '''outage''' column, not to the whole matrix: a
    * radial spur elsewhere in the network is perfectly ordinary and says nothing
    * about whether some meshed branch is a credible contingency. Checking every
    * column made the refusal far broader than the contract, and since almost any
    * real network has a radial branch somewhere it made SCLOPF unusable outside
    * purpose-built meshed fixtures. `None` keeps the behaviour of validating
    * everything, which is right when every branch is a contingency.
    */
  def of(network: Network, sub: SubNetwork, outages: Option[Set[(String, String)]]): Lodf =
    val slack = Slack.of(network, sub).bus
    val free  = sub.buses.filterNot(_ == slack)
    val index = free.zipWithIndex.toMap
    val n     = free.length

    val edges = Branches.within(network, sub)
    val m     = edges.length

    if m == 0 then return new Lodf(IndexedSeq.empty, IArray.empty, IArray.empty)

    val susceptance = edges.map(e => Branches.susceptance(network, sub, e, new Unsupported(_)))

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

    val lodf      = new Array[Double](m * m)
    val populated = new Array[Boolean](m)
    (0 until m).foreach { j =>
      val key       = (edges(j).component, edges(j).id)
      val requested = outages.forall(_.contains(key))
      val self = branchPtdf(j)(j)
      val denominator = 1.0 - self
      // A branch whose removal disconnects the network takes the whole flow with
      // it, and `1 - branchPTDF[o,o]` goes to zero. Producing an infinity here
      // would put one into a constraint coefficient and the LP would come back
      // infeasible with no indication why.
      // A loosened absolute tolerance, and only that. `branchPTDF[o,o]` lies in
      // [0, 1], so scaling by it would be inert -- an earlier comment here
      // claimed a scaling that did nothing. For a true bridge the denominator is
      // analytically zero, but it arrives through a Cholesky solve, so a fixed
      // 1e-9 could pass on an ill-conditioned island. What actually catches a
      // near-bridge is the magnitude of the resulting factors, checked below.
      val bridge = math.abs(denominator) < 1e-7
      if requested && bridge then
        throw new Unsupported(
          s"${edges(j).component} '${edges(j).id}' is a bridge: removing it disconnects the " +
            "sub-network, so there is no post-outage flow to redistribute and it is not a " +
            "credible contingency"
        )
      if bridge then
        // Not a requested contingency and not computable. Left unpopulated, and
        // flagged so reading it fails rather than returning a placeholder.
        ()
      else
        populated(j) = true
        var worst = 0.0
        (0 until m).foreach { l =>
          val value = if l == j then -1.0 else branchPtdf(l)(j) / denominator
          lodf(l * m + j) = value
          if l != j then worst = math.max(worst, math.abs(value))
        }
        // The magnitude check the tolerance above cannot make on its own. A
        // denominator just past the threshold yields a finite factor of order
        // 1e7, which the LP would swallow and return infeasible over; only the
        // factors themselves reveal it. Applied to requested columns, since an
        // unrequested one is nobody's constraint coefficient.
        if requested && (!worst.isFinite || worst > 1e6) then
          throw new Unsupported(
            s"outage of ${edges(j).component} '${edges(j).id}' redistributes by a factor of " +
              f"$worst%.3e, which is not a physical redistribution -- the branch is at or near " +
              "the point where removing it disconnects the sub-network"
          )
    }

    new Lodf(
      edges.map(e => (e.component, e.id)),
      IArray.unsafeFromArray(lodf),
      IArray.unsafeFromArray(populated),
    )
