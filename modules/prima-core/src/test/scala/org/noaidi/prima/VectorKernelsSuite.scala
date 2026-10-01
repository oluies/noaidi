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
