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

  private def runSpmv(matrix: SparseMatrix, vector: Array[Double]): Array[Float] =
    given runtime: VkCyfraRuntime = VkCyfraRuntime()

    val maxRowNnz =
      (0 until matrix.rows).map(r => matrix.rowPtr(r + 1) - matrix.rowPtr(r)).maxOption.getOrElse(0)
    val program = spmvProgram(matrix.rows, matrix.cols, math.max(maxRowNnz, 1))
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
