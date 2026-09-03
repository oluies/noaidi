package org.noaidi.prima
package cyfra

import io.computenode.cyfra.core.layout.Layout
import io.computenode.cyfra.dsl.{*, given}
import io.computenode.cyfra.dsl.binding.GBuffer

/** The buffer sets each kernel binds, one case class per shape.
  *
  * They are top-level rather than nested because `Layout` is derived by a macro
  * over the product's fields, and a scalar the shader needs — `tau`, `alpha`,
  * the pair `(alpha, beta)` — has to arrive as one of them. Cyfra's DSL body
  * cannot read a dispatch parameter, so every number a kernel uses is either
  * captured when the program is built, which specialises it, or read out of a
  * buffer at run time, which does not. Sizes are captured; scalars are read.
  */
private[cyfra] object CyfraLayouts:

  /** `out := f(x)` with up to four scalars. */
  final case class Unary(x: GBuffer[Float32], out: GBuffer[Float32], params: GBuffer[Float32]) derives Layout

  /** `out := f(x, y)` with up to four scalars. `out` may alias either input. */
  final case class Binary(
      x: GBuffer[Float32],
      y: GBuffer[Float32],
      out: GBuffer[Float32],
      params: GBuffer[Float32],
  ) derives Layout

  final case class Spmv(
      rowPtr: GBuffer[Int32],
      colIndices: GBuffer[Int32],
      values: GBuffer[Float32],
      x: GBuffer[Float32],
      out: GBuffer[Float32],
  ) derives Layout

  final case class Primal(
      x: GBuffer[Float32],
      ktY: GBuffer[Float32],
      cost: GBuffer[Float32],
      lower: GBuffer[Float32],
      upper: GBuffer[Float32],
      out: GBuffer[Float32],
      params: GBuffer[Float32],
  ) derives Layout

  final case class Dual(
      y: GBuffer[Float32],
      kxBar: GBuffer[Float32],
      rhs: GBuffer[Float32],
      out: GBuffer[Float32],
      params: GBuffer[Float32],
  ) derives Layout

  /** A reduction's inputs and its per-lane partial sums.
    *
    * `y` is bound even for a single-argument reduction, where the caller passes
    * the same buffer twice: `||x||^2` is `x . x`, and one kernel that squares
    * what it multiplies is one kernel to get right rather than two.
    */
  final case class Reduce(x: GBuffer[Float32], y: GBuffer[Float32], partials: GBuffer[Float32]) derives Layout

  /** One buffer, so a submission can be forced through whatever last touched it. */
  final case class One(buffer: GBuffer[Float32]) derives Layout
