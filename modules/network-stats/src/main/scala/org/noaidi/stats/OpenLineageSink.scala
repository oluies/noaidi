package org.noaidi.stats

import java.net.URI
import java.time.ZonedDateTime

import scala.jdk.CollectionConverters.*

import io.openlineage.client.OpenLineage
import io.openlineage.client.OpenLineageClient
import io.openlineage.client.transports.{ConsoleTransport, FileConfig, FileTransport, Transport}

/** A [[LineageRun]] as an OpenLineage `RunEvent`.
  *
  * The only file in this module that imports `io.openlineage`. Everything it needs is
  * already in the [[LineageRun]], so this is a translation and nothing else: no catalog
  * queries, no parsing, no decisions about what the lineage is. That separation is what
  * lets the derivation be tested without a client and emitted without a collector.
  *
  * ==What goes on the wire==
  *
  * One `COMPLETE` event per run. The schema step is not a long-running job with a start and
  * an end -- the views exist or they do not -- so a `START`/`COMPLETE` pair would describe a
  * duration that means nothing. A single terminal event is the honest shape for a snapshot.
  *
  * The tables are `inputs` and the views `outputs`, which is the step [[LineageRun]]
  * describes. Each output carries a `schema` facet, a `columnLineage` facet and, when the
  * run measured them, an `outputStatistics` facet with the row count.
  *
  * ==Two places this does not pretend==
  *
  * `transformationType` is left unset. The spec's vocabulary for it is about what a
  * transformation does to a value -- identity, masking -- and [[LineageBasis]] is about how
  * much the derivation could prove. Writing one into the other would put a word in a field
  * that consumers read as meaning something else. A widened field says so in its
  * description instead, where a human reads it and no tool mistakes it for a claim.
  *
  * Table row counts stay out of the event. They are in the model, but an input's statistics
  * facet describes what a run '''read''', and this run read whatever each view's query read
  * rather than every row of every table. Reporting the table's size there would be a number
  * in the right field about the wrong thing.
  */
final class OpenLineageSink private (
    transport: Transport,
    producer: URI,
) extends LineageSink,
      AutoCloseable:

  private val client = new OpenLineageClient(transport)
  private val ol     = new OpenLineage(producer)

  def emit(run: LineageRun): Unit =
    val event = ol
      .newRunEventBuilder()
      .eventTime(ZonedDateTime.now())
      .eventType(OpenLineage.RunEvent.EventType.COMPLETE)
      .run(
        ol.newRunBuilder()
          .runId(run.runId)
          .facets(environmentFacet(run))
          .build())
      .job(
        ol.newJobBuilder()
          .namespace(run.namespace)
          .name(run.job)
          .build())
      .inputs(run.inputs.map(inputDataset(run.namespace, _)).asJava)
      .outputs(run.outputs.map(outputDataset(run.namespace, _)).asJava)
      .build()
    client.emit(event)

  def close(): Unit = client.close()

  /** The run's environment, as one custom facet rather than several.
    *
    * `RunFacets` has a fixed set of named slots and none of them is "what produced this", so
    * the values go through `put` under one key. One facet rather than one per value because
    * a consumer that does not know the key should be able to ignore all of it at once.
    */
  private def environmentFacet(run: LineageRun): OpenLineage.RunFacets =
    val facet = ol.newRunFacet()
    run.facets.foreach((key, value) => facet.getAdditionalProperties.put(key, value))
    new OpenLineage.RunFacetsBuilder().put("noaidi_environment", facet).build()

  private def schemaFacet(dataset: LineageDataset): OpenLineage.SchemaDatasetFacet =
    ol.newSchemaDatasetFacetBuilder()
      .fields(dataset.fields.map { field =>
        ol.newSchemaDatasetFacetFieldsBuilder()
          .name(field.name)
          .`type`(field.dataType)
          .build()
      }.asJava)
      .build()

  private def inputDataset(
      namespace: String,
      dataset: LineageDataset,
  ): OpenLineage.InputDataset =
    ol.newInputDatasetBuilder()
      .namespace(namespace)
      .name(dataset.name)
      .facets(ol.newDatasetFacetsBuilder().schema(schemaFacet(dataset)).build())
      .build()

  private def outputDataset(
      namespace: String,
      dataset: LineageDataset,
  ): OpenLineage.OutputDataset =
    val facets = ol
      .newDatasetFacetsBuilder()
      .schema(schemaFacet(dataset))
      .columnLineage(columnLineageFacet(namespace, dataset))
      .build()

    val builder = ol
      .newOutputDatasetBuilder()
      .namespace(namespace)
      .name(dataset.name)
      .facets(facets)

    dataset.rows
      .map { rows =>
        builder.outputFacets(
          ol.newOutputDatasetOutputFacetsBuilder()
            .outputStatistics(
              ol.newOutputStatisticsOutputDatasetFacetBuilder().rowCount(rows).build())
            .build())
      }
      .getOrElse(builder)
      .build()

  private def columnLineageFacet(
      namespace: String,
      dataset: LineageDataset,
  ): OpenLineage.ColumnLineageDatasetFacet =
    val fields = dataset.fields.foldLeft(new OpenLineage.ColumnLineageDatasetFacetFieldsBuilder()) {
      (builder, field) =>
        builder.put(
          field.name,
          ol.newColumnLineageDatasetFacetFieldsAdditionalBuilder()
            .inputFields(field.inputs.map { ref =>
              ol.newInputFieldBuilder()
                .namespace(namespace)
                .name(ref.dataset)
                .field(ref.field)
                .build()
            }.asJava)
            .transformationDescription(description(field))
            .build(),
        )
    }
    ol.newColumnLineageDatasetFacetBuilder().fields(fields.build()).build()

  /** The field's defining SQL, and a word when the edges are a superset of the truth. */
  private def description(field: LineageField): String =
    val sql = field.expressions.mkString(" | ")
    if field.basis == LineageBasis.Widened then
      if sql.isEmpty then "superset: at least one reference could not be narrowed to one relation"
      else s"$sql  [superset: at least one reference could not be narrowed to one relation]"
    else sql

object OpenLineageSink:

  /** Where the events go. */
  enum Destination:
    /** Standard output, as JSON. Costs nothing and reaches no other system. */
    case Console

    /** Appended to a file, one JSON event per line. */
    case ToFile(path: java.nio.file.Path)

  /** The default `producer`: what a consumer is told generated the event. */
  val Producer: URI = URI.create("https://github.com/oluies/noaidi/tree/main/modules/network-stats")

  def apply(
      destination: Destination,
      producer: URI = Producer,
  ): OpenLineageSink =
    val transport = destination match
      case Destination.Console      => new ConsoleTransport()
      case Destination.ToFile(path) => new FileTransport(fileConfig(path))
    new OpenLineageSink(transport, producer)

  /** An HTTP destination, which is left to the client's own configuration.
    *
    * Deliberately not a `Destination` case. An HTTP transport needs a URL, usually a key,
    * and a decision about timeouts and circuit breaking, and wrapping that in two
    * constructor arguments would be a worse version of configuration the client already
    * does properly. A caller that wants to post to a collector builds the transport and
    * passes it here.
    */
  def to(transport: Transport, producer: URI = Producer): OpenLineageSink =
    new OpenLineageSink(transport, producer)

  private def fileConfig(path: java.nio.file.Path): FileConfig =
    val config = new FileConfig()
    config.setLocation(path.toAbsolutePath.toString)
    config
