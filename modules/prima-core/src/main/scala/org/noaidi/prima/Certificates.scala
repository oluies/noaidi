package org.noaidi.prima

/** Farkas-style certificates of primal infeasibility and unboundedness.
  *
  * A first-order method has no basis to read a status off, so infeasibility has
  * to be proven from the iterates themselves. PDHG's saving grace is that when
  * an LP is infeasible its dual iterates diverge along a ray that is itself the
  * certificate, so testing a few candidate directions at each evaluation point
  * is enough.
  *
  * Both tests are scale-invariant: a certificate may be multiplied by any
  * positive constant, so each direction is first normalised by its own
  * certificate value and the residuals are then compared against an absolute
  * tolerance. That avoids having to guess the magnitude a diverging iterate
  * happens to have reached.
  */
object Certificates:

  /** Evidence that `{x : Kx ~ q, l <= x <= u}` is empty.
    *
    * `y` certifies this when it lies in the dual cone, its induced reduced cost
    * `-K'y` is fully absorbed by the finite variable bounds, and the resulting
    * dual objective is strictly positive — the homogeneous dual has an
    * improving ray, so the primal cannot be feasible.
    *
    * Returns the normalised residual of the test: the smaller it is the better
    * `y` works as a certificate, and a caller declares infeasibility once it
    * falls under its tolerance. `None` means `y` is not even a candidate,
    * because its certificate value is not positive.
    */
  def primalInfeasibility(
      problem: LpProblem,
      y: Array[Double],
      ktY: Array[Double] | Null = null,
  ): Option[Double] =
    val m     = problem.numConstraints
    val n     = problem.numVariables
    val nEq   = problem.numEqualities
    val q     = problem.rhsRaw
    val lower = problem.lowerRaw
    val upper = problem.upperRaw

    val aty =
      if ktY != null then ktY
      else
        val buf = new Array[Double](n)
        problem.constraintMatrix.transpose.multiplyInto(y, buf)
        buf

    // Homogeneous problem: cost vector is zero, so the reduced cost is -K'y.
    var value  = 0.0
    var i      = 0
    while i < m do
      value += q(i) * y(i)
      i += 1

    var residualSq = 0.0
    i = 0
    while i < n do
      val reduced = -aty(i)
      if reduced > 0.0 then
        val l = lower(i)
        if l.isNegInfinity then residualSq += reduced * reduced else value += l * reduced
      else if reduced < 0.0 then
        val u = upper(i)
        if u.isPosInfinity then residualSq += reduced * reduced else value += u * reduced
      i += 1

    // The dual cone is violated wherever an inequality row's multiplier went
    // negative. Differences of iterates can leave the cone even though the
    // iterates themselves cannot.
    var coneSq = 0.0
    i = nEq
    while i < m do
      if y(i) < 0.0 then coneSq += y(i) * y(i)
      i += 1

    if !(value > 0.0) || !value.isFinite then None
    else Some(math.sqrt(residualSq + coneSq) / value).filter(_.isFinite)

  /** Evidence that the objective is unbounded below on the feasible set.
    *
    * `d` certifies this when it is a recession direction of the feasible region
    * — equality rows unchanged, inequality rows non-decreasing, and moving along
    * it never leaves a finite variable bound — while strictly decreasing the
    * objective.
    */
  def dualInfeasibility(
      problem: LpProblem,
      d: Array[Double],
      kd0: Array[Double] | Null = null,
  ): Option[Double] =
    val m     = problem.numConstraints
    val n     = problem.numVariables
    val nEq   = problem.numEqualities
    val c     = problem.objectiveRaw
    val lower = problem.lowerRaw
    val upper = problem.upperRaw

    var value = 0.0
    var i     = 0
    while i < n do
      value += c(i) * d(i)
      i += 1

    if !(value < 0.0) || !value.isFinite then None
    else
      val kd =
        if kd0 != null then kd0
        else
          val buf = new Array[Double](m)
          problem.constraintMatrix.multiplyInto(d, buf)
          buf

      var residualSq = 0.0
      i = 0
      while i < nEq do
        residualSq += kd(i) * kd(i)
        i += 1
      while i < m do
        if kd(i) < 0.0 then residualSq += kd(i) * kd(i)
        i += 1

      // Moving in direction `d` must not push a variable through a finite bound.
      i = 0
      while i < n do
        val di = d(i)
        if di > 0.0 && !upper(i).isPosInfinity then residualSq += di * di
        else if di < 0.0 && !lower(i).isNegInfinity then residualSq += di * di
        i += 1

      Some(math.sqrt(residualSq) / math.abs(value)).filter(_.isFinite)

  /** Test a direction against both certificates and report whichever holds.
    *
    * A direction cannot certify both, since a positive Farkas value and a
    * negative objective slope are mutually exclusive on the same vector, so the
    * order of these checks does not matter.
    */
  def classify(
      problem: LpProblem,
      x: Array[Double],
      y: Array[Double],
      tolerance: Double,
      kx: Array[Double] | Null = null,
      ktY: Array[Double] | Null = null,
  ): Option[SolveStatus] =
    if primalInfeasibility(problem, y, ktY).exists(_ <= tolerance) then
      Some(SolveStatus.PrimalInfeasible)
    else if dualInfeasibility(problem, x, kx).exists(_ <= tolerance) then
      Some(SolveStatus.DualInfeasible)
    else None

end Certificates
