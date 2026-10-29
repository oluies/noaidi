package org.noaidi.stats

import java.nio.file.{Files, Path}
import java.sql.DriverManager

import scala.jdk.CollectionConverters.*

/** The event that reaches a collector, read back off disk.
  *
  * Through the file transport rather than against the builders, because the question is what
  * a consumer receives. A test that asserted on the objects would pass while the client
  * serialised them to something a collector rejects, and the client's own naming -- `_facets`
  * keys, `COMPLETE` spellings, where `columnLineage` sits -- is exactly the part this module
  * does not control and therefore has to check.
  */
class OpenLineageSinkSuite extends munit.FunSuite:

  /** One emission, shared by every test here.
    *
    * `OpenLineageClient` registers its circuit-breaker gauge in a global micrometer
    * registry, so a client per test logs "This Gauge has been already registered" three
    * times over. Nothing is wrong when it does, but a warning that appears because of how
    * the suite is written teaches a reader to ignore warnings. One event is also all these
    * tests want: they ask different questions of the same document.
    */
  private lazy val event: ujson.Value = emitted()

  private def emitted(): ujson.Value =
    val out  = Files.createTempFile("noaidi-lineage", ".jsonl")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      try StatsSchema.ddl.foreach(st.execute)
      finally st.close()
      val sink = OpenLineageSink(OpenLineageSink.Destination.ToFile(out))
      try sink.emit(Lineage.of(conn))
      finally sink.close()

      val lines = Files.readAllLines(out).asScala.filter(_.trim.nonEmpty).toSeq
      assertEquals(lines.length, 1, s"one COMPLETE event per run, got ${lines.length}")
      ujson.read(lines.head)
    finally
      conn.close()
      Files.deleteIfExists(out)

  test("the event is a COMPLETE run naming the job and the producer") {
    assertEquals(event("eventType").str, "COMPLETE")
    assertEquals(event("job")("name").str, "network-stats.schema")
    assertEquals(event("job")("namespace").str, "duckdb://network-stats")
    assert(event("producer").str.contains("noaidi"), event("producer").str)
    assert(event("run")("runId").str.nonEmpty)
    // The environment went under one key, so a consumer that does not know it can skip it
    // whole rather than per value.
    val env = event("run")("facets")("noaidi_environment")
    assert(env("duckdb").str.startsWith("v1.3"), env.render())
  }

  test("the tables arrive as inputs with their schema") {
    val inputs = event("inputs").arr.toSeq
    assertEquals(inputs.length, 8)
    val fact = inputs.find(_("name").str == "fact").getOrElse(fail("no fact input"))
    val cols = fact("facets")("schema")("fields").arr.toSeq.map(f => f("name").str)
    assertEquals(cols, Seq("component", "entity", "snapshot", "quantity", "value"))
    assert(fact("facets")("schema")("fields").arr.forall(_.obj.contains("type")))
  }

  test("the views arrive as outputs carrying column lineage") {
    val outputs = event("outputs").arr.toSeq
    assertEquals(outputs.length, 8)

    val injection = outputs.find(_("name").str == "injection").getOrElse(fail("no injection output"))
    val value     = injection("facets")("columnLineage")("fields")("value")
    val cited = value("inputFields").arr.toSeq
      .map(f => s"${f("name").str}.${f("field").str}")
      .toSet
    assertEquals(cited, Set("entity.sign", "fact.value"))
    assert(value("inputFields").arr.forall(_("namespace").str == "duckdb://network-stats"))

    // The defining SQL rides along as the transformation's description, all three branches
    // of it, because that is the answer to how the column is computed.
    val described = value("transformationDescription").str
    assert(described.contains("e.sign") && described.contains("COALESCE"), described)

    // `transformationType` is deliberately absent: the spec's vocabulary for it describes
    // what a transformation does to a value, and this module has nothing to say in that
    // vocabulary. See `OpenLineageSink`.
    assert(!value.obj.contains("transformationType") || value("transformationType").isNull,
      value.render())
  }

  test("a measured row count becomes an output statistics facet") {
    val stats = event("outputs").arr.toSeq
      .find(_("name").str == "injection")
      .getOrElse(fail("no injection output"))("outputFacets")("outputStatistics")
    assertEquals(stats("rowCount").num.toLong, 0L)
  }
