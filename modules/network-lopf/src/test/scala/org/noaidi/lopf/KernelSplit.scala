package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema}
import org.noaidi.prima.{PdhgParams, Pdhg, SparseMatrix}
import org.noaidi.prima.kernels.{KernelCapabilities, Kernels, ScalaKernels, VectorKernels}

/** Where a PDHG solve's time actually goes, per kernel operation.
  *
  * `NOTES.md` records one performance number for a realistic network — 281,792
  * iterations in 145 s on `scigrid-de` — and reasons from it that the sparse
  * matrix-vector product is the workload worth moving. That is the standard
  * expectation for a first-order method and it is probably right, but nothing
  * here had measured it: the 145 s is a whole-solve figure, and a decision about
  * which kernels to vectorise or move to a device needs the split rather than
  * the total.
  *
  * This is not a microbenchmark and does not try to be. It runs the real solve
  * with the real matrix and attributes wall clock to the eight operations
  * `Kernels` declares, which is the question a backend decision actually turns
  * on — a microbenchmark of `axpby` in isolation would report a number that no
  * amount of arithmetic converts into "and therefore SIMD would buy N% here".
  *
  * ==What the numbers are and are not==
  *
  *   - `System.nanoTime` is read twice per kernel call. At roughly 25 ns a pair
  *     against calls that touch tens of thousands of doubles, that is under a
  *     tenth of a percent — but it is charged to every operation equally, so it
  *     inflates the *cheap* ones' share slightly. Call counts are reported
  *     alongside the times so a reader can see which rows that could matter for.
  *   - It runs single threaded on one machine and reports one run. Shares
  *     between operations are stable across runs in a way absolute times are
  *     not, so the shares are the output; the totals are context.
  *   - JIT warm-up is paid before measuring, by solving the same problem once
  *     and discarding the result.
  */
object KernelSplit:

  /** Any backend whose vectors are plain arrays -- the two CPU ones. */
  type ArrayKernels = Kernels { type Vec = Array[Double]; type Mat = SparseMatrix }

  /** Counts and wall clock per operation, wrapped around a real backend.
    *
    * Written against a refinement rather than against `Kernels` generically:
    * the trait's `Vec` and `Mat` are abstract type members, so a fully generic
    * decorator has to thread path-dependent types through every signature to say
    * nothing this needs to say. Pinning them to `Array[Double]` covers both CPU
    * backends and would not cover a device one, which is the right limit for a
    * tool that measures host wall clock.
    *
    * One decorator for both, so the two runs are comparable: the timing overhead
    * is charged identically rather than by a separate harness per backend.
    */
  final class Instrumented(inner: ArrayKernels) extends Kernels:
    type Vec = Array[Double]
    type Mat = SparseMatrix

    private val names   = IndexedSeq("spmv", "axpby", "scale", "copy", "dot", "squaredNorm",
                                     "primalStep", "dualStep")
    private val nanos   = Array.fill(names.length)(0L)
    private val calls   = Array.fill(names.length)(0L)

    private inline def timed[A](op: Int)(inline body: => A): A =
      val t0 = System.nanoTime()
      val r  = body
      nanos(op) += System.nanoTime() - t0
      calls(op) += 1
      r

    def capabilities: KernelCapabilities = inner.capabilities
    def allocate(n: Int): Array[Double]  = inner.allocate(n)
    def upload(d: Array[Double]): Array[Double] = inner.upload(d)
    def download(v: Array[Double], into: Array[Double]): Unit = inner.download(v, into)
    def uploadMatrix(m: SparseMatrix): SparseMatrix = inner.uploadMatrix(m)
    def length(v: Array[Double]): Int = inner.length(v)
    def close(): Unit = inner.close()

    def spmv(a: SparseMatrix, x: Array[Double], out: Array[Double]): Unit =
      timed(0)(inner.spmv(a, x, out))
    def axpby(al: Double, x: Array[Double], be: Double, y: Array[Double], o: Array[Double]): Unit =
      timed(1)(inner.axpby(al, x, be, y, o))
    def scale(al: Double, x: Array[Double], o: Array[Double]): Unit =
      timed(2)(inner.scale(al, x, o))
    def copy(s: Array[Double], d: Array[Double]): Unit = timed(3)(inner.copy(s, d))
    def dot(x: Array[Double], y: Array[Double]): Double = timed(4)(inner.dot(x, y))
    def squaredNorm(x: Array[Double]): Double = timed(5)(inner.squaredNorm(x))
    def primalStep(x: Array[Double], k: Array[Double], c: Array[Double], lo: Array[Double],
                   hi: Array[Double], tau: Double, o: Array[Double]): Unit =
      timed(6)(inner.primalStep(x, k, c, lo, hi, tau, o))
    def dualStep(y: Array[Double], kx: Array[Double], r: Array[Double], sigma: Double,
                 nEq: Int, o: Array[Double]): Unit =
      timed(7)(inner.dualStep(y, kx, r, sigma, nEq, o))

    def report(): String =
      val total = nanos.sum.toDouble
      val rows = names.indices
        .sortBy(i => -nanos(i))
        .map { i =>
          val ms    = nanos(i) / 1e6
          val share = if total > 0 then 100.0 * nanos(i) / total else 0.0
          val each  = if calls(i) > 0 then nanos(i).toDouble / calls(i) / 1000.0 else 0.0
          f"  ${names(i)}%-12s ${ms}%10.1f ms  ${share}%5.1f%%  ${calls(i)}%10d calls  ${each}%8.1f us/call"
        }
      val sparse = 100.0 * nanos(0) / total
      (rows :+ f"%n  sparse ${sparse}%.1f%%, dense ${100.0 - sparse}%.1f%%  (total in kernels: ${total / 1e6}%.1f ms)")
        .mkString("\n")

  private def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  def main(args: Array[String]): Unit =
    val name      = args.headOption.getOrElse("scigrid-de")
    val tolerance = args.lift(1).map(_.toDouble).getOrElse(1e-4)
    // Which backend to attribute. The instrument is the same either way, so the
    // two runs are comparable in a way a separate harness per backend would not
    // be -- the overhead below is charged identically to both.
    val backend = args.lift(2).getOrElse("scala")

    val schema  = Schema.fromFile(goldens.resolve("schema.json"))
    val network = CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)
    val model   = Lopf.build(network)
    val problem = model.problem
    val params  = PdhgParams(epsAbs = tolerance, epsRel = tolerance, maxIterations = 500_000)

    println(s"$name: ${problem.numVariables} variables, ${problem.numConstraints} rows, " +
      s"tolerance $tolerance")

    // What one timed call costs before it has done anything. Measured rather
    // than assumed, because it is charged per call and the operations differ by
    // three orders of magnitude in call cost: on a small network `axpby` runs in
    // tens of nanoseconds, which is the same scale, and its share there is not
    // trustworthy. On `scigrid-de` the cheapest operation is 5.6 us, so the same
    // overhead is under a percent of it.
    val probe = locally {
      var acc = 0L
      var i   = 0
      while i < 1_000_000 do
        val t0 = System.nanoTime(); acc += System.nanoTime() - t0; i += 1
      acc / 1_000_000.0
    }
    println(f"timing overhead: ${probe}%.1f ns per instrumented call")

    // Warm up on the same problem, so the measured run meets a compiled loop.
    // Discarded: the point is the JIT state it leaves behind.
    locally {
      val warm = ScalaKernels()
      try Pdhg.solveWith(problem, params, warm)
      finally warm.close()
    }

    val inner: ArrayKernels = backend match
      case "vector" =>
        require(VectorKernels.isAvailable, "jdk.incubator.vector not resolved; pass --add-modules")
        VectorKernels()
      case _ => ScalaKernels()
    println(s"backend: ${inner.capabilities.name}")
    val k = Instrumented(inner)
    val t0 = System.nanoTime()
    val solution = try Pdhg.solveWith(problem, params, k) finally ()
    val wall = (System.nanoTime() - t0) / 1e6

    println(s"status ${solution.status}, objective ${solution.objectiveValue}")
    println(f"wall clock ${wall}%.1f ms")
    println(k.report())
    k.close()
