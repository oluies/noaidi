package org.noaidi.prima

import org.noaidi.prima.kernels.{ScalaKernels, VectorKernels}

// Split out of `KernelsSuite.scala` so the Scala.js build can exclude the SIMD backend
// without losing the contract suite with it. That file holds four suites and only these
// two touch `jdk.incubator.vector`; excluding the whole file took `ScalaKernelsSuite` and
// `Float32KernelsSuite` out of the cross-platform run as well -- the kernel contract, and
// the float32 path in particular, which is the code most likely to differ on a platform
// where `Float` arithmetic goes through `Math.fround`.

/** The SIMD backend against the same contract as every other.
  *
  * `assume` rather than a hard failure when the module is absent: this suite
  * runs wherever the build runs, and `--add-modules=jdk.incubator.vector` is set
  * for this project's forked test JVM but cannot be guaranteed for someone
  * running the suite another way. A skip says "not measured here"; a failure
  * would say "the backend is broken", which would be a lie about the code.
  *
  * The reduction cases are the ones that make this more than a copy of
  * `ScalaKernelsSuite`: `dot` and `squaredNorm` accumulate lane-wise and reduce
  * at the end, so they sum in a different order from the reference and are held
  * to the contract's tolerance rather than to equality.
  */
class VectorKernelsSuite extends KernelContractSuite("scala-vector"):
  override def munitIgnore: Boolean = !VectorKernels.isAvailable
  def newKernels(): kernels.Kernels = VectorKernels()

  test("the vector backend advertises full double precision") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved")
    val k = VectorKernels()
    assert(k.capabilities.supportsFloat64)
    assert(!k.capabilities.requiresDoublePrecisionRefinement)
    // The lane count is in the name, so a report says which machine's width
    // produced it rather than leaving that to be inferred.
    assert(k.capabilities.name.startsWith("scala-vector-"), k.capabilities.name)
  }

  test("a length that is not a whole number of vectors is still exact") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved")
    // The tail is where a widened loop goes wrong, and it goes wrong silently:
    // every operation here writes the whole array, so a tail that was skipped
    // leaves whatever `allocate` put there, which is zero -- a plausible number.
    // Lengths are swept rather than sampled so that every remainder against the
    // machine's lane count is covered whatever that count is.
    //
    // Both coverages, because they widen different operations: the default
    // widens three, and `widenEverything` widens the three `ScalaKernels` is
    // otherwise left to. Sweeping only the default would leave the second set's
    // tails untested on every machine.
    val ref = ScalaKernels()
    Seq(VectorKernels(), VectorKernels.widenEverything()).foreach { vector =>
      val who = vector.capabilities.name
      try
        (0 to 40).foreach { n =>
          val x  = Array.tabulate(n)(i => 1.0 + i * 0.25)
          val y  = Array.tabulate(n)(i => 2.0 - i * 0.125)
          val a  = new Array[Double](n)
          val b  = new Array[Double](n)
          vector.axpby(1.5, x, -0.5, y, a)
          ref.axpby(1.5, x, -0.5, y, b)
          assertEquals(a.toSeq, b.toSeq, s"$who: axpby at length $n")

          vector.scale(0.75, x, a)
          ref.scale(0.75, x, b)
          assertEquals(a.toSeq, b.toSeq, s"$who: scale at length $n")

          // `primalStep` has the only nontrivial tail -- two blends and a scalar
          // `if/else if` that duplicates the clamp -- and bounds chosen so the
          // clamp fires on some elements rather than passing everything through.
          val cost  = Array.tabulate(n)(i => 0.5 - i * 0.05)
          val ktY   = Array.tabulate(n)(i => i * 0.1)
          val lower = Array.tabulate(n)(i => if i % 3 == 0 then 1.0 else Double.NegativeInfinity)
          val upper = Array.tabulate(n)(i => if i % 4 == 0 then 2.0 else Double.PositiveInfinity)
          vector.primalStep(x, ktY, cost, lower, upper, 0.5, a)
          ref.primalStep(x, ktY, cost, lower, upper, 0.5, b)
          assertEquals(a.toSeq, b.toSeq, s"$who: primalStep at length $n")

          assertEqualsDouble(vector.dot(x, y), ref.dot(x, y), 1e-12, s"$who: dot at length $n")
          assertEqualsDouble(
            vector.squaredNorm(x), ref.squaredNorm(x), 1e-12, s"$who: squaredNorm at length $n")
        }
      finally vector.close()
    }
    ref.close()
  }

  test("the dual projection splits at the equality boundary wherever it falls") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved")
    // Against `widenEverything`, and that is the point of writing it that way.
    // The default coverage delegates `dualStep` to `ScalaKernels`, so the
    // earlier version of this test compared `ScalaKernels` with itself across
    // all 38 splits and was true by construction -- it asserted nothing about
    // the widening it claimed to cover.
    //
    // `dualStep` applies two different projections to two ranges of one array,
    // and the boundary is `numEqualities`, a number with no reason to land on a
    // vector boundary. The widened version stops its first run at the last whole
    // vector *inside* the equality block and starts the second unaligned, so
    // every split has to be checked and not just the aligned ones.
    val vector = VectorKernels.widenEverything()
    val ref    = ScalaKernels()
    try
      val n     = 37
      val y     = Array.tabulate(n)(i => 0.5 - i * 0.1)
      val kxBar = Array.tabulate(n)(i => i * 0.2 - 2.0)
      val rhs   = Array.tabulate(n)(i => 1.0 - i * 0.05)
      (0 to n).foreach { eq =>
        val a = new Array[Double](n)
        val b = new Array[Double](n)
        vector.dualStep(y, kxBar, rhs, 0.7, eq, a)
        ref.dualStep(y, kxBar, rhs, 0.7, eq, b)
        assertEquals(a.toSeq, b.toSeq, s"dualStep with $eq equality rows")
      }
    finally
      vector.close()
      ref.close()
  }

  test("an infinite bound passes an infinite candidate through") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved")
    // The property `ScalaKernels` writes its clamp as two comparisons to
    // preserve, restated against the blended version. `min`/`max` would give the
    // same answer here; what would not is getting the blend order wrong, so a
    // degenerate box with `lower` above `upper` is included -- the scalar
    // `if/else if` returns `lower` there, and the blends have to agree.
    val vector = VectorKernels()
    val ref    = ScalaKernels()
    try
      val inf   = Double.PositiveInfinity
      val x     = Array(inf, -inf, 1.0, 5.0, 0.0, 2.0, -3.0, 9.0, 4.0)
      val ktY   = new Array[Double](x.length)
      val cost  = new Array[Double](x.length)
      val lower = Array(-inf, -inf, 0.0, 10.0, -inf, 1.0, -1.0, -inf, 0.0)
      val upper = Array(inf, inf, inf, 0.0, 3.0, 1.5, inf, 2.0, inf)
      val a     = new Array[Double](x.length)
      val b     = new Array[Double](x.length)
      vector.primalStep(x, ktY, cost, lower, upper, 0.5, a)
      ref.primalStep(x, ktY, cost, lower, upper, 0.5, b)
      assertEquals(a.toSeq, b.toSeq)
      assertEquals(a(0), inf, "an infinite candidate under an infinite bound")
    finally
      vector.close()
      ref.close()
  }

/** The widened coverage against the whole contract, not just the tail cases.
  *
  * `VectorKernelsSuite` supplies `VectorKernels()`, under which `axpby`, `scale`
  * and `dualStep` delegate to `ScalaKernels` -- so every behavioural case in
  * `KernelContractSuite` was exercising the reference for those three, and the
  * widened implementations had only the length sweep and the boundary test.
  *
  * The gap that matters is aliasing. `Pdhg` writes an output array it is also
  * reading, in the hot loop and in the averaging accumulator, and none of the
  * cases added for widening pass an aliased pair. One declaration runs the whole
  * contract against the new paths instead.
  */
class VectorKernelsWidenedSuite extends KernelContractSuite("scala-vector-all"):
  override def munitIgnore: Boolean = !VectorKernels.isAvailable
  def newKernels(): kernels.Kernels = VectorKernels.widenEverything()

/** One search, on backends that sum in different orders.
  *
  * CI runs this solver on two JDKs, and on one commit the two disagreed: 423
  * nodes on one and a budget-exhausting search on the other, not reproduced on
  * the next commit. Nothing in this project's suites would have caught it. The
  * determinism the job claims holds only with the JDK and the runner's vector
  * width fixed, and no test varied either, so the one property the claim is
  * about was the one property never measured.
  *
  * A JDK cannot be varied from inside a test. What changes with it can be: the
  * order the reductions sum in. [[ScalaKernels]] accumulates serially,
  * [[VectorKernels]] lane-wise at the machine's width, and
  * [[VectorKernels.widenEverything]] widens three further operations on top of
  * that -- three summation orders in one JVM, which is the same class of
  * difference a JDK or a lane count makes.
  *
  * What they may be held to is the '''answer''', and measurement is what says
  * so rather than caution: on the 400-variable equality chain below the serial
  * backend converges in 21,696 iterations and the vector one in 21,632, with
  * root objectives of -1.9e-9 against -3.4e-10. Equal answers, different
  * arithmetic. So these tests hold the backends to the answer and to a bounded
  * amount of '''work''' -- the bound being what a divergence like CI's breaks,
  * at a hundred times over -- and reserve bit-for-bit equality for repeating a
  * solve on one backend, where it is the right bar and nothing weaker would do.
  *
  * Every assertion carries [[report]] as its clue, so a failure arrives as the
  * whole comparison: each backend's root status and iteration count, tree size
  * and objective with raw bits, then each pairwise difference down to the first
  * primal coordinate that differs and how many ulps apart it is. A bare
  * "expected 305, got 1041" would start the investigation CI already failed
  * once for want of; this finishes it.
  */
class SearchReproducibilitySuite extends munit.FunSuite:

  private val lp     = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)
  private val params = BnbParams(lp = lp, maxNodes = 50_000)

  /** A branching instance: 305 nodes, so the tree's shape is what it reports. */
  private def knapsack16: (LpProblem, Set[Int]) =
    val values  = Seq(41.0, 50, 49, 59, 45, 47, 12, 33, 21, 44, 51, 19, 28, 37, 40, 25)
    val weights = Seq(31.0, 27, 12, 34, 22, 19, 8, 16, 11, 24, 29, 9, 14, 20, 23, 13)
    val b       = LpProblem.builder(values.length)
    values.zipWithIndex.foreach((v, i) => b.objectiveCoefficient(i, -v))
    values.indices.foreach(i => b.bounds(i, 0.0, 1.0))
    b.lessThan(weights.zipWithIndex.map((w, i) => (i, w)), 120.0)
    (b.build()._1, values.indices.toSet)

  /** A hard relaxation: one node, 21,696 iterations, so the arithmetic is what
    * it reports. A chain of equalities pinning every variable to 1 -- a trivial
    * answer reached the slow way, which is exactly where summation order shows.
    */
  private def equalityChain: (LpProblem, Set[Int]) =
    val n = 400
    val b = LpProblem.builder(n)
    (0 until n).foreach { i =>
      b.objectiveCoefficient(i, if i % 2 == 0 then 1.0 else -1.0)
      b.bounds(i, 0.0, 10.0)
    }
    b.equalityConstraint(Seq((0, 1.0)), 1.0)
    (0 until n - 1).foreach(i => b.equalityConstraint(Seq((i, 1.0), (i + 1, -1.0)), 0.0))
    (b.build()._1, (0 until n).toSet)

  private def instances = Seq("knapsack-16" -> knapsack16, "equality-chain" -> equalityChain)

  /** The backends available here, serial first so it is the reference column.
    *
    * `widenEverything` is included because it is a third summation order and
    * costs one more solve: the default widens three operations and it widens
    * three more, so a disagreement between those two localises the arithmetic
    * to the second set without any further bisection.
    */
  private def backends: Seq[() => kernels.Kernels] =
    Seq[() => kernels.Kernels](() => ScalaKernels()) ++
      (if VectorKernels.isAvailable then
         Seq[() => kernels.Kernels](() => VectorKernels(), () => VectorKernels.widenEverything())
       else Nil)

  private final case class Observation(
      backend: String,
      rootStatus: SolveStatus,
      rootIterations: Int,
      rootObjective: Double,
      status: MilpStatus,
      nodes: Int,
      unproven: Int,
      objective: Double,
      primal: IArray[Double],
  )

  /** The root relaxation and then the search, on one backend.
    *
    * The root relaxation is solved separately and reported because it is the
    * proximate cause of a tree divergence: a relaxation that takes a different
    * number of iterations returns a slightly different iterate, which can
    * choose a different branching variable, and from there the two searches are
    * exploring different trees. Reporting only the node count would show the
    * symptom with the cause left to guess at.
    */
  private def observe(problem: LpProblem, integers: Set[Int], make: () => kernels.Kernels) =
    val k = make()
    try
      val root = Pdhg.solveWith(problem, lp, k)
      val milp = BranchAndBound.solveWith(problem, integers, params, k)
      Observation(
        backend = k.capabilities.name,
        rootStatus = root.status,
        rootIterations = root.iterations,
        rootObjective = root.objectiveValue,
        status = milp.status,
        nodes = milp.nodesExplored,
        unproven = milp.unprovenNodes,
        objective = milp.objectiveValue,
        primal = milp.primal,
      )
    finally k.close()

  private def bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

  /** How far apart two doubles are in representable steps.
    *
    * Reported alongside the absolute difference because on numbers this small
    * the absolute difference does not say whether two backends landed next to
    * each other or in different neighbourhoods: -1.9e-9 and -3.4e-10 differ by
    * about 1.5e-9, which reads as agreement until the same gap is read as the
    * 1.15e16 representable steps it actually spans.
    */
  private def ulps(a: Double, b: Double): String =
    if java.lang.Double.compare(a, b) == 0 then "same bits"
    else if a.isNaN || b.isNaN then "NaN involved"
    else if !a.isFinite || !b.isFinite then "non-finite"
    else
      val (ia, ib) = (bits(a), bits(b))
      if (ia < 0) != (ib < 0) then s"opposite signs, |d|=${math.abs(a - b)}"
      else s"${math.abs(ia - ib)} ulp, |d|=${math.abs(a - b)}"

  private def pad(s: String, n: Int): String =
    if s.length >= n then s else s + " " * (n - s.length)

  /** A ratio for the report, in a fixed locale.
    *
    * `f` interpolation follows the default locale, which on the machine this was
    * written on renders 0.997 as "0,997" -- a decimal comma inside a diagnostic
    * that a reader elsewhere would have to decode. `MilpReport` sets
    * `Locale.ROOT` for the same reason.
    */
  private def ratio(now: Double, was: Double): String =
    String.format(java.util.Locale.ROOT, "%.3fx", (now / math.max(1.0, was)).asInstanceOf[AnyRef])

  private def firstPrimalDifference(a: Observation, b: Observation): Option[String] =
    if a.primal.length != b.primal.length then
      Some(s"primal lengths ${a.primal.length} vs ${b.primal.length}")
    else
      a.primal.indices
        .find(i => java.lang.Double.compare(a.primal(i), b.primal(i)) != 0)
        .map { i =>
          val (x, y) = (a.primal(i), b.primal(i))
          f"primal[$i] ${x.toString}%-24s vs ${y.toString}%-24s" +
            f" 0x${bits(x)}%016x vs 0x${bits(y)}%016x  (${ulps(x, y)})"
        }

  /** The whole comparison, as the clue on every assertion below. */
  private def report(instance: String, obs: Seq[Observation]): String =
    val header = s"$instance: ${obs.length} backend(s)\n  " +
      pad("backend", 22) + pad("root", 20) + pad("nodes", 8) + pad("unproven", 10) +
      pad("milp", 10) + pad("objective", 26) + "bits"
    val rows = obs.map { o =>
      "  " + pad(o.backend, 22) +
        pad(s"${o.rootStatus}/${o.rootIterations}", 20) +
        pad(o.nodes.toString, 8) + pad(o.unproven.toString, 10) +
        pad(o.status.toString, 10) + pad(o.objective.toString, 26) +
        f"0x${bits(o.objective)}%016x"
    }
    val diffs = obs.drop(1).flatMap { o =>
      val a     = obs.head
      val lines = Seq.newBuilder[String]
      if a.status != o.status then lines += s"    milp status  ${a.status} vs ${o.status}"
      if a.rootStatus != o.rootStatus then
        lines += s"    root status  ${a.rootStatus} vs ${o.rootStatus}"
      if a.rootIterations != o.rootIterations then
        lines += s"    root iters   ${a.rootIterations} vs ${o.rootIterations}" +
          f" (${o.rootIterations - a.rootIterations}%+d, ${ratio(o.rootIterations.toDouble, a.rootIterations.toDouble)})"
      if java.lang.Double.compare(a.rootObjective, o.rootObjective) != 0 then
        lines += s"    root obj     ${a.rootObjective} vs ${o.rootObjective}  (${ulps(a.rootObjective, o.rootObjective)})"
      if a.nodes != o.nodes then
        lines += s"    nodes        ${a.nodes} vs ${o.nodes} (${ratio(o.nodes.toDouble, a.nodes.toDouble)})"
      if a.unproven != o.unproven then
        lines += s"    unproven     ${a.unproven} vs ${o.unproven}"
      if java.lang.Double.compare(a.objective, o.objective) != 0 then
        lines += s"    objective    ${a.objective} vs ${o.objective}  (${ulps(a.objective, o.objective)})"
      firstPrimalDifference(a, o).foreach(d => lines += s"    $d")
      val body = lines.result()
      if body.isEmpty then Seq(s"  ${a.backend} vs ${o.backend}: identical in every reported field")
      else s"  ${a.backend} vs ${o.backend}:" +: body
    }
    (header +: (rows ++ diffs)).mkString("\n")

  test("a search repeated on one backend reaches a bit-for-bit identical answer") {
    // The answer is where equality is the right bar: same code, same summation
    // order, same machine, so a difference in what comes back is state carried
    // between solves or a dependence on something that is not the problem -- a
    // reused buffer left dirty, a hash seed, iteration order over a `Set`. None
    // of those would announce itself; it would arrive as the next
    // unreproducible CI failure.
    //
    // The work is not held to that bar, and this test claimed it was until CI
    // disproved it. On an 8-lane x86_64 runner two consecutive solves of the
    // chain -- same backend, same instance -- took 25,728 and 24,704 iterations,
    // 402 and 386 evaluation intervals, and reached byte-identical answers from
    // both. A 2-lane machine shows none of it, which is why the claim survived
    // being written.
    //
    // So the iteration count is not reproducible even inside one JVM on some
    // hardware. That is worth stating plainly rather than asserting around: it
    // is the same quantity whose divergence between two JDKs on one commit sent
    // a search from 423 nodes to a budget-exhausting one. What this bounds
    // instead is how far it may move, which is the part that separates ordinary
    // variation from that failure.
    val slack = 1.5
    instances.foreach { (name, inst) =>
      val (problem, integers) = inst
      backends.foreach { make =>
        // Discarded. Including the JIT's own transition in a measurement of
        // reproducibility measures the compiler warming up rather than the
        // solver, and the first solve in a fresh JVM is the one run guaranteed
        // to be unlike the others.
        observe(problem, integers, make)

        val first  = observe(problem, integers, make)
        val second = observe(problem, integers, make)
        val clue   = report(s"$name, repeated", Seq(first, second))

        assertEquals(second.status, first.status, clue)
        assertEquals(second.nodes, first.nodes, clue)
        assertEquals(second.unproven, first.unproven, clue)
        assertEquals(bits(second.objective), bits(first.objective), clue)
        assertEquals(firstPrimalDifference(first, second), None, clue)
        assertEquals(second.rootStatus, first.rootStatus, clue)

        assert(second.rootIterations <= first.rootIterations * slack, clue)
        assert(first.rootIterations <= second.rootIterations * slack, clue)
      }
    }
  }

  test("backends that sum in different orders reach the same answer") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved, so there is one backend")
    instances.foreach { (name, inst) =>
      val (problem, integers) = inst
      val obs                 = backends.map(observe(problem, integers, _))
      val clue                = report(name, obs)
      val reference           = obs.head

      obs.foreach { o =>
        assertEquals(o.status, MilpStatus.Optimal, clue)
        assertEquals(o.status, reference.status, clue)
        assertEquals(o.unproven, 0, clue)

        // Held to a tolerance, because the measurement above says the arithmetic
        // genuinely differs: lane-wise reduction is not serial reduction and
        // never will be. Relative, so the same bar applies to an objective of
        // -271 and one of -1e-9.
        val scale = math.max(1.0, math.abs(reference.objective))
        assert(math.abs(o.objective - reference.objective) <= 1e-6 * scale, clue)

        // The relaxation's objective as well, and this is the one the tolerance
        // is actually for. On both instances here the integer objective comes
        // out exact -- -271 and 0 -- so holding only that would be an equality
        // test wearing a tolerance, and it would pass on a backend whose
        // relaxation had converged somewhere quite different. The relaxation is
        // where the arithmetic shows: -1.9e-9 against -3.4e-10 on the chain.
        val rootScale = math.max(1.0, math.abs(reference.rootObjective))
        assertEquals(o.rootStatus, SolveStatus.Optimal, clue)
        assert(math.abs(o.rootObjective - reference.rootObjective) <= 1e-6 * rootScale, clue)

        // The integer coordinates, though, are snapped to whole numbers before
        // they are reported, so there is no rounding left in them to excuse a
        // difference. A discrete optimum is a discrete optimum on every backend,
        // and two backends choosing different items is a disagreement about the
        // answer rather than about arithmetic -- which a tolerance on the
        // objective alone would hide completely whenever the instance has ties.
        integers.toSeq.sorted.foreach { j =>
          assertEquals(o.primal(j), reference.primal(j), clue)
        }
      }
    }
  }

  test("backends that sum in different orders do comparable work") {
    assume(VectorKernels.isAvailable, "jdk.incubator.vector not resolved, so there is one backend")
    // The test for CI's actual failure. There, one JDK took 423 nodes and the
    // other exhausted a 50,000-node budget: a factor of 118. Measured here the
    // backends sit at 1.00x on the tree and 1.003x on the iteration count, so a
    // factor of four is loose enough that ordinary rounding will not trip it and
    // tight enough that nothing resembling that divergence gets through.
    //
    // Floored, because a ratio between small counts says nothing: two searches
    // of three and seven nodes are not a 2.3x divergence in anything.
    val slack = 4.0
    val floor = 16
    instances.foreach { (name, inst) =>
      val (problem, integers) = inst
      val obs                 = backends.map(observe(problem, integers, _))
      val clue                = report(name, obs)
      val reference           = obs.head

      obs.foreach { o =>
        if math.max(o.nodes, reference.nodes) > floor then
          assert(o.nodes <= reference.nodes * slack, clue)
          assert(reference.nodes <= o.nodes * slack, clue)
        if math.max(o.rootIterations, reference.rootIterations) > floor then
          assert(o.rootIterations <= reference.rootIterations * slack, clue)
          assert(reference.rootIterations <= o.rootIterations * slack, clue)

        // Reaching the iteration cap is the state the whole chain starts from:
        // a relaxation that stops converging is what makes a node cost a
        // thousand times what it should, and what made one JDK's search
        // unbounded. Worth asserting directly rather than only through a ratio,
        // which two equally stuck backends would satisfy.
        assert(o.rootIterations < lp.maxIterations, clue)
        assert(o.nodes < params.maxNodes, clue)
      }
    }
  }
