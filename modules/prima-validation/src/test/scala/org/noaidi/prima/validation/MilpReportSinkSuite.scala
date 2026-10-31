package org.noaidi.prima.validation

import java.nio.file.{Files, Path}

/** The report's output has to survive the process being killed.
  *
  * Every row of `MilpReport` is printed as its instance finishes, so that a
  * search outrunning CI's ten-minute budget leaves the rows that did finish and
  * names the instance that did not. That had never worked: the step piped sbt
  * through `tee`, a pipe between two JVMs is block-buffered, and `timeout` killed
  * the pipeline with the buffer unwritten -- three timeouts were investigated
  * from a log holding the kill message, no header and no rows.
  *
  * So the sink writes a file and flushes each line. This asserts the property
  * that makes that worth anything: the bytes are on disk before the sink is
  * closed, and therefore before anything has a chance to kill the process.
  * Racing an actual `timeout` would test the same thing less reliably -- the
  * report finishes in about three seconds on a warm build, so the kill lands
  * either before any work or after all of it.
  */
class MilpReportSinkSuite extends munit.FunSuite:

  private def withTemp[A](f: Path => A): A =
    val path = Files.createTempFile("milp-report-sink", ".txt")
    try f(path)
    finally Files.deleteIfExists(path)

  test("each line is on disk before the sink is closed") {
    withTemp { path =>
      val sink = new MilpReport.Sink(Some(path))
      try
        sink.line("instance           size")
        assertEquals(Files.readAllLines(path).size, 1, "the first line had not reached the file")

        sink.line("knapsack-16        16v/1c/16nz")
        val seen = Files.readAllLines(path)
        assertEquals(seen.size, 2)
        assert(seen.get(1).contains("knapsack-16"), seen.toString)
      finally sink.close()
    }
  }

  test("a sink with no path writes no file and still prints") {
    // The local default. `MilpReport` takes the path as an argument because a
    // forked test JVM runs from the module rather than the workspace, so there
    // is no safe relative name to default to -- and a run without one must not
    // fail for want of a file nobody asked for.
    val sink = new MilpReport.Sink(None)
    try sink.line("no file for this one")
    finally sink.close()
  }

  test("closing twice is not an error") {
    // `main` closes in a `finally`, and a report that threw partway would
    // otherwise lose the original failure to an exception from the cleanup.
    withTemp { path =>
      val sink = new MilpReport.Sink(Some(path))
      sink.line("one")
      sink.close()
      sink.close()
      assertEquals(Files.readAllLines(path).size, 1)
    }
  }
