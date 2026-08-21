package org.noaidi.network

/** A file this port reads correctly and does not model.
  *
  * Distinct from either reader's `MalformedNetwork`, and the distinction is the
  * one `reference/goldens/binary/malformed` draws: those fixtures are files that
  * are *broken* — a column shorter than its index, a time unit CF does not
  * define, a dataset that is not a PyPSA network at all. A piecewise cost curve
  * is none of those. It is exactly what PyPSA meant to write, and the reason it
  * is refused is that [[org.noaidi.lopf.Lopf]]'s objective is linear in one
  * coefficient per entity, which is a statement about this port rather than
  * about the file.
  *
  * Shared by both readers rather than declared twice, because the feature has two
  * spellings and one meaning: CSV writes `<list>-<attr>-pw.csv` and netCDF writes
  * a `<list>_pw_<attr>` variable with its own breakpoint dimension. A caller that
  * wants to tell "your export is damaged" from "this port does not model that"
  * can do it on the type, and `goldens/unsupported/` holds a real PyPSA export of
  * each so both paths are tested against a file rather than against a guess.
  *
  * Not a subclass of either `MalformedNetwork`. Making it one would keep every
  * existing `catch` working, which is precisely the property that would let the
  * two go on being confused.
  */
final class UnsupportedNetworkFile(message: String) extends RuntimeException(message)
