package org.noaidi.prima
package cyfra

import org.noaidi.prima.kernels.{Float32Kernels, ScalaKernels}

/** A whole LP solved on the GPU, then finished on the CPU.
  *
  * The contract suite says each kernel is right; this says the loop they make
  * up converges to the same answer as the reference. They are different claims:
  * every operation can agree elementwise while the solve still diverges,
  * because the step-size rule reads reductions back and a float32 reduction is
  * not the host's.
  *
  * The timing is printed rather than asserted. Wall clock on a laptop is not
  * something to gate a build on, and the number that matters here is not the
  * ratio anyway — it is the shape of where the time goes, which the report
  * makes visible.
  */
class CyfraSolveSuite extends munit.FunSuite:

  // These are benchmarks, and one of them runs a whole solve per backend on
  // three instances. munit's default half minute is a limit for a test that
  // hangs, not for one that is doing the work it says it is.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(5, "min")

  private val instances = Seq(
    "economic-dispatch" -> LpFixtures.conclusive.find(_.name == "economic-dispatch").get.problem,
    "random-60x30"      -> LpFixtures.randomFeasible(1, 60, 10, 20, 0.25),
    "random-200x120"    -> LpFixtures.randomFeasible(2, 200, 40, 80, 0.10),
  )

  private val tolerance = 1e-6
  private val params    = PdhgParams(epsAbs = tolerance, epsRel = tolerance, maxIterations = 100_000)

  test("a mixed-precision solve on the GPU reaches the reference answer") {
    instances.foreach { (name, problem) =>
      val expected = Pdhg.solve(problem, params)
      assertEquals(expected.status, SolveStatus.Optimal, s"$name: the reference solve")

      val device = CyfraKernels()
      val result =
        try MixedPrecision.solve(problem, params, device)
        finally device.close()

      assertEquals(result.solution.status, SolveStatus.Optimal, s"$name: the GPU solve")
      // The answer comes from the double-precision refinement in both cases, so
      // this is the tolerance the caller asked for and not a float32 one.
      val gap =
        math.abs(result.solution.objectiveValue - expected.objectiveValue) /
          math.max(1.0, math.abs(expected.objectiveValue))
      assert(gap < 1e-6, s"$name: objective disagreed by $gap")
    }
  }

  test("where the time goes, against the CPU running the same arithmetic") {
    println(
      f"%n${"instance"}%-20s ${"backend"}%-16s ${"iters"}%7s ${"ms"}%8s ${"us/iter"}%9s"
    )
    instances.foreach { (name, problem) =>
      val reducedParams = params.copy(epsAbs = 1e-5, epsRel = 1e-5, maxIterations = 20_000, infeasibilityTolerance = 1e-5)

      def timed(label: String, run: () => LpSolution): Unit =
        // One untimed run first: the first dispatch through Cyfra pays SPIR-V
        // compilation and first-touch device allocation, and the first CPU run
        // pays JIT. Neither is what this table is about.
        run()
        val started  = System.nanoTime()
        val solution = run()
        val micros   = (System.nanoTime() - started) / 1000.0
        println(
          f"$name%-20s $label%-16s ${solution.iterations}%7d ${micros / 1000.0}%8.1f " +
            f"${micros / math.max(1, solution.iterations)}%9.1f"
        )

      timed("cpu fp64", () => Pdhg.solve(problem, params))
      timed(
        "cpu fp32",
        () =>
          val k = Float32Kernels()
          try Pdhg.solveWith(problem, reducedParams, k)
          finally k.close(),
      )
      timed(
        "gpu fp32",
        () =>
          val k = CyfraKernels()
          try Pdhg.solveWith(problem, reducedParams, k)
          finally k.close(),
      )
    }
  }

  test("the per-dispatch cost does not move with the size of the dispatch") {
    // The table above shows the same ~4 ms per iteration on a 21-variable
    // problem and on a 200-variable one, which is already most of the argument:
    // a cost that does not move with the work is not the work. This says where
    // it does go, by separating three kinds of operation over a thousandfold
    // range of vector length:
    //
    //   - `copy` is a bare dispatch;
    //   - `axpby` is a dispatch plus a scalar written to the device, which on
    //     this release means a staging buffer, a copy command buffer and a
    //     pending execution, because every `GBuffer` Cyfra hands out is
    //     device-local and there is no host-visible one to ask for;
    //   - `squaredNorm` is a dispatch plus a blocking read back, which stages
    //     the same way in the other direction.
    val rounds = 200

    Seq(64, 1024, 4096, 65536).foreach { n =>
      val k = CyfraKernels()
      try
        val x    = k.upload(Array.tabulate(n)(i => (i % 17).toDouble))
        val y    = k.upload(Array.tabulate(n)(i => (i % 13).toDouble))
        val out  = k.allocate(n)
        val host = new Array[Double](n)

        // Warm: SPIR-V compilation and first-touch device allocation are
        // once-per-solve costs, and this is about the per-iteration ones.
        (0 until 20).foreach(_ => k.axpby(1.0, x, 1.0, y, out))
        k.download(out, host)
        k.squaredNorm(x)

        def per(body: () => Unit): Double =
          val started = System.nanoTime()
          (0 until rounds).foreach(_ => body())
          k.download(out, host)
          (System.nanoTime() - started) / 1000.0 / rounds

        val copyMicros  = per(() => k.copy(x, out))
        val axpbyMicros = per(() => k.axpby(1.0, x, 1.0, y, out))
        val normMicros  = per(() => k.squaredNorm(x))

        println(
          f"n=$n%6d  copy ${copyMicros}%6.1f us   axpby ${axpbyMicros}%6.1f us   " +
            f"squaredNorm ${normMicros}%6.1f us"
        )
      finally k.close()
    }

    // What the hand-off to the device thread costs on its own, so the figures
    // above are not quietly measuring a queue.
    val loop = new DeviceLoop()
    try
      (0 until 20).foreach(_ => loop.submit((_, _) => ()))
      val idleStart = System.nanoTime()
      (0 until rounds).foreach(_ => loop.submit((_, _) => ()))
      println(f"device-thread hand-off alone: ${(System.nanoTime() - idleStart) / 1000.0 / rounds}%.1f us")
    finally loop.close()
  }

  test("chaining dispatches into one execution amortises nearly all of that") {
    // `Kernels` is a call-at-a-time interface and the cost above is charged per
    // call, so the question that decides whether a Cyfra backend can ever be
    // fast is whether Cyfra's own batching -- `addProgram`, which chains
    // programs into one execution, one command buffer and one submission --
    // amortises it. It does, almost completely: a chain of eight costs about
    // what one costs.
    //
    // That is a finding about the seam, not about the device. `Kernels` was
    // drawn so that a backend implements one operation at a time, and this says
    // the interface, not the GPU, is what sets the floor.
    val n      = 1024
    val rounds = 20

    val runtime = new io.computenode.cyfra.runtime.VkCyfraRuntime()
    try
      runtime.withAllocation { alloc =>
        given io.computenode.cyfra.core.Allocation = alloc
        given io.computenode.cyfra.runtime.VkCyfraRuntime = runtime
        import io.computenode.cyfra.core.{GExecution, GProgram}
        import io.computenode.cyfra.core.GProgram.StaticDispatch
        import io.computenode.cyfra.dsl.{*, given}
        import io.computenode.cyfra.dsl.binding.GBuffer
        import CyfraLayouts.{Binary, One}

        val program = GProgram[Unit, Binary](
          layout = _ =>
            Binary(
              x = GBuffer[Float32](n),
              y = GBuffer[Float32](n),
              out = GBuffer[Float32](n),
              params = GBuffer[Float32](4),
            ),
          dispatch = (_, _) => StaticDispatch(((n + 63) / 64, 1, 1)),
          workgroupSize = (64, 1, 1),
        ) { l =>
          val i = GIO.invocationId
          GIO.when(i < n)(GIO.write(l.out, i, GIO.read(l.x, i) + GIO.read(l.y, i)))
        }

        val layout = Binary(
          x = GBuffer(Array.tabulate(n)(i => (i % 17).toFloat)),
          y = GBuffer(Array.tabulate(n)(i => (i % 13).toFloat)),
          out = GBuffer(new Array[Float](n)),
          params = GBuffer(new Array[Float](4)),
        )

        // Submitting after every round rather than at the end. Cyfra keeps a
        // pending execution per `execute` and frees them when the allocation
        // closes, so leaving hundreds queued measures the queue.
        def sync(): Unit = alloc.submitLayout(One(layout.out))

        program.execute((), layout)
        sync()

        println(f"%n${"dispatches"}%11s ${"separate"}%12s ${"batched"}%12s")
        Seq(1, 2, 4, 8, 16).foreach { chain =>
          val batched = (0 until chain).foldLeft[GExecution[Unit, Binary, Binary]](GExecution[Unit, Binary]()) {
            (acc, _) => acc.addProgram(program)(identity, identity)
          }
          batched.execute((), layout)
          sync()

          val separateStart = System.nanoTime()
          (0 until rounds).foreach { _ =>
            (0 until chain).foreach(_ => program.execute((), layout))
            sync()
          }
          val separate = (System.nanoTime() - separateStart) / 1000.0 / rounds

          val batchedStart = System.nanoTime()
          (0 until rounds).foreach { _ =>
            batched.execute((), layout)
            sync()
          }
          val together = (System.nanoTime() - batchedStart) / 1000.0 / rounds

          println(f"$chain%11d ${separate}%9.0f us ${together}%9.0f us")
        }
      }
    finally runtime.close()
  }
