package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.Allocation
import io.computenode.cyfra.runtime.VkCyfraRuntime

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue}
import scala.util.control.NonFatal

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
                try done.complete(work(runtime, allocation))
                catch case NonFatal(e) => done.completeExceptionally(e)
        }
      finally runtime.close()
    catch
      // A failure before the allocation opens -- no Vulkan driver, no device --
      // would otherwise leave every caller blocked on `ready` forever.
      case NonFatal(e) => ready.completeExceptionally(e)

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
    if !thread.isAlive then throw new IllegalStateException("the Cyfra device loop has been closed")
    val done = new CompletableFuture[Any]()
    queue.put(Run(body, done))
    try done.join().asInstanceOf[A]
    catch
      case e: java.util.concurrent.CompletionException if e.getCause != null =>
        throw e.getCause

  /** Idempotent, and joins: the allocation's buffers are destroyed on the
    * device thread as it unwinds, and a caller that returned before that
    * happened would be free to open a second runtime against a device still
    * holding the first one's memory.
    */
  def close(): Unit =
    if thread.isAlive then
      queue.put(Stop)
      thread.join()

end DeviceLoop
