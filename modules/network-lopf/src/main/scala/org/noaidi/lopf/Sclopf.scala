package org.noaidi.lopf

import org.noaidi.prima.*
import org.noaidi.network.*
import org.noaidi.pf.Lodf

/** Security-constrained linear optimal power flow: dispatch that survives N-1.
  *
  * An ordinary [[Lopf]] answers "what is the cheapest dispatch this network can
  * carry". This answers "what is the cheapest dispatch it can carry '''and still
  * carry after any one branch fails'''", which is the question a system operator
  * actually has. The two differ by a lot: on the reference triangle the plain
  * optimum costs 6900 and the secure one 14100.
  *
  * ==Formulation==
  *
  * No post-contingency variables. Removing a branch redistributes its flow onto
  * the rest by a factor that depends only on the network's impedances, so the
  * post-outage flow is a linear function of the pre-outage ones:
  *
  * {{{
  * f_l^after(o) = f_l + LODF[l, o] · f_o
  * }}}
  *
  * and security is then just more rating rows on variables the dispatch model
  * already has:
  *
  * {{{
  * −s_nom_l · s_max_pu  <=  f_l + LODF[l, o] · f_o  <=  s_nom_l · s_max_pu
  * }}}
  *
  * one pair per monitored branch per credible outage per snapshot. That is PyPSA's
  * formulation, and the reason it is worth stating is that the obvious
  * alternative — a full copy of the flow variables per contingency — multiplies
  * the problem size by the number of outages for the same answer.
  *
  * The factors come from [[Lodf]] in `network-pf`, because they are a power-flow
  * sensitivity rather than an optimisation concept, and sharing them keeps one
  * definition of susceptance and slack across both layers.
  */
object Sclopf:

  final class UnsupportedNetwork(message: String) extends RuntimeException(message)

  /** A contingency: a passive branch assumed to fail. */
  final case class Outage(component: String, id: String)

  /** Solve, monitoring every passive branch under each given outage.
    *
    * `outages` defaults to every passive branch, matching PyPSA. A branch whose
    * removal would disconnect the network is refused by [[Lodf]] rather than
    * silently producing an infinite factor — the reference `ac-dc-meshed`'s
    * Bremen–Frankfurt line is exactly that, and it is not a credible contingency
    * because there is no post-outage flow to redistribute.
    */
  def solve(
      network: Network,
      outages: Option[IndexedSeq[Outage]] = None,
      params: PdhgParams = PdhgParams.default,
  ): LopfResult =
    val model = build(network, outages)
    val solution = Pdhg.solve(model.problem, params)
    LopfResult(network, model, solution)

  /** Build the LP: the dispatch model plus one rating pair per (branch, outage,
    * snapshot).
    */
  def build(network: Network, outages: Option[IndexedSeq[Outage]] = None): Lopf.Model =
    val base = Lopf.build(network)

    // The empty case is exactly the dispatch model, and returning before any
    // factors are computed matters: building them can refuse a network for a
    // bridge that was never named as a contingency.
    if outages.exists(_.isEmpty) then return base

    val subs = Topology.subNetworks(network)

    // Only the requested outages are validated. A radial spur elsewhere is
    // ordinary and says nothing about whether a meshed branch is a credible
    // contingency; checking every column refused almost any real network.
    val wanted = outages.map(_.map(o => (o.component, o.id)).toSet)
    val lodfs  = subs.map(sub => sub -> Lodf.of(network, sub, wanted))

    // Every passive branch, paired with the sub-network it belongs to.
    val monitored = lodfs.flatMap((sub, lodf) => lodf.branches.map(b => (sub, lodf, b)))

    val requested = outages.getOrElse(monitored.map((_, _, b) => Outage(b._1, b._2)))
    if requested.isEmpty then return base

    requested.foreach { o =>
      if !monitored.exists((_, _, b) => b == (o.component, o.id)) then
        throw new UnsupportedNetwork(
          s"${o.component} '${o.id}' is not a passive branch of this network, so it cannot be a " +
            "contingency"
        )
    }

    val snapshots = network.snapshots.indices
    val builder   = LpProblem.builder(base.map.numVariables)

    // The base model's rows are rebuilt rather than appended to, because
    // `LpProblem` is immutable and its builder is the only way to add rows.
    //
    // `base.map` is reused, and its `balanceRows` indices were assigned in the
    // base builder's row numbering. They still line up only because the balance
    // rows are the first rows `Lopf.build` emits, they are equalities, and
    // `LpBuilder` puts equalities first in the order given -- so re-emitting
    // standard-form row `r` as new original row `r` preserves them. Nothing in
    // the type system says so, and `LopfResult.marginalPrice` reads through that
    // map, so a future equality row added ahead of the balances would make SCLOPF
    // nodal prices silently wrong. Checked rather than trusted.
    val baseProblem = base.problem
    require(
      base.map.balanceRows.isEmpty || base.map.balanceRows.values.max < baseProblem.numEqualities,
      "balance rows are no longer the leading equalities, so reusing the base variable map " +
        "would misindex the duals",
    )

    // Copy the base problem's bounds, objective and rows.
    (0 until baseProblem.numVariables).foreach { j =>
      builder.bounds(j, baseProblem.variableLower(j), baseProblem.variableUpper(j))
      builder.objectiveCoefficient(j, baseProblem.objective(j))
    }
    builder.objectiveOffset(baseProblem.objectiveOffset)

    val matrix = baseProblem.constraintMatrix
    (0 until baseProblem.numConstraints).foreach { r =>
      val terms = (matrix.rowPtr(r) until matrix.rowPtr(r + 1))
        .map(p => (matrix.colIndices(p), matrix.values(p)))
      val q = baseProblem.rhs(r)
      if r < baseProblem.numEqualities then builder.equalityConstraint(terms, q)
      else builder.greaterThan(terms, q)
    }

    // A range row in the base model would have been split into two independent
    // `>=` rows by the copy above, and its dual could then no longer be
    // recombined. `Lopf.build` emits only equalities and one-sided rows today.
    require(
      baseProblem.numConstraints == base.translation.numOriginalRows,
      "the base model contains a range row, which the row-by-row copy would split",
    )

    // The security rows.
    snapshots.foreach { t =>
      monitored.foreach { (_, lodf, affected) =>
        val (component, id) = affected
        val table  = network.require(component)
        val limit  = table.float("s_nom", id) * table.valueAt("s_max_pu", id, t)
        val column = base.map.column(component, id, t)

        requested.foreach { outage =>
          // Only within one sub-network: an outage cannot move flow onto a
          // branch it has no electrical path to, and `Lodf.factor` returns zero
          // for such a pair rather than a missing entry.
          if lodf.contains((outage.component, outage.id)) && (outage.component, outage.id) != affected
          then
            val factor = lodf.factor(affected, (outage.component, outage.id))
            if factor != 0.0 then
              val outageColumn = base.map.column(outage.component, outage.id, t)
              val terms        = Seq((column, 1.0), (outageColumn, factor))
              builder.constraint(terms, -limit, limit)
        }
      }
    }

    val (problem, translation) = builder.build()
    Lopf.Model(problem, translation, base.map)
