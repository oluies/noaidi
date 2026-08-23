# Running Prima on Arrhenius

A plan, not a commitment. Nothing here has been executed — everything below is
either quoted from NAISS documentation or derived from what this repository
already does. Anything I could not verify is marked as an unknown rather than
assumed.

## The machine

[Arrhenius GPU](https://supr.naiss.se/resource/arrhenius-gpu/) is 382 nodes with
4 × NVIDIA **GH200** (Grace Hopper) each: 96 GB HBM3 per GPU, and a Grace CPU of
72 **Arm Neoverse V2** cores with 120 GiB LPDDR5X. In production from June 2026.

The [programming environment](https://hpc.pages.naiss.se/user-documentation/support-docs/arrhenius_hpc/software_development/programming_environment/)
provides NVHPC 25.9, GCC 14.3.0, CUDA 13.0, OpenMPI 5.0.10, MPICH 4.3.2,
NCCL/NVSHMEM, Lmod modules and Slurm. Two absences matter: **no JVM is listed**,
and neither HIP nor SYCL is mentioned.

## Three findings that change the existing GPU plan

**The float32 problem does not apply here.** `modules/prima-core/NOTES.md`
records that Cyfra is float32-only on every target, and concludes that mixed
precision is a *prerequisite* for a GPU backend rather than a follow-up. That is
a Vulkan/SPIR-V constraint, not a GPU one — Hopper has native, fast fp64. On
Arrhenius `MixedPrecision` becomes an optimisation, and the unexplained
dense-instance warm-start regression stops being on the critical path.

**So `prima-cyfra` is the wrong vehicle for this machine.** It targets
SPIR-V/Vulkan through MoltenVK and is gated on macOS/aarch64. The target here is
CUDA. The spike keeps its value — it answered whether CSR SpMV is expressible on
a GPU at all — but it is not the thing to port.

**PDHG is unusually well matched to GH200.** Two sparse matrix-vector products
per iteration and no factorisation makes the inner loop memory-bandwidth bound,
which is what HBM3 and the NVLink-C2C link between Grace and Hopper are for. This
is the payoff for the architectural bet in ARCHITECTURE.md: a simplex or
interior-point solver would not port to this hardware at all.

## A fourth reason to go, independent of performance

NOTES records that the worst objective gap against the ojAlgo oracle differs by
configuration — 4.9e-10 on macOS/aarch64/JDK 26 against 5.874e-10 on
Linux/x86_64 — and states plainly that architecture is "the leading hypothesis,
not established", because the JDK was a second uncontrolled variable.

CI has since narrowed that, without closing it. The Linux figure is bit-identical
on JDK 21 and JDK 25, so there is no JVM sensitivity *between those two
versions* — but the macOS measurement is on JDK 26, which is in neither matrix,
so the JDK is not ruled out.

Grace is **aarch64 Linux**. It is the missing cell in that table, and it is the
one that separates the two remaining variables: an aarch64 Linux run on a JDK the
matrix already covers leaves architecture as the only thing that changed. That is
worth doing whether or not any GPU work follows.

## Plan

### Stage 1 — build and validate on Grace, no GPU

Get a JVM (see unknowns), build, run the full suite plus the Netlib corpus, and
publish the third row of the oracle-gap table.

Portability is better than it looks: `prima-core` has no third-party
dependencies by construction, and jhdf, ojAlgo and upickle are pure Java. The
only module with native code is `prima-cyfra`, whose LWJGL natives are x86_64 and
macOS — and it is deliberately not in the aggregated build, so `sbt testFull`
never touches it.

Cost: about a day. No GPU allocation needed. It either confirms the port is
portable or finds out cheaply that it is not.

### Stage 2 — scale out, which is where the value actually is

PyPSA workloads are overwhelmingly *many independent solves*: weather years ×
policy scenarios × network variants. That is embarrassingly parallel and needs no
MPI — a Slurm array job, one JVM per node, and the ZIO Streams fan-out the brief
already calls for in `prima-zio`.

This gets useful throughput on Arrhenius without writing any CUDA, and it is the
shape most real studies want. It should be done before Stage 3, not after.

### Stage 3 — a CUDA `Kernels` backend

`Kernels` exists for exactly this: 8 operations, swappable, with a pure-Scala
fp64 oracle and a `KernelContractSuite` every backend must pass. Of the eight,
two are SpMV — which **cuSPARSE already implements well** — and the remaining six
are elementwise (projections, axpby, norms, dot) and are small kernels.

For the JVM↔CUDA boundary, **Project Panama** (`java.lang.foreign`, JDK 22+) is
preferable to JNI or JCuda: no native glue to build per architecture, which
matters on aarch64.

On *generating* code rather than writing it: **TornadoVM** JIT-compiles JVM
bytecode to PTX and is the literal answer. I would not lead with it — it
constrains how kernels are written and adds a large runtime, for an interface
that is only eight functions and whose hardest one cuSPARSE already provides.
Emitting CUDA C from the `Kernels` interface is the fallback if hand-written
kernels prove awkward.

### Not planned: distributed single-LP solve

Splitting one LP's SpMV across GPUs with NCCL/NVSHMEM is a large piece of work
and is only justified if a single model does not fit in 96 GB. That should be
measured before it is attempted, not assumed.

## Unknowns to resolve first

Each is cheap to check and each can invalidate a stage:

1. **Is a JVM available?** Not in the module list. Temurin and GraalVM ship
   aarch64 Linux builds, and NAISS supports containers (`hpc_container_shell`,
   `mpprun`), so the fallback is a container image — but this needs confirming
   rather than assuming.
2. **Is there outbound network access on the build node?** sbt resolves
   dependencies from Maven Central. If not, the build must be vendored.
3. **What is the sane allocation unit** — one GPU per rank, or four per node?
4. **Does any real model exceed 96 GB?** This decides whether the
   "not planned" section above stays unplanned.

## What this does not address

Arrhenius is a scheduled batch machine. Nothing in this repository currently
knows about Slurm, checkpointing or wall-clock limits, and a solve that is killed
at a time limit loses its work — `PdhgParams.timeLimitMillis` exists but nothing
persists an iterate. If Stage 2 goes ahead, warm-start-from-disk is the piece
that makes a long solve survivable across job boundaries.
