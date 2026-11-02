package org.noaidi.stats

/** What an environment facet has to look like, in one place rather than two.
  *
  * `Lineage.environment` answers `SELECT version()` and puts the result under `duckdb`, and
  * two suites assert that the facet carries it: [[LineageSuite]] on the run and
  * [[OpenLineageSinkSuite]] on the emitted event. Both used to spell the assertion
  * `startsWith("v1.3")`, and both went red the moment Scala Steward opened
  * `duckdb_jdbc 1.3.2.1 -> 1.5.6.0`: `v1.5.6` does not start with `v1.3`.
  *
  * Nothing was wrong with the lineage code. The assertion was about a '''dependency's minor
  * version''' rather than about anything this repository does, so every bump of that
  * dependency reddened a build for a reason the diff could not explain -- which trains a
  * reader to edit the assertion rather than read it, and that is how a real finding gets
  * edited away.
  *
  * ==What the shape still catches==
  *
  * The point of the assertion is that the facet holds the '''real''' version rather than a
  * placeholder, and a shape test keeps all of that: an absent key, an empty string, a
  * literal `unknown`, a stub like `duckdb` or a number with no `v` all fail it. What it
  * stops catching is a version this repository never pinned anyway.
  *
  * The trailing `.*` is deliberate. DuckDB's own `version()` returns `v1.3.2` on the pinned
  * driver and `v1.5.6` on the bump, both three components with nothing after them -- but a
  * development build appends a suffix, and refusing one would be the same mistake a minor
  * version lower down.
  */
object LineageFacets:

  /** `v` and a three-part version, with anything a development build may append. */
  val DuckDbVersion = """v\d+\.\d+\.\d+.*"""

  /** Whether a facet value names a DuckDB version at all. */
  def namesDuckDb(value: String): Boolean = value.matches(DuckDbVersion)
