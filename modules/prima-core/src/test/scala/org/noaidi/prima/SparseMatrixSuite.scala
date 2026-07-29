package org.noaidi.prima

class SparseMatrixSuite extends munit.FunSuite:

  test("triplets are canonicalised: sorted, duplicates summed, zeros dropped") {
    val m = SparseMatrix.fromTriplets(
      2,
      3,
      Seq((1, 2, 5.0), (0, 1, 2.0), (0, 1, 3.0), (1, 0, 1.0), (0, 0, 4.0), (0, 0, -4.0)),
    )
    // (0,0) cancelled to zero and must not be stored; (0,1) summed to 5.
    assertEquals(m.nnz, 3)
    assertEquals(m.colIndices.toList, List(1, 0, 2))
    assertEquals(m.values.toList, List(5.0, 1.0, 5.0))
    assertEquals(m(0, 1), 5.0)
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(1, 2), 5.0)
  }

  test("rows may be empty and still index correctly") {
    val m = SparseMatrix.fromTriplets(3, 2, Seq((0, 0, 1.0), (2, 1, 2.0)))
    assertEquals(m.rowPtr.toList, List(0, 1, 1, 2))
    assertEquals(m.multiply(IArray(3.0, 4.0)).toList, List(3.0, 0.0, 8.0))
  }

  test("transpose round-trips and matches the dense transpose") {
    val m = SparseMatrix.fromDense(
      Seq(Seq(1.0, 0.0, 2.0), Seq(0.0, 3.0, 0.0), Seq(4.0, 5.0, 6.0)),
      cols = 3,
    )
    val t = m.transpose
    assertEquals(t.rows, 3)
    assertEquals(t.cols, 3)
    for r <- 0 until 3; c <- 0 until 3 do assertEquals(t(c, r), m(r, c), s"($r,$c)")
    val back = t.transpose
    assertEquals(back.values.toList, m.values.toList)
    assertEquals(back.colIndices.toList, m.colIndices.toList)
  }

  test("transpose of a non-square matrix keeps column indices ascending") {
    val m = SparseMatrix.fromTriplets(2, 4, Seq((0, 3, 1.0), (0, 1, 2.0), (1, 0, 3.0), (1, 3, 4.0)))
    val t = m.transpose
    assertEquals(t.rows, 4)
    assertEquals(t.cols, 2)
    for r <- 0 until t.rows do
      val slice = (t.rowPtr(r) until t.rowPtr(r + 1)).map(t.colIndices.apply)
      assertEquals(slice, slice.sorted, s"row $r of the transpose is unsorted")
  }

  test("multiply agrees with the dense product") {
    val dense = Seq(Seq(1.0, 2.0, 0.0), Seq(0.0, -1.0, 3.0))
    val m     = SparseMatrix.fromDense(dense, cols = 3)
    val x     = IArray(2.0, 3.0, 4.0)
    val expected = dense.map(row => row.zip(x.toList).map(_ * _).sum)
    assertEquals(m.multiply(x).toList, expected)
  }

  test("vstack concatenates rows and preserves each block") {
    val a = SparseMatrix.fromDense(Seq(Seq(1.0, 2.0)), cols = 2)
    val b = SparseMatrix.fromDense(Seq(Seq(0.0, 3.0), Seq(4.0, 0.0)), cols = 2)
    val s = SparseMatrix.vstack(Seq(a, b))
    assertEquals(s.rows, 3)
    assertEquals(s.nnz, 4)
    assertEquals(s.toDense.map(_.toList).toList, List(List(1.0, 2.0), List(0.0, 3.0), List(4.0, 0.0)))
  }

  test("vstack handles an empty block in the middle") {
    val a = SparseMatrix.fromDense(Seq(Seq(1.0, 2.0)), cols = 2)
    val s = SparseMatrix.vstack(Seq(a, SparseMatrix.zeros(2, 2), a))
    assertEquals(s.rows, 4)
    assertEquals(s.nnz, 4)
    assertEquals(s.multiply(IArray(1.0, 1.0)).toList, List(3.0, 0.0, 0.0, 3.0))
  }

  test("norms") {
    val m = SparseMatrix.fromDense(Seq(Seq(1.0, -2.0), Seq(3.0, 0.0)), cols = 2)
    assertEquals(m.maxAbs, 3.0)
    assertEquals(m.normInf, 3.0) // row 0 sums to 3, row 1 to 3
    assertEqualsDouble(m.normFrobenius, math.sqrt(14.0), 1e-12)
  }

  test("spectralNormBound is an upper bound, including where normInf is not") {
    // Diagonal: largest singular value is the largest |entry|, and both induced
    // norms agree with it.
    val diagonal = SparseMatrix.fromTriplets(3, 3, Seq((0, 0, 2.0), (1, 1, -7.0), (2, 2, 3.0)))
    assertEqualsDouble(diagonal.norm1, 7.0, 1e-12)
    assertEqualsDouble(diagonal.normInf, 7.0, 1e-12)
    assert(diagonal.spectralNormBound >= 7.0 - 1e-12)

    // A column of ones is the counterexample to using normInf alone: its
    // spectral norm is sqrt(m) while every row sums to 1.
    val m      = 25
    val column = SparseMatrix.fromTriplets(m, 1, (0 until m).map(r => (r, 0, 1.0)))
    assertEqualsDouble(column.normInf, 1.0, 1e-12)
    assertEqualsDouble(column.norm1, m.toDouble, 1e-12)
    assertEqualsDouble(column.spectralNormBound, math.sqrt(m.toDouble), 1e-12)
    assert(column.spectralNormBound > column.normInf)
  }

  test("non-finite entries are rejected at construction") {
    // An infinite entry survives arithmetic but turns a scaling factor into
    // zero, surfacing much later as a NaN variable bound.
    intercept[IllegalArgumentException] {
      SparseMatrix.fromTriplets(1, 1, Seq((0, 0, Double.PositiveInfinity)))
    }
    intercept[IllegalArgumentException] {
      SparseMatrix.fromTriplets(1, 1, Seq((0, 0, Double.NaN)))
    }
  }

  test("scaledBy applies row and column diagonals") {
    val m      = SparseMatrix.fromDense(Seq(Seq(1.0, 2.0), Seq(3.0, 4.0)), cols = 2)
    val scaled = m.scaledBy(Array(2.0, 10.0), Array(1.0, 0.5))
    assertEquals(scaled.toDense.map(_.toList).toList, List(List(2.0, 2.0), List(30.0, 20.0)))
  }

  test("construction rejects out-of-range indices") {
    interceptMessage[IllegalArgumentException]("requirement failed: row index 2 out of range [0, 2)") {
      SparseMatrix.fromTriplets(2, 2, Seq((2, 0, 1.0)))
    }
  }
