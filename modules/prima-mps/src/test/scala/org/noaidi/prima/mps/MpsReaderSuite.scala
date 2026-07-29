package org.noaidi.prima
package mps

/** Tests for the MPS reader.
  *
  * The risk in a format reader is not that it fails to parse — that is loud and
  * immediate. It is that a convention is implemented differently from everyone
  * else's, producing a valid LP that solves cleanly to the wrong answer. Every
  * convention `MpsReader` documents is therefore pinned here, most of them by
  * solving the parsed problem and checking the optimum against one derived by
  * hand.
  */
class MpsReaderSuite extends munit.FunSuite:

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9)

  private def solve(mps: String): LpSolution =
    Pdhg.solve(MpsReader.fromString(mps).problem, params)

  // min x + y  s.t.  x + y >= 2,  x <= 3,  x,y >= 0  ->  optimum 2
  private val basic =
    """NAME          BASIC
      |ROWS
      | N  COST
      | G  LIM1
      | L  LIM2
      |COLUMNS
      |    X         COST      1.0        LIM1      1.0
      |    X         LIM2      1.0
      |    Y         COST      1.0        LIM1      1.0
      |RHS
      |    RHS       LIM1      2.0        LIM2      3.0
      |ENDATA
      |""".stripMargin

  test("a minimal instance parses and solves to its hand-derived optimum") {
    val instance = MpsReader.fromString(basic)
    assertEquals(instance.name, "BASIC")
    assertEquals(instance.columnNames, IndexedSeq("X", "Y"))
    assertEquals(instance.rowNames, IndexedSeq("LIM1", "LIM2"))
    assert(!instance.isMixedInteger)

    val solution = Pdhg.solve(instance.problem, params)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 2.0, 1e-6)
  }

  test("comments, blank lines and tabs are ignored") {
    val decorated = basic.linesIterator
      .flatMap(l => Iterator("* a comment", "", l.replace("    ", "\t")))
      .mkString("\n")
    val solution = solve(decorated)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 2.0, 1e-6)
  }

  test("the first N row is the objective and later N rows are dropped") {
    val withFreeRow =
      """NAME          FREEROW
        |ROWS
        | N  COST
        | N  IGNORED
        | G  LIM1
        |COLUMNS
        |    X         COST      1.0        IGNORED   99.0
        |    X         LIM1      1.0
        |RHS
        |    RHS       LIM1      4.0        IGNORED   1234.0
        |ENDATA
        |""".stripMargin
    val instance = MpsReader.fromString(withFreeRow)
    // The free row contributes neither a constraint nor an objective term.
    assertEquals(instance.rowNames, IndexedSeq("LIM1"))
    assertEquals(instance.problem.numConstraints, 1)

    val solution = Pdhg.solve(instance.problem, params)
    assertEqualsDouble(solution.objectiveValue, 4.0, 1e-6)
  }

  test("an RHS on the objective row becomes a negated objective constant") {
    // The convention is not universal, so it is pinned: RHS 10 on the objective
    // shifts the optimum by -10, not +10.
    val withConstant = basic.replace(
      "    RHS       LIM1      2.0        LIM2      3.0",
      "    RHS       LIM1      2.0        LIM2      3.0\n    RHS       COST      10.0",
    )
    val solution = solve(withConstant)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    assertEqualsDouble(solution.objectiveValue, 2.0 - 10.0, 1e-6)
  }

  test("RANGES on a G row extends upwards from the right-hand side") {
    // 2 <= x <= 2 + |3| = 5, minimising x gives 2; maximise by negating.
    val mps =
      """NAME          RG
        |ROWS
        | N  COST
        | G  R1
        |COLUMNS
        |    X         COST      -1.0       R1        1.0
        |RHS
        |    RHS       R1        2.0
        |RANGES
        |    RNG       R1        3.0
        |ENDATA
        |""".stripMargin
    val solution = solve(mps)
    assertEquals(solution.status, SolveStatus.Optimal, s"$solution")
    // min -x subject to 2 <= x <= 5 -> x = 5
    assertEqualsDouble(solution.objectiveValue, -5.0, 1e-6)
  }

  test("RANGES on an L row extends downwards from the right-hand side") {
    // x <= 5 with range 3 gives 2 <= x <= 5; minimising x gives 2.
    val mps =
      """NAME          RL
        |ROWS
        | N  COST
        | L  R1
        |COLUMNS
        |    X         COST      1.0        R1        1.0
        |RHS
        |    RHS       R1        5.0
        |RANGES
        |    RNG       R1        3.0
        |BOUNDS
        | FR BND       X
        |ENDATA
        |""".stripMargin
    val solution = solve(mps)
    assertEqualsDouble(solution.objectiveValue, 2.0, 1e-6)
  }

  test("RANGES on an E row takes its direction from the sign of the range") {
    // This is the only place in MPS where a sign carries this meaning, so both
    // directions are pinned.
    def optimum(range: String, cost: Double): Double =
      val mps =
        s"""NAME          RE
           |ROWS
           | N  COST
           | E  R1
           |COLUMNS
           |    X         COST      $cost       R1        1.0
           |RHS
           |    RHS       R1        10.0
           |RANGES
           |    RNG       R1        $range
           |BOUNDS
           | FR BND       X
           |ENDATA
           |""".stripMargin
      val s = Pdhg.solve(MpsReader.fromString(mps).problem, params)
      assertEquals(s.status, SolveStatus.Optimal, s"$s")
      s.objectiveValue

    // Positive range: 10 <= x <= 12.
    assertEqualsDouble(optimum("2.0", 1.0), 10.0, 1e-6)
    assertEqualsDouble(optimum("2.0", -1.0), -12.0, 1e-6)
    // Negative range: 8 <= x <= 10.
    assertEqualsDouble(optimum("-2.0", 1.0), 8.0, 1e-6)
    assertEqualsDouble(optimum("-2.0", -1.0), -10.0, 1e-6)
  }

  private def boundsOf(bounds: String): (Double, Double) =
    val mps =
      s"""NAME          BND
         |ROWS
         | N  COST
         | G  R1
         |COLUMNS
         |    X         COST      1.0        R1        1.0
         |RHS
         |    RHS       R1        -1000.0
         |BOUNDS
         |$bounds
         |ENDATA
         |""".stripMargin
    val p = MpsReader.fromString(mps).problem
    (p.variableLower(0), p.variableUpper(0))

  test("bound types map to the intervals they are supposed to") {
    assertEquals(boundsOf(" UP BND       X         4.0"), (0.0, 4.0))
    assertEquals(boundsOf(" LO BND       X         -2.0"), (-2.0, Double.PositiveInfinity))
    assertEquals(boundsOf(" FX BND       X         7.0"), (7.0, 7.0))
    assertEquals(boundsOf(" FR BND       X"), (Double.NegativeInfinity, Double.PositiveInfinity))
    assertEquals(boundsOf(" MI BND       X"), (Double.NegativeInfinity, Double.PositiveInfinity))
    assertEquals(boundsOf(" PL BND       X"), (0.0, Double.PositiveInfinity))
    assertEquals(boundsOf(" BV BND       X"), (0.0, 1.0))
  }

  test("a valueless bound type tolerates an ignored value field") {
    // Writers that emit a value on FR/MI/PL/BV are not rare. Reading the column
    // name off the last token would invent a variable named "0.0" and leave the
    // real one at its defaults -- a different feasible region, solved cleanly to
    // the wrong answer.
    assertEquals(boundsOf(" MI BND       X         0.0"), (Double.NegativeInfinity, Double.PositiveInfinity))
    assertEquals(boundsOf(" FR BND       X         0.0"), (Double.NegativeInfinity, Double.PositiveInfinity))
  }

  test("a BOUNDS entry for a column that never appeared is rejected") {
    // Unknown rows were already rejected; unknown columns used to be invented
    // on demand, which is what made the failure above silent.
    intercept[MpsParseException] {
      MpsReader.fromString(
        "ROWS\n N  C\n G  R1\nCOLUMNS\n    X    R1   1.0\nBOUNDS\n UP BND  NOSUCHCOL  4.0\nENDATA\n"
      )
    }
  }

  test("a valueless bound resolves the column with the set name omitted") {
    // ` MI X 0.0` -- no bound-set name, but an ignored value present. Position
    // alone cannot tell this from ` MI BND X`, so the column is identified by
    // membership in COLUMNS.
    assertEquals(boundsOf(" MI X         0.0"), (Double.NegativeInfinity, Double.PositiveInfinity))
    assertEquals(boundsOf(" FR X"), (Double.NegativeInfinity, Double.PositiveInfinity))
  }

  test("a BOUNDS line carrying two entries is rejected, not misassigned") {
    // Taking the value from the last token would give X the bound meant for the
    // second entry and drop that entry entirely -- a different feasible region.
    intercept[MpsParseException] {
      MpsReader.fromString(
        "ROWS\n N  C\n G  R1\nCOLUMNS\n    X    R1   1.0\n    Y    R1   1.0\n" +
          "BOUNDS\n UP BND  X  4.0  Y  5.0\nENDATA\n"
      )
    }
  }

  test("MI leaves the upper bound alone rather than forcing it to zero") {
    // Older readers set the upper bound to zero here. The difference is
    // invisible until an instance relies on it.
    assertEquals(boundsOf(" MI BND       X\n UP BND2      X         5.0"), (Double.NegativeInfinity, 5.0))
  }

  test("a negative UP bound drives an implicit lower bound to negative infinity") {
    // Without this the domain would be the empty interval [0, -3].
    assertEquals(boundsOf(" UP BND       X         -3.0"), (Double.NegativeInfinity, -3.0))
  }

  test("an explicit lower bound survives a later negative UP bound") {
    assertEquals(boundsOf(" LO BND       X         -9.0\n UP BND       X         -3.0"), (-9.0, -3.0))
  }

  test("integer markers are recorded rather than silently relaxed") {
    val mps =
      """NAME          INT
        |ROWS
        | N  COST
        | G  R1
        |COLUMNS
        |    MARKER                 'MARKER'                 'INTORG'
        |    X         COST      1.0        R1        1.0
        |    MARKER                 'MARKER'                 'INTEND'
        |    Y         COST      1.0        R1        1.0
        |RHS
        |    RHS       R1        1.0
        |ENDATA
        |""".stripMargin
    val instance = MpsReader.fromString(mps)
    assert(instance.isMixedInteger, "the integer column was not recorded")
    assertEquals(instance.integerColumns, Set(0))
  }

  test("duplicate coefficients in the objective accumulate") {
    val mps =
      """NAME          DUP
        |ROWS
        | N  COST
        | G  R1
        |COLUMNS
        |    X         COST      1.0
        |    X         COST      2.0        R1        1.0
        |RHS
        |    RHS       R1        5.0
        |ENDATA
        |""".stripMargin
    val solution = solve(mps)
    // Cost is 1 + 2 = 3 per unit, x driven to 5 by the row.
    assertEqualsDouble(solution.objectiveValue, 15.0, 1e-6)
  }

  test("an infeasible instance is still parsed and then reported infeasible") {
    val mps =
      """NAME          INFEAS
        |ROWS
        | N  COST
        | G  R1
        | L  R2
        |COLUMNS
        |    X         COST      1.0        R1        1.0
        |    X         R2        1.0
        |RHS
        |    RHS       R1        5.0        R2        1.0
        |BOUNDS
        | FR BND       X
        |ENDATA
        |""".stripMargin
    val solution = solve(mps)
    assertEquals(solution.status, SolveStatus.PrimalInfeasible, s"$solution")
  }

  test("malformed input is rejected with a message naming the problem") {
    intercept[MpsParseException](MpsReader.fromString("ROWS\n X  BAD\nENDATA\n"))
    intercept[MpsParseException](MpsReader.fromString("ROWS\n G  R1\nENDATA\n")) // no objective
    intercept[MpsParseException] {
      MpsReader.fromString("ROWS\n N  C\n G  R1\nCOLUMNS\n    X    NOSUCHROW   1.0\nENDATA\n")
    }
    intercept[MpsParseException] {
      MpsReader.fromString("ROWS\n N  C\n G  R1\nCOLUMNS\n    X    R1   notanumber\nENDATA\n")
    }
  }

  test("OBJSENSE MAX is rejected rather than silently minimised") {
    val mps = "OBJSENSE\n    MAX\nROWS\n N  C\n G  R1\nCOLUMNS\n    X    R1   1.0\nENDATA\n"
    intercept[MpsParseException](MpsReader.fromString(mps))
  }
