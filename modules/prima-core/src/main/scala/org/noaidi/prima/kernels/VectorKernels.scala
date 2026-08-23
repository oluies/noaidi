package org.noaidi.prima
package kernels

import jdk.incubator.vector.{DoubleVector, VectorOperators, VectorSpecies}

/** The reference kernels with their dense loops widened to SIMD.
  *
  * `KernelSplit` measured a `scigrid-de` solve at 54.5% sparse and 45.5% dense,
  * and this repository had no SIMD anywhere: [[ScalaKernels]] is scalar `while`
  * loops by design, being the correctness oracle. So a little under half of a
  * solve was running one lane at a time for no reason other than that nothing had
  * been written to do otherwise. This is that half.
  *
  * ==Three operations, not six, and the reason is C2==
  *
  * The first version widened every dense loop and came out 2.9% faster than the
  * scalar reference, which is noise. Per operation it was not noise at all:
  *
  * {{{
  * dot          1.65x faster     axpby     1.44x slower
  * squaredNorm  1.64x faster     dualStep  1.30x slower
  * primalStep   1.27x faster     scale     2.17x slower
  * }}}
  *
  * The split is not arbitrary. HotSpot's SuperWord pass already auto-vectorises
  * a simple `while` loop over contiguous arrays, so the straight-line arithmetic
  * in `axpby`, `scale` and `dualStep` was *already* SIMD before this file existed
  * -- confirmed by running the reference under `-XX:-UseSuperWord`, where
  * `axpby` slows from 7.0 to 8.6 us. Against an already-vectorised loop the
  * Vector API only adds its own overhead.
  *
  * What C2 will not do is reassociate floating-point addition, because that
  * changes the answer -- so a reduction stays scalar however simple it looks.
  * `dot` and `squaredNorm` are where the win is, and `primalStep` joins them
  * because its clamp is a data-dependent branch per element, which SuperWord
  * also declines.
  *
  * So the three operations C2 already handles are left to it. This is a measured
  * division rather than a principled one, and it is measured on **two lanes**.
  *
  * `spmv` delegates unchanged: a CSR row is an indexed gather through a column
  * index buffer, which the Vector API can express but does not obviously win at,
  * and widening it is a separate question with its own measurement. Claiming it
  * here would be the pattern of asserting ahead of the evidence that this file
  * exists because of. `copy` is already `System.arraycopy`, intrinsified.
  *
  * ==The lane count is the variable that matters==
  *
  * `SPECIES_PREFERRED` is **2** on the machine these numbers come from -- Apple
  * Silicon, 128-bit NEON, two doubles. That is the narrowest useful width there
  * is, and it is why the losses above are losses: two lanes leave almost no
  * headroom to pay for the API's overhead.
  *
  * On x86_64 with AVX2 (four lanes) or AVX-512 (eight) the arithmetic changes and
  * `axpby` may well flip. Nothing here has measured that, so the division above
  * should be re-measured rather than trusted on a machine with wider vectors.
  * `KernelSplit` in `network-lopf` is the tool, and it prints the lane count in
  * the backend name for exactly this reason.
  *
  * ==Reductions do not associate==
  *
  * `dot` and `squaredNorm` accumulate into a vector of partial sums and reduce at
  * the end, so they add in a different order from the scalar loop and give
  * different last bits. That is not a defect being tolerated: [[ScalaKernels]]'s
  * own scaladoc records that a GPU reduces in tree order and will differ from any
  * host ordering, which is why `KernelContractSuite` asserts agreement within a
  * tolerance rather than exactly. This backend is the first CPU one to exercise
  * that, and `supportsFloat64` is true, so it is held to the tight tolerance.
  *
  * ==This class cannot be loaded without a JVM flag==
  *
  * `jdk.incubator.vector` is an incubator module. Scala compiles against it with
  * no ceremony, but the JVM does not resolve it at run time unless
  * `--add-modules jdk.incubator.vector` is passed, and loading this class without
  * it raises `NoClassDefFoundError`.
  *
  * That is why nothing reaches this class by default -- `Pdhg.solve` constructs
  * [[ScalaKernels]] -- and why [[VectorKernels.isAvailable]] probes by name
  * rather than by touching the class. `prima-core` still has no third-party
  * dependencies and is still callable from a host that has never heard of this
  * file; what it does not have is a SIMD path you get without asking.
  */
final class VectorKernels extends Kernels:

  type Vec = Array[Double]
  type Mat = SparseMatrix

  // `DoubleVector.SPECIES_PREFERRED` at every use site rather than cached in a
  // field. It is a `static final` in the JDK, which is what lets HotSpot fold the
  // lane count into the compiled loop; read through an instance field it is an
  // ordinary load and the vector operations stop being intrinsified. Measured:
  // as a field, `squaredNorm` ran three times slower than the scalar loop it
  // replaces.
  private inline def species: VectorSpecies[java.lang.Double] = DoubleVector.SPECIES_PREFERRED
  private val lanes                                           = species.length
  private val scalar                                          = ScalaKernels()

  val capabilities: KernelCapabilities =
    KernelCapabilities(name = s"scala-vector-$lanes", device = "cpu", supportsFloat64 = true)

  def allocate(n: Int): Array[Double]                       = scalar.allocate(n)
  def upload(data: Array[Double]): Array[Double]            = scalar.upload(data)
  def download(v: Array[Double], into: Array[Double]): Unit = scalar.download(v, into)
  def uploadMatrix(m: SparseMatrix): SparseMatrix           = scalar.uploadMatrix(m)
  def length(v: Array[Double]): Int                         = v.length
  def close(): Unit                                         = scalar.close()

  /** Unchanged. See the class scaladoc: the gather is its own question. */
  def spmv(a: SparseMatrix, x: Array[Double], out: Array[Double]): Unit = scalar.spmv(a, x, out)

  def copy(src: Array[Double], dst: Array[Double]): Unit = scalar.copy(src, dst)

  // The loop shape every dense operation below shares: whole vectors while a
  // whole vector remains, then a scalar tail. `loopBound` is the largest
  // multiple of the lane count that fits, so the tail is under one vector and is
  // written as the same arithmetic rather than delegated -- a tail that called
  // into `ScalaKernels` would be correct and would make each operation's edge
  // case a different piece of code from its body.

  def axpby(
      alpha: Double,
      x: Array[Double],
      beta: Double,
      y: Array[Double],
      out: Array[Double],
  ): Unit =
    // Left to SuperWord, which vectorises it already and better: widened by hand
    // it runs 1.44x slower at two lanes.
    scalar.axpby(alpha, x, beta, y, out)

  def scale(alpha: Double, x: Array[Double], out: Array[Double]): Unit =
    // The same, and the worst of the three at 2.17x slower. Also the rarest
    // operation in a solve -- 928 calls against `axpby`'s 133,660 -- so it would
    // not have been worth widening even had it won.
    scalar.scale(alpha, x, out)

  def dot(x: Array[Double], y: Array[Double]): Double =
    val n     = x.length
    val bound = species.loopBound(n)
    var acc   = DoubleVector.zero(species)
    var i     = 0
    while i < bound do
      val vx = DoubleVector.fromArray(species, x, i)
      val vy = DoubleVector.fromArray(species, y, i)
      // `fma` would be more accurate and is not used: the scalar reference does
      // a separate multiply and add, and the contract suite compares against it.
      acc = acc.add(vx.mul(vy))
      i += lanes
    var sum = acc.reduceLanes(VectorOperators.ADD)
    while i < n do
      sum += x(i) * y(i)
      i += 1
    sum

  def squaredNorm(x: Array[Double]): Double =
    val n     = x.length
    val bound = species.loopBound(n)
    var acc   = DoubleVector.zero(species)
    var i     = 0
    while i < bound do
      val vx = DoubleVector.fromArray(species, x, i)
      acc = acc.add(vx.mul(vx))
      i += lanes
    var sum = acc.reduceLanes(VectorOperators.ADD)
    while i < n do
      sum += x(i) * x(i)
      i += 1
    sum

  def primalStep(
      x: Array[Double],
      ktY: Array[Double],
      cost: Array[Double],
      lower: Array[Double],
      upper: Array[Double],
      tau: Double,
      out: Array[Double],
  ): Unit =
    val n     = out.length
    val bound = species.loopBound(n)
    val vtau  = DoubleVector.broadcast(species, tau)
    var i     = 0
    while i < bound do
      val vx    = DoubleVector.fromArray(species, x, i)
      val vk    = DoubleVector.fromArray(species, ktY, i)
      val vc    = DoubleVector.fromArray(species, cost, i)
      val vlo   = DoubleVector.fromArray(species, lower, i)
      val vhi   = DoubleVector.fromArray(species, upper, i)
      val step  = vx.sub(vtau.mul(vc.sub(vk)))
      // Two blends in this order, mirroring the scalar `if/else if`: the lower
      // bound is applied last and so wins if a degenerate box has `lower` above
      // `upper`. Written this way rather than as `min`/`max` for the reason
      // `ScalaKernels` gives -- an infinite bound must pass an infinite
      // candidate through untouched -- and a comparison against an infinity is
      // false in exactly the same way lane-wise as it is scalar.
      val above = step.compare(VectorOperators.GT, vhi)
      val below = step.compare(VectorOperators.LT, vlo)
      step.blend(vhi, above).blend(vlo, below).intoArray(out, i)
      i += lanes
    while i < n do
      val step = x(i) - tau * (cost(i) - ktY(i))
      out(i) =
        if step < lower(i) then lower(i)
        else if step > upper(i) then upper(i)
        else step
      i += 1

  def dualStep(
      y: Array[Double],
      kxBar: Array[Double],
      rhs: Array[Double],
      sigma: Double,
      numEqualities: Int,
      out: Array[Double],
  ): Unit =
    // Left to SuperWord as well, at 1.30x slower widened. The widened version
    // also had to split its two runs at `numEqualities`, which has no reason to
    // land on a vector boundary -- so it was the most intricate of the three and
    // the one paying least for it.
    scalar.dualStep(y, kxBar, rhs, sigma, numEqualities, out)


end VectorKernels

object VectorKernels:

  /** Whether this JVM resolved the incubator module.
    *
    * By name, deliberately: touching [[VectorKernels]] itself to find out would
    * raise the `NoClassDefFoundError` this exists to predict.
    */
  def isAvailable: Boolean =
    try
      Class.forName("jdk.incubator.vector.DoubleVector")
      true
    catch case _: Throwable => false

  def apply(): VectorKernels = new VectorKernels
