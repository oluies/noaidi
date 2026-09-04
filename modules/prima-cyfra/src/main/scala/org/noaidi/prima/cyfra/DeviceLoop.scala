package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.Allocation
import io.computenode.cyfra.runtime.VkCyfraRuntime

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue}

/** A Vulkan runtime and one open allocation, held for as long as the caller
  * wants them, on the single thread that is allowed to touch them.
  *
  * Cyfra hands out an `Allocation` only for the duration of a callback —
  * `withAllocation` closes it on the way out, destroying every buffer allocated
  * inside. That is the right shape for a one-shot dispatch and the wrong one
  * for [[org.noaidi.prima.kernels.Kernels]], whose whole point is that a
  * backend allocates once and the solver then calls into it a few hundred
  * thousand times. Re-entering `withAllocation` per operation would re-upload
  * the constraint matrix on every sparse product.
  *
  * So the callback is parked on a thread of its own and fed work through a
  * queue. The thread is also a requirement rather than a convenience: Cyfra
  * takes its command pool and descriptor-set manager from a
  * `VulkanThreadContext`, so every call against one allocation has to come from
  * the thread that opened it.
  */
private[cyfra] final class DeviceLoop extends AutoCloseable:

  private sealed trait Task
  private case object Stop extends Task
  private final case class Run(body: (VkCyfraRuntime, Allocation) => Any, done: CompletableFuture[Any]) extends Task

  private val queue = new LinkedBlockingQueue[Task]()
  private val ready = new CompletableFuture[Unit]()

  /** Set before the queue is drained, and the only honest liveness answer.
    *
    * `Thread.isAlive` is not one: the drain is the last thing the thread does,
    * so the thread is alive throughout it and for the interval between its
    * return and actual termination. A `submit` whose `put` lands in that window
    * sees `isAlive` twice and is never drained by anyone -- the hang the
    * re-check was written to remove.
    */
  @volatile private var dead = false

  private val body: Runnable = () =>
    try
      val runtime = VkCyfraRuntime()
      try
        runtime.withAllocation { allocation =>
          ready.complete(())
          var running = true
          while running do
            queue.take() match
              case Stop => running = false
              case Run(work, done) =>
                // `Throwable`, not `NonFatal`. The failure this thread most
                // has to survive is a `LinkageError` -- `UnsatisfiedLinkError`
                // from a host with no natives, `NoClassDefFoundError` from a
                // Cyfra class first touched inside a dispatch -- and
                // `NonFatal` excludes every one of them by definition. An
                // unmatched throw here unwinds the loop, kills the thread, and
                // leaves the caller parked on `done.join()` with no timeout:
                // a hang where the whole point was to report a nameable error.
                try done.complete(work(runtime, allocation))
                catch case e: Throwable => done.completeExceptionally(e)
        }
      finally runtime.close()
    catch
      // Likewise, and for the reason `build.sbt` gives in so many words: a host
      // without the LWJGL natives classifier fails here with an
      // `UnsatisfiedLinkError`, which is a `LinkageError` and not `NonFatal`.
      // Leaving it unmatched would leave every caller blocked on `ready`.
      case e: Throwable => ready.completeExceptionally(e)
    finally
      // Belt and braces. Every path above completes one of the two, but a
      // future edit that adds a `return`, or a `Throwable` thrown from inside
      // a catch block, would not -- and the cost of that mistake is a hang
      // rather than a stack trace. Completing an already-completed future is a
      // no-op, so this is free when nothing went wrong.
      ready.completeExceptionally(
        new IllegalStateException("the Cyfra device thread ended before the allocation opened")
      ): Unit
      // Ordered: anything put after this is seen by `submit`'s own check, and
      // anything put before it is on the queue for the drain. There is no
      // interval where a task is neither.
      dead = true
      drainPending()

  private val thread = new Thread(body, "prima-cyfra-device")
  thread.setDaemon(true)
  thread.start()
  ready.join()

  /** Run `body` on the device thread and wait for it.
    *
    * Blocking is not a compromise here: the operations behind it either write
    * into device memory the next one reads, or are reductions the solver needs
    * a number back from. Cyfra's own submission is asynchronous underneath, and
    * that is where the latency that matters is hidden or not.
    */
  def submit[A](body: (VkCyfraRuntime, Allocation) => A): A =
    if dead then throw new IllegalStateException("the Cyfra device loop has been closed")
    val done = new CompletableFuture[Any]()
    queue.put(Run(body, done))
    // Re-checked after the put, not only before it, and against `dead` rather
    // than against the thread: a task that lands on a queue nobody will read
    // again has to be failed by whoever put it there, because the drain has
    // already run.
    if dead then
      done.completeExceptionally(
        new IllegalStateException("the Cyfra device thread is no longer running")
      ): Unit
    try done.join().asInstanceOf[A]
    catch
      case e: java.util.concurrent.CompletionException if e.getCause != null =>
        throw e.getCause

  /** Fail everything still queued when the device thread is gone.
    *
    * A caller that had already put its task on the queue would otherwise wait
    * on a `CompletableFuture` nobody is left to complete.
    */
  private def drainPending(): Unit =
    var task = queue.poll()
    while task != null do
      task match
        case Run(_, done) =>
          done.completeExceptionally(
            new IllegalStateException("the Cyfra device thread is no longer running")
          ): Unit
        case Stop => ()
      task = queue.poll()

  /** Idempotent, and joins: the allocation's buffers are destroyed on the
    * device thread as it unwinds, and a caller that returned before that
    * happened would be free to open a second runtime against a device still
    * holding the first one's memory.
    */
  def close(): Unit =
    if !dead && thread.isAlive then
      queue.put(Stop)
      thread.join()

end DeviceLoop
