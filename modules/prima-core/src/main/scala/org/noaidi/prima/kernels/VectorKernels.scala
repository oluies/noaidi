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
  * ==Three operations, not six==
  *
  * HotSpot's SuperWord pass already auto-vectorises a plain `while` loop over
  * contiguous arrays, so `axpby`, `scale` and `dualStep` were vector
  * instructions before this file existed -- confirmed under `-XX:-UseSuperWord`,
  * where the reference `axpby` slows from 7.0 to 8.6 us. What C2 will not do is
  * reassociate floating-point addition, so a reduction stays scalar however
  * simple it looks. That is where the win is: `dot` and `squaredNorm` run
  * several times faster, with `primalStep` gaining less because its clamp is a
  * data-dependent branch per element rather than a reduction.
  *
  * Per-operation figures are deliberately not quoted here. The ones this file
  * carried came from a drift estimator that has since been replaced, on an arm
  * where the repaired reporter declines to publish a corrected number at all --
  * so they were pre-repair numbers surviving in shipped source after being
  * deleted from `NOTES.md`. `NOTES.md` and the CI job's summary carry the
  * current ones.
  *
  * The other three are delegated on a tiebreak rather than a measurement, and two
  * successive figures said otherwise before this settled. A first had them losing
  * by 1.44x to 2.17x, which was a harness that warmed up on the wrong backend. A
  * second had widening all six coming out slower end to end, which was one
  * four-lane run, where both coverages carry the same convergence penalty.
  * Measured since, the two coverages come out level. Nothing measured prefers
  * three to six; three fewer hand-written loops decides it.
  * [[VectorKernels.widenEverything]] keeps the other coverage so it stays
  * measurable.
  *
  * ==Faster per iteration, and that is not the question a caller asks==
  *
  * Reassociating a sum changes its rounding, and the lane count decides how the
  * partial sums are grouped. On `scigrid-de` the solve then takes measurably
  * longer at four lanes: against the reference's 14,848 iterations, two and eight
  * come out level and four takes **18,624**, in four CI sweeps at native width
  * and under `-XX:MaxVectorSize`.
  *
  * These are iteration counts and not line-search trials. The harness printed
  * only one number under the heading `iterations` until it was repaired; the
  * sweep run since prints both and they are equal, and the three earlier sweeps
  * report the same figure.
  *
  * Per iteration this backend is 1.19x to 1.24x faster on every width and
  * coverage measured away from two lanes. End to end:
  *
  * {{{
  * 2 lanes, aarch64    1.11x
  * 2 lanes, x86_64     0.33x
  * 4 lanes, x86_64     0.97x     1.22x per iteration, 25.4% more of them
  * 8 lanes, x86_64     1.19x
  * }}}
  *
  * So on a four-lane machine it is a net loss, and on x86_64 at two lanes it is
  * three times slower than the scalar reference at the same width. The kernels
  * are genuinely faster; whether the solve is depends on where the lane count
  * lands. `NOTES.md` carries the measurements and the confounds.
  *
  * `spmv` delegates unchanged: a CSR row is an indexed gather through a column
  * index buffer, which the Vector API can express but does not obviously win at,
  * and widening it is a separate question with its own measurement.
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
final class VectorKernels(alsoWidenAutoVectorised: Boolean = false) extends Kernels:

  type Vec = Array[Double]
  type Mat = SparseMatrix

  // `DoubleVector.SPECIES_PREFERRED` at every use site rather than cached in a
  // field. It is a `static final` in the JDK, which is what lets HotSpot fold the
  // lane count into the compiled loop; read through an instance field it is an
  // ordinary load and the vector operations stop being intrinsified. Measured:
  // as a field, `squaredNorm` ran three times slower than the scalar loop it
  // replaces.
  private inline def species: VectorSpecies[java.lang.Double] = DoubleVector.SPECIES_PREFERRED
  // Only for the capability name. As a loop stride it would be an instance-field
  // read in the hot loop, which is the pattern the comment above exists to avoid
  // -- `species.length` inlines to the `static final` and folds.
  private val lanes                                           = species.length
  private val scalar                                          = ScalaKernels()

  val capabilities: KernelCapabilities =
    KernelCapabilities(
      // The lane count and the coverage both travel in the name, because a
      // measurement of one is not a measurement of the other and the two were
      // conflated once already.
      name = if alsoWidenAutoVectorised then s"scala-vector-$lanes-all" else s"scala-vector-$lanes",
      device = "cpu",
      supportsFloat64 = true,
    )

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
    if !alsoWidenAutoVectorised then scalar.axpby(alpha, x, beta, y, out)
    else
      val n     = out.length
      val bound = species.loopBound(n)
      val va    = DoubleVector.broadcast(species, alpha)
      val vb    = DoubleVector.broadcast(species, beta)
      var i     = 0
      while i < bound do
        val vx = DoubleVector.fromArray(species, x, i)
        val vy = DoubleVector.fromArray(species, y, i)
        vx.mul(va).add(vy.mul(vb)).intoArray(out, i)
        i += species.length
      while i < n do
        out(i) = alpha * x(i) + beta * y(i)
        i += 1

  def scale(alpha: Double, x: Array[Double], out: Array[Double]): Unit =
    if !alsoWidenAutoVectorised then scalar.scale(alpha, x, out)
    else
      val n     = out.length
      val bound = species.loopBound(n)
      val va    = DoubleVector.broadcast(species, alpha)
      var i     = 0
      while i < bound do
        DoubleVector.fromArray(species, x, i).mul(va).intoArray(out, i)
        i += species.length
      while i < n do
        out(i) = alpha * x(i)
        i += 1

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
      i += species.length
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
      i += species.length
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
      i += species.length
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
    if !alsoWidenAutoVectorised then scalar.dualStep(y, kxBar, rhs, sigma, numEqualities, out)
    else
      val n      = out.length
      val vsigma = DoubleVector.broadcast(species, sigma)

      // The first run stops at the last whole vector *inside* the equality
      // block rather than at `loopBound(n)`, so the boundary between the two
      // projections never falls mid-vector -- which is what would otherwise need
      // a mask and a reason to trust it.
      val eqBound = species.loopBound(numEqualities)
      var i       = 0
      while i < eqBound do
        val vy = DoubleVector.fromArray(species, y, i)
        val vr = DoubleVector.fromArray(species, rhs, i)
        val vk = DoubleVector.fromArray(species, kxBar, i)
        vy.add(vsigma.mul(vr.sub(vk))).intoArray(out, i)
        i += species.length
      while i < numEqualities do
        out(i) = y(i) + sigma * (rhs(i) - kxBar(i))
        i += 1

      // The second starts wherever that scalar tail left off, so it is not
      // aligned to a vector boundary -- `fromArray` requires only that the whole
      // vector is in bounds, not that it is aligned.
      val zero     = DoubleVector.zero(species)
      val ineqStop = i + species.loopBound(n - i)
      while i < ineqStop do
        val vy   = DoubleVector.fromArray(species, y, i)
        val vr   = DoubleVector.fromArray(species, rhs, i)
        val vk   = DoubleVector.fromArray(species, kxBar, i)
        val step = vy.add(vsigma.mul(vr.sub(vk)))
        step.blend(zero, step.compare(VectorOperators.LE, zero)).intoArray(out, i)
        i += species.length
      while i < n do
        val step = y(i) + sigma * (rhs(i) - kxBar(i))
        out(i) = if step > 0.0 then step else 0.0
        i += 1

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

  def apply(): VectorKernels = new VectorKernels(alsoWidenAutoVectorised = false)

  /** Every dense operation widened, including the three SuperWord already does.
    *
    * Not the default, and not dead code: it exists so the division this class
    * makes stays a *measurement* rather than a decision baked in on one machine.
    * That division was made at two lanes, where widening those three lost by
    * 1.44x, 1.30x and 2.17x. Whether it still loses at eight is a different
    * question about a different machine, and answering it needs both versions
    * to exist at once so they can be run minutes apart on the same host.
    */
  def widenEverything(): VectorKernels = new VectorKernels(alsoWidenAutoVectorised = true)
