package org.noaidi.lopf

import org.noaidi.network.{ComponentTable, Network, Role}
import org.noaidi.prima.LpBuilder

/** A dispatch set point, which pins a variable the model would otherwise optimise.
  *
  * PyPSA's `define_fixed_operation_constraints`, applied to every operational variable by the
  * generic loop in `optimize.py:846`. Where `<attr>_set` holds a value, the variable is fixed
  * to it:
  *
  * {{{
  * p(a, t) = p_set(a, t)     for every snapshot a is active at and p_set is given for
  * }}}
  *
  * ==Which components reach here==
  *
  * `Generator` and the controllable branches. The other four of PyPSA's six are elsewhere:
  * `StorageUnit`'s three power set points and `Store`'s two are refused — [[Storage.reject]]
  * and [[Stores.reject]], with the reasoning — and `state_of_charge_set` is built inside the
  * storage energy balance, where its column already is.
  *
  * The two names PyPSA's own docstring adds, `Line (s)` and `Transformer (s)`, are '''not
  * attributes of anything'''. `component_attrs` carries only `s_nom_set` on those two, and
  * `define_fixed_operation_constraints` returns early when `attr_set not in
  * c.dynamic.keys()`, so the generic loop calls it for them and it does nothing. Established
  * by enumerating the `_set` columns of every `component_attrs` CSV, after reading the
  * docstring got it wrong once.
  *
  * A `Load`'s `p_set` is not a set point in this sense. It is the consumption that enters the
  * bus balance, read as data, which is why this selects by [[Role]] rather than refusing or
  * pinning everything that carries the column.
  *
  * ==A row, not a tightened bound==
  *
  * The alternative was to intersect `p_set` into the dispatch column's own bounds, the way
  * [[Lopf.activeBounds]] pins an inactive asset to `[0, 0]`. A row is right here and the
  * difference is visible exactly where it matters.
  *
  * PyPSA bounds a non-extendable dispatch with '''rows''' —
  * `define_operational_constraints_for_non_extendables` adds `>=` and `<=` against
  * `p_min_pu · p_nom` and `p_max_pu · p_nom` — and then adds this equality on top. So a
  * `p_set` above the unit's own ceiling makes upstream's LP infeasible. Intersecting bounds
  * instead gives `lo > hi` on a column, which is an empty range the builder rejects as a
  * malformed model: a crash where PyPSA has a verdict. Emitting the equality against the
  * existing bound reproduces the verdict, and the same holds for a `p_set` that conflicts
  * with a ramp limit, which is a row either way.
  *
  * It is also what `state_of_charge_set` already does — a single-term equality beside the
  * storage balance — and that is the one set point this model had built before this one.
  *
  * ==Masked by activity, which is the half that is not arithmetic==
  *
  * `mask = active & (~fix.isnull())`. Both halves matter and the first is the one with teeth:
  * a set point at a snapshot the asset does not exist at is '''dropped''', not applied. This
  * model pins an inactive asset's column to `[0, 0]`, so an equality emitted there would read
  * `0 = p_set` and make the LP infeasible — a verdict PyPSA does not reach, blamed on the
  * network rather than on this model. `fixed-dispatch-periods` is the fixture: its second
  * generator carries `p_set` at every snapshot and is built in the second period only, so the
  * unmasked reading is infeasible and the masked one is 20,400.
  */
object FixedDispatch:

  /** The tables whose `p_set` this builds: generators and controllable branches. */
  def tables(network: Network): IndexedSeq[ComponentTable] =
    network.tables.values.toIndexedSeq.filter { table =>
      table.size > 0 && (Role.of(table.spec) match
        case Role.ControllableBranch => true
        case Role.Attached           => table.spec.name == "Generator"
        case _                       => false)
    }

  /** Emit one equality per pinned `(entity, snapshot)`, or nothing where nothing is pinned.
    *
    * Every row is an equality, so this has to be called before the first inequality — see the
    * global-constraint block's note. It is called after the bus balance rather than before it
    * because `Sclopf` identifies those rows by index and inserting ahead of them would shift
    * every one.
    */
  def constrain(
      network: Network,
      columns: scala.collection.Map[(String, String, Int), Int],
      builder: LpBuilder,
      refuse: String => Nothing,
  ): Unit =
    // No `declares` filter, and that is measured rather than assumed. Every other builder
    // here guards an attribute read with "declared in the spec or present as a column", and
    // such a guard was written and then removed: `p_set` is schema-declared on `Generator`
    // and on every controllable branch, so the predicate is true for every table that
    // reaches this line. Dropping it changed no row across all 302 tests in this module. An
    // equivalent mutant is worse than no check, because it reads as coverage.
    tables(network).foreach { table =>
      val component = table.spec.name
      table.ids.foreach { id =>
        network.snapshots.indices.foreach { t =>
          val target = table.valueAt("p_set", id, t)
          // `p_set` defaults to NaN, and that default is what makes "not given"
          // distinguishable from "given as zero" at all. A zero `p_set` is a real constraint
          // holding the unit off, so a predicate written as "non-zero" would drop it --
          // `fixed-dispatch-periods` carries an empty cell for `old` beside a real value for
          // `new`, so the two readings are in one file.
          //
          // NaN is "not given" and is the default, so it is the common case rather than an
          // error. An infinity is neither: PyPSA has no null test that catches it, so it
          // reaches linopy as a right-hand side, and here it would reach the builder as one.
          // Refused by name rather than left to surface as an anonymous bad coefficient,
          // which is the same argument the snapshot-weighting guard makes.
          if target.isInfinite then
            refuse(
              s"$component '$id' sets p_set = $target at snapshot ${network.snapshotLabel(t)}. " +
                "It becomes the right-hand side of an equality on that dispatch, so it has to " +
                "be a finite number of MW."
            )
          // `active & ~isnull`, in that order of importance. See the class note: an equality
          // against a column pinned to zero is an infeasibility this model would be inventing.
          if target.isFinite && Periods.activeAt(network, table, id, t) then
            builder.equalityConstraint(Seq(columns((component, id, t)) -> 1.0), target)
        }
      }
    }
