package org.noaidi.prima

import scala.collection.mutable

/** Problem reductions applied before the iteration, and undone after it.
  *
  * A first-order method spends its whole budget on the constraint matrix it is
  * given, so anything removable is pure waste. On Netlib the cost of not doing
  * this is measurable: five feasible instances and seventeen infeasible ones
  * stop at the iteration limit rather than converging.
  *
  * Every reduction here is '''exact''' — none is a heuristic or a tolerance
  * judgement. A variable whose bounds coincide has one possible value; a row
  * with one entry is a bound in disguise; a row with no entries either holds or
  * proves the problem infeasible. Approximate reductions exist and are where
  * most of the remaining performance is, but they can change the answer, and
  * that is a different kind of decision from these.
  *
  * ==Postsolve==
  *
  * Reductions are only usable if the original solution can be recovered, and
  * for this project that means '''duals as well as primal values''' — nodal
  * prices are a first-class output of the power-system layer above, not a
  * diagnostic. Each reduction therefore records what it needs to invert itself,
  * and [[PresolveResult.restore]] replays that in reverse.
  */
object Presolve:

  /** What was removed, and how to put it back. */
  private[prima] enum Undo:
    /** A variable pinned to a single value by its own bounds. */
    case FixedColumn(index: Int, value: Double)

    /** A variable appearing in no row, driven to whichever bound its cost
      * prefers.
      */
    case EmptyColumn(index: Int, value: Double, reducedCost: Double)

    /** A row with no entries. It constrains nothing and its dual is zero. */
    case EmptyRow(index: Int)

    /** A row with one entry, absorbed into that variable's bounds. Its dual is
      * recovered from the surviving reduced cost.
      */
    case SingletonRow(index: Int, column: Int, coefficient: Double)

  final case class Stats(
      fixedColumns: Int,
      emptyColumns: Int,
      emptyRows: Int,
      singletonRows: Int,
  ):
    def total: Int = fixedColumns + emptyColumns + emptyRows + singletonRows
    def isEmpty: Boolean = total == 0

    override def toString: String =
      s"fixed=$fixedColumns emptyCols=$emptyColumns emptyRows=$emptyRows singletonRows=$singletonRows"

  /** A reduced problem plus everything needed to map a solution back. */
  final class PresolveResult private[prima] (
      val original: LpProblem,
      val reduced: LpProblem,
      val stats: Stats,
      private val undo: List[Undo],
      private val columnMap: Array[Int],
      private val rowMap: Array[Int],
      val provenInfeasible: Boolean,
  ):

    /** Map a solution of the reduced problem back onto the original.
      *
      * The objective is recomputed from the restored primal rather than carried
      * across, so a mistake in the mapping shows up as a changed objective
      * rather than as a plausible number attached to the wrong vector.
      */
    def restore(solution: LpSolution): LpSolution =
      val n = original.numVariables
      val m = original.numConstraints

      val primal  = new Array[Double](n)
      val dual    = new Array[Double](m)
      val reduced0 = new Array[Double](n)

      var j = 0
      while j < columnMap.length do
        val target = columnMap(j)
        if target >= 0 then
          primal(target) = solution.primal(j)
          reduced0(target) = solution.reducedCosts(j)
        j += 1

      var r = 0
      while r < rowMap.length do
        val target = rowMap(r)
        if target >= 0 then dual(target) = solution.dual(r)
        r += 1

      undo.foreach {
        case Undo.FixedColumn(index, value) =>
          primal(index) = value
          // A fixed variable's reduced cost absorbs whatever the rows charge
          // it; it is not determined by the reduced problem and is reported as
          // its raw objective coefficient less the row contributions, which are
          // recomputed below.
          reduced0(index) = Double.NaN
        case Undo.EmptyColumn(index, value, cost) =>
          primal(index) = value
          reduced0(index) = cost
        case Undo.EmptyRow(index) =>
          dual(index) = 0.0
        case Undo.SingletonRow(index, column, coefficient) =>
          // The row was folded into a bound. If the variable ended up strictly
          // inside that bound the row is slack and priced at zero; otherwise
          // the bound is active and carries the price.
          val rc = reduced0(column)
          dual(index) = if rc.isNaN || coefficient == 0.0 then 0.0 else rc / coefficient
      }

      // Reduced costs for fixed columns, and a corrected objective, both from
      // the restored vectors rather than from anything carried across.
      val ktY = new Array[Double](n)
      original.constraintMatrix.transpose.multiplyInto(dual, ktY)
      var i = 0
      while i < n do
        if reduced0(i).isNaN then reduced0(i) = original.objectiveRaw(i) - ktY(i)
        i += 1

      val restoredPrimal = Unsafe.wrap(primal)
      solution.copy(
        primal = restoredPrimal,
        dual = Unsafe.wrap(dual),
        reducedCosts = Unsafe.wrap(reduced0),
        objectiveValue = original.primalObjective(restoredPrimal),
        kkt = Kkt.evaluate(original, primal, dual),
      )

  end PresolveResult

  /** Reduce `problem` as far as the exact rules allow.
    *
    * Applied repeatedly until nothing changes, because the reductions feed each
    * other: folding a singleton row into a bound can fix a variable, and
    * removing a fixed variable can empty a row.
    */
  def apply(problem: LpProblem, maxPasses: Int = 20): PresolveResult =
    val n = problem.numVariables
    val m = problem.numConstraints

    val colAlive = Array.fill(n)(true)
    val rowAlive = Array.fill(m)(true)
    val lower    = problem.lowerRaw.clone()
    val upper    = problem.upperRaw.clone()
    val rhs      = problem.rhsRaw.clone()
    // Contribution of already-fixed variables, subtracted from each row's rhs.
    val fixedShift = new Array[Double](m)
    val fixedValue = new Array[Double](n)

    val undo = mutable.ListBuffer.empty[Undo]
    var fixedColumns  = 0
    var emptyColumns  = 0
    var emptyRows     = 0
    var singletonRows = 0
    var infeasible    = false

    val matrix    = problem.constraintMatrix
    val transpose = matrix.transpose

    def rowEntries(r: Int): Seq[(Int, Double)] =
      (matrix.rowPtr(r) until matrix.rowPtr(r + 1))
        .map(p => (matrix.colIndices(p), matrix.values(p)))
        .filter((c, _) => colAlive(c))

    def colEntries(c: Int): Seq[(Int, Double)] =
      (transpose.rowPtr(c) until transpose.rowPtr(c + 1))
        .map(p => (transpose.colIndices(p), transpose.values(p)))
        .filter((r, _) => rowAlive(r))

    def fixColumn(c: Int, value: Double): Unit =
      colAlive(c) = false
      fixedValue(c) = value
      fixedColumns += 1
      undo.prepend(Undo.FixedColumn(c, value))
      colEntries(c).foreach { (r, a) => fixedShift(r) += a * value }

    var pass    = 0
    var changed = true
    while changed && pass < maxPasses && !infeasible do
      changed = false
      pass += 1

      // Variables pinned by their own bounds carry no freedom.
      var c = 0
      while c < n && !infeasible do
        if colAlive(c) && lower(c) == upper(c) && lower(c).isFinite then
          fixColumn(c, lower(c))
          changed = true
        c += 1

      var r = 0
      while r < m && !infeasible do
        if rowAlive(r) then
          val entries  = rowEntries(r)
          val adjusted = rhs(r) - fixedShift(r)
          val isEquality = r < problem.numEqualities

          if entries.isEmpty then
            // Nothing left to satisfy it with: either it holds outright or the
            // problem has no solution at all.
            val holds = if isEquality then math.abs(adjusted) <= 1e-9 else adjusted <= 1e-9
            if !holds then infeasible = true
            else
              rowAlive(r) = false
              emptyRows += 1
              undo.prepend(Undo.EmptyRow(r))
              changed = true
          else if entries.length == 1 then
            // One entry means the row is a bound on that variable in disguise.
            val (col, a) = entries.head
            val bound    = adjusted / a
            var lo       = lower(col)
            var hi       = upper(col)
            if isEquality then
              lo = math.max(lo, bound)
              hi = math.min(hi, bound)
            else if a > 0.0 then lo = math.max(lo, bound)
            else hi = math.min(hi, bound)

            if lo > hi + 1e-9 then infeasible = true
            else
              lower(col) = lo
              upper(col) = math.max(hi, lo)
              rowAlive(r) = false
              singletonRows += 1
              undo.prepend(Undo.SingletonRow(r, col, a))
              changed = true
        r += 1

      // Variables in no surviving row are decided by their cost alone.
      c = 0
      while c < n && !infeasible do
        if colAlive(c) && colEntries(c).isEmpty then
          val cost = problem.objectiveRaw(c)
          val at =
            if cost > 0.0 then lower(c)
            else if cost < 0.0 then upper(c)
            else if lower(c).isFinite then lower(c)
            else if upper(c).isFinite then upper(c)
            else 0.0
          if !at.isFinite then
            // The objective can run away along this variable and no row stops
            // it. That is unboundedness, which presolve records by removing the
            // column at zero and leaving the solver to report the status; it is
            // emphatically not a reason to touch the infeasibility flag.
            colAlive(c) = false
            emptyColumns += 1
            undo.prepend(Undo.EmptyColumn(c, 0.0, cost))
          else
            colAlive(c) = false
            fixedValue(c) = at
            emptyColumns += 1
            undo.prepend(Undo.EmptyColumn(c, at, cost))
          changed = true
        c += 1

    val stats = Stats(fixedColumns, emptyColumns, emptyRows, singletonRows)

    if infeasible then
      new PresolveResult(problem, problem, stats, Nil, Array.empty, Array.empty, provenInfeasible = true)
    else
      // Renumber what survives, keeping a map back to the original indices.
      val columnMap = Array.fill(n)(-1)
      val rowMap    = Array.fill(m)(-1)
      var nextCol   = 0
      var i         = 0
      while i < n do
        if colAlive(i) then
          columnMap(nextCol) = i
          nextCol += 1
        i += 1
      var nextRow = 0
      i = 0
      while i < m do
        if rowAlive(i) then
          rowMap(nextRow) = i
          nextRow += 1
        i += 1

      val oldToNewCol = Array.fill(n)(-1)
      i = 0
      while i < nextCol do
        oldToNewCol(columnMap(i)) = i
        i += 1

      val builder = LpProblem.builder(nextCol)
      // Fixed variables contribute a constant to the objective.
      var offset = problem.objectiveOffset
      i = 0
      while i < n do
        if !colAlive(i) then offset += problem.objectiveRaw(i) * fixedValue(i)
        i += 1
      builder.objectiveOffset(offset)

      i = 0
      while i < nextCol do
        val old = columnMap(i)
        builder.objectiveCoefficient(i, problem.objectiveRaw(old))
        builder.bounds(i, lower(old), upper(old))
        i += 1

      // Equalities must still precede inequalities, and the builder handles
      // that ordering itself.
      i = 0
      while i < nextRow do
        val old     = rowMap(i)
        val entries = rowEntries(old).map((c, a) => (oldToNewCol(c), a))
        val value   = rhs(old) - fixedShift(old)
        if old < problem.numEqualities then builder.equalityConstraint(entries, value)
        else builder.greaterThan(entries, value)
        i += 1

      val (reducedProblem, _) = builder.build()
      new PresolveResult(
        problem,
        reducedProblem,
        stats,
        undo.toList,
        java.util.Arrays.copyOf(columnMap, nextCol),
        java.util.Arrays.copyOf(rowMap, nextRow),
        provenInfeasible = false,
      )

end Presolve
