package org.noaidi.lopf

import org.noaidi.network.*

/** Commitment, which this model does not have and did not say so.
  *
  * A committable unit has a binary on/off status per snapshot. When off it
  * produces nothing; when on it must produce at least `p_min_pu · p_nom`, and it
  * pays `start_up_cost` and `shut_down_cost` for each transition and must
  * respect `min_up_time` and `min_down_time`. That is a mixed-integer model.
  * [[Lopf]] builds a linear one, and [[UnitCommitment]] builds the other.
  *
  * The separation was already the stated design — `Expansion`'s refusal of a
  * unit that is both committable '''and''' extendable said so in as many words.
  * What no code said is what happens to a unit that is merely committable, and
  * the answer was that [[Lopf]] solved it as though the flag were absent.
  *
  * ==Which is wrong in both directions==
  *
  * Worth stating, because the other findings in this port all erred one way.
  * Ignoring the status does not relax the problem, it '''replaces''' it:
  *
  *   - `p_min_pu` becomes a floor the unit can never leave, so a unit that
  *     should shut down is held at its minimum output at every snapshot. That
  *     makes the answer '''dearer''' than the truth, and on a network whose
  *     load falls below the sum of the minima it makes a feasible problem
  *     '''infeasible'''.
  *   - `start_up_cost`, `shut_down_cost`, `min_up_time` and `min_down_time` are
  *     dropped outright, which makes it '''cheaper'''.
  *
  * On the `unit-commitment` fixture the first effect dominates: solved as a
  * linear program it costs 18,500 against PyPSA's 17,000, with `mid` pinned at
  * its 30 MW minimum through four snapshots where the commitment solution has it
  * off. A number 9% high, reported `Optimal`.
  *
  * ==Every committable unit, not only the ones where it bites==
  *
  * A unit with `committable = True` and every commitment attribute left at its
  * default — no minimum output, no transition costs, no minimum times — really
  * does have the same optimum either way, and refusing it is a refusal this
  * model did not strictly need to make.
  *
  * It is refused anyway. Deciding that a commitment is vacuous means getting six
  * attributes right at once, several of them `static or series`, and being wrong
  * in the permissive direction returns a plausible number rather than an error —
  * which is the failure this whole line of work exists to stop. A spurious
  * refusal is loud, and the caller has two ways out: [[UnitCommitment]], or
  * clearing the flag they did not need. Being wrong quietly has none.
  */
object Commitment:

  /** Refuse any committable entity.
    *
    * Swept over every table declaring the attribute rather than over `Generator`
    * by name: PyPSA declares `committable` on Link and Process too, and a third
    * literal copy of the component list is one more place to miss when a class is
    * added.
    */
  def reject(network: Network, refuse: String => Nothing): Unit =
    rejectCommittable(network, refuse)
    rejectMaintainable(network, refuse)

  /** Refuse any maintainable entity.
    *
    * PyPSA 1.3.0 added maintenance scheduling, and it is the same shape as
    * commitment one attribute over. `maintainable` turns an entity's
    * availability into a binary schedule: `maintenance_events` outages of
    * `maintenance_duration` snapshots each, over which output is capped at
    * `maintenance_pu` rather than `p_max_pu`. Placing those outages is an integer
    * decision, so it is a mixed-integer model for the same reason a commitment
    * is, and [[Lopf]] builds a linear one.
    *
    * Ignoring the flag errs in the '''cheap''' direction, which is the one this
    * port refuses on sight rather than reasons about: a unit that must stand down
    * for some window is instead available at every snapshot, so the answer comes
    * out below the truth and reports `Optimal`. [[UnitCommitment]] does not model
    * it either, so unlike the committable case there is no second path to name --
    * the remedy is to clear the flag, or to keep the maintenance out of the
    * network handed here.
    *
    * Swept over every table declaring the attribute, as the committable check is
    * and for the same reason: PyPSA puts `maintainable` on Generator, Link and
    * Process.
    */
  private def rejectMaintainable(network: Network, refuse: String => Nothing): Unit =
    network.tables.values.foreach { table =>
      if table.spec.attribute("maintainable").isDefined && table.static.contains("maintainable")
      then
        table.ids.foreach { id =>
          if table.bool("maintainable", id) then
            refuse(
              s"${table.spec.name} '$id' is maintainable, so PyPSA schedules maintenance_events " +
                "outages of maintenance_duration snapshots against it, capped at maintenance_pu; " +
                "placing them is an integer decision and this builds a linear model, which would " +
                "leave the entity available at every snapshot and answer below the truth"
            )
        }
    }

  private def rejectCommittable(network: Network, refuse: String => Nothing): Unit =
    network.tables.values.foreach { table =>
      if table.spec.attribute("committable").isDefined && table.static.contains("committable") then
        table.ids.foreach { id =>
          if table.bool("committable", id) then
            // The remedy is qualified rather than a bare "use UnitCommitment".
            // That model is formulated without branch flows, so it refuses any
            // network carrying a branch, storage or a global constraint -- which
            // is most of them, and every network that can hold a committable
            // Link at all. Naming it without that caveat sends the reader to a
            // path that refuses them a second time for an unrelated reason.
            refuse(
              s"${table.spec.name} '$id' is committable, which is a binary status per snapshot and " +
                "so a mixed-integer model; this builds a linear one, and solving it with the flag " +
                "ignored holds the unit at its p_min_pu floor when it should be off. Clear the flag " +
                "if the commitment is not wanted, or use UnitCommitment -- which models a single " +
                "bus and so takes no network with branches"
            )
        }
    }
