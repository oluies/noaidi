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
  * only'''. Connectivity is the only grouping; [[carrier]] is a label read off
  * the island's first bus in sorted order. That is the rule that makes an AC/DC
  * network
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

  /** What counts as a branch port column.
    *
    * Defined once because two modules must agree: `CsvReader` uses it to decide a
    * column holds identifiers rather than numbers, and [[portsOf]] uses it to
    * decide the same column is an endpoint. They are the same rule, and letting
    * them drift reintroduces the numeric-bus-name crash — the reader would type
    * the column as floats and every read would throw.
    */
  private[network] val PortColumn = "bus\\d+".r

  private[network] def isPortColumn(name: String): Boolean =
    PortColumn.matches(name)

  /** Buses reachable from each other over passive branches.
    *
    * Grouped by connectivity alone; the carrier is a label, not part of the
    * grouping. Returned in ascending order of the smallest bus name in each
    * island, so the result is deterministic without depending on hash iteration
    * order.
    *
    * @throws CsvReader.MalformedNetwork
    *   if a passive branch table lacks a `bus0` or `bus1` column, or a passive
    *   branch references a bus that does not exist, or leaves a declared
    *   endpoint blank. Use [[danglingReferences]] to check without throwing.
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
    //
    // Restricted to passive branches, which is what this function reads. A stale
    // reference in links.csv cannot affect a decomposition defined over passive
    // branches, so refusing to decompose the network over it would be a
    // different function's complaint.
    val dangling = danglingIn(branchEndpoints(network, _ == Role.PassiveBranch), network)
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
        val sorted = members.sorted
        // Read off `sorted.head`, not file order, so the label agrees with the
        // `buses` field a caller sees. Taking it from file order would let a
        // mixed island report a carrier belonging to none of `buses.head`.
        val carriers = sorted.map(buses.string("carrier", _)).distinct
        SubNetwork(carriers.head, sorted, carriers.length > 1)
      }
      .sortBy(_.buses.head)

  /** Endpoint pairs of every passive branch in the network. */
  def passiveBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.PassiveBranch)

  /** Endpoint pairs of every controllable branch — links. */
  def controllableBranches(network: Network): IndexedSeq[(String, String)] =
    branchesWhere(network, _ == Role.ControllableBranch)

  /** Public alias for [[portsOf]], for callers outside this package that need to
    * enumerate a branch's endpoints — the L2 modules above all, which must agree
    * with the topology about how many ports a branch has.
    */
  def branchPorts(table: ComponentTable): IndexedSeq[String] = portsOf(table)

  /** Efficiency applying to a branch's receiving port.
    *
    * PyPSA names these `efficiency` for `bus1` and `efficiency<i>` for later
    * ports. A passive branch declares none and is lossless in the linear model,
    * so the fallback is 1.
    *
    * Lives here rather than in a solver module because every consumer of
    * [[branchPorts]] needs it and they must agree: a port enumerated without its
    * matching efficiency silently becomes lossless, which balances the books at
    * the wrong number.
    */
  def portEfficiency(table: ComponentTable, id: String, port: String, snapshot: Int): Double =
    val attribute = if port == "bus1" then "efficiency" else s"efficiency${port.drop(3)}"

    // Three places a value can live, and all three have to be checked.
    // `efficiency2` and later are not schema attributes, so a multi-port link
    // carries them as custom columns — and a custom column can arrive as a series
    // with no static counterpart, which is the normal export shape when the
    // static value sits at its 1.0 default and only the time series is
    // non-default. Checking only `spec` and `static` reads such a port as
    // lossless, putting power at the receiving bus that the network never
    // produced.
    val declared = table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
    val varying  = table.series.get(attribute).exists(_.covers(id))

    if varying then
      val value = table.series(attribute).get(id, snapshot).getOrElse(Double.NaN)
      if value.isFinite then value else 1.0
    else if declared then
      // Not `valueAt`: for a series-only custom column it would fall through to
      // `float`, which requires a declared attribute and throws.
      val value = table.valueAt(attribute, id, snapshot)
      if value.isFinite then value else 1.0
    else 1.0

  private[network] def portsOf(table: ComponentTable): IndexedSeq[String] =
    val ports = table.static.keys.toIndexedSeq
      .filter(isPortColumn)
      .sortBy(_.drop(3).toIntOption.getOrElse(Int.MaxValue))
    // A branch with no bus1 column contributes no edges at all, which is the
    // silent island split this module is supposed to make impossible. Both
    // required ports are declared attributes, so their absence is malformed
    // input rather than a two-port branch with one port.
    if table.size > 0 then
      Seq("bus0", "bus1").foreach { required =>
        if !ports.contains(required) then
          throw new CsvReader.MalformedNetwork(
            s"${table.spec.listName} has no '$required' column; a branch needs both endpoints"
          )
      }
    ports

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
          // Blanks are excluded from the pairing but reported as dangling, so a
          // missing endpoint cannot quietly reduce the graph.
          val endpoints = ports.map(table.string(_, id)).filter(_.nonEmpty)
          endpoints.combinations(2).collect { case IndexedSeq(a, b) => (a, b) }.toIndexedSeq
        }
    }

  /** Every (component, entity, port, bus) a branch references.
    *
    * Used to check references before they are silently dropped.
    */
  private[network] def branchEndpoints(
      network: Network,
      accept: Role => Boolean = r => r == Role.PassiveBranch || r == Role.ControllableBranch,
  ): IndexedSeq[(String, String, String, String)] =
    // Tables are filtered *before* their ports are enumerated. Filtering the
    // resulting tuples instead would still run portsOf over every branch table,
    // so a links.csv missing bus1 would prevent a passive-only decomposition —
    // the outcome this validation was added to prevent.
    network.tables.values.toIndexedSeq.filter(t => accept(Role.of(t.spec))).flatMap { table =>
      val ports = portsOf(table)
      table.ids.flatMap { id =>
        ports.map(port => (table.spec.name, id, port, table.string(port, id)))
      }
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
    danglingIn(branchEndpoints(network), network)

  /** Every bus reference that names a bus which does not exist — branches '''and'''
    * one-ports.
    *
    * [[danglingReferences]] covers only branches, because that is all a
    * decomposition over branches can be affected by. A consumer that reads
    * injections needs more: a Load whose bus is stale or misspelt matches no bus
    * by string equality, so it simply vanishes. The island's demand drops, the
    * answer is the answer for a different network, and nothing reports it.
    *
    * Lifted here rather than duplicated because both L2 modules need exactly this
    * and one of them had it while the other did not, which made the same broken
    * network loud through one entry point and silently wrong through the other.
    */
  def danglingBusReferences(network: Network): IndexedSeq[(String, String, String, String)] =
    val known = network.table("Bus").map(_.ids.toSet).getOrElse(Set.empty)

    val attached = network.tables.values.toIndexedSeq.flatMap { table =>
      if Role.of(table.spec) != Role.Attached || table.spec.attribute("bus").isEmpty then
        IndexedSeq.empty
      else
        table.ids.flatMap { id =>
          val bus = table.string("bus", id)
          if known.contains(bus) then None else Some((table.spec.name, id, "bus", bus))
        }
    }

    danglingReferences(network) ++ attached

  /** As [[danglingReferences]], restricted to the branches a caller consumes. */
  private def danglingIn(
      endpoints: IndexedSeq[(String, String, String, String)],
      network: Network,
  ): IndexedSeq[(String, String, String, String)] =
    val known = network.table("Bus").map(_.ids.toSet).getOrElse(Set.empty)
    endpoints.filter { (component, _, port, bus) =>
      if bus.nonEmpty then !known.contains(bus)
      else
        // A blank is a missing reference only for a port the schema declares.
        // `bus0`/`bus1` have no meaningful default, so an empty cell there is an
        // error. An extra port is different: PyPSA's default for `bus2` is the
        // empty string and it omits all-default columns on export, so a `bus2`
        // column appears as soon as *one* link uses it and every link that does
        // not carries a blank. Treating those as dangling would reject a valid
        // multi-port network -- the very shape port enumeration was added for.
        network.schema.get(component).exists(_.attribute(port).isDefined)
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
