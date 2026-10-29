package org.noaidi.stats

import java.sql.{Connection, DriverManager}

/** The column lineage the schema's own SQL implies.
  *
  * Asserted against named columns rather than against totals. A count of edges would pass
  * while every one of them pointed somewhere else, and the property that matters here is not
  * how many edges there are but whether a reader following one arrives at the column the
  * value genuinely comes from.
  *
  * The schema is loaded without data. Lineage is a statement about the definitions, so rows
  * would only make the suite slower and the row counts here are all zero on purpose -- they
  * prove the facet is populated, not that any number in it is interesting.
  */
class LineageSuite extends munit.FunSuite:

  private def withSchema[A](f: Connection => A): A =
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      try StatsSchema.ddl.foreach(st.execute)
      finally st.close()
      f(conn)
    finally conn.close()

  private def run(): LineageRun = withSchema(Lineage.of(_))

  private def field(run: LineageRun, view: String, name: String): LineageField =
    val dataset = run.outputs
      .find(_.name == view)
      .getOrElse(fail(s"no view '$view' in ${run.outputs.map(_.name).mkString(", ")}"))
    dataset.fields
      .find(_.name == name)
      .getOrElse(fail(s"no field '$name' in $view: ${dataset.fields.map(_.name).mkString(", ")}"))

  private def inputs(run: LineageRun, view: String, name: String): Set[String] =
    field(run, view, name).inputs.map(_.toString).toSet

  test("the run describes the eight tables as inputs and the eight views as outputs") {
    val r = run()
    assertEquals(r.inputs.map(_.name),
      Seq("active", "bus", "carrier", "entity", "fact", "port", "price", "snapshot"))
    assertEquals(r.outputs.map(_.name),
      Seq("bus_dimensions", "bus_load_weight", "bus_withdrawal", "capital_share",
        "dimensions", "injection", "operation", "transmission_branch"))
    assert(r.inputs.forall(_.kind == DatasetKind.Table))
    assert(r.outputs.forall(_.kind == DatasetKind.View))

    // A base table's fields carry no inputs, and that is a statement rather than a gap:
    // their rows come from `Stats.load`, which is Scala and not SQL, so nothing here can
    // derive where a value came from. Recorded in the scaladoc of `LineageRun`.
    assert(r.inputs.forall(_.fields.forall(_.inputs.isEmpty)))
    assert(r.inputs.forall(_.fields.nonEmpty))
  }

  test("a plain projection traces through its wrapper to the column underneath") {
    val r = run()
    // COALESCE(b.carrier, '') is still bus.carrier, and two output columns built from the
    // same input column both say so -- `carrier` and `bus_carrier` differ in name only.
    assertEquals(inputs(r, "bus_dimensions", "carrier"), Set("bus.carrier"))
    assertEquals(inputs(r, "bus_dimensions", "bus_carrier"), Set("bus.carrier"))
    assertEquals(field(r, "bus_dimensions", "carrier").expressions, Seq("COALESCE(b.carrier, '')"))
  }

  test("a union gives a column every branch's inputs and every branch's expression") {
    val r = run()
    // `injection.value` is three UNION ALL branches: a signed one-port, a negated p0, and a
    // port scaled by its efficiency. All three contribute.
    assertEquals(inputs(r, "injection", "value"), Set("entity.sign", "fact.value"))
    assertEquals(field(r, "injection", "value").expressions.length, 3)

    // And this is why the expressions are a sequence. `port` is the constant 0 in two
    // branches and `p.port` in the third: reporting the first alone would describe the
    // column as a constant while its inputs said it came from a table.
    assertEquals(inputs(r, "injection", "port"), Set("port.port"))
    assertEquals(field(r, "injection", "port").expressions, Seq("0", "p.port"))
  }

  test("a derived table is resolved through its own select list") {
    val r = run()
    // capital_share joins `(SELECT DISTINCT period, p_objective FROM snapshot) AS w` and
    // selects `w.p_objective`. The alias `w` is not a table, so an implementation that only
    // understood qualifiers would have stopped there; the edge has to go to the column the
    // subquery selected.
    assertEquals(inputs(r, "capital_share", "weight"), Set("snapshot.p_objective"))
    // Its first two columns come from both branches of the union.
    assertEquals(inputs(r, "capital_share", "component"), Set("active.component", "entity.component"))
  }

  test("a correlated subquery inside an expression contributes its columns") {
    val r = run()
    // `bus_load_weight.value` is a CASE holding two correlated subqueries over
    // `bus_withdrawal AS w`. `w` is visible only inside them, so resolving the expression
    // against the outer FROM alone found no `w` at all and dropped `bus_withdrawal.value`
    // out of the graph -- the field came back widened with the one column its value most
    // obviously depends on missing. This is that regression, pinned.
    val got = inputs(r, "bus_load_weight", "value")
    assert(got.contains("bus_withdrawal.value"), got.toSeq.sorted.mkString(", "))
    assertEquals(field(r, "bus_load_weight", "value").basis, LineageBasis.Exact)
  }

  test("a view reading a view is an edge between them") {
    val r = run()
    // bus_withdrawal aggregates `injection`, which is itself a view. Lineage that stopped at
    // base tables would skip the step where the sign convention is applied.
    assertEquals(inputs(r, "bus_withdrawal", "value"), Set("injection.value"))
    assert(r.outputs.find(_.name == "bus_withdrawal").get.reads.contains("injection"))
  }

  test("every view column has an input and every edge points at something real") {
    val r = run()
    val known = (r.inputs ++ r.outputs).map(d => d.name -> d.fields.map(_.name).toSet).toMap

    r.outputs.foreach { d =>
      d.fields.foreach { f =>
        // A column with no inputs is the one failure a lineage graph cannot survive: the
        // output looks the same as a column that genuinely depends on nothing, so a reader
        // has no way to tell a derived answer from a missing one.
        assert(f.inputs.nonEmpty, s"${d.name}.${f.name} has no inputs")
        f.inputs.foreach { ref =>
          val fields = known.getOrElse(ref.dataset,
            fail(s"${d.name}.${f.name} cites unknown dataset '${ref.dataset}'"))
          assert(fields.contains(ref.field), s"${d.name}.${f.name} cites $ref, which does not exist")
        }
      }
    }
  }

  test("reads covers every dataset the columns cite, and more") {
    val r = run()
    r.outputs.foreach { d =>
      val cited = d.fields.flatMap(_.inputs.map(_.dataset)).toSet
      assert(cited.subsetOf(d.reads.toSet),
        s"${d.name} cites ${cited.diff(d.reads.toSet).mkString(", ")} without reading it")
    }
    // Wider on at least one view, which is the point of keeping both: `bus_load_weight`
    // reads `bus_withdrawal` in a NOT EXISTS as well, and `dimensions` joins tables whose
    // columns reach no output column of its own.
    assert(r.outputs.exists(d => d.reads.toSet.size > d.fields.flatMap(_.inputs.map(_.dataset)).toSet.size))
  }

  test("every column resolves exactly on this schema") {
    val r = run()
    // A statement about these eight views, not a law. `LineageBasis.Widened` is a legitimate
    // answer -- an unqualified name visible in two relations, which USING produces -- and the
    // model reports it per field so a consumer is never misled. Asserted because today
    // nothing needs it: if a new view trips this, the thing to do is read the clue and decide
    // whether that column's edges really are a superset, not to loosen the test.
    assertEquals(r.widenedFields.map(_.toString), Seq.empty,
      s"widened: ${r.widenedFields.mkString(", ")}")
    assert(r.edges.length >= 40, s"${r.edges.length} edges")
  }

  test("the row count facet is populated, and skipped when not asked for") {
    val counted = withSchema(Lineage.of(_, rowCounts = true))
    assert((counted.inputs ++ counted.outputs).forall(_.rows.contains(0L)))
    val skipped = withSchema(Lineage.of(_, rowCounts = false))
    assert((skipped.inputs ++ skipped.outputs).forall(_.rows.isEmpty))
  }

  test("the environment facets name what produced the run") {
    val r = run()
    assert(r.facets.get("duckdb").exists(_.startsWith("v1.3")), r.facets.toString)
    assert(r.facets.contains("java") && r.facets.contains("os"), r.facets.toString)
  }

  test("a sink receives the run it was given") {
    val collected = new LineageSink.Collected
    val r         = run()
    collected.emit(r)
    assertEquals(collected.runs.length, 1)
    assertEquals(collected.runs.head.runId, r.runId)
    // The default does nothing, which is what a suite run should do to other systems.
    LineageSink.discard.emit(r)
  }
