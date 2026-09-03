package org.noaidi.prima
package ortools

import org.noaidi.model.{ConstraintRef, Linear, Model, Variable}
import org.noaidi.prima.ojalgo.OjAlgoSolver

/** One model, three solvers that share no code.
  *
  * This is the claim the modeling layer exists to make and the only place all
  * three backends are in the same JVM: a model is written once and never names
  * the solver that will run it. A first-order method, a pure-JVM simplex and
  * Google's revised simplex agreeing on a model none of them was written
  * against is what makes the compilation trustworthy — one solver agreeing with
  * itself would only say the layer is self-consistent.
  */
class ThreeSolverAgreementSuite extends munit.FunSuite:

  private val solvers: Seq[LpSolver] = Seq(
    Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)),
    OjAlgoSolver(),
    OrToolsSolver(),
  )

  /** A small transport problem: two plants, three markets, a shipping cost per
    * pair, supply limits and demands.
    *
    * The numbers are chosen so the dual is unique, which takes more care than
    * it looks. With supply equal to demand every supply row binds; with the
    * second plant's supply equal to a single market's demand a basic variable
    * sits at zero. Either way the optimal dual is a face rather than a point,
    * the three solvers land on three different points of it, and the objective
    * still agrees -- a real property of the problem, and not one an assertion
    * about prices can survive. Here the second plant is the only binding row
    * and every price is pinned.
    */
  private def transport(): (Model, Seq[Seq[Variable]], Seq[ConstraintRef]) =
    val supply  = Seq(130.0, 85.0)
    val demand  = Seq(60.0, 90.0, 50.0)
    val cost    = Seq(Seq(4.0, 6.0, 9.0), Seq(5.0, 3.0, 8.0))

    val m = Model("transport")
    val ship = supply.indices.map { p =>
      demand.indices.map(k => m.variable(s"ship-$p-$k", 0.0, Double.PositiveInfinity))
    }

    val supplyRows = supply.indices.map { p =>
      m.subjectTo(s"supply-$p", Linear.sum(ship(p)) <= supply(p))
    }
    val demandRows = demand.indices.map { k =>
      m.subjectTo(s"demand-$k", Linear.sum(supply.indices.map(p => ship(p)(k))) === demand(k))
    }
    m.minimise(Linear.sum(supply.indices.flatMap(p => demand.indices.map(k => ship(p)(k) * cost(p)(k)))))
    (m, ship, (supplyRows ++ demandRows).toSeq)

  test("three solvers, one model, one answer") {
    val (m, ship, _) = transport()
    val compiled     = m.compile()

    val answers = solvers.map(s => s.name -> compiled.interpret(s))
    answers.foreach { (name, answer) =>
      assertEquals(answer.status, SolveStatus.Optimal, name)
    }

    val objectives = answers.map((_, a) => a.objectiveValue)
    objectives.foreach { value =>
      assertEqualsDouble(value, objectives.head, 1e-6, s"objectives: ${answers.map((n, a) => s"$n=${a.objectiveValue}")}")
    }

    // The flows themselves, not only the total: two optima with the same cost
    // would pass an objective-only comparison, and this problem has one.
    val flows = answers.map((name, a) => name -> ship.flatten.map(a.value))
    flows.foreach { (name, values) =>
      values.zip(flows.head._2).zipWithIndex.foreach { case ((v, reference), i) =>
        assertEqualsDouble(v, reference, 1e-5, s"$name: flow $i")
      }
    }
  }

  test("the two solvers that report prices report the same ones") {
    // ojAlgo is left out on purpose: it returns NaN rather than multipliers it
    // cannot vouch for, and that is the behaviour rather than a gap to work
    // around. Prima and OR-Tools both report, and a price is the reason most of
    // these models get written, so the two have to agree on more than the cost.
    val (m, _, rows) = transport()
    val compiled     = m.compile()

    val byPrima   = compiled.interpret(Pdhg.Solver(PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 200_000)))
    val byOrTools = compiled.interpret(OrToolsSolver())

    assertEquals(byPrima.status, SolveStatus.Optimal)
    assertEquals(byOrTools.status, SolveStatus.Optimal)
    rows.foreach { row =>
      assertEqualsDouble(byOrTools.dual(row), byPrima.dual(row), 1e-5, row.name)
    }
  }
