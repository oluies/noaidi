package org.noaidi.lopf

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
  */
trait CsvFixtures:

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
