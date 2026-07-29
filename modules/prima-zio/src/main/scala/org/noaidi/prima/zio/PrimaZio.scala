package org.noaidi.prima
package zio

import _root_.zio.*
import _root_.zio.stream.ZStream
import java.util.concurrent.atomic.AtomicBoolean
import org.noaidi.prima.kernels.{KernelCapabilities, Kernels, ScalaKernels}

/** The effect boundary around Prima.
  *
  * The solver core is a pure function of its inputs and knows nothing about
  * effects; everything that makes a solve an interaction with the outside world
  * — occupying a thread for a long time, holding device memory, being cancelled
  * — is handled here.
  *
  * Two things are worth knowing about the semantics:
  *
  *   - Solves run on ZIO's blocking pool. A PDHG loop occupies its thread
  *     continuously for the whole solve, so running it anywhere else would
  *     starve the main runtime.
  *   - Interruption is cooperative and takes effect at the next evaluation
  *     point, a bounded number of iterations away. A JVM thread in a tight
  *     numeric loop cannot be stopped from outside, so an interrupted solve
  *     completes its current stretch of iterations before it unwinds.
  */
object PrimaZio:

  /** Solve on the blocking pool, honouring interruption. */
  def solve(problem: LpProblem, params: PdhgParams = PdhgParams.default): Task[LpSolution] =
    ZIO.scoped(scopedKernels(ScalaKernels()).flatMap(solveWith(problem, params, _)))

  /** Solve with a caller-supplied backend, for instance a GPU one whose device
    * memory should outlive a single solve.
    */
  def solveWith(problem: LpProblem, params: PdhgParams, kernels: Kernels): Task[LpSolution] =
    ZIO.succeed(new AtomicBoolean(false)).flatMap { flag =>
      ZIO.attemptBlockingCancelable(
        Pdhg.solveWith(problem, params, kernels, () => flag.get())
      )(ZIO.succeed(flag.set(true)))
    }

  /** A backend whose lifetime is tied to a scope, so device memory is released
    * even when the fiber using it is interrupted.
    */
  def scopedKernels[K <: Kernels](acquire: => K): ZIO[Scope, Nothing, K] =
    ZIO.acquireRelease(ZIO.succeed(acquire))(k => ZIO.succeed(k.close()))

  /** What a backend can do, so a caller can decide whether a double-precision
    * refinement pass is needed before trusting the result.
    */
  def capabilities(kernels: Kernels): UIO[KernelCapabilities] = ZIO.succeed(kernels.capabilities)

  /** Solve a stream of independent LPs with bounded parallelism.
    *
    * This is the shape the power-system work needs: a scenario sweep, a
    * contingency list or a run of snapshots is a stream of separate LPs, and
    * streaming them keeps memory flat where collecting them into a list would
    * not. Each solve gets its own kernel backend, since a backend is not
    * required to be thread-safe.
    */
  def solveAll(
      problems: ZStream[Any, Throwable, LpProblem],
      params: PdhgParams = PdhgParams.default,
      parallelism: Int = java.lang.Runtime.getRuntime.availableProcessors(),
  ): ZStream[Any, Throwable, LpSolution] =
    problems.mapZIOPar(parallelism)(solve(_, params))

  /** Solve a stream of LPs, keeping each result paired with whatever labelled
    * it — a snapshot timestamp, a contingency name, a scenario id.
    */
  def solveLabelled[A](
      problems: ZStream[Any, Throwable, (A, LpProblem)],
      params: PdhgParams = PdhgParams.default,
      parallelism: Int = java.lang.Runtime.getRuntime.availableProcessors(),
  ): ZStream[Any, Throwable, (A, LpSolution)] =
    problems.mapZIOPar(parallelism) { (label, problem) => solve(problem, params).map(label -> _) }

end PrimaZio
