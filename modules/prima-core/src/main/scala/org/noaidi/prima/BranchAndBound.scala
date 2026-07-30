package org.noaidi.prima

import org.noaidi.prima.kernels.{Kernels, ScalaKernels}
import scala.collection.mutable

/** How a mixed-integer solve ended. */
enum MilpStatus:
  /** An optimal solution, proven within the gap tolerances. */
  case Optimal

  /** A feasible integer solution, but the gap was not closed before a limit was
    * reached. `bestBound` says how much might still be on the table.
    */
  case Feasible

  /** No integer solution exists. */
  case Infeasible

  /** The relaxation is unbounded, so the integer problem is unbounded or
    * infeasible. Which of the two is not determined here, for the same reason
    * [[Presolve]] reports `unboundedIfFeasible` rather than picking.
    */
  case Unbounded

  /** A limit was reached with no integer solution found, so nothing is known. */
  case NoSolutionFound

  def isConclusive: Boolean = this match
    case Optimal | Infeasible | Unbounded    => true
    case Feasible | NoSolutionFound          => false

/** Result of a branch-and-bound solve. */
final case class MilpSolution(
    status: MilpStatus,
    /** The incumbent, with integer coordinates snapped to whole numbers. Empty
      * when no integer solution was found.
      */
    primal: IArray[Double],
    objectiveValue: Double,
    /** The best proven bound over all unexplored nodes. Equals
      * [[objectiveValue]] to within the gap tolerance when [[status]] is
      * `Optimal`.
      */
    bestBound: Double,
    nodesExplored: Int,
    /** Relaxations that ended without a conclusive status.
      *
      * Non-zero means the search pruned or branched on at least one bound it
      * could not prove, so `Optimal` would be overstating the result — the
      * status is downgraded when this happens, and the count is reported rather
      * than folded away.
      */
    unprovenNodes: Int,
    solveTimeMillis: Long,
):
  def gap: Double = math.abs(objectiveValue - bestBound)

  override def toString: String =
    f"MilpSolution($status, obj=$objectiveValue%.10g, bound=$bestBound%.10g, " +
      f"nodes=$nodesExplored, unproven=$unprovenNodes, ${solveTimeMillis}ms)"

/** Controls for [[BranchAndBound]]. */
final case class BnbParams(
    /** Tolerances for each node's LP relaxation. */
    lp: PdhgParams = PdhgParams.default,

    /** How far from a whole number a variable may sit and still count as
      * integral.
      *
      * Cannot be tighter than the LP's own feasibility tolerance in any useful
      * way: a first-order method returns a point satisfying its constraints to
      * roughly `epsAbs`, so demanding integrality far below that just branches
      * forever on noise.
      */
    integralityTolerance: Double = 1e-6,

    /** Absolute and relative gap at which the incumbent is declared optimal. */
    gapAbs: Double = 1e-6,
    gapRel: Double = 1e-6,

    /** Safety margin subtracted from a node's bound before pruning against the
      * incumbent.
      *
      * This is the correction that makes branch-and-bound sound on top of an
      * '''inexact''' bound, and it has no counterpart in a simplex-based
      * implementation. Simplex returns a vertex whose objective is exact to
      * round-off, so `bound >= incumbent` is a safe pruning test. A first-order
      * method returns a point whose objective is accurate only to its own
      * convergence tolerance, so a node whose true bound is slightly below the
      * incumbent can report slightly above it — and pruning on that discards
      * the subtree containing the optimum, silently, returning a suboptimal
      * answer labelled `Optimal`.
      *
      * The margin is scaled by the node's own duality gap, so it adapts to how
      * well each relaxation actually converged rather than assuming the
      * requested tolerance was achieved.
      */
    pruningSafetyFactor: Double = 10.0,

    maxNodes: Int = 100_000,
    timeLimitMillis: Option[Long] = None,

    /** Warm-start each child from its parent's solution.
      *
      * A child differs from its parent in exactly one variable bound, so the
      * parent's iterate is usually an excellent starting point — this is the
      * property that makes a first-order method attractive inside
      * branch-and-bound at all. Left switchable because NOTES records a
      * warm-start regression on dense instances that is not yet explained.
      */
    warmStart: Boolean = true,
)

/** Branch-and-bound over a linear relaxation solved by [[Pdhg]].
  *
  * ==Why this is not the textbook algorithm==
  *
  * Branch-and-bound is normally described on top of an '''exact''' bound, and
  * every published correctness argument assumes one. Prima is a first-order
  * method: it returns a point converged to a tolerance, not a vertex, so the
  * objective it reports for a node is accurate only to roughly that tolerance.
  *
  * Two things follow, and both are handled explicitly rather than hoped past.
  *
  * '''Pruning needs a margin.''' A node whose true bound sits just below the
  * incumbent can report just above it, and pruning on that discards the subtree
  * holding the optimum while reporting success. So a node is pruned only when
  * its bound exceeds the incumbent by more than a multiple of that node's own
  * duality gap — the achieved accuracy, not the requested one. The cost is
  * exploring some nodes that an exact solver would have cut.
  *
  * '''A non-conclusive relaxation cannot be trusted either way.''' If a node's
  * LP hits its iteration limit, its objective is not a bound at all: pruning on
  * it is unsound, and so is declaring its solution integral. Such a node is
  * branched rather than pruned, and counted. Any incumbent found while such
  * nodes exist is reported as `Feasible` rather than `Optimal`, because
  * optimality was not proven — see [[MilpSolution.unprovenNodes]].
  *
  * ==Search==
  *
  * Depth-first, branching on the most fractional integer variable. Depth-first
  * because it finds an incumbent early — which is what makes pruning possible at
  * all — and because a child differs from its parent by one bound, so the
  * parent's solution warm-starts it well. Every open node carries the bound of
  * the parent that spawned it, so a valid global bound is available at all times
  * without a priority queue.
  */
object BranchAndBound:

  /** Solve `problem` with the given columns constrained to integers.
    *
    * Binary variables are just integer variables whose bounds are `[0, 1]`; no
    * separate concept is needed.
    */
  def solve(
      problem: LpProblem,
      integers: Set[Int],
      params: BnbParams = BnbParams(),
  ): MilpSolution =
    // One set of kernels for the whole search rather than one per node. `Pdhg.solve`
    // allocates and closes its own, which is right for a single LP and wasteful
    // across the thousands of relaxations a branch-and-bound tree can hold.
    val kernels = ScalaKernels()
    try solveWith(problem, integers, params, kernels)
    finally kernels.close()

  /** As [[solve]], against a caller-supplied kernel backend. */
  def solveWith(
      problem: LpProblem,
      integers: Set[Int],
      params: BnbParams,
      kernels: Kernels,
  ): MilpSolution =
    integers.foreach { j =>
      require(
        j >= 0 && j < problem.numVariables,
        s"integer column $j is out of range [0, ${problem.numVariables})",
      )
    }
    val started = System.currentTimeMillis()

    if integers.isEmpty then
      // No integrality to enforce, so the relaxation *is* the answer. Worth
      // short-circuiting rather than running one node, so a caller can hand this
      // a pure LP without paying for the machinery.
      val root = Pdhg.solveWith(problem, params.lp, kernels)
      return MilpSolution(
        status = root.status match
          case SolveStatus.Optimal          => MilpStatus.Optimal
          case SolveStatus.PrimalInfeasible => MilpStatus.Infeasible
          case SolveStatus.DualInfeasible   => MilpStatus.Unbounded
          case _                            => MilpStatus.NoSolutionFound,
        primal = root.primal,
        objectiveValue = root.objectiveValue,
        bestBound = root.objectiveValue,
        nodesExplored = 1,
        unprovenNodes = if root.status.isConclusive then 0 else 1,
        solveTimeMillis = System.currentTimeMillis() - started,
      )

    val ordered = integers.toIndexedSeq.sorted

    final case class Node(
        lower: IArray[Double],
        upper: IArray[Double],
        parentBound: Double,
        warm: Option[Pdhg.WarmStart],
    )

    val stack = mutable.Stack[Node]()
    stack.push(Node(problem.variableLower, problem.variableUpper, Double.NegativeInfinity, None))

    var incumbent: IArray[Double] = IArray.empty
    var incumbentValue            = Double.PositiveInfinity
    var nodes                     = 0
    var unproven                  = 0
    var rootUnbounded             = false

    // Bounds of nodes still on the stack, so the global bound is the best thing
    // any unexplored subtree could still deliver.
    def openBound: Double =
      if stack.isEmpty then incumbentValue
      else math.min(incumbentValue, stack.map(_.parentBound).min)

    def outOfTime: Boolean =
      params.timeLimitMillis.exists(System.currentTimeMillis() - started > _)

    while stack.nonEmpty && nodes < params.maxNodes && !outOfTime do
      val node = stack.pop()

      // Prune before solving: the parent's bound already exceeds the incumbent.
      if node.parentBound <= incumbentValue - 0.0 || incumbent.isEmpty then
        val sub = LpProblem(
          objective = problem.objective,
          constraintMatrix = problem.constraintMatrix,
          rhs = problem.rhs,
          numEqualities = problem.numEqualities,
          variableLower = node.lower,
          variableUpper = node.upper,
          objectiveOffset = problem.objectiveOffset,
        )

        val relaxation =
          if params.warmStart then Pdhg.solveWith(sub, params.lp, kernels, warmStart = node.warm)
          else Pdhg.solveWith(sub, params.lp, kernels)
        nodes += 1

        relaxation.status match
          case SolveStatus.PrimalInfeasible =>
            () // No integer point here either.

          case SolveStatus.DualInfeasible =>
            // An unbounded relaxation at the root says the integer problem is
            // unbounded or infeasible; deeper it means the bounds have not
            // pinned it down, which cannot happen for a bounded formulation.
            if nodes == 1 then rootUnbounded = true

          case status =>
            val conclusive = status.isConclusive
            if !conclusive then unproven += 1

            val bound = relaxation.objectiveValue

            // The margin that makes pruning sound on an inexact bound. Scaled by
            // this node's achieved duality gap rather than the requested
            // tolerance, so a relaxation that converged poorly is trusted less.
            val slack =
              params.pruningSafetyFactor *
                math.max(math.abs(relaxation.kkt.absoluteGap), params.lp.epsAbs)

            val prunable = conclusive && incumbent.nonEmpty && bound - slack >= incumbentValue

            if !prunable then
              val fractional = mostFractional(relaxation.primal, ordered, params.integralityTolerance)

              fractional match
                case None if conclusive =>
                  // Integral, and the bound is trustworthy: a candidate.
                  if bound < incumbentValue then
                    incumbentValue = bound
                    incumbent = snap(relaxation.primal, ordered)

                case None =>
                  // Integral but the relaxation never converged, so neither its
                  // objective nor its feasibility is established. Recording it
                  // as an incumbent would put an unverified point into the
                  // answer, so it is left alone and already counted as unproven.
                  ()

                case Some(j) =>
                  val value = relaxation.primal(j)
                  val floor  = math.floor(value)
                  val ceil   = math.ceil(value)
                  val warm   = if params.warmStart then Some(Pdhg.WarmStart(relaxation)) else None

                  // Pushed ceiling-first so the floor branch is explored first,
                  // which on a minimisation with mostly-zero binaries reaches a
                  // feasible incumbent sooner.
                  if ceil <= node.upper(j) then
                    stack.push(Node(raise(node.lower, j, ceil), node.upper, bound, warm))
                  if floor >= node.lower(j) then
                    stack.push(Node(node.lower, lowerTo(node.upper, j, floor), bound, warm))

    val elapsed = System.currentTimeMillis() - started
    val bound   = if stack.isEmpty then incumbentValue else openBound

    val status =
      if rootUnbounded then MilpStatus.Unbounded
      else if incumbent.isEmpty && stack.isEmpty && unproven == 0 then MilpStatus.Infeasible
      else if incumbent.isEmpty then MilpStatus.NoSolutionFound
      else if unproven > 0 then MilpStatus.Feasible
      else if stack.isEmpty then MilpStatus.Optimal
      else if closed(incumbentValue, bound, params) then MilpStatus.Optimal
      else MilpStatus.Feasible

    MilpSolution(
      status = status,
      primal = incumbent,
      objectiveValue = incumbentValue,
      bestBound = if incumbent.isEmpty then bound else math.min(bound, incumbentValue),
      nodesExplored = nodes,
      unprovenNodes = unproven,
      solveTimeMillis = elapsed,
    )

  private def closed(incumbent: Double, bound: Double, params: BnbParams): Boolean =
    val absolute = math.abs(incumbent - bound)
    absolute <= params.gapAbs ||
      absolute <= params.gapRel * math.max(1.0, math.abs(incumbent))

  /** The integer column furthest from a whole number, or `None` if all are
    * integral.
    *
    * Most-fractional rather than first-fractional: branching on a variable at
    * 0.5 splits the feasible set in two, where branching on one at 0.999 makes
    * one child almost identical to its parent and gets very little done.
    */
  private def mostFractional(
      x: IArray[Double],
      integers: IndexedSeq[Int],
      tolerance: Double,
  ): Option[Int] =
    var best     = -1
    var worstGap = tolerance
    integers.foreach { j =>
      val gap = math.abs(x(j) - math.round(x(j)).toDouble)
      if gap > worstGap then
        worstGap = gap
        best = j
    }
    if best < 0 then None else Some(best)

  /** The solution with integer coordinates snapped to whole numbers.
    *
    * Each moves by at most `integralityTolerance`, so any constraint residual
    * this introduces is bounded by that times the row's 1-norm. Returning
    * `0.9999999` for a commitment decision would push that rounding onto every
    * caller, and they would not all do it the same way.
    */
  private def snap(x: IArray[Double], integers: IndexedSeq[Int]): IArray[Double] =
    val out = x.toArray
    integers.foreach(j => out(j) = math.round(out(j)).toDouble)
    IArray.unsafeFromArray(out)

  private def raise(bounds: IArray[Double], j: Int, value: Double): IArray[Double] =
    val out = bounds.toArray
    out(j) = math.max(out(j), value)
    IArray.unsafeFromArray(out)

  private def lowerTo(bounds: IArray[Double], j: Int, value: Double): IArray[Double] =
    val out = bounds.toArray
    out(j) = math.min(out(j), value)
    IArray.unsafeFromArray(out)
