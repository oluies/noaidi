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
final case class SubNetwork(carrier: String, buses: IndexedSeq[String]):
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

    passiveBranches(network).foreach { (bus0, bus1) =>
      if parent.contains(bus0) && parent.contains(bus1) then union(bus0, bus1)
    }

    // Carrier partitions the result as well as connectivity. Two buses joined by
    // a passive branch necessarily share a carrier in a well-formed network, so
    // this is a grouping rather than a second constraint — but grouping by it
    // keeps the carrier available on the result, which power flow needs.
    val islands = buses.ids.groupBy(find)

    islands.toIndexedSeq
      .map { (_, members) =>
        val carrier = members.headOption.map(buses.string("carrier", _)).getOrElse("")
        SubNetwork(carrier, members.sorted)
      }
      .sortBy(_.buses.head)

  /** Endpoint pairs of every passive branch in the network. */
  def passiveBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.PassiveBranch)

  /** Endpoint pairs of every controllable branch — links. */
  def controllableBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.ControllableBranch)

  private def branchesWhere(
      network: Network,
      accept: Role => Boolean,
  ): IndexedSeq[(String, String)] =
    network.tables.values.toIndexedSeq.flatMap { table =>
      if !accept(Role.of(table.spec)) then IndexedSeq.empty
      else
        table.ids.map { id =>
          (table.string("bus0", id), table.string("bus1", id))
        }
    }

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
