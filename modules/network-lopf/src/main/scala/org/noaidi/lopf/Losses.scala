package org.noaidi.lopf

import org.noaidi.network.*

/** The standing-loss check, shared by [[Storage]] and [[Stores]].
  *
  * Both energy balances raise `1 − standing_loss` to the power of the elapsed
  * hours, and both were checking only the static column while reading the value
  * per snapshot. `standing_loss` is `static or series` in PyPSA, so a
  * `<component>-standing_loss.csv` carrying 1.5 passed the guard and produced
  * `math.pow(1.0 − 1.5, 2.0) = +0.25` — a store that *gains* energy while idle,
  * solved and reported `Optimal`. At a non-integer elapsed-hours value the same
  * expression is NaN instead, which at least fails loudly.
  *
  * One definition rather than two, because the two copies had already drifted
  * into the same bug independently.
  */
object Losses:

  /** Refuse a standing loss outside `[0, 1)` at any snapshot it is read for.
    *
    * Checks the series as well as the static value, which is what the balance
    * reads. `refuse` builds the exception so each caller keeps its own type.
    */
  def rejectStandingLoss(
      table: ComponentTable,
      component: String,
      refuse: String => Nothing,
  ): Unit =
    def check(id: String, value: Double, where: String): Unit =
      if !(value >= 0.0 && value < 1.0) then
        refuse(
          s"$component '$id' has standing_loss = $value$where, which is not a fraction below 1; " +
            "raised to the elapsed hours that is a gain rather than a loss"
        )

    table.ids.foreach(id => check(id, table.float("standing_loss", id), ""))

    // The per-snapshot overrides, which is where the balance actually reads from
    // whenever an entity has them.
    table.series.get("standing_loss").foreach { series =>
      series.entities.foreach { id =>
        (0 until series.snapshotCount).foreach { t =>
          series.get(id, t).foreach(value => check(id, value, s" at snapshot $t"))
        }
      }
    }
