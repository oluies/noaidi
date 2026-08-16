package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.{GBufferRegion, GProgram}
import io.computenode.cyfra.core.GProgram.StaticDispatch
import io.computenode.cyfra.core.layout.Layout
import io.computenode.cyfra.dsl.{*, given}
import io.computenode.cyfra.dsl.collections.GSeq
import io.computenode.cyfra.runtime.VkCyfraRuntime

/** Can a CSR sparse matrix-vector product be written in Cyfra's DSL?
  *
  * This is the question that decides whether a Cyfra backend is possible at
  * all. SpMV is the one kernel PDHG cannot do without, and it is the one Cyfra
  * was not designed around: its demonstrated use is dense arrays and ray
  * tracing, where every invocation does the same amount of work. A CSR row
  * needs two things neither of those requires — an indexed gather through a
  * column-index buffer, and an inner loop whose trip count is only known at
  * runtime and differs per row.
  *
  * The construction below uses `GSeq.gen` for the row's index range,
  * `takeWhile` for the runtime bound, and `limit` as a static safety cap. The
  * cap is a plain Scala `Int` read off the matrix when the program is built,
  * which specialises the kernel to a given sparsity pattern — acceptable here,
  * since a solve holds its matrix fixed for its whole run.
  */
class SpmvSpike extends munit.FunSuite:

  case class SpmvLayout(
      rowPtr: GBuffer[Int32],
      colIndices: GBuffer[Int32],
      values: GBuffer[Float32],
      x: GBuffer[Float32],
      out: GBuffer[Float32],
  ) derives Layout

  /** Build a kernel specialised to one matrix's shape.
    *
    * `rows` and `maxRowNnz` are captured when the program is constructed rather
    * than passed at dispatch, because the DSL body cannot read a runtime
    * parameter. That is why this returns a program per matrix.
    */
  private def spmvProgram(rows: Int, cols: Int, maxRowNnz: Int): GProgram[Int, SpmvLayout] =
    GProgram[Int, SpmvLayout](
      layout = nnz =>
        SpmvLayout(
          rowPtr = GBuffer[Int32](rows + 1),
          colIndices = GBuffer[Int32](nnz),
          values = GBuffer[Float32](nnz),
          x = GBuffer[Float32](cols.max(1)),
          out = GBuffer[Float32](rows),
        ),
      dispatch = (_, _) => StaticDispatch(((rows + 63) / 64, 1, 1)),
      workgroupSize = (64, 1, 1),
    ): layout =>
      val row = GIO.invocationId
      GIO.when(row < rows):
        val start = GIO.read(layout.rowPtr, row)
        val end   = GIO.read(layout.rowPtr, row + 1)

        // One invocation walks its own row. `takeWhile` supplies the runtime
        // bound; `limit` is the compile-time cap SPIR-V needs to bound the loop.
        val sum = GSeq
          .gen(start, p => p + 1)
          .takeWhile(p => p < end)
          .limit(maxRowNnz)
          .map { p =>
            // The indexed gather: a column index read from one buffer used to
            // address another. This is the operation a dense-array DSL has no
            // reason to support.
            GIO.read(layout.values, p) * GIO.read(layout.x, GIO.read(layout.colIndices, p))
          }
          .fold[Float32](0.0f, _ + _)

        GIO.write(layout.out, row, sum)

  /** Degenerate shapes are rejected rather than half-guarded.
    *
    * An all-zero matrix has `nnz == 0` and is reachable, since `fromTriplets`
    * drops exact zeros. Sprinkling `.max(1)` over some buffer sizes but not
    * others leaves the declared layout disagreeing with the data actually
    * uploaded, which surfaces as a buffer-length mismatch from inside Cyfra.
    * A spike does not need to handle these; it needs to say so.
    */
  private def requireSupported(matrix: SparseMatrix, vector: Array[Double]): Unit =
    require(matrix.rows > 0, "SpMV spike does not handle a matrix with no rows")
    require(matrix.cols > 0, "SpMV spike does not handle a matrix with no columns")
    require(matrix.nnz > 0, "SpMV spike does not handle a structurally empty matrix")
    require(
      vector.length == matrix.cols,
      s"vector has ${vector.length} entries, expected ${matrix.cols}",
    )

  // The buffer constructors below are deliberately written out at each call
  // site rather than shared in a helper: `GBuffer(data)` infers its element
  // type from the expected type of the field it initialises, and that inference
  // does not survive being moved behind a method boundary.

  private def maxRowNnzOf(matrix: SparseMatrix): Int =
    math.max(
      (0 until matrix.rows).map(r => matrix.rowPtr(r + 1) - matrix.rowPtr(r)).maxOption.getOrElse(0),
      1,
    )

  private def runSpmv(matrix: SparseMatrix, vector: Array[Double]): Array[Float] =
    requireSupported(matrix, vector)
    CyfraRuntimeFixture.withRuntime { runtime =>
      given VkCyfraRuntime = runtime
      val program = spmvProgram(matrix.rows, matrix.cols, maxRowNnzOf(matrix))
      val results = Array.ofDim[Float](matrix.rows)

      GBufferRegion
        .allocate[SpmvLayout]
        .map(layout => program.execute(matrix.nnz, layout))
        .runUnsafe(
          init = SpmvLayout(
            rowPtr = GBuffer(Array.tabulate(matrix.rows + 1)(matrix.rowPtr.apply)),
            colIndices = GBuffer(Array.tabulate(matrix.nnz)(matrix.colIndices.apply)),
            values = GBuffer(Array.tabulate(matrix.nnz)(i => matrix.values(i).toFloat)),
            x = GBuffer(vector.map(_.toFloat)),
            out = GBuffer[Float32](matrix.rows),
          ),
          onDone = layout => layout.out.readArray(results),
        )
      results
    }

  test("SpMV on the GPU matches the CPU reference") {
    val matrix = SparseMatrix.fromDense(
      Seq(
        Seq(1.0, 2.0, 0.0, 0.0),
        Seq(0.0, 3.0, 0.0, 4.0),
        Seq(0.0, 0.0, 0.0, 0.0), // empty row: the loop must run zero times
        Seq(5.0, 0.0, 6.0, 7.0),
      ),
      cols = 4,
    )
    val vector   = Array(1.0, 2.0, 3.0, 4.0)
    val expected = Unsafe.raw(matrix.multiply(Unsafe.wrap(vector)))

    val actual = runSpmv(matrix, vector)
    expected.indices.foreach { r =>
      assertEqualsFloat(actual(r), expected(r).toFloat, 1e-4f, s"row $r")
    }
  }

  test("one built program can be dispatched many times") {
    // The reason the per-matrix specialisation is tolerable is that a solve
    // holds its matrix fixed and dispatches SpMV tens of thousands of times, so
    // building the program is a per-solve cost rather than a per-iteration one.
    // That is the claim a backend rests on, so it is exercised here rather than
    // assumed: the same `GProgram` value is reused across many dispatches with
    // different right-hand sides, and every result is checked.
    val matrix = SparseMatrix.fromDense(
      Seq(
        Seq(1.0, 2.0, 0.0, 0.0),
        Seq(0.0, 3.0, 0.0, 4.0),
        Seq(0.0, 0.0, 0.0, 0.0),
        Seq(5.0, 0.0, 6.0, 7.0),
      ),
      cols = 4,
    )

    // Through the same guard every other dispatch goes through. Building the
    // layout inline let this one caller bypass `requireSupported`, so a matrix
    // the kernel cannot express would fail here as a wrong answer rather than
    // as the refusal that guard exists to give. The vector is representative:
    // every one below has the same length.
    requireSupported(matrix, Array.tabulate(matrix.cols)(_.toDouble))

    CyfraRuntimeFixture.withRuntime { runtime =>
      given VkCyfraRuntime = runtime

      // Built once, outside the dispatch loop. This is the object a backend
      // would cache for the lifetime of a solve.
      val program = spmvProgram(matrix.rows, matrix.cols, maxRowNnzOf(matrix))
      val timings = scala.collection.mutable.ArrayBuffer.empty[Long]

      (0 until 25).foreach { run =>
        val vector   = Array.tabulate(matrix.cols)(c => (c + run).toDouble)
        val expected = Unsafe.raw(matrix.multiply(Unsafe.wrap(vector)))
        val results  = Array.ofDim[Float](matrix.rows)

        val started = System.nanoTime()
        GBufferRegion
          .allocate[SpmvLayout]
          .map(layout => program.execute(matrix.nnz, layout))
          .runUnsafe(
            init = SpmvLayout(
              rowPtr = GBuffer(Array.tabulate(matrix.rows + 1)(matrix.rowPtr.apply)),
              colIndices = GBuffer(Array.tabulate(matrix.nnz)(matrix.colIndices.apply)),
              values = GBuffer(Array.tabulate(matrix.nnz)(i => matrix.values(i).toFloat)),
              x = GBuffer(vector.map(_.toFloat)),
              out = GBuffer[Float32](matrix.rows),
            ),
            onDone = layout => layout.out.readArray(results),
          )
        timings += System.nanoTime() - started

        expected.indices.foreach { r =>
          assertEqualsFloat(results(r), expected(r).toFloat, 1e-4f, s"run $run, row $r")
        }
      }

      // A control, because the first-versus-rest gap alone does not say what it
      // looks like it says. Every iteration above opens a fresh region and
      // re-uploads all five buffers, so that gap bundles JVM warm-up of the
      // whole Cyfra path and first-touch device allocation together with any
      // SPIR-V compilation. Building a second identical program here, after the
      // path is warm, isolates the compile cost: if it is near zero the first
      // dispatch's cost was warm-up, and if it is comparable to the gap it was
      // compilation.
      val controlStart   = System.nanoTime()
      val secondProgram  = spmvProgram(matrix.rows, matrix.cols, maxRowNnzOf(matrix))
      val controlMillis  = (System.nanoTime() - controlStart) / 1000000.0
      assert(secondProgram != null)

      // Informational, not asserted: wall-clock on a shared machine is too
      // noisy to gate a build on. What the test establishes is that reuse
      // works and stays correct.
      //
      // `rest` is indexed by its own length. Off the 25-element `timings` the
      // old expression read element 12 of a 24-element sequence -- off by one as
      // a median, and an IndexOutOfBoundsException for any dispatch count of two
      // or fewer, which is exactly what someone debugging this would reduce it
      // to.
      val rest   = timings.drop(1).sorted
      val first  = timings.head / 1000000.0
      val median = rest(rest.size / 2) / 1000000.0
      println(
        f"repeated dispatch: first ${first}%.1f ms, median of the rest ${median}%.1f ms, " +
          f"second program build ${controlMillis}%.1f ms"
      )
    }
  }

  test("SpMV matches the reference on a larger random sparse matrix") {
    // Rows of widely differing length, which is where a fixed-trip-count
    // formulation would quietly give wrong answers rather than fail.
    val rng  = new scala.util.Random(42)
    val rows = 500
    val cols = 300
    val entries = for
      r <- 0 until rows
      c <- 0 until cols
      if rng.nextDouble() < 0.02
    yield (r, c, rng.nextGaussian())

    val matrix   = SparseMatrix.fromTriplets(rows, cols, entries)
    val vector   = Array.fill(cols)(rng.nextGaussian())
    val expected = Unsafe.raw(matrix.multiply(Unsafe.wrap(vector)))

    val actual = runSpmv(matrix, vector)

    val worst = expected.indices.map(r => math.abs(actual(r) - expected(r))).max
    // float32 against a float64 reference, so agreement is to single precision.
    assert(worst < 1e-3, s"worst absolute disagreement $worst over $rows rows")
  }
