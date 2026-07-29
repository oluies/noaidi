package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.{GBufferRegion, GProgram}
import io.computenode.cyfra.core.GProgram.StaticDispatch
import io.computenode.cyfra.core.layout.Layout
import io.computenode.cyfra.dsl.{*, given}
import io.computenode.cyfra.runtime.VkCyfraRuntime

/** Does Cyfra run on this machine at all?
  *
  * Before asking whether a CSR sparse product can be expressed in the DSL, this
  * establishes that the whole stack — Scala to SPIR-V, LWJGL to the Vulkan
  * loader, MoltenVK to Metal — is present and working. A failure here is an
  * environment problem, not a design one, and the two are worth telling apart
  * before spending effort on a kernel.
  */
class VulkanSmokeTest extends munit.FunSuite:

  case class DoubleLayout(input: GBuffer[Float32], output: GBuffer[Float32]) derives Layout

  private val size = 256

  private val doubleProgram: GProgram[Int, DoubleLayout] = GProgram[Int, DoubleLayout](
    layout = n => DoubleLayout(input = GBuffer[Float32](n), output = GBuffer[Float32](n)),
    dispatch = (_, n) => StaticDispatch(((n + 255) / 256, 1, 1)),
    workgroupSize = (256, 1, 1),
  ): layout =>
    val idx = GIO.invocationId
    GIO.when(idx < size):
      val value = GIO.read(layout.input, idx)
      GIO.write(layout.output, idx, value * 2.0f)

  test("a trivial kernel compiles to SPIR-V and executes on the GPU") {
    given runtime: VkCyfraRuntime = VkCyfraRuntime()

    val input   = Array.tabulate(size)(_.toFloat)
    val results = Array.ofDim[Float](size)

    GBufferRegion
      .allocate[DoubleLayout]
      .map(layout => doubleProgram.execute(size, layout))
      .runUnsafe(
        init = DoubleLayout(input = GBuffer(input), output = GBuffer[Float32](size)),
        onDone = layout => layout.output.readArray(results),
      )

    input.indices.foreach { i =>
      assertEqualsFloat(results(i), input(i) * 2.0f, 1e-5f, s"element $i")
    }
  }
