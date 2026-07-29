package org.noaidi.prima

import org.noaidi.prima.kernels.ScalaKernels

/** Behavioural contract for a [[org.noaidi.prima.kernels.Kernels]] backend.
  *
  * Written against the trait rather than against `ScalaKernels`, so that the
  * Cyfra, CUDA and FPGA backends can be held to exactly the same expectations
  * by subclassing this and supplying their own instance. Tolerances are loose
  * enough to admit a different reduction order, which any parallel backend will
  * have, but not loose enough to admit a wrong answer.
  */
abstract class KernelContractSuite(backendName: String) extends munit.FunSuite:

  /** A fresh backend for each test. */
  def newKernels(): kernels.Kernels

  private def withKernels[A](f: kernels.Kernels => A): A =
    val k = newKernels()
    try f(k)
    finally k.close()

  private val tol = 1e-12

  test(s"$backendName: upload and download round-trip") {
    withKernels { k =>
      val data = Array(1.0, -2.5, 3.75, 0.0)
      val v    = k.upload(data)
      val out  = new Array[Double](4)
      k.download(v, out)
      assertEquals(out.toList, data.toList)
    }
  }

  test(s"$backendName: upload copies rather than aliases the caller's array") {
    withKernels { k =>
      val data = Array(1.0, 2.0)
      val v    = k.upload(data)
      data(0) = 99.0
      val out = new Array[Double](2)
      k.download(v, out)
      assertEquals(out(0), 1.0, "mutating the source array changed device state")
    }
  }

  test(s"$backendName: allocate yields zeros") {
    withKernels { k =>
      val out = new Array[Double](5)
      k.download(k.allocate(5), out)
      assertEquals(out.toList, List.fill(5)(0.0))
    }
  }

  test(s"$backendName: spmv matches the dense product") {
    withKernels { k =>
      val matrix = SparseMatrix.fromDense(Seq(Seq(1.0, 2.0, 0.0), Seq(0.0, -1.0, 3.0)), cols = 3)
      val m      = k.uploadMatrix(matrix)
      val x      = k.upload(Array(2.0, 3.0, 4.0))
      val out    = k.allocate(2)
      k.spmv(m, x, out)
      val host = new Array[Double](2)
      k.download(out, host)
      assertEqualsDouble(host(0), 8.0, tol)
      assertEqualsDouble(host(1), 9.0, tol)
    }
  }

  test(s"$backendName: axpby, including aliased output") {
    withKernels { k =>
      val x   = k.upload(Array(1.0, 2.0, 3.0))
      val y   = k.upload(Array(10.0, 20.0, 30.0))
      val out = k.allocate(3)
      k.axpby(2.0, x, -1.0, y, out)
      val host = new Array[Double](3)
      k.download(out, host)
      assertEquals(host.toList, List(-8.0, -16.0, -24.0))

      // Writing into one of the inputs must give the same answer.
      k.axpby(2.0, x, -1.0, y, y)
      k.download(y, host)
      assertEquals(host.toList, List(-8.0, -16.0, -24.0))
    }
  }

  test(s"$backendName: dot and squaredNorm") {
    withKernels { k =>
      val x = k.upload(Array(1.0, 2.0, 3.0))
      val y = k.upload(Array(4.0, -5.0, 6.0))
      assertEqualsDouble(k.dot(x, y), 12.0, tol)
      assertEqualsDouble(k.squaredNorm(x), 14.0, tol)
      assertEqualsDouble(k.squaredNorm(k.allocate(3)), 0.0, tol)
    }
  }

  test(s"$backendName: primalStep projects onto the box") {
    withKernels { k =>
      val x     = k.upload(Array(0.0, 0.0, 0.0))
      val ktY   = k.upload(Array(0.0, 0.0, 0.0))
      val cost  = k.upload(Array(1.0, -1.0, 0.0))
      val lower = k.upload(Array(-0.5, 0.0, -1.0))
      val upper = k.upload(Array(1.0, 0.5, 1.0))
      val out   = k.allocate(3)

      // Unprojected the step would be (-1, 1, 0); both bounds must bite.
      k.primalStep(x, ktY, cost, lower, upper, 1.0, out)
      val host = new Array[Double](3)
      k.download(out, host)
      assertEquals(host.toList, List(-0.5, 0.5, 0.0))
    }
  }

  test(s"$backendName: primalStep passes candidates through infinite bounds") {
    withKernels { k =>
      val x     = k.upload(Array(0.0, 0.0))
      val ktY   = k.upload(Array(0.0, 0.0))
      val cost  = k.upload(Array(1.0, -1.0))
      val lower = k.upload(Array(Double.NegativeInfinity, 0.0))
      val upper = k.upload(Array(0.0, Double.PositiveInfinity))
      val out   = k.allocate(2)

      k.primalStep(x, ktY, cost, lower, upper, 2.0, out)
      val host = new Array[Double](2)
      k.download(out, host)
      assertEquals(host.toList, List(-2.0, 2.0))
    }
  }

  test(s"$backendName: dualStep leaves equality rows free and clamps the rest") {
    withKernels { k =>
      val y     = k.upload(Array(0.0, 0.0, 0.0, 0.0))
      val kxBar = k.upload(Array(0.0, 0.0, 0.0, 0.0))
      val rhs   = k.upload(Array(-5.0, 5.0, -5.0, 5.0))
      val out   = k.allocate(4)

      // With two equality rows, the first two keep their negative values and the
      // last two are clamped at zero.
      k.dualStep(y, kxBar, rhs, 1.0, 2, out)
      val host = new Array[Double](4)
      k.download(out, host)
      assertEquals(host.toList, List(-5.0, 5.0, 0.0, 5.0))
    }
  }

  test(s"$backendName: reports its precision capability") {
    withKernels { k =>
      val caps = k.capabilities
      assert(caps.name.nonEmpty)
      assertEquals(caps.requiresDoublePrecisionRefinement, !caps.supportsFloat64)
    }
  }

end KernelContractSuite

class ScalaKernelsSuite extends KernelContractSuite("scala-reference"):
  def newKernels(): kernels.Kernels = ScalaKernels()

  test("the reference backend advertises full double precision") {
    val k = ScalaKernels()
    assert(k.capabilities.supportsFloat64)
    assert(!k.capabilities.requiresDoublePrecisionRefinement)
  }
