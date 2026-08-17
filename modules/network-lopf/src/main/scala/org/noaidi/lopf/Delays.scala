package org.noaidi.lopf

import org.noaidi.network.*

/** `delay`: a controllable branch whose energy arrives in a later snapshot.
  *
  * PyPSA shifts a Link's '''output''' in time. Power entering at `bus0` in one
  * snapshot leaves at `bus1` some elapsed time later; `bus0` is not shifted, so
  * the two ends of one link land in the balance rows of two different snapshots.
  * That is the one thing the rest of this model assumed never happened — every
  * other coupling across snapshots is an energy balance the builder emits per
  * snapshot, and the bus balances themselves were separable.
  *
  * ==Delay is elapsed time, not a snapshot count==
  *
  * The declared unit is "snapshot weighting units", and the weighting is
  * `snapshot_weightings.generators` — the same column the energy budgets read,
  * not the `objective` one beside it in the file nor the `stores` one the
  * storage balances use. Where every weighting is 1.0, which is every fixture
  * here but two, `delay = 2` and "two snapshots back" are the same thing and the
  * distinction cannot be seen.
  *
  * Writing `tau(t)` for the elapsed time at which snapshot `t` begins, the
  * source of `t` is the '''latest''' snapshot `s` with `tau(s) <= tau(t) −
  * delay`. A delay that lands between two boundaries therefore rounds *down* to
  * one. PyPSA warns and rounds the same way; this follows it rather than
  * refusing, because the contract is to agree with that implementation.
  *
  * ==Cyclic, and the mask that is not a zero==
  *
  * `cyclic_delay` — default '''true''', which is worth stating because every
  * other optional flag in this model defaults off — wraps `tau(t) − delay`
  * modulo the horizon, so energy still in flight at the end of the horizon
  * arrives at its beginning.
  *
  * Without it, the early targets have '''no source at all'''. PyPSA still
  * computes an index for them and then discards it through a validity mask, and
  * the discarded index is `0` — so an implementation that dropped the mask and
  * kept the index would deliver the first snapshot's inflow to every target
  * before the delay has elapsed. That is not a small error: on `link-delay-wrap`
  * it forces the delayed link to carry nothing at all, because its arrival at a
  * bus with no load must be zero, and the load it was built to serve then falls
  * to the expensive local unit.
  *
  * ==Efficiency is read at the arrival snapshot==
  *
  * `expr = coeff.sel(name=names) * (shifted_p * valid_mask)` in
  * `define_nodal_balance_constraints`: only `p` is shifted, so a time-varying
  * `efficiency` is read at the snapshot the energy '''arrives''', not the one it
  * left in.
  *
  * PyPSA's own result assignment disagrees with its own constraint here.
  * `_apply_delay_shift` shifts `-p0 * efficiency` as a product, so the reported
  * `links_t.p1` uses the efficiency of the departure snapshot. On
  * `link-delay-wrap` the constraint delivers 100 MW to `b` while PyPSA reports
  * `p1 = -200`. Nothing in this port reads `p1` — `LopfResult.dispatch` returns
  * `p0` — so the constraint is the only convention that has to be matched, and
  * it is the one the objective and the dispatch come from.
  *
  * ==One case this does not cover==
  *
  * PyPSA applies a delay '''per investment period''', since periods are not
  * temporally adjacent, and the horizon it measures against is a single period's
  * rather than the whole index. That is not built here, and it does not need a
  * guard of its own: `Periods.reject` runs ahead of [[reject]] in `Lopf.build`,
  * so a multi-period network never reaches this.
  */
object Delays:

  /** Which snapshot's inflow reaches one output port, per target snapshot. */
  final class Shift private[Delays] (source: Array[Int], valid: Array[Boolean]):

    /** The snapshot whose inflow arrives at `t`, or `None` if nothing does. */
    def sourceOf(t: Int): Option[Int] = if valid(t) then Some(source(t)) else None

  /** The shift for every delayed `(entity, port)` in a table.
    *
    * Absent from the map means instantaneous, which is the overwhelmingly common
    * case: `delay` defaults to 0 and no network outside the two fixtures written
    * for it sets one.
    */
  def forTable(network: Network, table: ComponentTable): Map[(String, String), Shift] =
    val ports = Topology.branchPorts(table).filter(_ != "bus0")
    if ports.isEmpty then Map.empty
    else
      // Computed once per `(port, delay, cyclic)` rather than once per entity:
      // the walk is linear in the horizon, and a year-long network has 8760
      // snapshots.
      val durations = network.snapshots.indices.map(t => network.weighting("generators", t))
      ports.flatMap { port =>
        configured(table, port) match
          case None => IndexedSeq.empty
          case Some((delayOf, cyclicOf)) =>
            table.ids
              .filter(id => delayOf(id) > 0)
              .groupBy(id => (delayOf(id), cyclicOf(id)))
              .toIndexedSeq
              .flatMap { case ((delay, cyclic), ids) =>
                val shift = positions(durations, delay, cyclic)
                ids.map(id => (id, port) -> shift)
              }
      }.toMap

  /** Refuse a delay PyPSA's own consistency check refuses.
    *
    * `n.optimize` runs `consistency_check` before building, and
    * `check_dispatch_delays` is strict by default — so a negative delay, or one
    * at least as long as the horizon, raises there rather than being solved.
    * Reproducing the refusal keeps the two implementations agreeing about which
    * networks have an answer, which is the same contract as agreeing about the
    * answer itself.
    *
    * The optimiser would not refuse them on its own. A negative delay is grouped
    * as immediate by `_iter_balance_args`, and one longer than the horizon leaves
    * every target invalid, so the link silently becomes either instantaneous or
    * inert.
    */
  def reject(network: Network, refuse: String => Nothing): Unit =
    val horizon = network.snapshots.indices.map(t => network.weighting("generators", t)).sum
    network.tables.values.foreach { table =>
      // Branches only, and by role rather than by name. `branchPorts` throws on
      // a table with no `bus0` column, which every one-port component is, so the
      // filter is load-bearing rather than an optimisation.
      val branch = Role.of(table.spec) match
        case Role.PassiveBranch | Role.ControllableBranch => true
        case _                                            => false
      if table.size > 0 && branch then
        Topology.branchPorts(table).filter(_ != "bus0").foreach { port =>
          configured(table, port).foreach { (delayOf, cyclicOf) =>
            table.ids.foreach { id =>
              val delay = delayOf(id)
              if delay < 0 then
                refuse(
                  s"${table.spec.name} '$id' has ${delayAttribute(port)} = $delay; PyPSA's " +
                    "consistency check rejects a negative delay, and its optimiser would treat " +
                    "it as no delay at all"
                )
              if delay > 0 && delay >= horizon then
                refuse(
                  s"${table.spec.name} '$id' has ${delayAttribute(port)} = $delay, which is at " +
                    s"least the horizon of $horizon snapshot weighting unit(s); PyPSA's " +
                    "consistency check rejects that, and nothing the link carries would ever arrive"
                )
              if delay > 0 && cyclicOf(id) && !(horizon > 0.0) then
                refuse(
                  s"${table.spec.name} '$id' has ${delayAttribute(port)} = $delay and wraps, but " +
                    s"the snapshot weightings total $horizon; there is no horizon to wrap around"
                )
            }
          }
        }
    }

  /** `delay`/`cyclic_delay` for `bus1`, `delay<i>`/`cyclic_delay<i>` after it.
    *
    * `None` when the table declares no delay for this port, which is how PyPSA
    * reads it too — `_get_delay_config` falls back to `(0, True)` on a missing
    * column rather than erroring.
    *
    * The suffix rule is [[Topology.portEfficiency]]'s, and for the same reason:
    * a port enumerated without the attribute that goes with it is silently given
    * the default, which for a delay means delivering the energy at once.
    */
  private def configured(
      table: ComponentTable,
      port: String,
  ): Option[(String => Int, String => Boolean)] =
    val delay  = delayAttribute(port)
    val cyclic = if port == "bus1" then "cyclic_delay" else s"cyclic_delay${port.drop(3)}"
    // `int` and `bool` fall through to `spec.require`, which throws on a column
    // that is neither declared nor present -- and `delay2` and later are custom
    // columns rather than schema attributes, exactly as `efficiency2` is.
    if !declares(table, delay) then None
    else
      // PyPSA's default, and the one flag here that defaults on.
      val cyclicOf: String => Boolean =
        if declares(table, cyclic) then id => table.bool(cyclic, id) else _ => true
      Some((id => table.int(delay, id), cyclicOf))

  private def delayAttribute(port: String): String =
    if port == "bus1" then "delay" else s"delay${port.drop(3)}"

  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)

  /** `_Multiport._delay_positions`, transcribed.
    *
    * `tau` holds the elapsed time at which each snapshot begins, so `tau(0)` is
    * 0 and the last snapshot's own duration is never part of it.
    */
  private def positions(durations: IndexedSeq[Double], delay: Int, cyclic: Boolean): Shift =
    val n     = durations.length
    val tau   = new Array[Double](n)
    var total = 0.0
    var i     = 0
    while i < n do
      tau(i) = total
      total += durations(i)
      i += 1

    val source = new Array[Int](n)
    val valid  = new Array[Boolean](n)
    var t      = 0
    while t < n do
      val shifted = tau(t) - delay
      val at =
        if cyclic then
          // `np.mod`, which follows the sign of the divisor rather than of the
          // dividend as Scala's `%` does.
          val wrapped = shifted % total
          valid(t) = true
          if wrapped < 0.0 then wrapped + total else wrapped
        else
          valid(t) = shifted >= 0.0
          shifted
      // `np.searchsorted(tau, at, side="right") - 1`, then clipped: the latest
      // snapshot beginning at or before `at`. The clip only ever matters where
      // `valid` is false, and those entries are discarded.
      //
      // Binary, not a scan back from the end. A wrapped source is not monotone
      // in `t`, so there is no incremental shortcut to fall back on, and a
      // year-long horizon would make the scan quadratic.
      var lo = 0
      var hi = n - 1
      while lo < hi do
        val mid = (lo + hi + 1) >>> 1
        if tau(mid) <= at then lo = mid else hi = mid - 1
      source(t) = lo
      t += 1

    Shift(source, valid)
