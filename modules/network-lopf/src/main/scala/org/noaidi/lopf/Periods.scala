package org.noaidi.lopf

import org.noaidi.network.*

/** Multi-investment periods, which were read as a single period.
  *
  * A multi-period network optimises several build years at once. Its snapshots
  * are `(period, timestep)` pairs rather than plain labels; an asset is active
  * only in the periods between its `build_year` and the end of its `lifetime`;
  * costs carry a per-period weighting and a discount factor; and a carrier's
  * `max_growth` limits how fast capacity may be added between periods.
  *
  * None of that is built here. What made it worth a refusal rather than a line
  * on a list of gaps is that '''nothing said so'''. `investment_periods.csv` sat
  * in the reader's set of non-component files and no code read it, so a
  * multi-period network arrived at the builder indistinguishable from an
  * ordinary one and was solved as though every asset existed from the start.
  *
  * ==What that cost==
  *
  * On a two-period network whose cheap generator has `build_year = 2040`, PyPSA
  * spends 17,000: the expensive unit carries the whole of 2030 because the cheap
  * one does not exist yet. This port returned '''2,000''', running the cheap
  * generator ten years before it was built, and reported `Optimal`.
  *
  * The reader compounded it. With `period` and `timestep` columns and no
  * `snapshot` column, the label was taken from the first column after the
  * index — so four snapshots came back labelled `2030, 2030, 2040, 2040`, two
  * pairs of duplicates, and `timestep` was parsed as though it were a weighting.
  *
  * ==Refused, not approximated==
  *
  * There is no conservative reading available. Ignoring the periods drops the
  * build-year restriction, which makes the answer cheaper than the truth; and
  * the discounting and period weightings move it in the other direction, so the
  * error does not even have a reliable sign. Implementing it properly is a
  * feature, not a fix, and it needs its own goldens.
  */
object Periods:

  /** Refuse a network that declares investment periods. */
  def reject(network: Network, refuse: String => Nothing): Unit =
    if network.investmentPeriods.nonEmpty then
      val periods = network.investmentPeriods.mkString(", ")
      refuse(
        s"network declares investment period(s) $periods; assets become active by build_year and " +
          "lifetime, costs are weighted and discounted per period, and none of that is modelled " +
          "here. Solving it as a single period runs assets before they are built"
      )
