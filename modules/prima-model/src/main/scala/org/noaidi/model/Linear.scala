package org.noaidi
package model

/** A decision variable, identified by which model made it.
  *
  * Identity is the class's own: two variables with the same name are two
  * variables. Names exist for reading a model and a solution back, and a model
  * that silently merged two columns because a caller reused a string would be
  * wrong in a way nothing downstream could detect.
  */
final class Variable private[model] (
    private[model] val column: Int,
    val name: String,
    val lower: Double,
    val upper: Double,
    private[model] val owner: Model,
) extends Linear:

  def expression: LinearExpression = LinearExpression(Vector(this -> 1.0), 0.0)

  override def toString: String = name

/** A weighted sum of variables plus a constant.
  *
  * Terms are kept in the order they were written and duplicates are kept as
  * they were written; both are resolved when the model is compiled. Summing on
  * the way in would make every `+` walk the expression built so far, which
  * turns building one bus balance row over a thousand generators into a
  * quadratic.
  */
final class LinearExpression private[model] (
    private[model] val terms: Vector[(Variable, Double)],
    val constant: Double,
) extends Linear:

  def expression: LinearExpression = this

  /** The same expression with its constant moved out, and that constant. */
  private[model] def split: (LinearExpression, Double) =
    (new LinearExpression(terms, 0.0), constant)

  override def toString: String =
    val body = terms.map((v, c) => f"$c%+.6g $v").mkString(" ")
    if constant == 0.0 then body else f"$body ${constant}%+.6g"

object LinearExpression:
  private[model] def apply(terms: Vector[(Variable, Double)], constant: Double): LinearExpression =
    new LinearExpression(terms, constant)

  val zero: LinearExpression = new LinearExpression(Vector.empty, 0.0)

  /** A constant, for the rare model that wants one on its own. */
  def constant(value: Double): LinearExpression = new LinearExpression(Vector.empty, value)

/** Everything a linear thing can do, written once.
  *
  * [[Variable]] and [[LinearExpression]] both extend this and differ only in
  * how they answer `expression`. The alternative — an implicit conversion from
  * variable to expression — would need `scala.language.implicitConversions` at
  * every call site that writes a model, which is a lot of import for the
  * privilege of writing `x + y`.
  */
sealed trait Linear:

  /** This, as an expression. The one thing the two cases answer differently. */
  def expression: LinearExpression

  def *(scale: Double): LinearExpression =
    val e = expression
    LinearExpression(e.terms.map((v, c) => (v, c * scale)), e.constant * scale)

  def /(divisor: Double): LinearExpression =
    require(divisor != 0.0, "cannot divide a linear expression by zero")
    this * (1.0 / divisor)

  def +(other: Linear): LinearExpression =
    val a = expression
    val b = other.expression
    LinearExpression(a.terms ++ b.terms, a.constant + b.constant)

  def -(other: Linear): LinearExpression = this + (other * -1.0)

  def +(offset: Double): LinearExpression =
    val e = expression
    LinearExpression(e.terms, e.constant + offset)

  def -(offset: Double): LinearExpression = this + (-offset)

  def unary_- : LinearExpression = this * -1.0

  /** `expression <= bound`, with any constant folded into the bound. */
  def <=(bound: Double): Constraint =
    val (body, c) = expression.split
    Constraint(body, Double.NegativeInfinity, bound - c)

  def >=(bound: Double): Constraint =
    val (body, c) = expression.split
    Constraint(body, bound - c, Double.PositiveInfinity)

  /** `===` rather than `==`, which every value in Scala already answers and
    * which would compile to a boolean here without a word of warning.
    */
  def ===(value: Double): Constraint =
    val (body, c) = expression.split
    Constraint(body, value - c, value - c)

  def <=(other: Linear): Constraint = (this - other) <= 0.0
  def >=(other: Linear): Constraint = (this - other) >= 0.0
  def ===(other: Linear): Constraint = (this - other) === 0.0

  /** `lower <= expression <= upper`, which the solver keeps as one row.
    *
    * Written as one constraint rather than two because the dual of a range row
    * is a single number — the difference of the two standard-form multipliers —
    * and two separate rows would hand back two halves of it.
    */
  def between(lower: Double, upper: Double): Constraint =
    val (body, c) = expression.split
    Constraint(body, lower - c, upper - c)

object Linear:

  /** The sum of many linear things, in one pass. */
  def sum(parts: IterableOnce[Linear]): LinearExpression =
    val terms   = Vector.newBuilder[(Variable, Double)]
    var offset  = 0.0
    parts.iterator.foreach { part =>
      val e = part.expression
      terms ++= e.terms
      offset += e.constant
    }
    LinearExpression(terms.result(), offset)

/** A row: `lower <= a'x <= upper`, with the constant already on the right.
  *
  * Both sides may be infinite on one end; neither may be infinite on both,
  * which is a row that constrains nothing and is far more likely to be a
  * mistake than an intention.
  */
final case class Constraint(expression: LinearExpression, lower: Double, upper: Double):
  require(
    !lower.isNaN && !upper.isNaN,
    s"constraint bounds must be numbers, got [$lower, $upper]",
  )
  require(lower <= upper, s"constraint has an empty range [$lower, $upper]")
  require(
    !(lower.isNegInfinity && upper.isPosInfinity),
    "constraint is unbounded on both sides and would have no effect",
  )
  require(expression.constant == 0.0, "a constraint's constant belongs on its bounds")

  def isEquality: Boolean = lower == upper
