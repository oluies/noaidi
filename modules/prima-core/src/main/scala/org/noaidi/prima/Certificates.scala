package org.noaidi.prima

/** Farkas-style certificates of primal infeasibility and unboundedness.
  *
  * A first-order method has no basis to read a status off, so infeasibility has
  * to be proven from the iterates themselves. PDHG's saving grace is that when
  * an LP is infeasible its dual iterates diverge along a ray that is itself the
  * certificate, so testing a few candidate directions at each evaluation point
  * is enough.
  *
  * ==Normalisation==
  *
  * Both tests measure how far a direction falls short of being an exact
  * certificate. The measure must be '''dimensionless''': the same LP written in
  * different units has to get the same verdict, or the tolerance means nothing.
  *
  * Getting that right requires normalising each kind of violation against
  * something with matching units, which is why the terms are normalised
  * separately and only then combined:
  *
  *   - A dual-cone violation is a component of `y`, so `||y||` makes it
  *     dimensionless.
  *   - An unabsorbed reduced cost is a component of `K'y`, so it carries the
  *     units of the constraint matrix as well and needs `||K|| * ||y||`.
  *   - For the unboundedness test, `Kd` residuals likewise need `||K|| * ||d||`
  *     while bound-direction violations are components of `d` and need `||d||`.
  *
  * Two normalisations that look reasonable are not, and both were tried:
  *
  *   - Dividing by the certificate's own objective value is scale-invariant in
  *     the direction but lets a large right-hand side dilute a violation. On the
  *     feasible problem `x in [0, inf)` with the row `x >= 1e10`, the direction
  *     `y = 1` leaves an unabsorbed reduced cost of 1 against a value of 1e10 —
  *     which reads as 1e-10 and declares a feasible problem infeasible.
  *   - Dividing everything by `||y||` fixes that but leaves the matrix's units
  *     in the reduced-cost term, so the same hole reopens by shrinking a
  *     coefficient instead. On the feasible problem `x in [0, inf)` with the row
  *     `1e-10 x >= 1`, the direction `y = 1` reads as 1e-10 for the same reason.
  *
  * Reporting a feasible problem as infeasible is the one failure mode a solver
  * must not have, so no rescaling of the data may bring a violating direction
  * under the tolerance.
  */
object Certificates:

  /** Evidence that `{x : Kx ~ q, l <= x <= u}` is empty.
    *
    * `y` certifies this when it lies in the dual cone, its induced reduced cost
    * `-K'y` is fully absorbed by the finite variable bounds, and the resulting
    * dual objective is strictly positive — the homogeneous dual has an
    * improving ray, so the primal cannot be feasible.
    *
    * Returns how far `y` is from satisfying the first two conditions, as a
    * fraction of `||y||`: the worse of its dual-cone violation and its
    * unabsorbed reduced cost. A caller declares infeasibility once that falls
    * under its tolerance. `None` means `y` is not a candidate at all, either
    * because it is the zero vector or because its certificate value is not
    * positive.
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

    var normSq = 0.0
    var i      = 0
    while i < m do
      normSq += y(i) * y(i)
      i += 1
    if normSq <= 0.0 || !normSq.isFinite then return None
    val norm = math.sqrt(normSq)

    val aty =
      if ktY != null then ktY
      else
        val buf = new Array[Double](n)
        problem.constraintMatrix.transpose.multiplyInto(y, buf)
        buf

    // Homogeneous problem: the cost vector is zero, so the reduced cost is -K'y.
    var value = 0.0
    i = 0
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
    else
      // The two violations carry different units, so they are made
      // dimensionless separately and combined afterwards. A zero matrix norm
      // implies `K'y` is zero, hence no reduced-cost residual to scale.
      val matrixNorm  = problem.constraintMatrix.spectralNormBound
      val reducedTerm = if matrixNorm > 0.0 then math.sqrt(residualSq) / (matrixNorm * norm) else 0.0
      val coneTerm    = math.sqrt(coneSq) / norm
      Some(math.hypot(reducedTerm, coneTerm)).filter(_.isFinite)

  /** Evidence that the objective is unbounded below on the feasible set.
    *
    * `d` certifies this when it is a recession direction of the feasible region
    * — equality rows unchanged, inequality rows non-decreasing, and moving along
    * it never leaves a finite variable bound — while strictly decreasing the
    * objective.
    *
    * Returns the recession violation as a fraction of `||d||`, on the same
    * reasoning as [[primalInfeasibility]]: normalising by `|c'd|` would let one
    * large cost coefficient hide a direction that walks straight through a
    * finite bound.
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

    var normSq = 0.0
    var i      = 0
    while i < n do
      normSq += d(i) * d(i)
      i += 1
    if normSq <= 0.0 || !normSq.isFinite then return None
    val norm = math.sqrt(normSq)

    var value = 0.0
    i = 0
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

      // Row violations are components of `Kd`, bound violations are components
      // of `d`. Different units, so they are accumulated separately.
      var rowSq = 0.0
      i = 0
      while i < nEq do
        rowSq += kd(i) * kd(i)
        i += 1
      while i < m do
        if kd(i) < 0.0 then rowSq += kd(i) * kd(i)
        i += 1

      // Moving in direction `d` must not push a variable through a finite bound.
      var boundSq = 0.0
      i = 0
      while i < n do
        val di = d(i)
        if di > 0.0 && !upper(i).isPosInfinity then boundSq += di * di
        else if di < 0.0 && !lower(i).isNegInfinity then boundSq += di * di
        i += 1

      val matrixNorm = problem.constraintMatrix.spectralNormBound
      val rowTerm    = if matrixNorm > 0.0 then math.sqrt(rowSq) / (matrixNorm * norm) else 0.0
      val boundTerm  = math.sqrt(boundSq) / norm
      Some(math.hypot(rowTerm, boundTerm)).filter(_.isFinite)

  /** Test the primal and dual iterates for a certificate and report whichever
    * holds.
    *
    * The two tests read different vectors — infeasibility from `y`, unbounded-
    * ness from `x` — so both can pass at once. That is not a contradiction: an
    * LP can be both primal and dual infeasible. This reports primal
    * infeasibility in preference, because it is the answer that describes the
    * constraint set the caller wrote, and a caller needing both can run the two
    * tests directly.
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
