# The same model in PyPSA and in noaidi

noaidi is validated against PyPSA, so for every constraint family there are two
implementations of the same mathematics: NordPSA's `extra_functionality` callback in Python
over [linopy](https://linopy.readthedocs.io), and noaidi's in Scala over `LpBuilder`. This
file puts them beside each other.

It is not an argument that one is better. The two make opposite trades and the differences
are visible in five lines of either.

## The worked example

`reference/nordpsa/networks/nordic-today` is the real thing: the network `nordpsa today`
builds, exported as PyPSA CSVs and committed.

| | |
| --- | --- |
| snapshots | 365, daily, 2023 |
| buses | 6 — SE-N, SE-S, NO-N, NO-S, DK, FI |
| generators | 117 |
| storage units | 8, of which 5 are hydro reservoirs |
| links | 8 cross-border |
| on disk | 756 KB |

`reference/nordpsa/nordic.json` holds what PyPSA answered on it, and `BidLadderSuite`
asserts that noaidi reaches the same number: **−2,460,299,248.77** with no bid ladder and
**−3,758,050,248.35** with one, agreeing to ten significant figures across 57,305 columns.

That pairing is the whole method. A PyPSA callback adds rows to the linopy model at solve
time and stores **nothing** on the network, so an export carries the buses and the inflow but
not one of the constraints. Read the file back, solve it, and you get a different and
strictly easier problem — which looks exactly like a port under-counting something. So the
network and the answer travel separately: `networks/` holds what the export can carry, and
the JSON holds what the callback did on top of it.

## Building the model

PyPSA is a mutable object you add to:

```python
n = pypsa.Network()
n.set_snapshots(sn)
n.add("Bus", "SE-S", carrier="AC")
n.add("StorageUnit", "SE-S hydro", bus="SE-S", carrier="hydro",
      p_nom=2502.9, max_hours=1398.5, marginal_cost=0.6,
      inflow=pd.Series(..., index=sn), cyclic_state_of_charge=True)
```

noaidi is an immutable value you construct:

```scala
Network(
  name = "nordic-today",
  schema = schema,
  snapshots = labels,
  tables = ListMap(
    "Bus" -> ComponentTable(schema("Bus"), IndexedSeq("SE-S"),
      ListMap("carrier" -> Column.Strings(IArray("AC"))), ListMap.empty),
    "StorageUnit" -> ComponentTable(schema("StorageUnit"), IndexedSeq("SE-S hydro"),
      ListMap("p_nom" -> Column.Floats(IArray(2502.9)), /* … */),
      ListMap("inflow" -> TimeSeries(IndexedSeq("SE-S hydro"), /* … */))),
  ),
)
```

The Scala is wordier and that is the trade, not an accident. `Column` is a sum type, so a
float column cannot hold a string, and the whole value is immutable, so nothing can add a
generator halfway through a build.

What it does **not** do is validate attribute names at construction. `ComponentTable` is a
plain case class: it checks neither that `static`'s keys are in its `ComponentSpec` nor that
its columns are as long as `ids`. An attribute PyPSA does not define can be put in the map
quite happily — it is simply never read. The check that does exist is on the read side, and
it is `ComponentSpec.require` throwing `NoSuchElementException` for an attribute the spec
does not have. A *misspelled* read of a defined attribute is worse than either: `float` and
`string` fall back to the schema default, so it returns a plausible number.

So the honest comparison is narrower than "the types catch it". It is that values cannot be
the wrong type and nothing mutates — which is real, and is not the same as a validated
network.

In normal use nobody writes either: both read the same CSV directory.

## A constraint, side by side

The bid ladder is the clearest pair — it is short on both sides and it adds both columns and
a row. It splits a reservoir's output into `K` tiers with rising bids.

**PyPSA / linopy** (`nordpsa/constraints/bid_ladder.py`), array-oriented:

```python
cap = np.array([[n.storage_units.at[su, "p_nom"] / K] * K for su in units])
upper = xr.DataArray(np.broadcast_to(cap[:, :, None], (len(names), K, len(sns))),
                     coords=[names, segs, sns])

d = m.add_variables(lower=0.0, upper=upper, coords=[names, segs, sns],
                    name="hydro_bid_tier")

m.add_constraints(d.sum("bid_tier") - p_dis.sel(name=names, snapshot=sns) == 0.0,
                  name="hydro_bid_tier_def")

coef = xr.DataArray(np.broadcast_to(offsets[None, :, None] * w[None, None, :], ...),
                    coords=[names, segs, sns])
m.objective = m.objective + (coef * d).sum()
```

**noaidi** (`BidLadder.scala`), index-oriented:

```scala
units.foreach { id =>
  val cap = storage.float("p_nom", id) / config.tiers
  snapshots.foreach { t =>
    if Periods.activeAt(network, storage, id, t) then
      columns.get((Storage.Dispatch, id, t)).foreach { dispatch =>
        val weight = Periods.objectiveWeight(network, t)
        val tiers = ladder.zipWithIndex.map { (offset, k) =>
          declare(Tier, tierOf(id, k), t, 0.0, cap, offset * weight)
        }
        builder.equalityConstraint(tiers.map(_ -> 1.0) :+ (dispatch -> -1.0), 0.0)
      }
  }
}
```

Three differences, in order of how much they matter.

**linopy has named dimensions; noaidi has explicit indices.** `d.sum("bid_tier")` sums over a
named axis and linopy aligns the rest. The Scala writes the loop and names the column by
`(component, entity, snapshot)`. linopy's version is shorter and cannot transpose two axes by
accident; the Scala's is longer and never allocates a dense `(units × tiers × snapshots)`
array for what is a sparse set of rows.

**The objective is accumulated differently.** linopy adds an expression to `m.objective`;
noaidi puts the coefficient on the column at the moment it is declared, because
`LpBuilder.objectiveCoefficient` *sets* rather than adds and the dispatch column beside it
already carries `marginal_cost`. The arithmetic is identical. The hazard is not: in noaidi a
second write to the same column would silently replace the first.

**Row order is load-bearing in noaidi and not in linopy.** `LpBuilder.build()` sorts
equalities ahead of inequalities, and `Sclopf.build` rebuilds the model row by row and
requires every original row to keep its index. So a family that emits an equality must do so
before the first inequality. That constraint does not exist in linopy, where the model is a
bag of named constraints. It is the price of a formulation that can be rebuilt and
re-indexed — and it has been got wrong three times in this repository, which is why
`SclopfFamiliesSuite` exists.

## What happens when the configuration is wrong

This is the sharpest difference and it is a choice rather than a language property.

NordPSA, asked for a weekly ceiling on a zone that does not exist, keeps the global value and
carries on. The run finishes and reports a number as though the limit had applied.

noaidi refuses:

```scala
throw new Lopf.UnsupportedNetwork(
  s"network '${network.name}' was given zone limits that cannot take effect: " +
    problems.mkString("; ") + ". Its hydro StorageUnits are " + units.mkString(", ") + ".")
```

Every family does this: a floor against a bus that is not an electrical zone, a λ against a
reservoir that is not there, a profile override for a unit with no λ, a NaN anywhere a
positive number was meant. The argument is that a silently inert setting is worse than a
stopped build, because the output of the first is a plausible number.

The cost is real and worth stating: a config naming a zone that was legitimately dropped now
stops instead of proceeding, and that is a divergence from upstream rather than a bug fixed.

## Reading the answer back

```python
n.storage_units_t.p_dispatch["SE-S hydro"]      # a DataFrame column
n.buses_t.marginal_price["SE-S"]
```

```scala
result.discharging("SE-S hydro", t)             // one value
result.marginalPrice("SE-S", t)
```

One trap the port had to get right: PyPSA's `storage_units_t.p` is the **net** injection
`p_dispatch − p_store`, while `p_dispatch` is the discharge half alone. `LopfResult.dispatch`
follows `p`, and `discharging` follows `p_dispatch`. Comparing the wrong pair reads a
reservoir absorbing free wind as one that is producing — which cost an afternoon in
`StabilitySuite` before the two were separated.

## What each is better at

**PyPSA** is better at being changed. A new constraint is a function; the data model is
pandas, so anything you can express in pandas you can express about a network; and the
ecosystem around it — plotting, statistics, the atlite/pypsa-eur toolchain — does not exist
on the other side and would be years of work.

**noaidi** is better at being depended on — though narrowly, and the paragraph above says
where the narrowness is. Column values cannot be the wrong type, the model is immutable, and
`Network` checks that its snapshot-period axis matches its snapshot axis; beyond that a
malformed network is perfectly constructible. The solver is in the same process with no file
round-trip; the
formulation is a value that can be rebuilt, re-indexed and handed to a different backend; and
it runs where a JVM does not — see `modules/demo-js`, which solves this same model in a
browser.

The honest summary is that noaidi is a port, not a replacement, and the directory this file
sits beside exists to keep it one: every family is checked against the Python it came from,
on fixtures generated by running that Python.
