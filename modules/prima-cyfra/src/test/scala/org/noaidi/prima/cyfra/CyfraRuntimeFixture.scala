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

  /** Run `f` with a runtime that is released even if `f` throws. */
  def withRuntime[A](f: VkCyfraRuntime => A): A =
    val runtime = VkCyfraRuntime()
    try f(runtime)
    finally
      // The RC does not expose a documented shutdown; this releases what it can
      // and is the seam to tighten when one appears.
      runtime match
        case closeable: AutoCloseable => closeable.close()
        case _                        => ()
