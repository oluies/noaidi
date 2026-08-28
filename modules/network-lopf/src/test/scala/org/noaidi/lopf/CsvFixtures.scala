package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network, Schema}

/** Editing a golden CSV in place, for the mutation tests.
  *
  * One definition rather than one per suite. Both `LopfSuite` and `SclopfSuite`
  * need to rewrite a column of a fixture, and when only the first had a helper
  * the second went on appending columns blindly — which is the idiom this exists
  * to replace, reappearing in the same commit that removed it.
  *
  * ==Why appending is not enough==
  *
  * Appending a column that the fixture already carries produces a duplicated
  * header field. That happens to work, because `CsvReader` collects its columns
  * into a `ListMap` where a later key overwrites an earlier one — an undocumented
  * dependency, and one that makes "add a `standing_loss` column" read as an
  * addition when it is really shadowing the fixture's own value.
  *
  * ==The rest of the fixture machinery==
  *
  * `goldens`, `schema`, `copyOf`, `mutate` and the temp-directory bookkeeping
  * live here for the same reason `setColumn` does. Every suite in this module
  * had carried its own copy and they had already drifted three ways: some walked
  * the tree to clean up, `DelaysSuite` deleted a single level with `Files.list`
  * so a fixture with a subdirectory leaked, and `CyclesSuite` walked but never
  * closed the stream `copyOf` below explains must be closed. One definition ends
  * all three.
  *
  * `LinearPowerFlowSuite` and `NewtonRaphsonSuite` keep their own copies. They
  * are in `network-pf`, and a test-jar dependency between the two modules costs
  * more than the duplication does — that is the only reason a copy remains, and
  * it does not apply to anything in this module.
  */
trait CsvFixtures extends munit.Suite, munit.Assertions:

  /** Prefix for this suite's temporary directories, so a leak names its owner.
    *
    * Only [[copyOf]] reads it; suites that build fixtures from scratch pass a
    * prefix to [[tempDir]] directly and need not override this.
    */
  protected def tempPrefix: String = "noaidi-"

  protected def goldens: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_GOLDENS", "reference/goldens"))

  protected lazy val available: Boolean = Files.exists(goldens.resolve("schema.json"))
  protected lazy val schema: Schema     = Schema.fromFile(goldens.resolve("schema.json"))

  protected def network(name: String): Network =
    CsvReader.read(goldens.resolve("networks").resolve(name), schema, name)

  protected val temporaries = scala.collection.mutable.ArrayBuffer.empty[Path]

  /** A registered temporary directory, deleted by [[afterAll]].
    *
    * Creating one without registering it is the leak this exists to prevent, and
    * every call site in this module had the two lines written out separately.
    */
  protected def tempDir(prefix: String): Path =
    val dir = Files.createTempDirectory(prefix)
    temporaries += dir
    dir

  /** A copy of a golden network's directory, for the mutations below.
    *
    * Routed through `CsvReader` rather than assembled in memory on purpose: a
    * hand-built table can express states the reader never produces, so a test
    * built that way can pass while the real path stays broken.
    */
  protected def copyOf(name: String): Path =
    val dir    = tempDir(tempPrefix)
    val source = goldens.resolve("networks").resolve(name)
    // Closed explicitly: `Files.list` is backed by an open directory handle, and
    // this runs once per mutation test.
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    dir

  /** A golden network with one file edited, read back through the reader. */
  protected def mutate(name: String, file: String, edit: String => String): Network =
    val dir    = copyOf(name)
    val target = dir.resolve(file)
    val before = Files.readString(target)
    val after  = edit(before)
    // Otherwise a fixture that silently stops matching turns into a test that
    // asserts something about an unmodified network.
    assertNotEquals(after, before, s"the edit to $file changed nothing")
    Files.writeString(target, after)
    CsvReader.read(dir, schema, name)

  override def afterAll(): Unit =
    temporaries.foreach { dir =>
      if Files.exists(dir) then
        scala.util.Using.resource(Files.walk(dir)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder).forEach(Files.delete)
        }
    }

  /** Split a CSV line.
    *
    * `CsvReader.splitLine` is `private[network]`, and these fixtures have no
    * quoted fields, so a plain split is the honest local tool rather than a
    * reason to widen the reader's API.
    */
  protected def splitCsv(line: String): IndexedSeq[String] = line.split(",", -1).toIndexedSeq

  /** Set one column of a CSV, rewriting it in place when it already exists.
    *
    * `value` receives `(id, current)` and returns the new cell, so a test can
    * change one entity and genuinely leave the rest alone — returning `current`
    * keeps whatever the fixture had. The earlier version passed only the id,
    * which meant "leave the rest as they were" had to be written as a literal
    * copy of the fixture's value: if the golden changed, the test wrote the old
    * number back over it and stayed green. That is the same hidden dependence on
    * a fixture's contents that the in-place rewrite was introduced to remove.
    *
    * `current` is `""` for a column being appended, since there is nothing to
    * preserve.
    */
  protected def setColumn(
      text: String,
      column: String,
      value: (String, String) => String,
  ): String =
    val rows   = text.linesIterator.toIndexedSeq
    val header = splitCsv(rows.head)
    val at     = header.indexOf(column)
    val body = rows.tail.map { row =>
      val fields = splitCsv(row)
      val id     = fields.head
      if at >= 0 then fields.updated(at, value(id, fields(at))).mkString(",")
      else (fields :+ value(id, "")).mkString(",")
    }
    val newHeader = if at >= 0 then rows.head else (header :+ column).mkString(",")
    (newHeader +: body).mkString("\n") + "\n"

  /** Set a column to the same value for every entity. */
  protected def setColumn(text: String, column: String, value: String): String =
    setColumn(text, column, (_, _) => value)
