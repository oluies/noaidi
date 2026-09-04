package org.noaidi
package model

import org.noaidi.prima.{LpProblem, LpSolution, LpSolver, RowTranslation, SolveStatus}

import scala.collection.mutable

/** Which way the objective goes. */
enum Sense:
  case Minimise, Maximise

/** A handle on a row, for reading its dual back. */
final class ConstraintRef private[model] (private[model] val row: Int, val name: String, private[model] val owner: Model):
  override def toString: String = name

/** A linear program written in its own terms, and solved by whichever backend
  * the caller hands it.
  *
  * [[org.noaidi.prima.LpProblem]] is a matrix, a right-hand side and two bound
  * vectors, which is what a solver needs and not what a model is. Everything
  * above it in this port has so far kept its own map from
  * `(component, entity, snapshot)` to a column index and done the arithmetic by
  * hand — `network-lopf` still does — and every model that does that reinvents
  * the same three things: naming, expression building, and getting the duals
  * back out in the caller's own row numbering.
  *
  * This is those three things, once. What it deliberately is not is a solver
  * abstraction: that is [[org.noaidi.prima.LpSolver]], which already exists and
  * which this layer simply hands a problem to. Prima, ojAlgo and OR-Tools are
  * all reached the same way, and a model never names the one that will run it.
  *
  * {{{
  * val m = Model("dispatch")
  * val coal = m.variable("coal", 0.0, 100.0)
  * val gas  = m.variable("gas", 0.0, 50.0)
  * val load = m.subjectTo("load", coal + gas === 120.0)
  * m.minimise(coal * 30.0 + gas * 60.0)
  *
  * val answer = m.solve(Pdhg.Solver())
  * answer(coal)      // 100.0
  * answer.dual(load) // the marginal price at the bus
  * }}}
  *
  * Not thread-safe while being built, and finished once `compile` is called:
  * the compiled form is a snapshot, so a model that grows afterwards does not
  * invalidate a solution already taken from it.
  */
final class Model(val name: String = "model"):

  private val variables   = mutable.ArrayBuffer.empty[Variable]
  private val constraints = mutable.ArrayBuffer.empty[(String, Constraint)]
  private var objective   = LinearExpression.zero
  private var sense       = Sense.Minimise

  def numVariables: Int   = variables.length
  def numConstraints: Int = constraints.length

  /** A variable bounded below at zero, which is what most of them are. */
  def variable(
      name: String,
      lower: Double = 0.0,
      upper: Double = Double.PositiveInfinity,
  ): Variable =
    require(!lower.isNaN && !upper.isNaN, s"variable $name has a bound that is not a number")
    require(lower <= upper, s"variable $name has an empty bound interval [$lower, $upper]")
    val v = new Variable(variables.length, name, lower, upper, this)
    variables += v
    v

  /** A variable with no bounds at all — a phase angle, a net flow, a slack that
    * is allowed to go either way.
    */
  def free(name: String): Variable =
    variable(name, Double.NegativeInfinity, Double.PositiveInfinity)

  def subjectTo(name: String, constraint: Constraint): ConstraintRef =
    constraint.expression.terms.foreach { (v, _) =>
      require(
        v.owner eq this,
        s"constraint $name uses ${v.name}, which belongs to a different model",
      )
    }
    val ref = new ConstraintRef(constraints.length, name, this)
    constraints += ((name, constraint))
    ref

  def minimise(expression: Linear): this.type = setObjective(expression, Sense.Minimise)
  def maximise(expression: Linear): this.type = setObjective(expression, Sense.Maximise)

  private def setObjective(expression: Linear, direction: Sense): this.type =
    val e = expression.expression
    e.terms.foreach { (v, _) =>
      require(v.owner eq this, s"the objective uses ${v.name}, which belongs to a different model")
    }
    objective = e
    sense = direction
    this

  /** Freeze the model into the standard form a solver takes.
    *
    * Duplicate terms are summed here rather than as they are written, so that
    * building a row over a thousand variables costs a thousand appends and not
    * a thousand walks of what has been appended so far.
    */
  def compile(): CompiledModel =
    val builder = LpProblem.builder(variables.length)
    variables.foreach(v => builder.bounds(v.column, v.lower, v.upper))

    val direction = if sense == Sense.Maximise then -1.0 else 1.0
    collapse(objective.terms).foreach((column, c) => builder.objectiveCoefficient(column, direction * c))
    builder.objectiveOffset(direction * objective.constant)

    constraints.foreach { (_, c) =>
      builder.constraint(collapse(c.expression.terms), c.lower, c.upper)
    }

    val (problem, translation) = builder.build()
    new CompiledModel(this, problem, translation, sense, variables.toVector, constraints.map(_._1).toVector)

  /** Compile and solve. The solver is the caller's choice and the model does
    * not know which one it got.
    */
  def solve(solver: LpSolver): Solution = compile().interpret(solver)

  private def collapse(terms: Vector[(Variable, Double)]): Seq[(Int, Double)] =
    // Insertion-ordered, so the compiled row reads in the order it was written
    // and two compilations of the same model give byte-identical matrices.
    val sums = mutable.LinkedHashMap.empty[Int, Double]
    terms.foreach((v, c) => sums.updateWith(v.column)(existing => Some(existing.getOrElse(0.0) + c)))
    // A coefficient that cancelled to zero is dropped rather than stored: a
    // structural zero in the matrix would be scaled and multiplied like any
    // other entry and would change nothing but the cost.
    sums.iterator.filter((_, c) => c != 0.0).toSeq

object Model:
  def apply(name: String = "model"): Model = new Model(name)

/** A model frozen into standard form, plus everything needed to read a solution
  * back in the model's own terms.
  */
final class CompiledModel private[model] (
    val model: Model,
    val problem: LpProblem,
    private val translation: RowTranslation,
    private val sense: Sense,
    private val variables: Vector[Variable],
    private val constraintNames: Vector[String],
):

  /** Hand the problem to a solver and interpret what comes back. */
  def interpret(solver: LpSolver): Solution = interpret(solver.solve(problem))

  /** Interpret a solution that was obtained elsewhere — from a warm-started
    * solve, or from a backend reached through something other than
    * [[org.noaidi.prima.LpSolver]].
    */
  def interpret(solution: LpSolution): Solution =
    require(
      solution.primal.length == variables.length,
      s"solution has ${solution.primal.length} variables, this model has ${variables.length}",
    )
    // The dual side is checked too. `originalDuals` indexes the standard-form
    // vector by the rows this translation knows about, so a solution from a
    // different problem reaches it and throws an array index rather than saying
    // what went wrong.
    require(
      solution.dual.length == problem.numConstraints,
      s"solution has ${solution.dual.length} duals, this model compiled to ${problem.numConstraints} rows",
    )
    // A maximisation was handed to the solver negated, so everything that
    // carries the objective's sign has to come back the other way: the value
    // itself, the duals, and the reduced costs. Leaving the duals alone would
    // report a price whose sign says the opposite of what the model asked for.
    val flip  = if sense == Sense.Maximise then -1.0 else 1.0
    val duals = translation.originalDuals(solution.dual)
    new Solution(
      status = solution.status,
      objectiveValue = flip * solution.objectiveValue,
      primal = solution.primal,
      duals = IArray.tabulate(duals.length)(i => flip * duals(i)),
      reducedCosts = IArray.tabulate(solution.reducedCosts.length)(i => flip * solution.reducedCosts(i)),
      underlying = solution,
      model = model,
      constraintNames = constraintNames,
    )

/** A solution, in the model's own terms. */
final class Solution private[model] (
    val status: SolveStatus,
    val objectiveValue: Double,
    private val primal: IArray[Double],
    private val duals: IArray[Double],
    private val reducedCosts: IArray[Double],
    /** The solver's own report, for iteration counts, residuals and timings. */
    val underlying: LpSolution,
    private val model: Model,
    private val constraintNames: Vector[String],
):

  def apply(variable: Variable): Double = value(variable)

  /** Ownership is not enough on its own.
    *
    * `Model` invites the case this guards: the compiled form is a snapshot, so
    * a model may keep growing after a solution has been taken from it — and a
    * variable created after that `compile()` belongs to the same model, passes
    * the ownership check, and then indexes past the end of a snapshot that
    * never had a column for it. An `ArrayIndexOutOfBoundsException` from inside
    * the layer says nothing about why.
    */
  private def within(index: Int, size: Int, what: String, name: String): Int =
    require(
      index < size,
      s"$name has no $what in this solution: it was created after the model was compiled, " +
        s"which took a snapshot of $size",
    )
    index

  def value(variable: Variable): Double =
    require(variable.owner eq model, s"${variable.name} belongs to a different model")
    primal(within(variable.column, primal.length, "column", variable.name))

  /** The multiplier on a row, in the orientation the row was written in.
    *
    * '''May be `NaN`.''' Not every backend reports duals — `OjAlgoSolver`
    * returns `NaN` rather than multipliers it cannot vouch for — and this layer
    * passes that through rather than substituting a zero that would read as a
    * non-binding constraint.
    */
  def dual(constraint: ConstraintRef): Double =
    require(constraint.owner eq model, s"${constraint.name} belongs to a different model")
    duals(within(constraint.row, duals.length, "row", constraint.name))

  def reducedCost(variable: Variable): Double =
    require(variable.owner eq model, s"${variable.name} belongs to a different model")
    reducedCosts(within(variable.column, reducedCosts.length, "column", variable.name))

  def isOptimal: Boolean = status == SolveStatus.Optimal

  override def toString: String =
    f"Solution($status, objective=$objectiveValue%.10g, ${model.name})"
