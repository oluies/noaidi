# NordPSA fixture reproduction

Measured on 2026-10-23. Every fixture in this directory was regenerated from the pinned
reference environment and compared against what is committed. **7 of 7 fixture files and all 194 exported network files
came back unchanged**, in each of 4 full sweeps.

The JSON files were compared key by key with exact equality rather than with a tolerance,
and the exports by sha256. Over the 4 sweeps that is 28 fixture
comparisons with no difference in any of them.

Reference environment: PyPSA 1.3.0, pandas 3.0.6, numpy 2.4.6 and
Python 3.11.15 — the versions the committed files record, so a regeneration rewrites
no version stamp either.

## Did anything move

| Family | Cases | Networks | JSON | Export files | Verdict |
| --- | --: | --: | --: | --: | --- |
| `hydro` | 9 | 5 | 3.9 kB | 55 | identical |
| `soc` | 3 | 1 | 0.9 kB | 12 | identical |
| `terminal` | 6 | 1 | 2.4 kB | 11 | identical |
| `ladder` | 4 | 1 | 5.6 kB | 12 | identical |
| `stability` | 24 | 9 | 59.9 kB | 76 | identical |
| `sclopf` | 10 | 1 | 265.2 kB | 13 | identical |
| `nordic` | 2 | 1 | 1.9 kB | 15 | identical |
| **total** | **58** | **19** | | **194** | **identical** |

## What it costs in time

Wall clock in seconds for each generator, over four sequential sweeps on one laptop. The
bar is the median; the spread is real and the reason four samples are reported rather than
one. This is a cost, not a benchmark.

| Generator | Median | Min | Max | |
| --- | --: | --: | --: | --- |
| `stability` | 28.3 s | 27.5 s | 29.6 s | `██████████████████████` |
| `sclopf` | 27.5 s | 23.2 s | 32.0 s | `█████████████████████` |
| `nordic` | 9.0 s | 7.7 s | 14.4 s | `███████` |
| `hydro` | 7.5 s | 5.5 s | 19.1 s | `██████` |
| `terminal` | 4.1 s | 4.0 s | 5.9 s | `███` |
| `ladder` | 3.4 s | 3.3 s | 4.3 s | `███` |
| `soc` | 3.3 s | 2.9 s | 5.6 s | `███` |
| **one full sweep** | **83 s** | | | |

CI wall clock over the last 44 completed runs of `ci.yml`, of which 35 succeeded:
median 9.3 min, fastest 5.3, slowest 35.7. The spread
is the runner pool rather than the code — the same job swings by a factor of three between
runs.

Jobs of one pull-request run, which run in parallel:

| Job | Duration | |
| --- | --: | --- |
| Kernel split and SIMD ratio (x86_64) | 31.8 min | `██████████████████████` |
| Test (JDK 25) | 8.6 min | `██████` |
| Test (JDK 21) | 6.3 min | `████` |
| Solver validation report (JDK 25) | 1.2 min | `█` |
| Solver validation report (JDK 21) | 1.1 min | `█` |
| Source checks the compiler does not make | 11 s | `█` |

## What the fixtures measure

The reference answers PyPSA reaches, which the Scala port is then held to. Each family
exists because some term of the formulation was invisible without it.

### A rising bid spreads the output

Share of hours the reservoir sits at a bound, and the standard deviation of its dispatch.
A flat bid goes all-or-nothing as the price crosses it; a wider ladder spreads the output.

| Case | Tiers | Width | At ceiling | At floor | Dispatch sd | Objective |
| --- | --: | --: | --: | --: | --: | --: |
| `flat` | — | — | 42.9% | 55.4% | 450 MW | 4,508,956 |
| `k3-w36` | 3 | 36 | 25.0% | 37.5% | 367 MW | 4,398,241 |
| `k3-w72` | 3 | 72 | 0.0% | 10.7% | 202 MW | 3,999,943 |
| `k5-w36` | 5 | 36 | 21.4% | 33.9% | 361 MW | 4,388,612 |

### Holding water back has a price

Where the week ends against what it cost. A concave terminal value holds a segment where a
linear one holds nothing, so `none` and `linear-25` reach the same answer — as do
`linear-45` and `flat-two-45`.

| Case | Lambda | Objective | Ends at |
| --- | --: | --: | --: |
| `none` | — | 642,600 | 0% |
| `linear-25` | 25 EUR/MWh | 642,600 | 0% |
| `concave-25` | 25 EUR/MWh | 554,600 | 20% |
| `linear-45` | 45 EUR/MWh | 424,200 | 91% |
| `concave-45` | 45 EUR/MWh | -161,400 | 80% |
| `flat-two-45` | 45 EUR/MWh | 424,200 | 91% |

### What security costs, family by family

Plain dispatch against the same problem solved by PyPSA's own
`optimize_security_constrained`. Each family changes the secure answer, and the
contingency limits still bind under every one of them.

| Case | Plain | Secure | Premium |
| --- | --: | --: | --: |
| `none` | 336,000 | 448,033 | +33.3% |
| `hydro` | 356,578 | 453,178 | +27.1% |
| `terminal-linear` | 336,000 | 373,344 | +11.1% |
| `terminal-concave` | 336,000 | 376,017 | +11.9% |
| `ladder` | 70,013 | 181,578 | +159.3% |
| `stability-scr` | 379,982 | 504,471 | +32.8% |
| `stability-ek` | 542,424 | 579,278 | +6.8% |
| `hydro-and-stability` | 406,357 | 506,256 | +24.6% |
| `all-three` | 87,778 | 119,978 | +36.7% |
| `all-four` | 137,557 | 170,857 | +24.2% |

### The Nordic trajectory is a tie-break, and the fixture says so

Simplex against interior-point on the same problem. The objectives agree to eleven digits
while the trajectories do not, so the port cites this measurement instead of asserting a
solver's choice.

| Solver | Hours pinned to a bound | Objective |
| --- | --: | --: |
| simplex | 37.2% | -2,460,299,249 |
| interior | 4.9% | -2,460,299,249 |

### Reservoir operating limits, case by case

NordPSA varies inflow and cheap capacity per scenario to put each limit on the binding
side — under cyclic state-of-charge a floor below what the water already forces changes
nothing and would prove nothing. `infeasible-floor` is deliberate: a daily floor of 0.45 on
the scarcest network has no solution, and the fixture records `status: warning` with
`condition: infeasible` so the port is held to refusing it too.

| Case | Network | Objective | Hourly floor | Daily floor | Weekly ceiling | Spill | Status |
| --- | --- | --: | --: | --: | --: | --: | --- |
| `floors-ref` | inflow450-cheap600 | 297,360 | 0 MW | 30.0% | 30.0% | 75,600 MWh | ok |
| `floors` | inflow450-cheap600 | 302,400 | 100 MW | 32.5% | 32.5% | 63,000 MWh | ok |
| `daily-45` | inflow450-cheap600 | 327,600 | 100 MW | 45.0% | 45.0% | 0 MWh | ok |
| `weekly-cap-ref` | inflow900-cheap0 | 1,908,900 | 400 MW | 77.5% | 77.5% | 63,000 MWh | ok |
| `weekly-cap-40` | inflow900-cheap0 | 9,298,800 | 100 MW | 23.1% | 40.0% | 252,000 MWh | ok |
| `zone-override-ref` | inflow900-cheap600 | 325,080 | 100 MW | 32.5% | 32.5% | 289,800 MWh | ok |
| `zone-override-25` | inflow900-cheap600 | 1,803,060 | 100 MW | 20.0% | 25.0% | 327,600 MWh | ok |
| `bypass-hinge` | inflow600-cheap0 | 5,599,440 | 100 MW | 46.4% | 59.8% | 6,574 MWh | ok |
| `infeasible-floor` | inflow300-cheap600 | — | — | — | — | — | warning |

## Which NordPSA this was measured against

NordPSA is public — [beow/NordPSA](https://github.com/beow/NordPSA), MIT. Every fixture
here records the revision it was generated from, under `versions.nordpsa`:
`beccc3f` committed 2026-09-26, with that clone's tracked files
clean. Before this was recorded each file named the upstream
file it came from, such as `tests/unit/test_hydro_ops.py`, and not the commit — so a
difference after a regeneration could not be told apart from drift in the port, which is
the one thing this directory exists to measure.

### Against upstream `main`

Checked on 2026-10-27 with the reference environment held fixed, so the only variable was
NordPSA's own code. Upstream `main` was then `34d0b77`, 24 commits and 43 files ahead of the
pinned revision, and the diff reaches this directory: `constraints/hydro_ops.py`,
`bid_ladder.py`, `stability.py`, `config/zones.yaml` and `config/worlds/today.yaml`. All
seven regenerated against it:

| Family | Against upstream `main` |
| --- | --- |
| `hydro`, `soc`, `terminal`, `sclopf`, `nordic` | identical |
| `ladder` | two refusal *messages* differ |
| `stability` | one refusal changed behaviour |
| the 194 network exports | all byte-identical |

**No solved number changed anywhere.** The `ladder` differences are text: upstream added an
optional `width_by_zone` and its validation message gained `/ {}`. With that argument left
out the new code is arithmetically the old one.

The `stability` difference is real. `refused/share-zero` went from `true` to `false`:
upstream `scr_joint` now drops a joint share of zero instead of rejecting it. This port
rejects it, and `StabilitySuite` asserts that upstream rejects it too, so following upstream
there is a change to the port and its suite rather than a fixture bump.

That change is wider than the one fixture entry, which is why it was not made. The new
filter runs *before* the host and member checks, so a zero share does not merely drop
itself — it drops the whole entry, and with it every other check that entry would have
failed. Measured against upstream `main` with `zones = [A, B]` and `B` exempt:

| `scr_joint` | Upstream `main` | This port |
| --- | --- | --- |
| `{A: {B: 0.0}}` | accepted, returns `{}` | refused |
| `{ZZ: {B: 0.0}}`, `ZZ` not a zone | accepted, returns `{}` | refused |
| `{B: {B: 0.0}}`, host itself exempt | accepted, returns `{}` | refused |
| `{A: {B: 0.0}}`, `B` not exempt | accepted, returns `{}` | refused |
| `{A: {B: 0.0, ZZ: 0.3}}` | refused — `ZZ` still reaches the check | refused |
| `{A: {B: -0.3}}` | refused | refused |

So a host zone that does not exist, or one that could not host a share at all, stops being
an error as soon as the share beside it is zero. The three middle rows are configuration
typos the pinned revision catches and `main` does not. Keeping the port where it is keeps
those three refusals, at the price of disagreeing with upstream on the first row, where the
disagreement costs a reader nothing: a share of zero asks for no constraint either way.

The pinned revision is the one these answers hold for.

One other thing a bump would need: against upstream `main`, `generate_nordic.py` also reads
`data/raw/inflow_nve_<zone>_<year>.parquet`, a dependency added since the pinned revision.
At the pinned revision `data/processed/` alone is enough.

## Reproducing this

The generators and what each one needs are in [`README.md`](README.md) under
Regenerating. In short, from this directory, with a NordPSA clone beside it:

```bash
for g in generate generate_soc generate_terminal generate_ladder \
         generate_stability generate_sclopf; do
  .venv/bin/python $g.py <output-dir>
done
NORDPSA_ROOT=<a clone with data/processed built> \
  .venv/bin/python generate_nordic.py <output-dir>
```

Then compare the seven JSON files in `<output-dir>` against the ones here. Every number
above is read from those committed files or from the GitHub Actions API; nothing here is
typed by hand, and the files in this directory stay the authority.
