package org.noaidi.prima

/** Zero-cost bridges between the immutable arrays the public API speaks in and
  * the primitive arrays the kernels work over.
  *
  * `IArray` is an opaque alias for `Array` and erases to it, so both directions
  * are casts the JIT never sees. They are safe exactly as long as the two rules
  * below hold, which is why every conversion in the codebase goes through here
  * rather than being spelled out at the call site:
  *
  *   - `wrap` may only be applied to an array the caller solely owns and will
  *     never write to again (typically one freshly allocated in the same method).
  *   - `raw` yields an array that must be treated as read-only.
  */
private[prima] object Unsafe:

  inline def wrap(a: Array[Double]): IArray[Double] = a.asInstanceOf[IArray[Double]]
  inline def wrapInts(a: Array[Int]): IArray[Int]   = a.asInstanceOf[IArray[Int]]
  inline def raw(a: IArray[Double]): Array[Double]  = a.asInstanceOf[Array[Double]]

end Unsafe
