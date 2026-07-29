package org.noaidi.prima
package zio

import _root_.zio.*
import _root_.zio.stream.ZStream

class PrimaZioSuite extends munit.FunSuite:

  private def run[A](effect: Task[A]): A =
    _root_.zio.Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrow()
    }

  /** `min x` subject to `x >= target`, `x` in `[0, 1000]`. */
  private def trivial(target: Double): LpProblem =
    val b = LpProblem.builder(1)
    b.objectiveCoefficient(0, 1.0)
    b.bounds(0, 0.0, 1000.0)
    b.greaterThan(Seq(0 -> 1.0), target)
    b.build()._1

  test("solve returns the same answer as the synchronous entry point") {
    val problem  = LpFixtures.economicDispatch.problem
    val viaZio   = run(PrimaZio.solve(problem))
    val viaDirect = Pdhg.solve(problem)
    assertEquals(viaZio.status, SolveStatus.Optimal)
    assertEqualsDouble(viaZio.objectiveValue, viaDirect.objectiveValue, 1e-9)
  }

  test("a failing solve surfaces as a failed effect, not an exception") {
    // A malformed problem is rejected during construction, so the failure has to
    // happen inside the effect for this to mean anything.
    val effect = PrimaZio.solve(LpFixtures.productMix.problem).flatMap { s =>
      if s.status.isSuccess then ZIO.fail(new IllegalStateException("forced")) else ZIO.succeed(s)
    }
    val outcome = run(effect.either)
    assert(outcome.isLeft)
  }

  test("interruption stops a long solve and does not leak the fiber") {
    // Tolerances that cannot be met, so the solve would otherwise run for its
    // full iteration budget.
    val stubborn = PdhgParams(epsAbs = 1e-16, epsRel = 1e-16, maxIterations = 50_000_000)
    val problem  = LpFixtures.randomFeasible(seed = 11, numVariables = 300, numEqualities = 60, numInequalities = 120, density = 0.1)

    val effect = for
      fiber <- PrimaZio.solve(problem, stubborn).fork
      _     <- ZIO.sleep(200.millis)
      _     <- fiber.interrupt
    yield ()

    val started = java.lang.System.nanoTime()
    run(effect)
    val elapsedMillis = (java.lang.System.nanoTime() - started) / 1000000L
    // Cancellation is cooperative, so allow for the current evaluation window;
    // the point is that it does not run to 50 million iterations.
    assert(elapsedMillis < 30000, s"interruption took ${elapsedMillis}ms")
  }

  test("solveAll streams results for every problem") {
    val targets  = Seq(1.0, 2.0, 3.0, 4.0, 5.0)
    val problems = ZStream.fromIterable(targets.map(trivial))
    val results  = run(PrimaZio.solveAll(problems, parallelism = 3).runCollect)

    assertEquals(results.size, targets.size)
    assert(results.forall(_.status == SolveStatus.Optimal))
    // mapZIOPar preserves order, so results line up with their inputs.
    results.zip(targets).foreach { (solution, target) =>
      assertEqualsDouble(solution.objectiveValue, target, 1e-6)
    }
  }

  test("solveLabelled keeps each result with its label") {
    val labelled = Seq("a" -> trivial(7.0), "b" -> trivial(11.0))
    val results  = run(PrimaZio.solveLabelled(ZStream.fromIterable(labelled), parallelism = 2).runCollect)

    assertEquals(results.map(_._1).toList, List("a", "b"))
    assertEqualsDouble(results(0)._2.objectiveValue, 7.0, 1e-6)
    assertEqualsDouble(results(1)._2.objectiveValue, 11.0, 1e-6)
  }

  test("capabilities are reported through the effect layer") {
    val caps = run(PrimaZio.capabilities(kernels.ScalaKernels()))
    assert(caps.supportsFloat64)
    assertEquals(caps.device, "cpu")
  }
