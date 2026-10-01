package org.noaidi.network

/** The shipped standard-type CSVs, as text.
  *
  * A file of its own, and four lines of it, because this is the one thing in `network-model`
  * that cannot be written once for both platforms: it reads a '''classpath resource''', and
  * a browser has no classpath. Everything else in the module is either pure or reachable
  * only from a path-taking entry point the Scala.js linker drops as dead.
  *
  * Splitting it out rather than making [[StandardTypes]] itself platform-specific keeps the
  * fork to the smallest thing that has to fork: 25 lines against 400, and the parsing,
  * merging and expansion stay in one copy. `modules/network-model-js` excludes this file and
  * generates its own, embedding the same CSVs at build time — so there is still exactly one
  * copy of the data, in `src/main/resources`, and no way for the two platforms to ship
  * different line types.
  */
private[network] object StandardTypeLibrary:

  /** The text of one shipped library, by the component's PyPSA list name. */
  def text(listName: String): String =
    val path = s"/org/noaidi/network/standard_types/$listName.csv"
    Option(getClass.getResourceAsStream(path))
      .map(stream => scala.util.Using.resource(stream)(s => new String(s.readAllBytes, "UTF-8")))
      .getOrElse(throw new IllegalStateException(s"the standard type library $path is missing"))
