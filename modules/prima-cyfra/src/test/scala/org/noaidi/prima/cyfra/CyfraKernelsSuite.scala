package org.noaidi.prima
package cyfra

/** The whole [[org.noaidi.prima.kernels.Kernels]] contract, on the GPU.
  *
  * Nothing here is specific to Cyfra: `KernelContractSuite` is written against
  * the trait and this supplies one method. That was the claim the seam was
  * built on — "a new backend inherits the whole contract by supplying one
  * method" — and this is the first backend to test it rather than repeat it.
  *
  * It needs a working Vulkan device. On a machine without one the suite fails
  * rather than skips: `prima-cyfra` is outside the root build and is run
  * deliberately, so a silent pass on a machine that ran nothing would be a
  * worse answer than an error naming the missing driver.
  */
class CyfraKernelsSuite extends KernelContractSuite("cyfra-vulkan"):
  def newKernels(): kernels.Kernels = CyfraKernels()
