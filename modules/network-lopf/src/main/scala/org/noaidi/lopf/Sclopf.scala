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
      input: Network,
      outages: Option[IndexedSeq[Outage]] = None,
      params: PdhgParams = PdhgParams.default,
  ): LopfResult =
    val network = StandardTypes.expand(input)
    val model = build(network, outages)
    val solution = Pdhg.solve(model.problem, params)
    LopfResult(network, model, solution)

  /** Build the LP: the dispatch model plus one rating pair per (branch, outage,
    * snapshot).
    */
  def build(input: Network, outages: Option[IndexedSeq[Outage]] = None): Lopf.Model =
    // Before `Topology` and `Lodf` below, not only inside `Lopf.build`: the
    // outage factors are computed from susceptance, so an unexpanded network
    // would give the dispatch model the right impedances and the contingency
    // rows the wrong ones.
    val network = StandardTypes.expand(input)
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
    // What the row-by-row copy actually requires: each original row maps to a
    // single standard-form row of the same index. `Direct` and `Negated` both
    // satisfy that; only `Range` (one original row becoming two) and a
    // reordering would break it.
    //
    // Demanding `Direct` alone was too strong and regressed real networks:
    // `Lopf.build` emits global constraints through `lessThan`, which the
    // builder records as `Negated`, so every CO2-capped network -- `ac-dc-co2`
    // and `storage-hvdc` among the goldens -- was rejected outright. That traded
    // a sign quirk in output nothing reads for a hard refusal of secure dispatch
    // under an emissions cap, which is one of SCLOPF's commonest uses.
    val baseProblem = base.problem
    (0 until base.translation.numOriginalRows).foreach { r =>
      base.translation.expansionOf(r) match
        case RowExpansion.Direct(row) if row == r  => ()
        case RowExpansion.Negated(row) if row == r => ()
        case other =>
          throw new UnsupportedNetwork(
            s"the base model maps original row $r to $other rather than to the standard-form row " +
              "of the same index, so the row-by-row copy would misindex its duals"
          )
    }

    // Copy the base problem's bounds, objective and rows.
    (0 until baseProblem.numVariables).foreach { j =>
      builder.bounds(j, baseProblem.variableLower(j), baseProblem.variableUpper(j))
      builder.objectiveCoefficient(j, baseProblem.objective(j))
    }
    builder.objectiveOffset(baseProblem.objectiveOffset)

    // A row the base negated is re-emitted negated, so the rebuilt translation
    // records `Negated` too and `originalDuals` returns the same sign the base
    // model would. Re-emitting it through `greaterThan` would copy the primal
    // correctly and silently flip that dual.
    val negated = (0 until base.translation.numOriginalRows).collect {
      case r if base.translation.expansionOf(r).isInstanceOf[RowExpansion.Negated] => r
    }.toSet

    val matrix = baseProblem.constraintMatrix
    (0 until baseProblem.numConstraints).foreach { r =>
      val terms = (matrix.rowPtr(r) until matrix.rowPtr(r + 1))
        .map(p => (matrix.colIndices(p), matrix.values(p)))
      val q = baseProblem.rhs(r)
      if r < baseProblem.numEqualities then builder.equalityConstraint(terms, q)
      else if negated.contains(r) then
        builder.lessThan(terms.map((c, a) => (c, -a)), -q)
      else builder.greaterThan(terms, q)
    }

    // The security rows.
    snapshots.foreach { t =>
      monitored.foreach { (_, lodf, affected) =>
        val (component, id) = affected
        val table  = network.require(component)
        val limit  = table.float("s_nom", id) * table.valueAt("s_max_pu", id, t)
        val column = base.map.column(component, id, t)

        requested.foreach { outage =>
          // `factorOrZero`, not `factor`: an outage cannot move flow onto a
          // branch it has no electrical path to, and zero is the physically
          // correct answer for a pair spanning two sub-networks. `factor` throws
          // for a branch it does not know, which is right for a typo and wrong
          // here -- this is the case the two accessors exist to separate.
          if (outage.component, outage.id) != affected then
            val factor = lodf.factorOrZero(affected, (outage.component, outage.id))
            if factor != 0.0 then
              val outageColumn = base.map.column(outage.component, outage.id, t)
              val terms        = Seq((column, 1.0), (outageColumn, factor))
              builder.constraint(terms, -limit, limit)
        }
      }
    }

    val (problem, translation) = builder.build()
    Lopf.Model(problem, translation, base.map)
