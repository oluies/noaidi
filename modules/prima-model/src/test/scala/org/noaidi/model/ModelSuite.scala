package org.noaidi
package model

import org.noaidi.prima.ojalgo.OjAlgoSolver
import org.noaidi.prima.{LpSolver, Pdhg, PdhgParams, SolveStatus}

class ModelSuite extends munit.FunSuite:

  private val prima: LpSolver = Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000))

  /** Two units and a demand that only one of them can meet on its own.
    *
    * Cheap coal runs to its limit and expensive gas covers the rest, which
    * makes gas the marginal unit and 60 the price at the bus. A fixture where
    * the cheap unit could meet the whole load would have a price of 30 and a
    * degenerate dual, and would not test the sign of anything.
    */
  private def dispatch(): (Model, Variable, Variable, ConstraintRef) =
    val m    = Model("dispatch")
    val coal = m.variable("coal", 0.0, 100.0)
    val gas  = m.variable("gas", 0.0, 50.0)
    val load = m.subjectTo("load", coal + gas === 120.0)
    m.minimise(coal * 30.0 + gas * 60.0)
    (m, coal, gas, load)

  test("a model is solved in its own terms") {
    val (m, coal, gas, load) = dispatch()
    val answer = m.solve(prima)

    assertEquals(answer.status, SolveStatus.Optimal)
    assertEqualsDouble(answer(coal), 100.0, 1e-6)
    assertEqualsDouble(answer(gas), 20.0, 1e-6)
    assertEqualsDouble(answer.objectiveValue, 4200.0, 1e-6)
    // The marginal unit sets the price: one more unit of demand costs 60.
    assertEqualsDouble(answer.dual(load), 60.0, 1e-6)
  }

  test("the same model, a different solver, the same answer") {
    // The whole point of the layer: a model never names the backend that runs
    // it. Two solvers that fail in completely different ways -- a first-order
    // method and a simplex -- agreeing is the evidence that the compilation is
    // right rather than that one of them is self-consistent.
    val (m, coal, gas, _) = dispatch()
    val compiled = m.compile()

    val byPrima  = compiled.interpret(prima)
    val byOjAlgo = compiled.interpret(OjAlgoSolver())

    assertEquals(byOjAlgo.status, SolveStatus.Optimal)
    assertEqualsDouble(byOjAlgo.objectiveValue, byPrima.objectiveValue, 1e-6)
    assertEqualsDouble(byOjAlgo(coal), byPrima(coal), 1e-6)
    assertEqualsDouble(byOjAlgo(gas), byPrima(gas), 1e-6)
  }

  test("a maximisation reports its own objective, and prices with its own sign") {
    // Maximise 3a + 5b subject to a + b <= 10, b <= 4.
    val m = Model("profit")
    val a = m.variable("a", 0.0, 10.0)
    val b = m.variable("b", 0.0, 4.0)
    val capacity = m.subjectTo("capacity", a + b <= 10.0)
    m.maximise(a * 3.0 + b * 5.0)

    val answer = m.solve(prima)
    assertEquals(answer.status, SolveStatus.Optimal)
    assertEqualsDouble(answer(b), 4.0, 1e-6)
    assertEqualsDouble(answer(a), 6.0, 1e-6)
    assertEqualsDouble(answer.objectiveValue, 38.0, 1e-6)
    // One more unit of capacity is worth one more unit of `a`, at 3. Positive:
    // the model was handed to the solver negated and the dual comes back
    // negated with it, so a relaxation that helps reads as a gain.
    assertEqualsDouble(answer.dual(capacity), 3.0, 1e-6)
  }

  test("a range row keeps one dual, not two halves of one") {
    val m = Model("range")
    val x = m.variable("x", 0.0, 100.0)
    val y = m.variable("y", 0.0, 100.0)
    // 20 <= x + y <= 30, and an objective that pushes down onto the lower side.
    val band = m.subjectTo("band", (x + y).between(20.0, 30.0))
    m.minimise(x * 2.0 + y * 3.0)

    val answer = m.solve(prima)
    assertEquals(answer.status, SolveStatus.Optimal)
    assertEqualsDouble(answer(x) + answer(y), 20.0, 1e-6)
    // The lower side binds, so the price is the cost of the cheapest unit.
    assertEqualsDouble(answer.dual(band), 2.0, 1e-6)
  }

  test("duplicate terms are summed and cancelling ones disappear") {
    val m = Model("terms")
    val x = m.variable("x", 0.0, 10.0)
    val y = m.variable("y", 0.0, 10.0)
    // `x + x + y - y` is `2x`, and `y` must not appear in the row at all: a
    // structural zero would be scaled and multiplied like any other entry.
    val row = m.subjectTo("doubled", (x + x + y - y) === 6.0)
    m.minimise(x)

    assertEquals(m.compile().problem.constraintMatrix.nnz, 1)
    val answer = m.solve(prima)
    assertEqualsDouble(answer(x), 3.0, 1e-6)
    assertEqualsDouble(answer.dual(row), 0.5, 1e-6)
  }

  test("a constant in a constraint moves to the bound") {
    val m = Model("offset")
    val x = m.variable("x", 0.0, 10.0)
    m.subjectTo("shifted", (x + 4.0) === 10.0)
    m.minimise(x)
    assertEqualsDouble(m.solve(prima)(x), 6.0, 1e-6)
  }

  test("a constant in the objective reaches the reported value") {
    val m = Model("constant")
    val x = m.variable("x", 2.0, 10.0)
    m.minimise(x * 3.0 + 100.0)
    val answer = m.solve(prima)
    assertEqualsDouble(answer.objectiveValue, 106.0, 1e-6)
  }

  test("a variable from another model is refused") {
    val one = Model("one")
    val two = Model("two")
    val x   = one.variable("x")
    val y   = two.variable("y")

    interceptMessage[IllegalArgumentException](
      "requirement failed: constraint mixed uses x, which belongs to a different model"
    )(two.subjectTo("mixed", (x + y) === 1.0))

    intercept[IllegalArgumentException](two.minimise(x))

    val answer = one.solve(prima)
    intercept[IllegalArgumentException](answer(y))
  }

  test("a row that constrains nothing is refused rather than compiled away") {
    val m = Model("vacuous")
    val x = m.variable("x")
    intercept[IllegalArgumentException](m.subjectTo("nothing", x.between(Double.NegativeInfinity, Double.PositiveInfinity)))
    intercept[IllegalArgumentException](m.subjectTo("backwards", x.between(5.0, 1.0)))
  }

  test("compiling twice gives the same matrix") {
    // The compiled form is a snapshot, so a solution taken from one compilation
    // stays valid while the model keeps growing -- and two compilations of the
    // same model have to agree, or a golden-file comparison of a built problem
    // would depend on hash iteration order.
    val (m, _, _, _) = dispatch()
    val first  = m.compile().problem
    val second = m.compile().problem
    assertEquals(first.constraintMatrix.values.toSeq, second.constraintMatrix.values.toSeq)
    assertEquals(first.constraintMatrix.colIndices.toSeq, second.constraintMatrix.colIndices.toSeq)
    assertEquals(first.rhs.toSeq, second.rhs.toSeq)
  }
