package org.noaidi.stats

import java.sql.DriverManager

/** What the schema actually contains, asserted by name.
  *
  * Both descriptions of this module had drifted from it: `StatsSchema`'s own scaladoc said
  * "two long fact tables and five dimensions, with nine views", and
  * `docs/network-stats-design.md` said "seven tables, nine views". The catalog says eight of
  * each. Nothing was wrong with the code -- the DDL was always what it was -- but a reader
  * counting on either sentence was told a shape the database does not have, in both
  * directions at once.
  *
  * Asserted by name rather than by count, which is the part a count check would have missed:
  * a table renamed and a table added leave the total alone, and it is the names a query is
  * written against. The counts in those two sentences are then a consequence of this list
  * rather than a second place to maintain them, and this suite is where a reader finds out
  * which sentence to trust.
  */
class StatsSchemaCensusSuite extends munit.FunSuite:

  private def census[A](what: String)(f: java.sql.Statement => A): A =
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      try
        StatsSchema.ddl.foreach(st.execute)
        f(st)
      finally st.close()
    finally conn.close()

  private def names(st: java.sql.Statement, sql: String): List[String] =
    val rs      = st.executeQuery(sql)
    val builder = List.newBuilder[String]
    while rs.next() do builder += rs.getString(1)
    builder.result()

  test("the schema is the eight tables and eight views its documentation describes") {
    val (tables, views) = census("schema") { st =>
      (names(st, "SELECT table_name FROM duckdb_tables() WHERE NOT internal ORDER BY table_name"),
       names(st, "SELECT view_name FROM duckdb_views() WHERE NOT internal ORDER BY view_name"))
    }

    // The two long fact tables, then the six dimensions. `price` is the second fact table,
    // keyed by bus rather than by component, for the reason `StatsSchema` gives.
    assertEquals(
      tables,
      List("active", "bus", "carrier", "entity", "fact", "port", "price", "snapshot"),
      s"tables: ${tables.mkString(", ")}",
    )
    assertEquals(
      views,
      List("bus_dimensions", "bus_load_weight", "bus_withdrawal", "capital_share",
        "dimensions", "injection", "operation", "transmission_branch"),
      s"views: ${views.mkString(", ")}",
    )

    // Every DDL statement accounted for, so a statement that creates neither -- an index, a
    // macro, a sequence -- cannot slip in unnamed and leave the two lists above looking
    // complete when they are not.
    assertEquals(StatsSchema.ddl.length, tables.length + views.length,
      s"${StatsSchema.ddl.length} DDL statements against ${tables.length} tables " +
        s"and ${views.length} views")
  }
