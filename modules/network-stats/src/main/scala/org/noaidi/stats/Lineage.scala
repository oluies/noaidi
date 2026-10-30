package org.noaidi.stats

import java.sql.Connection
import java.util.UUID

import scala.collection.mutable

/** One input field that an output field's value is computed from. */
final case class FieldRef(dataset: String, field: String):
  override def toString: String = s"$dataset.$field"

/** How much the derivation is entitled to claim about a field's inputs.
  *
  * [[Exact]] means every edge was resolved through a qualifier to a named relation, or
  * through a subquery's own select list to one. [[Widened]] means at least one reference
  * could not be narrowed to a single relation -- an unqualified name visible in more than
  * one of them, which `USING` produces by merging two columns into one -- so the edges are a
  * '''superset''': every true input is in there, and so is at least one that may not be.
  *
  * Recorded per field rather than globally, and recorded at all rather than silently
  * widening, because a lineage graph that cannot say which of its edges are certain is one
  * a reader has to re-derive by hand before trusting any of it.
  */
enum LineageBasis:
  case Exact, Widened

/** A relation's kind, which decides whether it is an input or an output of the schema step. */
enum DatasetKind:
  case Table, View

/** One output field, traced to the inputs it is computed from.
  *
  * `expressions` holds the field's defining SQL, recovered by rebuilding a one-column
  * statement around the expression and asking DuckDB to render it back. It is not sliced out
  * of the view text: DuckDB reports `query_location_length` as zero for every one of the 52
  * select entries in this schema, so there is no span to slice.
  *
  * A sequence, one per contributing branch, because a set operation has an expression per
  * branch and reporting the first alone misdescribes the column. `injection.port` is `0` in
  * two of its three `UNION ALL` branches and `p.port` in the third: shown as just `0` it
  * reads as a constant, while its inputs say otherwise -- a field whose two halves
  * contradict each other is worse than one that is merely incomplete.
  */
final case class LineageField(
    name: String,
    dataType: String,
    inputs: Seq[FieldRef],
    expressions: Seq[String],
    basis: LineageBasis,
)

/** A table or view, its fields, and what it reads. */
final case class LineageDataset(
    name: String,
    kind: DatasetKind,
    fields: Seq[LineageField],
    /** Every relation the definition names, including ones only a `WHERE` or `JOIN` touches.
      *
      * Wider than the union of the fields' inputs, deliberately. A column that decides
      * which rows survive does not contribute to any output column's '''value''', so it has
      * no place in that column's lineage -- but the view does depend on it, and a reader
      * asking what breaks if a table changes needs the wider answer. The two questions get
      * two fields rather than one compromise between them.
      */
    reads: Seq[String],
    rows: Option[Long],
    definition: Option[String],
)

/** What a sink is told about one run of the stats schema.
  *
  * The tables are the inputs and the views the outputs, which is the step this describes:
  * how the eight views are computed from the eight tables. The step before it -- the network
  * components becoming rows in those tables -- is deliberately absent rather than guessed.
  * That mapping lives in `Stats.load` as Scala, not as SQL, so nothing here can derive it,
  * and a hand-written copy of it would be a second source of truth that drifts. This file
  * reports what the catalog knows.
  */
final case class LineageRun(
    namespace: String,
    job: String,
    runId: UUID,
    inputs: Seq[LineageDataset],
    outputs: Seq[LineageDataset],
    facets: Map[String, String],
):
  /** Every column-level edge, for a caller that wants the graph rather than the datasets. */
  def edges: Seq[(FieldRef, FieldRef)] =
    outputs.flatMap(d => d.fields.flatMap(f => f.inputs.map(_ -> FieldRef(d.name, f.name))))

  def widenedFields: Seq[FieldRef] =
    outputs.flatMap(d => d.fields.filter(_.basis == LineageBasis.Widened).map(f => FieldRef(d.name, f.name)))

/** Column-level lineage for the stats schema, derived from the catalog and DuckDB's parser.
  *
  * Nothing here is hand-maintained. The relations come from `duckdb_tables()` and
  * `duckdb_views()`, the fields from `duckdb_columns()`, and each view's column lineage from
  * `json_serialize_sql` over that view's own definition. The alternative -- a table of
  * column edges written out beside the DDL -- is a second description of the same SQL, and
  * this module has already shipped two sentences that drifted from their own schema.
  *
  * ==Working against the parser==
  *
  * Four things about DuckDB's AST had to be established by measurement rather than assumed,
  * and each one changed the implementation:
  *
  *  - `json_serialize_sql` '''only takes a SELECT'''. `duckdb_views().sql` hands back a
  *    `CREATE VIEW ... AS ...`, which it refuses with "Only SELECT statements can be
  *    serialized to json!", so the prefix comes off first.
  *  - Its argument must be '''typed''' VARCHAR. A bare `?` fails at prepare time, hence the
  *    `CAST(? AS VARCHAR)` -- which is also what keeps view SQL out of string interpolation.
  *  - `query_location_length` is '''zero everywhere''', all 52 select entries in this schema,
  *    so an expression cannot be sliced out of the source text. It is rebuilt instead.
  *  - `query_location` carries `UINT64_MAX` as its "unknown" sentinel, and that '''cannot
  *    survive''' a `ujson` round trip: ujson reads every number as a `Double`, 2^64-1 is not
  *    one, and writing it back produces `1.8446744073709552E19`, which the deserialiser
  *    rejects with "expected type 'uint64_t', but got type: 'real'". Both keys are scrubbed
  *    before anything is written back, which is sound because neither is usable anyway.
  */
object Lineage:

  /** The alias a rebuilt one-column statement carries, as the delimiter for its text.
    *
    * A sentinel rather than the field's real name: the text is taken as what lies between
    * `SELECT ` and this alias, so the delimiter has to be one that cannot also occur inside
    * the expression. A real column name can.
    */
  private val Sentinel = "__noaidi_expr__"

  /** Guard on subquery recursion.
    *
    * A view's definition is a tree and this walks it, so a cycle is not reachable through
    * well-formed SQL. The cap is here for the case that is: a definition this code
    * misreads, which would otherwise recurse until the stack ended the test run with no
    * indication of which view did it.
    */
  private val MaxDepth = 12

  def of(
      conn: Connection,
      namespace: String = "duckdb://network-stats",
      job: String = "network-stats.schema",
      /** Count the rows of every relation.
        *
        * Off by default because it is not free and nothing about lineage needs it: a
        * `count(*)` over these views evaluates them, and `bus_load_weight` runs a correlated
        * subquery per bus and snapshot to answer it. Describing a schema should not be the
        * expensive call.
        */
      rowCounts: Boolean = false,
  ): LineageRun =
    val columns = columnsByRelation(conn)
    val views   = viewDefinitions(conn)
    val tables  = queryStrings(conn,
      "SELECT table_name FROM duckdb_tables() WHERE NOT internal ORDER BY table_name")

    val inputs = tables.map { t =>
      LineageDataset(
        name = t,
        kind = DatasetKind.Table,
        // No field-level inputs: a base table's rows come from `Stats.load`, which is Scala.
        fields = columns.getOrElse(t, Seq.empty).map { (name, dataType) =>
          LineageField(name, dataType, Seq.empty, Seq.empty, LineageBasis.Exact)
        },
        reads = Seq.empty,
        rows = if rowCounts then Some(countRows(conn, t)) else None,
        definition = None,
      )
    }

    val outputs = views.map { (name, sql) =>
      val analysis = analyse(conn, name, sql, columns)
      LineageDataset(
        name = name,
        kind = DatasetKind.View,
        fields = analysis.fields,
        reads = analysis.reads,
        rows = if rowCounts then Some(countRows(conn, name)) else None,
        definition = Some(sql),
      )
    }

    LineageRun(
      namespace = namespace,
      job = job,
      runId = UUID.randomUUID(),
      inputs = inputs,
      outputs = outputs,
      facets = environment(conn),
    )

  // -- catalog ---------------------------------------------------------------

  private def queryStrings(conn: Connection, sql: String): Seq[String] =
    val st = conn.createStatement()
    try
      val rs      = st.executeQuery(sql)
      val builder = Seq.newBuilder[String]
      while rs.next() do builder += rs.getString(1)
      builder.result()
    finally st.close()

  private def columnsByRelation(conn: Connection): Map[String, Seq[(String, String)]] =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(
        """SELECT table_name, column_name, data_type
          |FROM duckdb_columns() WHERE NOT internal
          |ORDER BY table_name, column_index""".stripMargin)
      val builder = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[(String, String)]]
      while rs.next() do
        builder
          .getOrElseUpdate(rs.getString(1), mutable.ArrayBuffer.empty)
          .append((rs.getString(2), rs.getString(3)))
      builder.map((k, v) => k -> v.toSeq).toMap
    finally st.close()

  private def viewDefinitions(conn: Connection): Seq[(String, String)] =
    val st = conn.createStatement()
    try
      val rs      = st.executeQuery(
        "SELECT view_name, sql FROM duckdb_views() WHERE NOT internal ORDER BY view_name")
      val builder = Seq.newBuilder[(String, String)]
      while rs.next() do builder += ((rs.getString(1), rs.getString(2)))
      builder.result()
    finally st.close()

  private def countRows(conn: Connection, relation: String): Long =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(s"""SELECT count(*) FROM "$relation"""")
      rs.next()
      rs.getLong(1)
    finally st.close()

  /** What produced this run, for the facets a sink attaches to it. */
  private def environment(conn: Connection): Map[String, String] =
    val duckdb =
      val st = conn.createStatement()
      try
        val rs = st.executeQuery("SELECT version()")
        rs.next()
        rs.getString(1)
      finally st.close()
    Map(
      "duckdb" -> duckdb,
      "java" -> System.getProperty("java.version"),
      "jvm" -> System.getProperty("java.vm.name"),
      "os" -> s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
    )

  // -- the parser ------------------------------------------------------------

  /** The `CREATE VIEW <name> AS ` that `json_serialize_sql` will not accept.
    *
    * Anchored on the keywords and an optionally quoted identifier rather than on the first
    * ` AS ` in the string, which a view body can also contain -- every column alias in this
    * schema is written with one.
    */
  private val CreateView = """(?is)^\s*CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(?:"(?:[^"]|"")*"|[^\s]+)\s+AS\s+""".r

  private def selectBody(name: String, sql: String): String =
    CreateView.findPrefixMatchOf(sql) match
      case Some(m) => sql.substring(m.end).trim.stripSuffix(";").trim
      case None =>
        throw new IllegalStateException(
          s"the catalog's definition of view '$name' does not begin with CREATE VIEW ... AS: $sql")

  private def serialize(conn: Connection, sql: String): ujson.Value =
    val ps = conn.prepareStatement("SELECT json_serialize_sql(CAST(? AS VARCHAR))")
    try
      ps.setString(1, sql)
      val rs = ps.executeQuery()
      rs.next()
      val parsed = ujson.read(rs.getString(1))
      parsed.obj.get("error").foreach { flag =>
        if flag.bool then
          throw new IllegalStateException(
            s"DuckDB refused to serialise a view body: ${parsed.obj.get("error_message").map(_.str).getOrElse("(no message)")}")
      }
      scrubLocations(parsed)
      parsed
    finally ps.close()

  /** Remove the location keys, for the `UINT64_MAX` reason in this object's scaladoc. */
  private def scrubLocations(value: ujson.Value): Unit = value match
    case o: ujson.Obj =>
      o.obj.remove("query_location")
      o.obj.remove("query_location_length")
      o.obj.values.foreach(scrubLocations)
    case a: ujson.Arr => a.arr.foreach(scrubLocations)
    case _            => ()

  private def nodeType(value: ujson.Value): String =
    value.objOpt.flatMap(_.get("type")).flatMap(_.strOpt).getOrElse("")

  /** A relation a reference can resolve against. */
  private enum Relation:
    /** A named table or view: a reference to it is an edge. */
    case Named(name: String)

    /** A derived table: a reference to it resolves through its own select list. */
    case Derived(node: ujson.Value)

  /** The relations one SELECT's `FROM` makes visible, by the name a qualifier would use. */
  private final case class Scope(byName: Map[String, Relation], ordered: Seq[Relation])

  private object Scope:
    val empty: Scope = Scope(Map.empty, Seq.empty)
    def merge(a: Scope, b: Scope): Scope =
      Scope(a.byName ++ b.byName, a.ordered ++ b.ordered)

  private def scopeOf(from: ujson.Value): Scope =
    nodeType(from) match
      case "BASE_TABLE" =>
        val table = from.obj("table_name").str
        val alias = from.obj.get("alias").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(table)
        Scope(Map(alias -> Relation.Named(table)), Seq(Relation.Named(table)))

      case "JOIN" =>
        Scope.merge(scopeOf(from.obj("left")), scopeOf(from.obj("right")))

      case "SUBQUERY" =>
        val inner = from.obj("subquery")
        val node  = inner.objOpt.flatMap(_.get("node")).getOrElse(inner)
        val alias = from.obj.get("alias").flatMap(_.strOpt).filter(_.nonEmpty)
        val rel   = Relation.Derived(node)
        Scope(alias.map(_ -> rel).toMap, Seq(rel))

      case _ => Scope.empty

  /** Every `SELECT` that contributes a column at each output position.
    *
    * A set operation contributes one per branch, and they are matched by position because
    * that is how SQL matches them. So an output column of `injection`, which is three
    * `UNION ALL` branches, has three expressions behind it and the lineage is their union.
    */
  private def projections(node: ujson.Value): Seq[Seq[(ujson.Value, Scope)]] =
    nodeType(node) match
      case "SELECT_NODE" =>
        val scope = node.obj.get("from_table").map(scopeOf).getOrElse(Scope.empty)
        node.obj("select_list").arr.toSeq.map(expr => Seq((expr, scope)))

      case "SET_OPERATION_NODE" =>
        val left  = projections(node.obj("left"))
        val right = projections(node.obj("right"))
        val width = math.max(left.length, right.length)
        (0 until width).map { i =>
          left.lift(i).getOrElse(Seq.empty) ++ right.lift(i).getOrElse(Seq.empty)
        }

      case _ => Seq.empty

  /** The name a derived table's column is visible under. */
  private def outputName(expr: ujson.Value): Option[String] =
    val alias = expr.objOpt.flatMap(_.get("alias")).flatMap(_.strOpt).filter(_.nonEmpty)
    alias.orElse {
      if nodeType(expr) == "COLUMN_REF" then
        expr.obj.get("column_names").flatMap(_.arrOpt).flatMap(_.lastOption).flatMap(_.strOpt)
      else None
    }

  private final case class Resolution(inputs: Set[FieldRef], basis: LineageBasis):
    def ++(other: Resolution): Resolution =
      Resolution(
        inputs ++ other.inputs,
        if basis == LineageBasis.Widened || other.basis == LineageBasis.Widened then
          LineageBasis.Widened
        else LineageBasis.Exact,
      )

  private object Resolution:
    val exact: Resolution   = Resolution(Set.empty, LineageBasis.Exact)
    val widened: Resolution = Resolution(Set.empty, LineageBasis.Widened)

  /** Every reference in an expression, resolved against the scope in force where it stands.
    *
    * The scope is threaded through the walk rather than fixed at the top, because an
    * expression can open one. `bus_load_weight.value` is a `CASE` holding two correlated
    * subqueries over `bus_withdrawal AS w`, and `w` is visible only inside them: resolving
    * the whole expression against the outer `FROM` alone found no `w`, called the field
    * widened, and dropped `bus_withdrawal.value` -- a column the value plainly comes from --
    * out of the graph entirely. A missing edge is the one error a lineage graph cannot
    * survive, since nothing about the output says it is missing.
    *
    * A nested `FROM` is brought into scope but its own references are not collected. A join
    * condition decides which rows meet, not what an output column is computed from; the
    * dataset's `reads` is where that dependency is recorded. Inside a scalar subquery the
    * distinction does not apply -- its `WHERE` chooses which single value comes back, so
    * those references do reach the result and are kept.
    */
  private def resolve(
      expr: ujson.Value,
      scope: Scope,
      columns: Map[String, Seq[(String, String)]],
      depth: Int,
  ): Resolution =
    if depth > MaxDepth then Resolution.widened
    else
      expr match
        case o: ujson.Obj =>
          val from  = o.obj.get("from_table")
          val here  = from.map(ft => Scope.merge(scope, scopeOf(ft))).getOrElse(scope)
          val local = if from.isDefined then depth + 1 else depth
          val self =
            if nodeType(o) == "COLUMN_REF" then
              o.obj
                .get("column_names")
                .flatMap(_.arrOpt)
                .map(parts => resolveRef(parts.toSeq.flatMap(_.strOpt), here, columns, local))
                .getOrElse(Resolution.widened)
            else Resolution.exact
          o.obj.toSeq.foldLeft(self) { case (acc, (key, value)) =>
            if key == "from_table" then acc
            else acc ++ resolve(value, here, columns, local)
          }

        case a: ujson.Arr =>
          a.arr.foldLeft(Resolution.exact)((acc, v) => acc ++ resolve(v, scope, columns, depth))

        case _ => Resolution.exact

  private def resolveRef(
      parts: Seq[String],
      scope: Scope,
      columns: Map[String, Seq[(String, String)]],
      depth: Int,
  ): Resolution =
    if parts.isEmpty then Resolution.widened
    else
      val column = parts.last
      if parts.length >= 2 then
        scope.byName.get(parts(parts.length - 2)) match
          case Some(rel) => through(rel, column, columns, depth)
          // A qualifier naming nothing in scope: a correlated reference to an enclosing
          // query. Those are real inputs, so the field cannot be called exact, but this
          // walk cannot see the relation they belong to.
          case None => Resolution.widened
      else
        // Unqualified. Resolved by looking for the one visible relation that has such a
        // column; more than one and the edges are a superset, which is what `USING` leaves
        // behind after it merges two columns into one name.
        val candidates = scope.ordered.filter(hasColumn(_, column, columns, depth))
        candidates match
          case Seq(one) => through(one, column, columns, depth)
          case Seq()    => Resolution.widened
          case many =>
            many.foldLeft(Resolution.widened) { (acc, rel) =>
              acc ++ through(rel, column, columns, depth)
            }

  private def through(
      relation: Relation,
      column: String,
      columns: Map[String, Seq[(String, String)]],
      depth: Int,
  ): Resolution =
    relation match
      case Relation.Named(name) => Resolution(Set(FieldRef(name, column)), LineageBasis.Exact)
      case Relation.Derived(node) =>
        val inner = projections(node)
        val names = inner.map(_.headOption.flatMap((expr, _) => outputName(expr)))
        names.indexWhere(_.contains(column)) match
          case -1 => Resolution.widened
          case i =>
            inner(i).foldLeft(Resolution.exact) { case (acc, (expr, innerScope)) =>
              acc ++ resolve(expr, innerScope, columns, depth + 1)
            }

  private def hasColumn(
      relation: Relation,
      column: String,
      columns: Map[String, Seq[(String, String)]],
      depth: Int,
  ): Boolean =
    relation match
      case Relation.Named(name) => columns.getOrElse(name, Seq.empty).exists(_._1 == column)
      case Relation.Derived(node) =>
        depth <= MaxDepth &&
          projections(node).exists(_.headOption.flatMap((expr, _) => outputName(expr)).contains(column))

  /** The expression's own SQL, by rebuilding a one-column statement and rendering it back.
    *
    * The branch is kept whole and only its select list replaced, so the `FROM` the
    * expression was written against is still there and the deserialiser has a well-formed
    * statement to render. The sentinel alias is then the only delimiter needed.
    */
  private def expressionText(
      conn: Connection,
      statement: ujson.Value,
      branch: ujson.Value,
      expr: ujson.Value,
  ): Option[String] =
    val oneColumn = ujson.copy(branch)
    val aliased   = ujson.copy(expr)
    aliased.obj("alias") = ujson.Str(Sentinel)
    oneColumn.obj("select_list") = ujson.Arr(aliased)

    // DISTINCT has to come off, and it is the only modifier that does. The text is taken
    // from after `SELECT `, and DuckDB renders a `DISTINCT_MODIFIER` in exactly that gap:
    // `transmission_branch` is `SELECT DISTINCT a.component, a.entity`, and its two fields
    // came back claiming to be computed by `DISTINCT a.component` and `DISTINCT a.entity`.
    // Neither is an expression, and both were being shipped as an OpenLineage
    // `transformationDescription`. Every other modifier -- ORDER BY, LIMIT, OFFSET --
    // renders after the select list and so cannot reach the text.
    oneColumn.obj.get("modifiers").foreach { modifiers =>
      oneColumn.obj("modifiers") = ujson.Arr.from(
        modifiers.arr.filterNot(m => nodeType(m) == "DISTINCT_MODIFIER"))
    }

    val head    = statement.obj("statements").arr.head
    val rebuilt = ujson.Obj.from(head.obj.toSeq.map {
      case ("node", _) => ("node", oneColumn: ujson.Value)
      case kv          => kv
    })
    val whole = ujson.copy(statement)
    whole.obj("statements") = ujson.Arr(rebuilt)

    val ps = conn.prepareStatement("SELECT json_deserialize_sql(CAST(? AS JSON))")
    try
      ps.setString(1, ujson.write(whole))
      val rs = ps.executeQuery()
      rs.next()
      val sql = rs.getString(1)
      Seq(s" AS $Sentinel", s""" AS "$Sentinel"""")
        .find(sql.contains)
        .map(marker => sql.substring("SELECT ".length, sql.indexOf(marker)).trim)
    catch
      // Best-effort: a definition this cannot render still gets its lineage, and the
      // dataset still carries the whole view text. Losing the per-field expression is worth
      // less than losing the run.
      case _: Exception => None
    finally ps.close()

  /** Refuse to pair the catalog's columns with the select list unless they correspond.
    *
    * Everything below matches output column `i` to select entry `i`, which is how SQL
    * matches them -- until a `*` appears. A star is one select entry standing for several
    * columns, so every entry after it is at the wrong index: those columns would be handed
    * another column's inputs and still be labelled [[LineageBasis.Exact]].
    *
    * Not reported as widened, and not skipped. The lineage of column A, presented as the
    * lineage of column B with full confidence, is worse than no lineage at all -- a reader
    * cannot tell it apart from the truth, and `reads` and the "every edge points at
    * something real" test both stay green while it happens, because the edges do point at
    * real columns. So this refuses to describe the view at all, in the same way
    * [[selectBody]] refuses a definition it cannot recognise.
    *
    * No view in this schema selects a star. The guard is for the one that does next.
    */
  private def requireAligned(
      name: String,
      declared: Seq[(String, String)],
      columnsOf: Seq[Seq[(ujson.Value, Scope)]],
  ): Unit =
    val stars = columnsOf.zipWithIndex.collect {
      case (contributors, i) if contributors.exists((expr, _) => nodeType(expr) == "STAR") => i
    }
    if stars.nonEmpty then
      throw new IllegalStateException(
        s"view '$name' selects a star at position(s) ${stars.mkString(", ")}, so its output " +
          "columns cannot be matched to its select list by position; column lineage for it " +
          "would attribute one column's inputs to another")
    if declared.length != columnsOf.length then
      throw new IllegalStateException(
        s"view '$name' declares ${declared.length} columns but its select list has " +
          s"${columnsOf.length}, so they cannot be matched by position")

  private final case class Analysis(fields: Seq[LineageField], reads: Seq[String])

  private def analyse(
      conn: Connection,
      name: String,
      sql: String,
      columns: Map[String, Seq[(String, String)]],
  ): Analysis =
    val body      = selectBody(name, sql)
    val statement = serialize(conn, body)
    val node      = statement.obj("statements").arr.head.obj("node")
    val declared  = columns.getOrElse(name, Seq.empty)
    val columnsOf = projections(node)
    requireAligned(name, declared, columnsOf)

    val fields = declared.zipWithIndex.map { case ((field, dataType), i) =>
      val contributors = columnsOf(i)
      val resolved = contributors.foldLeft(Resolution.exact) { case (acc, (expr, scope)) =>
        acc ++ resolve(expr, scope, columns, 0)
      }
      val texts = contributors.flatMap { (expr, _) =>
        expressionText(conn, statement, branchFor(node, i, expr), expr)
      }
      LineageField(
        name = field,
        dataType = dataType,
        inputs = resolved.inputs.toSeq.sortBy(r => (r.dataset, r.field)),
        expressions = texts.distinct,
        basis = resolved.basis,
      )
    }

    val reads = allTables(node).distinct.sorted
    Analysis(fields, reads)

  /** The `SELECT` that a given contributing expression belongs to.
    *
    * Needed because `expressionText` rebuilds a statement around the expression and the
    * surrounding `FROM` has to be the one the expression was written against -- which, under
    * a set operation, is a branch rather than the top node.
    */
  private def branchFor(node: ujson.Value, position: Int, expr: ujson.Value): ujson.Value =
    nodeType(node) match
      case "SELECT_NODE" => node
      case "SET_OPERATION_NODE" =>
        val left = node.obj("left")
        val here = projections(left).lift(position).exists(_.exists((e, _) => e eq expr))
        if here then branchFor(left, position, expr) else branchFor(node.obj("right"), position, expr)
      case _ => node

  private def allTables(node: ujson.Value): Seq[String] =
    val found = Seq.newBuilder[String]
    def walk(v: ujson.Value): Unit = v match
      case o: ujson.Obj =>
        if nodeType(o) == "BASE_TABLE" then o.obj.get("table_name").flatMap(_.strOpt).foreach(found += _)
        o.obj.values.foreach(walk)
      case a: ujson.Arr => a.arr.foreach(walk)
      case _            => ()
    walk(node)
    found.result()
