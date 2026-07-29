package org.noaidi.prima
package kernels

/** What a kernel backend can actually do, so callers can adapt rather than
  * guess.
  *
  * Precision is the one that bites: Apple GPUs expose no float64 through either
  * Vulkan or Metal, so a backend targeting them runs the iteration in float32
  * and the solver has to finish on the CPU in double precision. Making that a
  * declared property rather than a per-vendor special case keeps the decision
  * in one place.
  */
final case class KernelCapabilities(
    name: String,
    device: String,
    supportsFloat64: Boolean,
):
  /** Iterating in reduced precision means the reported solution is only as good
    * as float32 allows, so a double-precision refinement pass on the CPU is
    * required before results can be compared against a float64 oracle.
    */
  def requiresDoublePrecisionRefinement: Boolean = !supportsFloat64

/** The complete set of numeric operations the PDHG inner loop performs.
  *
  * Everything here runs once or twice per iteration and is the only code that
  * needs a GPU, an FPGA or a staged implementation. Convergence testing,
  * restart bookkeeping and infeasibility certificates all happen at evaluation
  * points a few dozen iterations apart, so they stay in ordinary Scala on the
  * host and are deliberately absent from this interface — which is what keeps
  * it small enough to be worth porting.
  *
  * Vectors and matrices are abstract so a backend can hold them in device
  * memory. All operations write into a caller-supplied output, so a backend
  * allocates only through [[allocate]] and can reuse buffers across iterations.
  * Reductions return a `Double` and are therefore the only synchronisation
  * points on an asynchronous device.
  *
  * Implementations are not required to be thread-safe; the solver uses one
  * instance from one thread.
  */
trait Kernels extends AutoCloseable:

  type Vec
  type Mat

  def capabilities: KernelCapabilities

  // -- memory ---------------------------------------------------------------

  /** A zero-filled vector of length `n`. */
  def allocate(n: Int): Vec

  /** Copy host data onto the device. */
  def upload(data: Array[Double]): Vec

  /** Copy device data back to the host, into a caller-owned array. */
  def download(v: Vec, into: Array[Double]): Unit

  /** Upload a constraint matrix in whatever layout the backend prefers. */
  def uploadMatrix(m: SparseMatrix): Mat

  def length(v: Vec): Int

  /** Release any device resources. Idempotent. */
  def close(): Unit

  // -- sparse ---------------------------------------------------------------

  /** `out := a * x`. The single sparse kernel; `K` and `K'` are both passed
    * here, the transpose having been materialised as its own matrix.
    */
  def spmv(a: Mat, x: Vec, out: Vec): Unit

  // -- dense vector ---------------------------------------------------------

  /** `out := alpha * x + beta * y`. Aliasing `out` with `x` or `y` is allowed. */
  def axpby(alpha: Double, x: Vec, beta: Double, y: Vec, out: Vec): Unit

  /** `out := alpha * x`. */
  def scale(alpha: Double, x: Vec, out: Vec): Unit

  def copy(src: Vec, dst: Vec): Unit

  // -- reductions -----------------------------------------------------------

  def dot(x: Vec, y: Vec): Double

  /** `||x||_2^2`. Squared, so callers can combine norms without a spurious
    * square root on the device.
    */
  def squaredNorm(x: Vec): Double

  // -- fused PDHG steps -----------------------------------------------------

  /** The primal half-step, projected onto the variable box:
    * `out := clamp(x - tau * (cost - ktY), lower, upper)`.
    *
    * Fused because the intermediate is never needed and a GPU would otherwise
    * pay three launches and two round trips through memory for it.
    */
  def primalStep(
      x: Vec,
      ktY: Vec,
      cost: Vec,
      lower: Vec,
      upper: Vec,
      tau: Double,
      out: Vec,
  ): Unit

  /** The dual half-step, projected onto the dual cone:
    * `out := proj(y + sigma * (rhs - kxBar))`, where the projection leaves the
    * first `numEqualities` entries free and clamps the rest at zero from below.
    */
  def dualStep(
      y: Vec,
      kxBar: Vec,
      rhs: Vec,
      sigma: Double,
      numEqualities: Int,
      out: Vec,
  ): Unit

end Kernels

object Kernels:
  /** A backend able to hold vectors as plain `Array[Double]`, which lets host
    * code read iterates without a copy. Only the CPU reference satisfies this.
    */
  type Aux[V, M] = Kernels { type Vec = V; type Mat = M }
