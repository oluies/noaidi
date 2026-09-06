package org.noaidi.prima
package cyfra

import io.computenode.cyfra.runtime.VkCyfraRuntime

/** Scoped ownership of a Vulkan runtime.
  *
  * A `VkCyfraRuntime` holds a Vulkan instance and logical device. Constructing
  * one per test and dropping it leaks both, and pays full device-creation cost
  * every time. These files are the worked example a `CyfraKernels` author would
  * copy, and the rule established for `Pdhg.solveWith` — the backend's lifetime
  * belongs to the caller — should be visible here rather than contradicted.
  */
object CyfraRuntimeFixture:

  /** Run `f`, then release the runtime.
    *
    * This used to probe for `AutoCloseable` and document that on the pinned
    * release "the match most likely falls through and nothing is released".
    * That premise is false: `VkCyfraRuntime.close()` exists and is called
    * directly by `DeviceLoop`, so the probe was leaking a Vulkan instance and a
    * logical device per test on the strength of a caveat the module's own code
    * disproves.
    */
  def withRuntime[A](f: VkCyfraRuntime => A): A =
    val runtime = VkCyfraRuntime()
    try f(runtime)
    finally runtime.close()
