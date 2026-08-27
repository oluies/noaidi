package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.{CsvReader, Network}

/** Cycle basis construction, on graphs small enough to check by hand.
  *
  * The end-to-end comparison in `LopfSuite` cannot see most of what this file
  * covers. Its cycle test checks the '''count''' against the circuit rank, and a
  * count is blind to orientation — both arms of the tree walk were inverted at
  * one point, which made the LP infeasible while leaving the count identical. The
  * triangle worked out by hand to find that bug lived in a commit message; it
  * lives here now.
  *
  * The graphs are built as CSV and read back through `CsvReader`, not assembled
  * in memory, so a fixture cannot express a state the reader never produces.
  */
class CyclesSuite extends munit.FunSuite, CsvFixtures:

  /** A network with the given buses and AC lines, via the real reader. */
  private def network(buses: Seq[String], lines: Seq[(String, String, String)]): Network =
    val dir = tempDir("noaidi-cycles-")
    Files.writeString(
      dir.resolve("buses.csv"),
      "name,v_nom,carrier\n" + buses.map(b => s"$b,1.0,AC\n").mkString,
    )
    Files.writeString(
      dir.resolve("lines.csv"),
      "name,bus0,bus1,x,r,s_nom\n" +
        lines.map((id, a, b) => s"$id,$a,$b,1.0,0.0,100.0\n").mkString,
    )
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    CsvReader.read(dir, schema, "cycles-fixture")

  /** A cycle's terms as a map, so assertions do not depend on traversal order. */
  private def terms(cycle: Cycle): Map[String, Double] =
    cycle.terms.map((_, id, orientation) => id -> orientation).toMap

  test("a triangle gives one cycle traversed consistently") {
    assume(available, "goldens missing")
    // London -> Manchester -> Norwich -> London, every line oriented head to
    // tail. Walking the cycle in that direction traverses each line forwards, so
    // every coefficient is +1. This is the case that was worked out by hand when
    // both arms of the tree walk came out inverted -- and the inverted version
    // has the same cycle *count*, so only the signs distinguish them.
    val n      = network(Seq("L", "M", "N"), Seq(("a", "L", "M"), ("b", "M", "N"), ("c", "N", "L")))
    val cycles = Cycles.basis(n)

    assertEquals(cycles.length, 1)
    val signs = terms(cycles.head)
    assertEquals(signs.keySet, Set("a", "b", "c"))
    // Either direction round the loop is a valid basis element, so what is
    // asserted is consistency: all three the same sign, not a particular one.
    assert(signs.values.forall(_ == signs("a")), s"inconsistent orientation: $signs")
    assert(math.abs(signs("a")) == 1.0)
  }

  test("a parallel pair gives a cycle whose two branches oppose") {
    assume(available, "goldens missing")
    // Two lines between the same buses. The loop goes out along one and back
    // along the other, so with both declared bus0=A they must carry opposite
    // signs -- a length-2 cycle is where an orientation rule that happens to work
    // on a triangle can still be wrong.
    val n      = network(Seq("A", "B"), Seq(("a", "A", "B"), ("b", "A", "B")))
    val cycles = Cycles.basis(n)

    assertEquals(cycles.length, 1)
    val signs = terms(cycles.head)
    assertEquals(signs.keySet, Set("a", "b"))
    assertEquals(signs("a"), -signs("b"), s"parallel branches must oppose: $signs")
  }

  test("a tree has no cycles") {
    assume(available, "goldens missing")
    val n = network(Seq("A", "B", "C", "D"), Seq(("a", "A", "B"), ("b", "B", "C"), ("c", "B", "D")))
    assertEquals(Cycles.basis(n).length, 0)
  }

  test("two independent loops give two cycles, one per component") {
    assume(available, "goldens missing")
    // Disconnected graphs are where a basis built per connected component can
    // quietly return only the first component's cycles.
    val n = network(
      Seq("A", "B", "C", "X", "Y", "Z"),
      Seq(
        ("a", "A", "B"), ("b", "B", "C"), ("c", "C", "A"),
        ("x", "X", "Y"), ("y", "Y", "Z"), ("z", "Z", "X"),
      ),
    )
    val cycles = Cycles.basis(n)
    assertEquals(cycles.length, 2)
    assertEquals(cycles.flatMap(c => terms(c).keySet).toSet, Set("a", "b", "c", "x", "y", "z"))
  }

  test("a square with a chord gives two cycles of the right size") {
    assume(available, "goldens missing")
    // Four buses, five lines: circuit rank 5 - 4 + 1 = 2. The chord forces the
    // two arms of at least one cycle to meet above depth 1, which the triangle
    // never exercises.
    val n = network(
      Seq("A", "B", "C", "D"),
      Seq(("a", "A", "B"), ("b", "B", "C"), ("c", "C", "D"), ("d", "D", "A"), ("e", "A", "C")),
    )
    val cycles = Cycles.basis(n)
    assertEquals(cycles.length, 2)
    // Each cycle of a square-with-chord is a triangle.
    cycles.foreach(c => assertEquals(c.length, 3, s"unexpected cycle ${terms(c)}"))
    // And each is a genuine closed walk: every bus it touches is entered and left
    // exactly once, which a wrong tree walk would break.
    cycles.foreach { c =>
      assert(terms(c).values.forall(o => math.abs(o) == 1.0), s"non-unit orientation in ${terms(c)}")
    }
  }

  test("cycles over a DC island take their impedance from resistance") {
    assume(available, "goldens missing")
    // The AC/DC split is what makes the reference network solvable at all: its DC
    // lines carry x = 0, so reading reactance there gives a vacuously satisfied
    // constraint. Built with x = 0 deliberately, so reading the wrong attribute
    // shows up as a zero rather than as a near-miss.
    val dir = tempDir("noaidi-cycles-dc-")
    Files.writeString(
      dir.resolve("buses.csv"),
      "name,v_nom,carrier\nA,2.0,DC\nB,2.0,DC\nC,2.0,DC\n",
    )
    Files.writeString(
      dir.resolve("lines.csv"),
      "name,bus0,bus1,x,r,s_nom\na,A,B,0.0,0.5,100.0\nb,B,C,0.0,0.5,100.0\nc,C,A,0.0,0.5,100.0\n",
    )
    Files.writeString(dir.resolve("snapshots.csv"), ",snapshot\n0,2015-01-01 00:00:00\n")
    val n      = CsvReader.read(dir, schema, "cycles-dc")
    val cycles = Cycles.basis(n)

    assertEquals(cycles.length, 1)
    assertEquals(cycles.head.carrier, "DC")
    // r / v_nom^2 = 0.5 / 4 = 0.125. Reading `x` would give exactly 0.
    assertEqualsDouble(Cycles.impedance(n, "Line", "a", "DC"), 0.125, 1e-12)
  }
