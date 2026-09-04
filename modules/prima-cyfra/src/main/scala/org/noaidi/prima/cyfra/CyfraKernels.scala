package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.Allocation
import io.computenode.cyfra.core.GProgram
import io.computenode.cyfra.core.GProgram.StaticDispatch
import io.computenode.cyfra.dsl.{*, given}
import io.computenode.cyfra.dsl.binding.GBuffer
import io.computenode.cyfra.dsl.collections.GSeq
import io.computenode.cyfra.runtime.VkCyfraRuntime
import org.noaidi.prima.kernels.{KernelCapabilities, Kernels}

import scala.collection.mutable

/** Every operation the PDHG loop performs, on the GPU, through Cyfra.
  *
  * The spike that preceded this settled the one question that could have made a
  * Cyfra backend impossible — whether CSR SpMV, with its indexed gather and its
  * per-row trip count, can be written in a DSL built for dense arrays. It can.
  * What is here is the rest of [[Kernels]] on top of that answer, plus the two
  * pieces of plumbing the spike did not need: an allocation that outlives a
  * single dispatch (see [[DeviceLoop]]), and a submission point.
  *
  * '''Float32 throughout.''' Cyfra's DSL has no double type on any target, so
  * `supportsFloat64` is false and a solve through this backend is a reduced
  * precision pass that [[org.noaidi.prima.MixedPrecision]] finishes on the CPU.
  * Vectors are stored as `Float` and widened on the way out, which is why the
  * contract suite holds this backend to single precision and not to the
  * reference's.
  *
  * '''One backend accumulates.''' [[Kernels]] has no deallocation -- a vector
  * is freed when the backend is closed and not before -- so a `CyfraKernels`
  * driven through many solves holds every solve's buffers until `close()`.
  * That is a property of the interface rather than of this backend, and it
  * bounds how one should be used: cycle it rather than holding a single
  * instance across an arbitrarily long run of solves.
  *
  * '''Work is recorded, not run, until a number is needed.''' Cyfra's `execute`
  * records a command buffer and registers it as pending; `submitLayout` is what
  * puts it on the queue. Nothing here forces a submission except `download` and
  * the two reductions — which is exactly the split [[Kernels]] describes, where
  * reductions are "the only synchronisation points on an asynchronous device".
  * A dozen elementwise kernels therefore go to the device as one batch per
  * iteration rather than as a dozen round trips.
  */
final class CyfraKernels extends Kernels:

  import CyfraLayouts.*

  /** A device buffer and the length the solver thinks it has.
    *
    * The length is carried rather than read back off the buffer because
    * [[Kernels.length]] is called per iteration and a device round trip for a
    * number the host already knows would be absurd.
    */
  final class DeviceVector private[cyfra] (private[cyfra] val buffer: GBuffer[Float32], val size: Int)

  /** CSR on the device, plus the two shapes a kernel has to be specialised to. */
  final class DeviceMatrix private[cyfra] (
      private[cyfra] val rowPtr: GBuffer[Int32],
      private[cyfra] val colIndices: GBuffer[Int32],
      private[cyfra] val values: GBuffer[Float32],
      private[cyfra] val rows: Int,
      private[cyfra] val cols: Int,
      private[cyfra] val nnz: Int,
      private[cyfra] val maxRowNnz: Int,
  )

  type Vec = DeviceVector
  type Mat = DeviceMatrix

  private val device = new DeviceLoop()
  private var closed = false

  /** How many invocations share a reduction, and so how many partial sums come
    * back to the host to be added in double precision.
    *
    * Small enough that the host's final pass is free, large enough that a
    * 60,000-entry vector is not walked by a handful of lanes. It also makes the
    * reduction tree-shaped rather than sequential, which is why the contract
    * suite allows a backend to disagree with the host's accumulation order.
    */
  private val ReduceLanes = 256

  private val workgroup = 64

  // Keyed by everything the program captures, as a tuple. An encoding -- one
  // Int mixing the length with the equality split, say -- collides inside the
  // reachable domain, and a collision here returns a program with the wrong row
  // count baked into its guard, its dispatch size and its buffer lengths. It
  // would come back through an unchecked cast as a wrong dual vector rather
  // than as an error.
  private val elementwise  = mutable.Map.empty[(String, Int, Int), GProgram[Unit, ?]]
  private val spmvPrograms = mutable.Map.empty[(Int, Int, Int, Int), GProgram[Unit, Spmv]]
  private val partials     = mutable.Map.empty[Int, GBuffer[Float32]]

  // Recorded but unsubmitted work, tracked by the buffer each operation wrote.
  // Submitting through a buffer pulls in everything the write depended on, so
  // this is a list of roots rather than of every operation.
  private val unsubmitted = mutable.ArrayBuffer.empty[GBuffer[Float32]]

  // Scalars reach a shader through a buffer, and one shared buffer is enough:
  // Cyfra chains a binding's pending executions in the order they were
  // recorded, so a write for the next operation cannot overtake the dispatch
  // that reads the previous one.
  private var scalars: GBuffer[Float32] = null

  private val partialsHost = new Array[Float](ReduceLanes)

  // Reused rather than allocated per call. `setScalars` runs on eight of the
  // seventeen operations in an iteration, and `BufferUtils.createByteBuffer`
  // allocates off-heap with a `Cleaner` registration each time -- 160,000 of
  // them over a 20,000-iteration solve, reclaimed only when GC gets round to
  // it, on the exact path this backend's cost is about.
  private val scalarHost  = new Array[Float](4)
  private val scalarBytes = org.lwjgl.BufferUtils.createByteBuffer(4 * java.lang.Float.BYTES)

  private def groups(n: Int): Int = math.max(1, (n + workgroup - 1) / workgroup)

  private def onDevice[A](body: (VkCyfraRuntime, Allocation) => A): A =
    if closed then throw new IllegalStateException("CyfraKernels has been closed")
    device.submit(body)

  private def scalarBuffer(using Allocation): GBuffer[Float32] =
    if scalars == null then scalars = GBuffer(new Array[Float](4))
    scalars

  private def setScalars(first: Double, second: Double = 0.0)(using Allocation): Unit =
    scalarHost(0) = first.toFloat
    scalarHost(1) = second.toFloat
    // No `clear()` here, and not by omission: `GCodec.toByteBuffer` opens with
    // `inBuf.clear()` and closes with `position(...).flip()`, so it both resets
    // the buffer it is handed and leaves it at position zero with the right
    // limit. A second `clear()` would read as though the reuse were unsafe
    // without one.
    io.computenode.cyfra.core.GCodec.toByteBuffer[Float32, Float](scalarBytes, scalarHost)
    // Written through `write` rather than `writeArray`: on this release
    // `writeArray` copies the byte buffer to the device *before* filling it
    // from the array, so it uploads whatever the fresh allocation happened to
    // contain. Building the byte buffer here and handing it over is the same
    // path `GBuffer(array)` takes, and that one is correct.
    scalarBuffer.write(scalarBytes, 0)

  private var dispatched = false

  private def record(out: GBuffer[Float32]): Unit =
    dispatched = true
    unsubmitted += out

  /** Put everything recorded so far on the queue.
    *
    * Submitting through each recorded output rather than through the last one:
    * Cyfra follows a pending execution's dependencies, so a chain is covered by
    * its tail, but two independent chains are not — and an operation whose
    * result nothing has read yet is exactly the case a read is about to create.
    * Re-submitting one already sent is free; `submitLayout` drops executions
    * that are no longer pending.
    */
  private def flush()(using Allocation): Unit =
    unsubmitted.reverseIterator.foreach(b => summon[Allocation].submitLayout(One(b)))
    unsubmitted.clear()

  def capabilities: KernelCapabilities =
    KernelCapabilities(name = "cyfra-vulkan", device = "Vulkan compute (SPIR-V)", supportsFloat64 = false)

  // -- memory ---------------------------------------------------------------

  def allocate(n: Int): Vec =
    require(n >= 0, s"cannot allocate a vector of length $n")
    onDevice { (_, alloc) =>
      given Allocation = alloc
      new DeviceVector(GBuffer(new Array[Float](math.max(n, 1))), n)
    }

  def upload(data: Array[Double]): Vec =
    onDevice { (_, alloc) =>
      given Allocation = alloc
      val floats = if data.isEmpty then new Array[Float](1) else data.map(_.toFloat)
      new DeviceVector(GBuffer(floats), data.length)
    }

  def download(v: Vec, into: Array[Double]): Unit =
    require(into.length == v.size, s"expected an array of length ${v.size}, got ${into.length}")
    if v.size > 0 then
      onDevice { (_, alloc) =>
        given Allocation = alloc
        flush()
        val floats = new Array[Float](v.size)
        v.buffer.readArray(floats)
        var i = 0
        while i < v.size do
          into(i) = floats(i).toDouble
          i += 1
      }

  def uploadMatrix(m: SparseMatrix): Mat =
    onDevice { (_, alloc) =>
      given Allocation = alloc
      // A new matrix means a new solve, and a program built for the last one
      // may not be dispatched again here.
      //
      // This is a correctness fix and not an optimisation. `economic-dispatch`
      // solved twice on one backend gave the right answer and then
      // `NumericalError` at iteration two, with the primal still at the origin
      // -- silently, not as a failure. A second solve of a *different* shape
      // was always fine, which is what points at the cache: that path builds
      // new programs.
      //
      // What that establishes is the trigger, not the mechanism. Solve
      // boundaries are where it was reproduced and where it is guarded. It is
      // not "a program dispatched against buffers allocated after its earlier
      // dispatches were cleaned up", which was the first guess and is
      // contradicted from inside a single solve: `partialsFor` allocates on the
      // first reduction, after every elementwise program has been built and
      // dispatched, and that path is correct. NOTES records the open question.
      //
      // Rebuilding is cheaper than it sounds. `VkCyfraRuntime` caches shaders
      // on `SpirvProgram.shaderHash` -- a digest of the SPIR-V, the entry
      // point, the workgroup size and the binding tags -- so an identically
      // rebuilt program resolves to the same `ComputePipeline` and no pipeline
      // is built twice. Forty consecutive solves on one backend hold at ~700 ms
      // each with no drift, which is what that predicts and what a recompile
      // per solve would not.
      //
      // It matters beyond a benchmark: `BranchAndBound.solveWith` deliberately
      // holds one set of kernels for a whole search and solves a relaxation per
      // node, so without this every node after the first would be silently
      // wrong.
      if dispatched then
        elementwise.clear()
        spmvPrograms.clear()
        partials.clear()
        dispatched = false
      val nnz = math.max(m.nnz, 1)
      val maxRow =
        math.max(1, (0 until m.rows).foldLeft(0)((acc, r) => math.max(acc, m.rowPtr(r + 1) - m.rowPtr(r))))
      new DeviceMatrix(
        rowPtr = GBuffer(Array.tabulate(m.rows + 1)(m.rowPtr.apply)),
        colIndices = GBuffer(Array.tabulate(nnz)(i => if i < m.nnz then m.colIndices(i) else 0)),
        values = GBuffer(Array.tabulate(nnz)(i => if i < m.nnz then m.values(i).toFloat else 0.0f)),
        rows = m.rows,
        cols = m.cols,
        nnz = m.nnz,
        maxRowNnz = maxRow,
      )
    }

  def length(v: Vec): Int = v.size

  def close(): Unit =
    if !closed then
      closed = true
      device.close()

  // -- programs -------------------------------------------------------------

  private def unaryProgram(name: String, n: Int)(
      body: (Unary, Value.Int32) => Float32
  )(using VkCyfraRuntime): GProgram[Unit, Unary] =
    elementwise
      .getOrElseUpdate(
        (name, n, 0),
        GProgram[Unit, Unary](
          layout = _ =>
            Unary(
              x = GBuffer[Float32](math.max(n, 1)),
              out = GBuffer[Float32](math.max(n, 1)),
              params = GBuffer[Float32](4),
            ),
          dispatch = (_, _) => StaticDispatch((groups(n), 1, 1)),
          workgroupSize = (workgroup, 1, 1),
        ) { layout =>
          val i = GIO.invocationId
          GIO.when(i < n)(GIO.write(layout.out, i, body(layout, i)))
        },
      )
      .asInstanceOf[GProgram[Unit, Unary]]

  private def binaryProgram(name: String, n: Int)(
      body: (Binary, Value.Int32) => Float32
  )(using VkCyfraRuntime): GProgram[Unit, Binary] =
    elementwise
      .getOrElseUpdate(
        (name, n, 0),
        GProgram[Unit, Binary](
          layout = _ =>
            Binary(
              x = GBuffer[Float32](math.max(n, 1)),
              y = GBuffer[Float32](math.max(n, 1)),
              out = GBuffer[Float32](math.max(n, 1)),
              params = GBuffer[Float32](4),
            ),
          dispatch = (_, _) => StaticDispatch((groups(n), 1, 1)),
          workgroupSize = (workgroup, 1, 1),
        ) { layout =>
          val i = GIO.invocationId
          GIO.when(i < n)(GIO.write(layout.out, i, body(layout, i)))
        },
      )
      .asInstanceOf[GProgram[Unit, Binary]]

  private def spmvProgram(m: DeviceMatrix)(using VkCyfraRuntime): GProgram[Unit, Spmv] =
    spmvPrograms.getOrElseUpdate(
      // `nnz` is in the key because the layout captures it: `colIndices` and
      // `values` are declared at that length, and a program shared by two
      // matrices agreeing on the rest would declare buffer sizes that do not
      // match the ones bound at dispatch.
      (m.rows, m.cols, m.maxRowNnz, m.nnz),
      GProgram[Unit, Spmv](
        layout = _ =>
          Spmv(
            rowPtr = GBuffer[Int32](m.rows + 1),
            colIndices = GBuffer[Int32](math.max(m.nnz, 1)),
            values = GBuffer[Float32](math.max(m.nnz, 1)),
            x = GBuffer[Float32](math.max(m.cols, 1)),
            out = GBuffer[Float32](math.max(m.rows, 1)),
          ),
        dispatch = (_, _) => StaticDispatch((groups(m.rows), 1, 1)),
        workgroupSize = (workgroup, 1, 1),
      ) { layout =>
        val row = GIO.invocationId
        GIO.when(row < m.rows) {
          val start = GIO.read(layout.rowPtr, row)
          val end   = GIO.read(layout.rowPtr, row + 1)
          val sum = GSeq
            .gen(start, p => p + 1)
            .takeWhile(p => p < end)
            .limit(m.maxRowNnz)
            .map(p => GIO.read(layout.values, p) * GIO.read(layout.x, GIO.read(layout.colIndices, p)))
            .fold[Float32](0.0f, _ + _)
          GIO.write(layout.out, row, sum)
        }
      },
    )

  private def reduceProgram(n: Int)(using VkCyfraRuntime): GProgram[Unit, Reduce] =
    val chunk = math.max(1, (n + ReduceLanes - 1) / ReduceLanes)
    elementwise
      .getOrElseUpdate(
        ("reduce", n, 0),
        GProgram[Unit, Reduce](
          layout = _ =>
            Reduce(
              x = GBuffer[Float32](math.max(n, 1)),
              y = GBuffer[Float32](math.max(n, 1)),
              partials = GBuffer[Float32](ReduceLanes),
            ),
          dispatch = (_, _) => StaticDispatch((ReduceLanes / workgroup, 1, 1)),
          workgroupSize = (workgroup, 1, 1),
        ) { layout =>
          val lane = GIO.invocationId
          // Strided rather than blocked, so neighbouring lanes read neighbouring
          // addresses on every step of the walk.
          val sum = GSeq
            .gen(lane, p => p + ReduceLanes)
            .takeWhile(p => p < n)
            .limit(chunk)
            .map(p => GIO.read(layout.x, p) * GIO.read(layout.y, p))
            .fold[Float32](0.0f, _ + _)
          GIO.write(layout.partials, lane, sum)
        },
      )
      .asInstanceOf[GProgram[Unit, Reduce]]

  private def partialsFor(n: Int)(using Allocation): GBuffer[Float32] =
    partials.getOrElseUpdate(n, GBuffer(new Array[Float](ReduceLanes)))

  // -- sparse ---------------------------------------------------------------

  def spmv(a: Mat, x: Vec, out: Vec): Unit =
    require(x.size == a.cols, s"spmv: vector of length ${x.size} against ${a.cols} columns")
    require(out.size == a.rows, s"spmv: output of length ${out.size} against ${a.rows} rows")
    if a.rows > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation      = alloc
        given VkCyfraRuntime  = runtime
        spmvProgram(a).execute((), Spmv(a.rowPtr, a.colIndices, a.values, x.buffer, out.buffer))
        record(out.buffer)
      }

  // -- dense vector ---------------------------------------------------------

  def axpby(alpha: Double, x: Vec, beta: Double, y: Vec, out: Vec): Unit =
    require(x.size == out.size && y.size == out.size, "axpby: mismatched vector lengths")
    if out.size > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val program = binaryProgram("axpby", out.size) { (l, i) =>
          GIO.read(l.params, 0) * GIO.read(l.x, i) + GIO.read(l.params, 1) * GIO.read(l.y, i)
        }
        setScalars(alpha, beta)
        program.execute((), Binary(x.buffer, y.buffer, out.buffer, scalarBuffer))
        record(out.buffer)
      }

  def scale(alpha: Double, x: Vec, out: Vec): Unit =
    require(x.size == out.size, "scale: mismatched vector lengths")
    if out.size > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val program = unaryProgram("scale", out.size)((l, i) => GIO.read(l.params, 0) * GIO.read(l.x, i))
        setScalars(alpha)
        program.execute((), Unary(x.buffer, out.buffer, scalarBuffer))
        record(out.buffer)
      }

  /** A copy, on a device, is a dispatch.
    *
    * Not `scale(1.0, ...)`: a copy has to reproduce infinities and the sign of
    * zero exactly, and `1.0f * x` does neither for every `x` a bound vector can
    * hold. The kernel writes what it read.
    */
  def copy(src: Vec, dst: Vec): Unit =
    require(src.size == dst.size, "copy: mismatched vector lengths")
    if dst.size > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val program = unaryProgram("copy", dst.size)((l, i) => GIO.read(l.x, i))
        program.execute((), Unary(src.buffer, dst.buffer, scalarBuffer))
        record(dst.buffer)
      }

  // -- reductions -----------------------------------------------------------

  private def reduce(x: Vec, y: Vec): Double =
    require(x.size == y.size, "reduction: mismatched vector lengths")
    if x.size == 0 then 0.0
    else
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val out = partialsFor(x.size)
        reduceProgram(x.size).execute((), Reduce(x.buffer, y.buffer, out))
        record(out)
        flush()
        out.readArray(partialsHost)
        // The lanes are added in double precision. The products were not, and
        // could not be, but there is no reason to throw away the accuracy of
        // the last 256 additions as well.
        var acc = 0.0
        var i   = 0
        while i < ReduceLanes do
          acc += partialsHost(i).toDouble
          i += 1
        acc
      }

  def dot(x: Vec, y: Vec): Double = reduce(x, y)

  def squaredNorm(x: Vec): Double = reduce(x, x)

  // -- fused PDHG steps -----------------------------------------------------

  def primalStep(
      x: Vec,
      ktY: Vec,
      cost: Vec,
      lower: Vec,
      upper: Vec,
      tau: Double,
      out: Vec,
  ): Unit =
    require(
      x.size == out.size && ktY.size == out.size && cost.size == out.size &&
        lower.size == out.size && upper.size == out.size,
      "primalStep: mismatched vector lengths",
    )
    if out.size > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val n = out.size
        val program = elementwise
          .getOrElseUpdate(
            ("primalStep", n, 0),
            GProgram[Unit, Primal](
              layout = _ =>
                Primal(
                  x = GBuffer[Float32](n),
                  ktY = GBuffer[Float32](n),
                  cost = GBuffer[Float32](n),
                  lower = GBuffer[Float32](n),
                  upper = GBuffer[Float32](n),
                  out = GBuffer[Float32](n),
                  params = GBuffer[Float32](4),
                ),
              dispatch = (_, _) => StaticDispatch((groups(n), 1, 1)),
              workgroupSize = (workgroup, 1, 1),
            ) { l =>
              val i = GIO.invocationId
              GIO.when(i < n) {
                val step =
                  GIO.read(l.x, i) - GIO.read(l.params, 0) * (GIO.read(l.cost, i) - GIO.read(l.ktY, i))
                // `min` then `max` rather than a clamp, so an infinite bound
                // passes the candidate through rather than returning the
                // bound -- which is what a clamp would do to an infinite
                // candidate. This agrees with the reference's two comparisons
                // wherever `lower <= upper`, and only there: on a degenerate
                // box the reference returns `upper` and this returns `lower`.
                // `LpProblem.apply` rejects such a box, so no solve reaches it.
                GIO.write(l.out, i, max(min(step, GIO.read(l.upper, i)), GIO.read(l.lower, i)))
              }
            },
          )
          .asInstanceOf[GProgram[Unit, Primal]]
        setScalars(tau)
        program.execute(
          (),
          Primal(x.buffer, ktY.buffer, cost.buffer, lower.buffer, upper.buffer, out.buffer, scalarBuffer),
        )
        record(out.buffer)
      }

  def dualStep(
      y: Vec,
      kxBar: Vec,
      rhs: Vec,
      sigma: Double,
      numEqualities: Int,
      out: Vec,
  ): Unit =
    require(
      y.size == out.size && kxBar.size == out.size && rhs.size == out.size,
      "dualStep: mismatched vector lengths",
    )
    if out.size > 0 then
      onDevice { (runtime, alloc) =>
        given Allocation     = alloc
        given VkCyfraRuntime = runtime
        val m = out.size
        // `numEqualities` is captured, so the split between free and clamped
        // rows is baked into the kernel. That is a specialisation the solver
        // never notices: it is fixed for the whole life of a problem.
        val program = elementwise
          .getOrElseUpdate(
            ("dualStep", m, numEqualities),
            GProgram[Unit, Dual](
              layout = _ =>
                Dual(
                  y = GBuffer[Float32](m),
                  kxBar = GBuffer[Float32](m),
                  rhs = GBuffer[Float32](m),
                  out = GBuffer[Float32](m),
                  params = GBuffer[Float32](4),
                ),
              dispatch = (_, _) => StaticDispatch((groups(m), 1, 1)),
              workgroupSize = (workgroup, 1, 1),
            ) { l =>
              val i = GIO.invocationId
              GIO.when(i < m) {
                val step =
                  GIO.read(l.y, i) + GIO.read(l.params, 0) * (GIO.read(l.rhs, i) - GIO.read(l.kxBar, i))
                val projected = max(step, 0.0f)
                GIO.write(l.out, i, when(i < numEqualities)(step).otherwise(projected))
              }
            },
          )
          .asInstanceOf[GProgram[Unit, Dual]]
        setScalars(sigma)
        program.execute((), Dual(y.buffer, kxBar.buffer, rhs.buffer, out.buffer, scalarBuffer))
        record(out.buffer)
      }

end CyfraKernels
