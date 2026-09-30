# Prima in a browser

`prima-core` and `network-model`, compiled to JavaScript with Scala.js and solving a linear
program live in the page.

![the demo](public/screenshot.png)

## What it answers

Not "does it compile" — that was never the doubt — but **is it fast enough to put behind a
slider**. Measured in Safari on this machine: **25–63 ms** for an 840-column, 1008-row
problem at `epsAbs = epsRel = 1e-8`. That is interactive.

The model is the formulation this repository's last constraint family ported, at one bus
instead of six:

```
balance        pw(t) + ph(t) + pg(t)                 = load(t)
online         p(t) − u(t)                          <= 0
min stable     p(t) − m_min · u(t)                  >= 0
grid strength  s_h·u_h(t) + s_g·u_g(t) − SCR·pw(t)  >= 0
```

Every row is one `Stability.scala` emits, and `s_h`/`s_g` are NordPSA's own `s_coef` values.
Moving the SCR slider does what it does in the real model: cheap synchronous plant is brought
online and made to produce, and where that is not enough the wind is curtailed. From 2 to 6 on
the default case, cost goes 898 k€ → 1212 k€ and curtailment 30.7 → 56.9 GWh.

## What had to change in the ported modules

Nothing. The two projects in `build.sbt` point `unmanagedSourceDirectories` at the existing
sources, so there is one copy of the code and the JVM build is untouched. One file is
excluded: `kernels/VectorKernels.scala`, which uses `jdk.incubator.vector`. It already sits
behind the `Kernels` trait with `ScalaKernels` as the fallback and nothing inside
`prima-core` constructs it, so the linker would drop it anyway — the exclusion is there so a
future reference fails loudly rather than as a link error.

`Unsafe` is two `asInstanceOf` casts. There are no threads and no `Future`. The only
`java.nio.file` in `network-model` is `Schema.fromFile` and `CsvReader.read`, which the
linker drops as unreachable — a browser fetches over HTTP, which is what the demo does, and
`Schema.fromJson` then parses PyPSA's schema with the same code the JVM uses.

A `crossProject` would have been the conventional shape and was not used: it moves every
source file into a `.jvm`/`.js` layout and touches every module that depends on these two,
which is a large change to make in order to answer a question.

## Sizes

| | |
| --- | --- |
| `fastLinkJS` | 1.7 MB |
| `fullLinkJS` | 1.07 MB |
| `fullLinkJS`, gzipped | **171 KB** |

## Running it

```bash
sbt demoJs/fullLinkJS
cp target/out/sjs1/scala-3.9.0/demo-js/demo-js-opt/main.js modules/demo-js/public/
cp reference/goldens/schema.json modules/demo-js/public/
cd modules/demo-js/public && python3 -m http.server 8731
```

then open <http://localhost:8731/>. `fastLinkJS` works too and lands in `demo-js-fastopt/`;
it also emits a `main.js.map` the page will ask for, so copy that alongside or ignore the
404.

`main.js` and `schema.json` are build outputs and are not committed.

## What this does not show

`network-lopf` is not ported here. Its only obstacle is `java.time`, which
`scala-java-time` provides, but porting it is what would let the page solve a *real* network
rather than a hand-built LP — and at that point the question becomes size rather than
language. A year of the six-zone Nordic model is ~460,000 columns against the 840 here; the
JVM takes about a minute on it, so that one belongs on a server with Scala.js kept for the
UI. One week of six zones is ~9,000 columns and would stay in this range.
