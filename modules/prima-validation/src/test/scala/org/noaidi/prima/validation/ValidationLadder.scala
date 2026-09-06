package org.noaidi.prima
package validation

/** The instance ladder and the gap measure, defined once.
  *
  * `Report` publishes a worst-gap figure that `OracleAgreementSuite` is supposed
  * to enforce. That only holds if both use the same instances and the same
  * formula — and previously both were copy-pasted, so adding an instance to the
  * report or changing the scale factor in one place would have decoupled the
  * enforced bound from the published number without any signal. That is exactly
  * the failure the enforcement was added to prevent, so the shared definition
  * lives here and both call it.
  */
object ValidationLadder:

  /** Named instances, in the order the report prints them.
    *
    * `conclusive` rather than `optimal`, so the infeasible and unbounded
    * fixtures appear in the report; consumers that need only the optimal ones
    * filter on status rather than on a second list.
    */
  final case class Entry(name: String, problem: LpProblem, expected: SolveStatus)

  /** Each instance carries the status it is expected to reach.
    *
    * Without it, a gap test can only filter on the *observed* status — and an
    * instance that regressed to `IterationLimit` would then vanish from the
    * comparison rather than fail it, leaving the published figure quietly
    * measured over fewer instances. The random ones have no other coverage at
    * these sizes, so that would go unnoticed.
    */
  val entries: Seq[Entry] =
    LpFixtures.conclusive.map(i => Entry(i.name, i.problem, i.expectedStatus)) ++
      Seq(
        Entry("random-60x30", LpFixtures.randomFeasible(1, 60, 10, 20, 0.25), SolveStatus.Optimal),
        Entry("random-200x120", LpFixtures.randomFeasible(2, 200, 40, 80, 0.10), SolveStatus.Optimal),
        Entry("random-600x400", LpFixtures.randomFeasible(3, 600, 120, 280, 0.04), SolveStatus.Optimal),
      )

  val instances: Seq[(String, LpProblem)] = entries.map(e => (e.name, e.problem))

  /** Relative objective disagreement, scaled so an optimum near zero does not
    * make the ratio meaningless.
    */
  def relativeGap(mine: Double, oracle: Double): Double =
    math.abs(mine - oracle) / math.max(1.0, math.abs(oracle))

  /** The worst gap at full precision, as both reports print it.
    *
    * Shared because the precision and the layout are what a reader diffs across
    * two job logs, so the two reports drifting apart here would be worse than
    * their prose drifting. `Report` carries the explanation of why both forms
    * are printed.
    *
    * The decimal round-trips rather than being exact: `%.17e` asks for more
    * digits than a `Double` carries and Java pads the shortest round-tripping
    * representation with zeros. The raw bits need no such caveat.
    */
  def exactly(worst: Double): String =
    f"  exactly: $worst%.17e  (raw bits 0x${java.lang.Double.doubleToRawLongBits(worst)}%016x)"

  /** The bound `OracleAgreementSuite` enforces and both documents quote.
    *
    * Set above the observed worst gap — 4.9e-10 on macOS/aarch64, 5.9e-10 on
    * Linux/x86_64 — with enough headroom to absorb that platform variation while
    * still catching an order-of-magnitude regression.
    */
  val worstGapBound: Double = 1e-8

  /** The machine that produced a report, printed at the top of one.
    *
    * NOTES now carries a `host` column on every table of iteration counts,
    * because those counts differ between platforms — the same reason
    * `Locale.ROOT` is forced before anything is formatted. A convention like
    * that needs support from the tool it applies to: without this line, someone
    * pasting a CI report into a two-row table has to remember which matrix leg
    * produced it, and the ladder dump already in NOTES says "Apple aarch64"
    * only because a human typed it.
    */
  def host: String =
    // OS, architecture and JVM, and not the Scala version: at run time
    // `util.Properties.versionNumberString` reports the 2.13 standard library
    // underneath Scala 3, which would put "2.13.16" at the top of a report
    // built with 3.7.4. What decides the numbers below it is the platform's
    // floating-point behaviour anyway.
    val p = (k: String) => sys.props.getOrElse(k, "unknown")
    s"host: ${p("os.name")} ${p("os.arch")}, JVM ${p("java.vm.name")} ${p("java.vm.version")}"

  /** Dense random instances of one shape over a spread of seeds.
    *
    * The ladder carries a single dense instance per size, which is enough to
    * show the shape of the method's behaviour and not enough to say anything
    * about its spread. It was taken for the latter once — a mixed-precision
    * result on `random-600x400` alone was published as a property of reduced
    * precision — and the instance turned out to be an outlier. So the sweep
    * exists to keep a claim about dense random LPs from resting on one draw.
    *
    * `random-600x400` is `seed = 3` of this family, so the ladder entry and the
    * sweep agree by construction rather than by two similar-looking calls.
    */
  val denseSeeds: Seq[Int] = 1 to 10

  def denseSpread: Seq[(String, LpProblem)] =
    denseSeeds.map(seed => s"dense-seed-$seed" -> LpFixtures.randomFeasible(seed, 600, 120, 280, 0.04))
