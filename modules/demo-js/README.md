# Prima in a browser

`prima-core`, `network-model`, `network-pf` and `network-lopf`, compiled to JavaScript with
Scala.js. The page builds a real `Network`, runs it through the real `Lopf.build`, and solves
it with Prima — no server in the loop.

![the demo](public/screenshot.png)

Live: <https://oluies.github.io/noaidi/>

## What was measured

The question was not "does it compile" but "is it fast enough to put behind a slider". Two
findings, and the second is the one that matters.

All browser numbers below are from **`fullLinkJS`**, measured on the published page in
Safari, taking the fastest of three runs. That qualification earns its place: the same sweep
on `fastLinkJS` is 2–3× slower, so an unoptimised build will mislead you about this.

**Scala.js costs about 4× the JVM per unit of work.** Per column per iteration:

| | |
| --- | --- |
| JVM, `nordic-today`, 57,305 columns | **4.4 ns** |
| Safari, `fullLinkJS`, 240–1,680 columns | **16–22 ns** |
| Safari, `fastLinkJS`, same problems | 43–61 ns |

**But the iteration count is what actually limits the size**, and it is platform-independent.
Holding everything else fixed and lengthening the horizon:

| horizon | columns | rows | iterations | time | ns/col/iter |
| --- | --- | --- | --- | --- | --- |
| 24 h | 240 | 120 | 4,288 | 23 ms | 22.3 |
| 48 h | 480 | 240 | 16,448 | 163 ms | 20.6 |
| 72 h | 720 | 360 | 50,688 | 730 ms | 20.0 |
| 120 h | 1,200 | 600 | 53,760 | 1.35 s | 20.9 |
| 168 h | 1,680 | 840 | 47,680 | 1.28 s | 16.0 |

Seven times the columns from 24 h to 168 h, eleven times the iterations, and **fifty-six
times the wall clock**. The per-iteration cost is flat — it is the iteration count that
moves, and it does not move monotonically: 168 h is *faster* than 120 h because it happened
to need 6,000 fewer iterations on a problem 40% larger. Time tracks iterations × columns, and
of those two only one is under your control.

The reservoir's state of charge chains every snapshot to the next, and a first-order method
is sensitive to the conditioning that produces — `nordic-today` needs 233,344 iterations on
the JVM for the same reason. So the ceiling on an in-browser model is not the language: a
couple of days of a small network is comfortable, a year of a real one is not, and moving to
the JVM buys a factor of four, not a factor of a thousand. If an in-browser model has to grow,
the thing to attack is the conditioning of the storage chain, not the platform.

The iteration counts are identical between `fastLinkJS` and `fullLinkJS`, which is the
expected result and worth stating: the optimiser changes how fast each iteration runs, not
what the solver does.

## What the sliders do

The model is three buses in a triangle — so every line's removal leaves it connected, the
same reason `sclopf-families` is one — with a reservoir at A, wind and a peaker at C, gas at
B. Three of the sliders switch on the constraint families this repository ported from
NordPSA, and they are the same code the JVM runs:

- **`Stability`** — `s_h·u_h + s_g·u_g ≥ SCR·p_wind` over a linearised commitment
  (`p ≤ u`, `p ≥ m_min·u`), with NordPSA's own `s_coef` values
- **`HydroOps`** — an hourly floor under the reservoir's output
- **`BidLadder`** — the reservoir's flat bid split into three rising tiers

Raising SCR brings synchronous plant online, makes it produce, and curtails the wind when
that is not enough. That is the mechanism the whole `stability` port exists to express.

## What had to change in the ported modules

One file, and not the one expected. `java.time` — the obvious candidate, confined to
`HydroOps.scala` — needed only the `scala-java-time` dependency and no source change at all.

What did need a change was `StandardTypes`, which loads PyPSA's line-type library from a
**classpath resource**, and a browser has no classpath. The read is now in
`StandardTypeLibrary.scala`, 25 lines of its own, which `network-model-js` excludes and
replaces with a generated file embedding the same CSVs from `src/main/resources`. Generated
rather than committed, so there is still exactly one copy of the data and a line type added
to the library reaches both platforms or neither.

Otherwise: `VectorKernels.scala` is excluded (`jdk.incubator.vector`, already behind the
`Kernels` trait with `ScalaKernels` as the fallback, and unreachable anyway). `Unsafe` is two
`asInstanceOf` casts. No threads, no `Future`. `Schema.fromFile`, `CsvReader.read` and
`MpsReader.fromFile` link out as unreachable — a browser fetches over HTTP, which is what the
page does, and `Schema.fromJson` then parses the schema with the same code the JVM uses.

## Which modules are ported

| ported | not ported, and why |
| --- | --- |
| `prima-core` | `network-io` — HDF5 through `jhdf`, and no filesystem to point it at |
| `prima-model` | `prima-ojalgo` — wraps a Java solver |
| `prima-mps` | `prima-ortools` — wraps a native one |
| `network-model` | `prima-cyfra` — a Vulkan backend |
| `network-pf` | `prima-netlib`, `prima-validation` — file-based benchmark suites |
| `network-lopf` | `prima-zio` — could be, and is left out because nothing here is effectful |

Not a `crossProject`: that moves every source file into a `.jvm`/`.js` layout and touches
every module depending on these six. The JS projects point `unmanagedSourceDirectories` at
the existing sources instead, so there is one copy of the code and the JVM build is
untouched.

## Sizes

| | |
| --- | --- |
| `fastLinkJS` | 1.7 MB |
| `fullLinkJS` | 1.07 MB |
| `fullLinkJS`, gzipped | **171 KB** |

`fullLinkJS` is worth the extra link time for more than the download: it is also 2–3× faster
at run time, which is why every number in this file comes from it.

## Running it locally

```bash
sbt demoJs/fullLinkJS
cp target/out/sjs1/scala-3.9.0/demo-js/demo-js-opt/main.js modules/demo-js/public/
cp reference/goldens/schema.json modules/demo-js/public/
cd modules/demo-js/public && python3 -m http.server 8731
```

`main.js` and `schema.json` are build outputs and are not committed; `.github/workflows/pages.yml`
produces them the same way for the published page.

## Would a GPU help?

Not in a browser, and the reason is the algorithm rather than WebGPU. `Kernels` is already
the abstraction a GPU backend plugs into — `prima-cyfra` is one — but PDHG's adaptive
step-size rule performs three reductions **per iteration** (`squaredNorm(dx)`,
`squaredNorm(dy)`, `dot(dy, kdx)`) and branches on the results inside the iteration. On
Vulkan you can wait on a fence cheaply; in JavaScript you cannot block at all, so each of
those becomes an awaited buffer mapping. At 48,000 iterations that is 144,000 round trips,
which is slower than doing the arithmetic on the CPU. The KKT check is every 64 iterations
and is not the problem; the step-size rule is.

The sizes here make it moot anyway — 1,680 columns is far below where a GPU pays for its
dispatch overhead. It would become a real question only at `nordic-today`'s 57,305 columns,
and by then the 233,344 iterations are the thing to fix first.
