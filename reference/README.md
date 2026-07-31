# PyPSA reference

A pinned PyPSA install and the golden files generated from it. Everything in the
Scala port from L1 upwards is checked against these.

The point is not that PyPSA is authoritative about power systems. It is that the
port has to agree with *this implementation*, including wherever it is
idiosyncratic, or the two are not interchangeable.

`tables` is PyPSA's optional HDF5 dependency. Without it `export_to_hdf5` raises,
so the `.h5` goldens cannot be written — netCDF needs nothing extra, because
xarray's netCDF-4 backend is already a PyPSA dependency.

## Recreating

```bash
uv venv reference/.venv --python 3.13
uv pip install --python reference/.venv/bin/python "pypsa==1.2.4" highspy tables
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
| `goldens/standard_types/` | PyPSA's line and transformer type library. Not part of any network export — see below. |

## What a regeneration diff means

Two kinds of churn are expected and say nothing:

**The HDF5 containers rewrite themselves.** Exporting the same network twice
gives byte-identical files, but a file written today differs from the committed
one — 47409, 47483 and 47497 bytes for the same three-bus network across runs —
while every variable, dimension and attribute compares equal. It is container
layout, not content. Re-exported binaries whose content is unchanged are worth
reverting so the diff stays reviewable.

**`scigrid-de`'s optimal dispatch is not unique, and PyPSA does not pick the
same one twice.** Six runs in fresh processes land on exactly two answers,
agreeing on the objective to 2e-8 relative and differing by up to 750 MW at
individual generators. The cause is upstream of the solver: `find_cycles`
returns 364 cycles either way but a basis of either 2372 or 2469 nonzeros, and
the 97-nonzero difference is exactly what shows up per snapshot in the LP handed
to HiGHS. `PYTHONHASHSEED` does not pin it. Both bases span the same cycle space
and both optima cost the same, so this is a property of the network rather than
a defect — but it does mean the `optimize` dispatch frames on that network
cannot gate an implementation, and the golden says so in a `dispatch_note`
beside them.

## The standard type library

`goldens/standard_types/` holds PyPSA's 59 line types and 14 transformer types,
which appear in **no** network export: `export_to_csv_folder` drops exactly the
rows a fresh `Network()` was born with, on the correct grounds that they are
library data. PyPSA's reader repopulates them from the installed package, and a
reader outside Python cannot.

This matters because `scigrid-de` is unreadable without them — all 852 of its
lines carry `x = 0` and a type name, and the impedance comes from
`x_per_length x length / num_parallel`. The port ships its own copy as a
resource, and a test holds that copy to this one, so a version bump that changes
an impedance fails a comparison instead of silently changing every answer on a
typed network.

## Two things the schema already settles

**Time-varying data is stored per entity, not per attribute.** In
`ac-dc-meshed`, `p_max_pu` varies for three of six generators; the other three
fall back to their static value. The store has to model "static default plus
per-entity overrides", not a dense snapshot-by-entity matrix for every
attribute — reproducing that is the difference between matching PyPSA's files
and merely being equivalent to them.

**The CSV export omits columns that were never set** — not columns whose values
equal the default. `buses.csv` in `ac-dc-meshed` carries six columns out of Bus's
nineteen attributes, and two of those six (`x`, `y`) hold exactly the default
value. That is provenance, not content, and it is not recoverable from the file,
so a reader must preserve the column set it was given rather than recompute it.

**Networks carry columns the schema does not describe.** `buses.csv` has a
`country` column and `carriers.csv` has `marginal_cost`, `efficiency` and
`capital_cost` — none of which appear under `Bus` or `Carrier` in
`schema.json`. PyPSA's component model is deliberately extensible, so these are
normal input rather than corruption, and L1 must preserve unknown columns
verbatim. `schema.json` is the authority for defaults and variability, not for
which columns may appear.

**`snapshots.csv` is not just an index.** Its header is
`,snapshot,objective,stores,generators`: pandas' positional index, the label,
then three weightings that scale each snapshot's contribution to the objective
and to storage accounting. Reading the first field as the label yields the row
number, which looks plausible and is wrong.

Per-file exported headers are recorded in `manifest.json` as
`exported_columns`, which is what a reader and writer can be held to — the
in-memory column set is larger (Bus: 14 loaded against 6 exported) and would be
an unmeetable target.

## Versions

Pinned at PyPSA 1.2.4 (pandas 3.0.5, Python 3.13). Note the migration brief
cites PyPSA 0.30.2 documentation; 1.x reorganised the component API, so the
schema here is the authority for what the port targets.
