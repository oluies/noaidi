package org.noaidi.lopf

import org.noaidi.network.*

/** Which snapshot a storage chain reaches back to, across four flags that interact.
  *
  * The storage-unit and store energy balances each couple a snapshot to the one before it,
  * and the only thing that differs between a horizon-wide cycle, a per-period cycle, a
  * horizon-wide initial level and a per-period one is '''which''' snapshot that is — or
  * whether there is one at all, in which case the initial level moves to the right-hand
  * side. Four flags, one answer, and two call sites that have to agree about it.
  *
  * ==The four, and the precedence between them==
  *
  * PyPSA's `define_storage_unit_constraints` treats an asset as per-period when
  * '''either''' per-period flag is set:
  *
  * {{{
  * per_period = cyclic_state_of_charge_per_period | state_of_charge_initial_per_period
  * }}}
  *
  * and then, at a period's first snapshot:
  *
  *   - `cyclic_state_of_charge_per_period` wraps to that '''period's''' last snapshot
  *   - `state_of_charge_initial_per_period` alone starts the period from
  *     `state_of_charge_initial`, which therefore enters the right-hand side once '''per
  *     period''' rather than once per horizon
  *   - both set: the cyclic one wins and the initial level is ignored, which upstream warns
  *     about rather than refuses
  *
  * Being per-period also overrides the horizon-wide `cyclic_state_of_charge`, warned about
  * the same way. So the flags form a precedence rather than a set of independent options,
  * and reading them as independent is the mistake the refusal this replaces made in the
  * other direction: it listed two flags and covered two, which reads as if the other two
  * were handled.
  *
  * ==Why this is a chain and not a mask==
  *
  * The rows are emitted over an asset's '''active''' snapshots, so `active(i - 1)` is the
  * previous snapshot the asset existed at and not simply `t - 1`. That is PyPSA's
  * `soc.where(active).ffill(...).roll(snapshot=1).ffill(...)`, and the reason is in
  * `Lopf.build`: a row emitted at an inactive snapshot against pinned columns collapses to
  * `eff_stand · soc(t-1) = 0` and forces the unit empty at its last active snapshot.
  *
  * On a per-period asset the period's first snapshot and the asset's first active snapshot
  * in that period are the same thing, because activity is decided per period — an asset
  * active in a period is active at every snapshot of it. PyPSA's masks are built from the
  * window's period starts rather than from the asset's activity and the two coincide for
  * that reason, which is worth knowing before anyone changes `Periods.activeIn` to vary
  * within a period.
  */
object Cycling:

  /** The snapshot this row's `soc(t-1)` term refers to, or `None` for none.
    *
    * `None` means the chain starts here, and every caller spends that the same way: the
    * initial level moves to the right-hand side. On a per-period asset that happens once
    * per period, which is the whole content of `state_of_charge_initial_per_period`.
    *
    * `active` is the asset's active snapshots in order and `i` indexes into it.
    */
  def previous(
      network: Network,
      active: IndexedSeq[Int],
      i: Int,
      cyclic: Boolean,
      cyclicPerPeriod: Boolean,
      initialPerPeriod: Boolean,
  ): Option[Int] =
    // `if not n._multi_invest` upstream: the per-period branch is not reached at all on a
    // flat index, so a network setting either flag without periods behaves as though it had
    // not. Reproduced rather than refused, because upstream reads the flags only inside that
    // branch and so does nothing with them either.
    val perPeriod = network.isMultiPeriod && (cyclicPerPeriod || initialPerPeriod)

    if !perPeriod then
      if i > 0 then Some(active(i - 1))
      else if cyclic then Some(active.last)
      else None
    else
      val period  = network.periodOf(active(i))
      val isStart = i == 0 || network.periodOf(active(i - 1)) != period
      if !isStart then Some(active(i - 1))
      // The cyclic flag first, because it wins when both are set.
      else if cyclicPerPeriod then active.filter(s => network.periodOf(s) == period).lastOption
      else None

  /** One boolean attribute, false where the column is absent.
    *
    * Read through the column rather than the schema default, for the reason
    * [[Storage.isCyclic]] gives: a network that never sets the attribute has no column at
    * all, and reading a missing one as anything but false would make every asset
    * per-period. The callers pass the attribute name because a store spells all four of
    * these differently from a storage unit.
    */
  def flag(table: ComponentTable, attribute: String, id: String): Boolean =
    table.static.contains(attribute) && table.bool(attribute, id)
