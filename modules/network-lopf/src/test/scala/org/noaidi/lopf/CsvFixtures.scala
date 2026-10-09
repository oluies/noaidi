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
  * Copies remain in `network-pf`, which cannot see this trait without a test-jar
  * dependency between the two modules -- more than the duplication costs. No
  * count of them is given here: this paragraph has named an incomplete set three
  * times running, each time missing one that carried the very defect the
  * sentence above claims to have ended. The rule is the durable part. Anything
  * in `network-lopf` mixes this in; anything in `network-pf` does not, and each
  * of those is its own copy to keep honest.
  */
trait CsvFixtures extends munit.Suite, munit.Assertions:

  /** Prefix for this suite's temporary directories, so a leak names its owner.
    *
    * Abstract on purpose. It briefly had a default of `"noaidi-"`, which quietly
    * gave that property away: a suite that forgot to override it would still
    * compile and would leave anonymous directories behind, which is the one
    * thing this member exists to prevent.
    */
  protected def tempPrefix: String

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
  protected def tempDir(prefix: String = tempPrefix): Path =
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
    copyFrom(goldens.resolve("networks").resolve(name))

  /** The same copy, from any fixture root.
    *
    * Parameterised rather than reimplemented per suite: `HydroOpsSuite` reads from
    * `reference/nordpsa` and had its own shallow copy, which is the duplication the
    * comment above says this trait exists to end -- reappearing, as it did before,
    * in a suite added after that comment was written.
    */
  protected def copyFrom(source: Path): Path =
    val dir = tempDir(tempPrefix)
    // Closed explicitly: `Files.list` is backed by an open directory handle, and
    // this runs once per mutation test.
    scala.util.Using.resource(Files.list(source)) { entries =>
      entries.iterator.forEachRemaining(f => Files.copy(f, dir.resolve(f.getFileName.toString)))
    }
    dir

  /** A fixture copied from `source` with files added or rewritten.
    *
    * Keeps [[mutate]]'s guard for a file that already exists: rewriting one to
    * content it already had is a test asserting something about an unmodified
    * network, and the assertion that catches it has to live here rather than in
    * each caller.
    */
  protected def copiedWith(source: Path, name: String, files: (String, String)*): Network =
    val dir = copyFrom(source)
    files.foreach { (file, content) =>
      val target = dir.resolve(file)
      if Files.exists(target) then
        assertNotEquals(content, Files.readString(target), s"the rewrite of $file changed nothing")
      Files.writeString(target, content)
    }
    CsvReader.read(dir, schema, name)

  /** A golden network with several files edited, read back through the reader.
    *
    * [[mutate]] takes one file and returns a `Network`, so two of them cannot be composed --
    * which is fine until a case needs two files changed at once to say anything. The first
    * such case was the `bus0` rule on a transmission capacity limit: it needs a branch made
    * extendable with the capped carrier in `lines.csv` '''and''' the cap moved in
    * `global_constraints.csv`, because either edit alone leaves a network where the rule is
    * invisible.
    *
    * Every edit has to change its file, for the reason [[mutate]] gives: a fixture that
    * silently stops matching turns into a test asserting something about an unmodified
    * network.
    */
  protected def mutateAll(name: String, edits: (String, String => String)*): Network =
    val dir = copyOf(name)
    edits.foreach { (file, edit) =>
      val target = dir.resolve(file)
      val before = Files.readString(target)
      val after  = edit(before)
      assertNotEquals(after, before, s"the edit to $file changed nothing")
      Files.writeString(target, after)
    }
    CsvReader.read(dir, schema, name)

  /** A golden network with one file edited, read back through the reader.
    *
    * Delegates rather than repeating [[mutateAll]]'s body. It had the body copied at first,
    * which is the idiom this whole file exists to replace — see the note at the top about a
    * helper that was added for one suite and then reappeared by hand in another. Two copies
    * of the "the edit to $file changed nothing" guard can drift apart, and the one that
    * drifts is the one nobody is looking at.
    */
  protected def mutate(name: String, file: String, edit: String => String): Network =
    mutateAll(name, file -> edit)

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
