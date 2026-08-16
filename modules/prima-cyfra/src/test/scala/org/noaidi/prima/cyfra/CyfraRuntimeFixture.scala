package org.noaidi.prima
package cyfra

import io.computenode.cyfra.runtime.VkCyfraRuntime

/** Scoped ownership of a Vulkan runtime.
  *
  * A `VkCyfraRuntime` holds a Vulkan instance and logical device. Constructing
  * one per test and dropping it leaks both, and pays full device-creation cost
  * every time. Forking the test JVM limits the damage, but these files are the
  * worked example a `CyfraKernels` author will copy, and the rule established
  * for `Pdhg.solveWith` — the backend's lifetime belongs to the caller — should
  * be visible here rather than contradicted.
  */
object CyfraRuntimeFixture:

  /** Run `f`, then release the runtime if this version of Cyfra offers a way to.
    *
    * '''Best-effort, and on the pinned release probably a no-op.''' The `finally`
    * below closes the runtime only if `VkCyfraRuntime` happens to implement
    * `AutoCloseable`, and the release candidate exposes no documented shutdown —
    * so on current Cyfra the match most likely falls through and nothing is
    * released. Said plainly because this file is the worked example a
    * `CyfraKernels` author will copy, and a scaladoc promising scoped ownership
    * over a body that may do nothing is worse than no fixture: it reads as a
    * solved problem rather than a placeholder.
    *
    * What it does give is the '''shape''' — one seam, in one place, to tighten
    * when a shutdown appears. Test JVM forking is what actually bounds the leak
    * today.
    */
  def withRuntime[A](f: VkCyfraRuntime => A): A =
    val runtime = VkCyfraRuntime()
    try f(runtime)
    finally
      runtime match
        case closeable: AutoCloseable => closeable.close()
        case _                        => ()
