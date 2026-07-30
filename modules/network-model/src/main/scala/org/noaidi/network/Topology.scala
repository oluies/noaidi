package org.noaidi.network

import scala.collection.mutable

/** How a component participates in the network graph.
  *
  * Taken from the schema's `category`, not from a hardcoded list of component
  * names, so a component added upstream lands in the right place without this
  * file changing.
  */
enum Role:
  /** Connects two buses and carries flow determined by physics — lines and
    * transformers. These are what form sub-networks.
    */
  case PassiveBranch

  /** Connects two buses but its flow is set rather than solved — links. A link
    * does '''not''' merge the sub-networks at its ends.
    */
  case ControllableBranch

  /** Attaches to a single bus: generators, loads, storage. */
  case Attached

  /** A bus. */
  case Bus

  /** Carries no topology — carriers, global constraints, standard types. */
  case None

object Role:
  /** Read off PyPSA's own category rather than a list of component names.
    *
    * The distinction that matters is `passive_branch` against
    * `controllable_branch`: both connect two buses, but only the former couples
    * them electrically. Hardcoding "Line and Transformer are passive, Link is
    * not" would be correct today and silently wrong when a component is added —
    * `Process` is already a second controllable branch alongside `Link`, and it
    * lands correctly here without being named.
    */
  def of(spec: ComponentSpec): Role =
    if spec.name == "Bus" then Bus
    else
      spec.category match
        case "passive_branch"                              => PassiveBranch
        case "controllable_branch"                          => ControllableBranch
        case "controllable_one_port" | "passive_one_port"   => Attached
        case _                                              => None

/** One connected island of the network.
  *
  * PyPSA calls these sub-networks and determines them over '''passive branches
  * only''', grouped by carrier. That is the rule that makes an AC/DC network
  * come out as separate AC and DC islands joined by links rather than as one
  * graph: a link's flow is a decision variable, so the buses at its ends are not
  * electrically coupled and cannot share a slack.
  *
  * Getting this wrong is not a performance question. Power flow solves each
  * sub-network independently against its own slack bus, so merging AC and DC
  * would produce one singular system instead of several solvable ones.
  */
final case class SubNetwork(
    carrier: String,
    buses: IndexedSeq[String],
    /** Whether the island's buses disagree about their carrier.
      *
      * `carrier` is then only the first bus's, which is what PyPSA reports too —
      * it warns in this case rather than failing, and so does this, by surfacing
      * the fact instead of hiding it behind a single label.
      */
    mixedCarriers: Boolean = false,
):
  def size: Int = buses.length

object Topology:

  /** Buses reachable from each other over passive branches, grouped by carrier.
    *
    * Returned in ascending order of the smallest bus name in each island, so the
    * result is deterministic without depending on hash iteration order.
    */
  def subNetworks(network: Network): IndexedSeq[SubNetwork] =
    val busTable = network.table("Bus")
    if busTable.isEmpty then return IndexedSeq.empty
    val buses = busTable.get

    // Union-find over passive branches only.
    val parent = mutable.Map.from(buses.ids.map(id => id -> id))

    def find(x: String): String =
      var root = x
      while parent.getOrElse(root, root) != root do root = parent(root)
      // Path compression, so a long chain of series lines does not make this
      // quadratic on a country-scale network.
      var cursor = x
      while parent.getOrElse(cursor, cursor) != root do
        val next = parent(cursor)
        parent(cursor) = root
        cursor = next
      root

    def union(a: String, b: String): Unit =
      val (ra, rb) = (find(a), find(b))
      if ra != rb then parent(ra) = rb

    // Loudly, not silently: a dangling reference would otherwise split an island
    // and produce a plausible-looking decomposition with the wrong slack count.
    val dangling = danglingReferences(network)
    if dangling.nonEmpty then
      val (component, id, port, bus) = dangling.head
      throw new CsvReader.MalformedNetwork(
        s"$component '$id' references unknown bus '$bus' via $port" +
          (if dangling.size > 1 then s" (and ${dangling.size - 1} other dangling reference(s))" else "")
      )

    passiveBranches(network).foreach((bus0, bus1) => union(bus0, bus1))

    // Connectivity is the only grouping. The carrier is a *label*, taken from the
    // first bus in the island, which is what PyPSA does too. A well-formed
    // network has one carrier per island, but neither implementation enforces
    // that -- PyPSA warns, so a mixed island is reported here rather than
    // silently labelled with whichever bus came first in file order.
    val islands = buses.ids.groupBy(find)

    islands.toIndexedSeq
      .map { (_, members) =>
        val carriers = members.map(buses.string("carrier", _)).distinct
        SubNetwork(carriers.headOption.getOrElse(""), members.sorted, carriers.length > 1)
      }
      .sortBy(_.buses.head)

  /** Endpoint pairs of every passive branch in the network. */
  def passiveBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.PassiveBranch)

  /** Endpoint pairs of every controllable branch — links. */
  def controllableBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.ControllableBranch)

  /** Port columns present on a branch table, in order.
    *
    * Not fixed at `bus0`/`bus1`. PyPSA's controllable branches are precisely the
    * multi-port ones — Link is documented as "controllable directed flows
    * between two or more buses" — and the extra ports arrive as `bus2`, `bus3`
    * and so on. The schema declares only the first two, so the rest are custom
    * columns; the reader preserves them, and reading only the declared pair
    * would drop endpoints that are present in the data.
    */
  private[network] def portsOf(table: ComponentTable): IndexedSeq[String] =
    table.static.keys.toIndexedSeq
      .filter(_.matches("bus\\d+"))
      .sortBy(_.drop(3).toIntOption.getOrElse(Int.MaxValue))

  /** Endpoints of every branch a role accepts, as pairs.
    *
    * A multi-port branch contributes every pair of its ports, so connectivity
    * over it is transitive — which is what a three-port link means physically.
    */
  private def branchesWhere(
      network: Network,
      accept: Role => Boolean,
  ): IndexedSeq[(String, String)] =
    network.tables.values.toIndexedSeq.flatMap { table =>
      if !accept(Role.of(table.spec)) then IndexedSeq.empty
      else
        val ports = portsOf(table)
        table.ids.flatMap { id =>
          val endpoints = ports.map(table.string(_, id)).filter(_.nonEmpty)
          endpoints.combinations(2).collect { case IndexedSeq(a, b) => (a, b) }.toIndexedSeq
        }
    }

  /** Every (component, entity, port, bus) a branch references.
    *
    * Used to check references before they are silently dropped.
    */
  private[network] def branchEndpoints(network: Network): IndexedSeq[(String, String, String, String)] =
    network.tables.values.toIndexedSeq.flatMap { table =>
      Role.of(table.spec) match
        case Role.PassiveBranch | Role.ControllableBranch =>
          val ports = portsOf(table)
          table.ids.flatMap { id =>
            ports.map(port => (table.spec.name, id, port, table.string(port, id)))
          }
        case _ => IndexedSeq.empty
    }

  /** Branch endpoints naming a bus that does not exist.
    *
    * A stale or mistyped bus reference splits what should be one island in two,
    * and the result still looks like a network — so it is reported rather than
    * skipped. Every other structural error in this module fails loudly, and the
    * consequence here is the same one the class doc names: the wrong number of
    * slack buses.
    */
  def danglingReferences(network: Network): IndexedSeq[(String, String, String, String)] =
    val known = network.table("Bus").map(_.ids.toSet).getOrElse(Set.empty)
    branchEndpoints(network).filter((_, _, _, bus) => bus.nonEmpty && !known.contains(bus))

  /** Components attached to `bus`, as (component name, entity id) pairs. */
  def attachedTo(network: Network, bus: String): IndexedSeq[(String, String)] =
    network.tables.values.toIndexedSeq.flatMap { table =>
      if Role.of(table.spec) != Role.Attached then IndexedSeq.empty
      else table.ids.filter(id => table.string("bus", id) == bus).map(table.spec.name -> _)
    }

  /** Buses with no branch of any kind, passive or controllable.
    *
    * Worth surfacing rather than silently tolerating: an isolated bus with load
    * on it makes the network infeasible, and the cause is far easier to see here
    * than in a solver's output.
    */
  def isolatedBuses(network: Network): IndexedSeq[String] =
    val connected = (passiveBranches(network) ++ controllableBranches(network))
      .flatMap((a, b) => IndexedSeq(a, b))
      .toSet
    network.table("Bus").map(_.ids.filterNot(connected.contains)).getOrElse(IndexedSeq.empty)
