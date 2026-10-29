package org.noaidi.stats

import scala.collection.mutable

/** Where a [[LineageRun]] goes.
  *
  * The seam that keeps `Lineage` free of any particular lineage vocabulary. The derivation
  * is the valuable part and it is pure: a connection in, a value out, no network, no client,
  * no wire format. Putting the emission behind a trait means a test can assert the graph is
  * right without a transport, and a run can be told to describe itself without anything
  * being sent anywhere -- which is the default, because emitting to a collector is a side
  * effect on another system and should never be what happens when someone runs the suite.
  *
  * [[OpenLineageSink]] is one implementation and the only file in this module that knows
  * OpenLineage exists.
  */
trait LineageSink:
  def emit(run: LineageRun): Unit

object LineageSink:

  /** Accepts a run and does nothing with it. */
  val discard: LineageSink = _ => ()

  /** Keeps every run it is given, in order.
    *
    * For tests, and for a caller that wants to look at the graph before deciding whether to
    * send it. Not thread-safe, which is the same contract the solver's kernels declare: one
    * instance, one thread.
    */
  final class Collected extends LineageSink:
    private val received = mutable.ArrayBuffer.empty[LineageRun]
    def emit(run: LineageRun): Unit = received.append(run)
    def runs: Seq[LineageRun]       = received.toSeq

  /** Writes each run as a readable outline, for a log or a terminal. */
  final class Printed(to: java.io.PrintStream = System.out) extends LineageSink:
    def emit(run: LineageRun): Unit =
      to.println(s"${run.job} (${run.runId})")
      run.facets.toSeq.sorted.foreach((k, v) => to.println(s"  $k = $v"))
      run.inputs.foreach { d =>
        to.println(s"  in  ${d.name}: ${d.fields.map(_.name).mkString(", ")}")
      }
      run.outputs.foreach { d =>
        to.println(s"  out ${d.name} <- ${d.reads.mkString(", ")}")
        d.fields.foreach { f =>
          val mark = if f.basis == LineageBasis.Widened then " (superset)" else ""
          to.println(s"        ${f.name}$mark <- ${f.inputs.mkString(", ")}")
          f.expressions.foreach(e => to.println(s"            = $e"))
        }
      }
