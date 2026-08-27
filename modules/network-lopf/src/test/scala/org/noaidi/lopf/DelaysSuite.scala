package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.{CsvReader, Network}

/** The delay shift itself, on the paths no golden reaches.
  *
  * `link-delay` and `link-delay-wrap` gate the two-port case end to end, which is
  * the case every network in this repository has. What they cannot reach is a
  * '''third port''': PyPSA names a multi-port link's delays `delay2`,
  * `cyclic_delay2` and so on, exactly as it names `efficiency2`, and those are
  * custom columns rather than schema attributes.
  *
  * That matters because [[Delays.configured]] falls back to
  * `table.static.contains` for them. A mistake in the suffix rule, or in the
  * default for a missing `cyclic_delay<i>`, silently makes the third port
  * instantaneous — the same silent under-price the rest of this module exists to
  * refuse, and one no golden comparison here would reveal.
  *
  * Built through `CsvReader` rather than by assembling a `ComponentTable`, for
  * the reason the other suites give: a hand-built table can express states the
  * reader never produces, so a test built that way can pass while the real path
  * stays broken.
  */
class DelaysSuite extends munit.FunSuite, CsvFixtures:

  /** A three-port network written out and read back through the real parser. */
  private def threePort(links: String): Network =
    val dir = tempDir("noaidi-delays-")
    Files.writeString(dir.resolve("snapshots.csv"),
      ",snapshot,objective,stores,generators\n0,0,1.0,1.0,1.0\n1,1,1.0,1.0,1.0\n" +
        "2,2,1.0,1.0,1.0\n3,3,1.0,1.0,1.0\n")
    Files.writeString(dir.resolve("buses.csv"),
      "name,v_nom\na,110.0\nb,110.0\nc,110.0\n")
    Files.writeString(dir.resolve("links.csv"), links)
    CsvReader.read(dir, schema, "three-port")

  private def shifts(n: Network): Map[(String, String), Delays.Shift] =
    Delays.forTable(n, n.require("Link"))

  test("a third port carries its own delay, under its own suffixed name") {
    assume(available, "goldens missing")
    // `delay2` set and `delay` left at 0: the two ports must be read apart. If
    // the suffix rule were wrong, `delay2` would go unread and `bus2` would
    // receive its energy instantly.
    val n = threePort(
      "name,bus0,bus1,bus2,efficiency2,delay,delay2\nt,a,b,c,1.0,0,2\n"
    )
    val s = shifts(n)

    assert(s.contains(("t", "bus2")), s"bus2 has no shift: ${s.keys}")
    assert(!s.contains(("t", "bus1")), "bus1 is delayed, but its delay is 0")

    // Default `cyclic_delay2` is PyPSA's `True`, so nothing is invalid and the
    // first two targets wrap to the last two.
    val shift = s(("t", "bus2"))
    assertEquals(n.snapshots.indices.map(shift.sourceOf).toIndexedSeq,
      IndexedSeq(Some(2), Some(3), Some(0), Some(1)),
      "a missing cyclic_delay2 should default to wrapping, as PyPSA's does")
  }

  test("a third port's cyclic flag is read from its own suffixed column") {
    assume(available, "goldens missing")
    // The same network with the wrap turned off. If `cyclic_delay2` were read
    // under the unsuffixed name -- or not read at all -- the first two targets
    // would still wrap instead of having no source.
    val n = threePort(
      "name,bus0,bus1,bus2,efficiency2,delay,delay2,cyclic_delay2\nt,a,b,c,1.0,0,2,False\n"
    )
    val shift = shifts(n)(("t", "bus2"))
    assertEquals(n.snapshots.indices.map(shift.sourceOf).toIndexedSeq,
      IndexedSeq(None, None, Some(0), Some(1)),
      "cyclic_delay2 = False should leave the first two targets without a source")
  }

  test("the unsuffixed delay still belongs to bus1 when a third port exists") {
    assume(available, "goldens missing")
    // The other half of the suffix rule: `delay` is bus1's, not "the link's".
    // A rule that applied it to every output port would delay bus2 as well.
    val n = threePort(
      "name,bus0,bus1,bus2,efficiency2,delay,cyclic_delay\nt,a,b,c,1.0,1,False\n"
    )
    val s = shifts(n)
    assert(s.contains(("t", "bus1")), s"bus1 has no shift: ${s.keys}")
    assert(!s.contains(("t", "bus2")), "bus2 has no delay2, so it must be instantaneous")
    assertEquals(n.snapshots.indices.map(s(("t", "bus1")).sourceOf).toIndexedSeq,
      IndexedSeq(None, Some(0), Some(1), Some(2)))
  }
