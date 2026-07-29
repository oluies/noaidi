# PyPSA reference

A pinned PyPSA install and the golden files generated from it. Everything in the
Scala port from L1 upwards is checked against these.

The point is not that PyPSA is authoritative about power systems. It is that the
port has to agree with *this implementation*, including wherever it is
idiosyncratic, or the two are not interchangeable.

## Recreating

```bash
uv venv reference/.venv --python 3.13
uv pip install --python reference/.venv/bin/python "pypsa==1.2.4" highspy
./reference/.venv/bin/python reference/generate_goldens.py
```

The virtualenv is not committed; the goldens are, so the Scala tests can run
without Python present. Regenerate and commit the diff when the pinned version
is bumped — a change in the goldens *is* the compatibility break, and reviewing
it is the point.

## What is here

| Path | What it is |
| --- | --- |
| `goldens/schema.json` | All 16 component types, 422 attributes: type, unit, default, and whether it may vary by snapshot. The typed store is generated from this rather than from the documentation, which describes a version rather than the one pinned. |
| `goldens/networks/<name>/` | PyPSA's own CSV directory export. This is the format L1 must round-trip, not a convenience dump. |
| `goldens/results/<name>.json` | Linear power flow and optimisation outputs that L2 must reproduce within documented tolerances. |
| `goldens/manifest.json` | Versions used, and a per-network summary of what carries time-varying data. |

## Two things the schema already settles

**Time-varying data is stored per entity, not per attribute.** In
`ac-dc-meshed`, `p_max_pu` varies for three of six generators; the other three
fall back to their static value. The store has to model "static default plus
per-entity overrides", not a dense snapshot-by-entity matrix for every
attribute — reproducing that is the difference between matching PyPSA's files
and merely being equivalent to them.

**The CSV export omits columns left at their default.** `buses.csv` in
`ac-dc-meshed` carries six columns out of Bus's nineteen attributes. A writer
that emits all of them produces a valid network and a different file, so
round-trip fidelity means knowing the defaults, which is what `schema.json` is
for.

## Versions

Pinned at PyPSA 1.2.4 (pandas 3.0.5, Python 3.13). Note the migration brief
cites PyPSA 0.30.2 documentation; 1.x reorganised the component API, so the
schema here is the authority for what the port targets.
