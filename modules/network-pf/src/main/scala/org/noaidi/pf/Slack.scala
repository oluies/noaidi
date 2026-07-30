package org.noaidi.pf

import org.noaidi.network.{Network, Role, SubNetwork}

/** Which bus carries a sub-network's imbalance, and which generator absorbs it.
  *
  * PyPSA's rule is not the obvious one, and guessing it wrong produces a
  * plausible answer rather than an error — every voltage angle is measured
  * against the slack, so a different choice shifts the whole profile by a
  * constant while leaving the flows correct. The angles would then disagree with
  * PyPSA's for a reason that looks like a sign or scaling bug.
  *
  * The rule, in order:
  *
  *   1. A generator in the island whose `control` is "Slack" wins outright. This
  *      is how a network states its own choice.
  *   2. Otherwise the island's '''first generator in file order''', and the slack
  *      bus is that generator's bus. On `ac-dc-meshed` this makes Manchester the
  *      slack of {London, Manchester, Norwich} — not London, which is both first
  *      alphabetically and first in `buses.csv`.
  *   3. Only if the island has no generator at all, its first bus in '''file
  *      order'''. That is Norwich DC for the DC island, where sorted order would
  *      have said Bremen DC.
  *
  * Both fallbacks read file order, which [[SubNetwork]] does not carry:
  * `SubNetwork.buses` is sorted so that decomposition is deterministic. So the
  * order is recovered from the `Bus` and `Generator` tables, which preserve it.
  */
object Slack:

  /** The slack of one sub-network.
    *
    * `generator` is empty when the island has no generator — legal, and harmless
    * when the island is also balanced, which is the case for the DC island whose
    * only attachments are links at their default zero flow. The flows are
    * well-defined either way; what is missing is only somewhere to attribute the
    * slack power to, so this is reported rather than rejected. PyPSA warns in the
    * same situation.
    */
  final case class Choice(subNetwork: SubNetwork, bus: String, generator: Option[String])

  def of(network: Network, sub: SubNetwork): Choice =
    val members = sub.buses.toSet

    // Only generators, matching PyPSA: a load or a store cannot hold an angle
    // reference even though it attaches to a bus the same way.
    val candidates = network
      .table("Generator")
      .toIndexedSeq
      .flatMap(t => t.ids.filter(id => members.contains(t.string("bus", id))).map(id => (t, id)))

    val declared = candidates.find { (t, id) =>
      t.spec.attribute("control").isDefined && t.string("control", id) == "Slack"
    }

    declared.orElse(candidates.headOption) match
      case Some((table, id)) => Choice(sub, table.string("bus", id), Some(id))
      case None =>
        // File order, not `sub.buses` order. `firstInFileOrder` cannot come back
        // empty: every bus in a sub-network came from the Bus table.
        val bus = network
          .require("Bus")
          .ids
          .find(members.contains)
          .getOrElse(sub.buses.head)
        Choice(sub, bus, None)

  /** Slack choices for every sub-network, in the order [[Role]] decomposition
    * returns them.
    */
  def all(network: Network, subs: IndexedSeq[SubNetwork]): IndexedSeq[Choice] =
    subs.map(of(network, _))
