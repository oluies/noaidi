package org.noaidi.prima

import org.noaidi.prima.kernels.{Float32Kernels, ScalaKernels}

/** Behavioural contract for a [[org.noaidi.prima.kernels.Kernels]] backend.
  *
  * Written against the trait rather than against `ScalaKernels`, so that the
  * Cyfra, CUDA and FPGA backends can be held to exactly the same expectations
  * by subclassing this and supplying their own instance.
  *
  * Tolerances come from the backend's own declared precision rather than being
  * fixed. Every accelerator worth having is float32-only — Cyfra's DSL has no
  * double type, and Apple GPUs expose none through Vulkan or Metal — so a
  * contract that demanded double-precision agreement would be one no real
  * backend could satisfy, and would get loosened for everyone the first time
  * one was written. Asking each backend for what it claims to offer keeps the
  * contract honest for both.
  */
abstract class KernelContractSuite(backendName: String) extends munit.FunSuite:

  /** A fresh backend for each test. */
  def newKernels(): kernels.Kernels

  private def withKernels[A](f: kernels.Kernels => A): A =
    val k = newKernels()
    try f(k)
    finally k.close()

  /** Elementwise comparison at the backend's declared precision.
    *
    * Anything that came out of arithmetic goes through here. Exact comparison
    * is reserved for the few results a backend must reproduce bit-for-bit —
    * zero-fill, copies, and clamps, which return a bound verbatim rather than
    * computing it — and those are called out where they appear.
    */
  private def assertVecEquals(actual: Array[Double], expected: Seq[Double], hint: String = ""): Unit =
    assertEquals(actual.length, expected.length, s"$hint: wrong length")
    actual.zip(expected).zipWithIndex.foreach { case ((a, e), i) =>
      assertEqualsDouble(a, e, tol * math.max(1.0, math.abs(e)), s"$hint: element $i")
    }

  /** Relative tolerance the backend is held to, from its declared precision.
    *
    * A little looser than the raw epsilon in each case, because a backend is
    * free to reduce in a different order — a GPU reduces in tree order and will
    * not match any host's sequential accumulation bit for bit.
    */
  // `lazy`, not `val`: this is a base class meant to be subclassed by device
  // backends, and a `val` here runs during superclass construction, before the
  // subclass's own fields are initialised. A `newKernels()` that reads a device
  // handle or config field would see it null and the suite would die with a
  // class-initialisation error instead of a test failure.
  private lazy val tol: Double =
    val k = newKernels()
    try if k.capabilities.supportsFloat64 then 1e-12 else 1e-6
    finally k.close()

  test(s"$backendName: upload and download round-trip") {
    withKernels { k =>
      // Exact, deliberately: these values are representable in every format a
      // backend might use, and a round-trip performs no arithmetic. A backend
      // that perturbs them is corrupting data, not losing precision.
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
      // Exact: zero is exactly representable everywhere and no arithmetic runs.
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
      assertVecEquals(host, Seq(8.0, 9.0), "spmv")
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
      assertVecEquals(host, Seq(-8.0, -16.0, -24.0), "axpby")

      // Writing into one of the inputs must give the same answer.
      k.axpby(2.0, x, -1.0, y, y)
      k.download(y, host)
      assertVecEquals(host, Seq(-8.0, -16.0, -24.0), "axpby aliased")
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
      assertVecEquals(host, Seq(-0.5, 0.5, 0.0), "primalStep")
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
      assertVecEquals(host, Seq(-2.0, 2.0), "primalStep with infinite bounds")
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
      assertVecEquals(host, Seq(-5.0, 5.0, 0.0, 5.0), "dualStep")
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

class Float32KernelsSuite extends KernelContractSuite("scala-float32"):
  def newKernels(): kernels.Kernels = Float32Kernels()

  test("the float32 backend asks for double-precision refinement") {
    val k = Float32Kernels()
    assert(!k.capabilities.supportsFloat64)
    assert(k.capabilities.requiresDoublePrecisionRefinement)
  }

  test("results are genuinely narrowed to single precision") {
    // The whole point of this backend is that it loses precision where a real
    // device would. If it did not, it would be a pointless copy of the
    // reference and would silently stop testing the mixed-precision path.
    val k = Float32Kernels()
    try
      // 1 + 2^-30 is representable in float64 and rounds to exactly 1 in float32.
      val epsilon = math.pow(2.0, -30)
      val x       = k.upload(Array(1.0 + epsilon))
      val host    = new Array[Double](1)
      k.download(x, host)
      assertEquals(host(0), 1.0, "value was not narrowed to float32")

      // And the reference backend keeps it, so the two really do differ.
      val ref     = ScalaKernels()
      val exact   = ref.upload(Array(1.0 + epsilon))
      val refHost = new Array[Double](1)
      ref.download(exact, refHost)
      assertNotEquals(refHost(0), 1.0)
      ref.close()
    finally k.close()
  }

  test("infinite bounds survive the narrowing") {
    // Variable bounds pass through `upload`, and an infinity that became
    // Float.MaxValue would silently turn a free variable into a bounded one.
    val k = Float32Kernels()
    try
      val v    = k.upload(Array(Double.NegativeInfinity, Double.PositiveInfinity, 1e300))
      val host = new Array[Double](3)
      k.download(v, host)
      assert(host(0).isNegInfinity)
      assert(host(1).isPosInfinity)
      // 1e300 exceeds float32's range and legitimately overflows; the point is
      // that the two infinities above were preserved deliberately, not by luck.
      assert(host(2).isPosInfinity)
    finally k.close()
  }
