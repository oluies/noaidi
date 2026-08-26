#!/usr/bin/env python3
"""Tests for the kernel-split reporter.

The reporter's failure mode is publishing a plausible number computed from the
wrong thing -- which is exactly what three reviews found in the hand-written
version it replaces. So the cases below are mostly about the analysis being the
one it claims: per-iteration rather than raw, drift estimated by weight rather
than by count, and both quantities labelled.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("kernel_split_report.py")
FAILURES = []
CHECKS = []


def check(name, condition, detail=""):
    CHECKS.append(name)
    if condition:
        print(f"ok    {name}")
    else:
        print(f"FAIL  {name} {detail}")
        FAILURES.append(name)


def run(log_text):
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as f:
        f.write(log_text)
        path = f.name
    out = subprocess.run([sys.executable, str(SCRIPT), path],
                         capture_output=True, text=True)
    return out.stdout + out.stderr


def entry(backend, wall, kernels, iterations, ops, trials=None):
    # The real one-line format the harness prints. Emitted here so `TRIALS` is
    # driven by a fixture: without one, deleting the regex left all sixteen
    # cases green while the summary silently lost the trial block -- which is
    # the measured-then-discarded behaviour the feature exists to end.
    trials = iterations if trials is None else trials
    lines = [f"backend: {backend}",
             f"iterations {iterations} (line-search trials {trials})",
             f"wall clock {wall} ms, in kernels {kernels} ms"]
    for name, (us, calls) in ops.items():
        lines.append(f"  {name}       {us} ms   10.0%   {calls} calls   {us} us/call")
    return "\n".join(lines) + "\n"


# A pair where the vector arm is twice as fast on everything except the two
# controls, which are identical -- so drift is 1.0 and the ratio is 2x.
clean = (entry("scala-reference", "200.0", "200.0", 100,
               {"spmv": ("100.0", 100), "copy": ("10.0", 100), "dot": ("90.0", 100)}) +
         entry("scala-vector-8", "155.0", "155.0", 100,
               {"spmv": ("100.0", 100), "copy": ("10.0", 100), "dot": ("45.0", 100)}))
out = run(clean)
check("reports both quantities", "Wall clock" in out and "Time inside" in out, out)
check("no drift when controls agree", "drift **1.000**" in out, out)

# The same, but the vector arm ran 10% more iterations. A raw totals comparison
# would report no speedup; per-iteration it is 1.1x.
counts = (entry("scala-reference", "100.0", "100.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
          entry("scala-vector-8", "100.0", "100.0", 110,
                {"spmv": ("50.0", 110), "copy": ("5.0", 110)}))
out = run(counts)
check("flags differing iteration counts", "Iteration counts differ" in out, out)
check("normalises per iteration", "1.10x" in out, out)

# Drift must be time-weighted: a cheap, rare operation showing a wild ratio must
# not move the correction as much as the one that dominates the total. `spmv`
# here is unchanged and huge; `copy` is tiny and looks 2x slower.
weighted = (entry("scala-reference", "100.0", "100.0", 100,
                  {"spmv": ("100.0", 1000), "copy": ("1.0", 10)}) +
            entry("scala-vector-8", "100.0", "100.0", 100,
                  {"spmv": ("100.0", 1000), "copy": ("2.0", 10)}))
out = run(weighted)
drift_line = next((l for l in out.splitlines() if "Time-weighted drift" in l), "")
# `copy` is half the speed and a hundredth of the time, so it should move the
# estimate a little and not much: weighting by time gives 101/102 = 0.990, where
# an unweighted mean of the two ratios would give 0.75. Asserting a band rather
# than a point, because the correct answer is *not* 1.000 -- an earlier version
# of this case demanded 1.000 and so pinned an estimator that ignored weights.
value = float(drift_line.split("drift **")[1].split("**")[0]) if "drift **" in drift_line else 0.0
check("drift is time-weighted, not an unweighted mean",
      0.98 <= value <= 0.995, f"{drift_line} (unweighted would be ~0.75)")

# A log missing the wall-clock line must degrade rather than crash: a reporting
# script that dies turns a non-gating benchmark into a failed step.
partial = "backend: scala-reference\n  spmv       1.0 ms   10.0%   1 calls   1.0 us/call\n"
out = run(partial)
check("survives a log with no wall clock", "Traceback" not in out, out)

# European locales print `5709,7`; parsing only the first form silently drops
# every digit after the separator.
comma = (entry("scala-reference", "200,0", "200,0", 100,
               {"spmv": ("100,0", 100), "copy": ("10,0", 100)}) +
         entry("scala-vector-8", "100,0", "100,0", 100,
               {"spmv": ("100,0", 100), "copy": ("10,0", 100)}))
out = run(comma)
check("parses a comma decimal separator", "2.00x" in out, out)

# Drift must not absorb a call-count difference. Both controls are the same
# speed per call; the vector arm simply makes 25% more calls. A drift estimator
# that multiplies totals by counts reports ~0.8 here and then "corrects" the
# headline by 20% -- which is what the first version did, and what made a clean
# run look too noisy to read.
countdrift = (entry("scala-reference", "100.0", "100.0", 100,
                    {"spmv": ("100.0", 1000), "copy": ("10.0", 100)}) +
              entry("scala-vector-8", "125.0", "125.0", 125,
                    {"spmv": ("125.0", 1250), "copy": ("12.5", 125)}))
out = run(countdrift)
drift_line = next((l for l in out.splitlines() if "Time-weighted drift" in l), "")
check("drift ignores a pure call-count difference",
      "drift **1.000**" in drift_line, drift_line)

# The width partitioning is the reason the reporter exists in its current form,
# and it fails silently: if the marker stops matching, every run lands in one
# group and width-limited arms get divided by a full-width reference again.
widths = ("=== pass 1 backend scala lanes default ===\n" +
          entry("scala-reference", "100.0", "100.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
          "=== pass 1 backend vector lanes default ===\n" +
          entry("scala-vector-8", "50.0", "50.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
          "=== pass 2 backend vector lanes 16 ===\n" +
          entry("scala-vector-2", "400.0", "400.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}))
out = run(widths)
check("a width with no matching reference refuses to compare",
      "No reference run at this width" in out, out)
# Asserting `2.00x` alone cannot distinguish partitioning from merging -- the
# merged case below produces it too. What only the partitioned case gives is the
# width-limited arm getting a refusal rather than a ratio.
vector2_block = out.split("`scala-vector-2`")[-1] if "`scala-vector-2`" in out else ""
check("the default-width ratio excludes the width-limited run",
      "**2.00x**" in out and "No reference run at this width" in vector2_block
      and "Wall clock" not in vector2_block, out)

# And the fallback, stated as a case so it cannot regress quietly: with no
# markers everything is one group, which is the behaviour to notice.
out = run(widths.replace("=== pass", "## pass"))
check("without markers the arms merge, and the table says so",
      "| default |" in out and "No reference run at this width" not in out, out)

# `controls_for` is what moved the default arm's drift off `spmv` and onto
# `axpby`'s call volume, and the `-all` branch decides whether widened code is
# mistaken for a control. Neither had a fixture.
def coverage(backend):
    return entry(backend, "100.0", "100.0", 100,
                 {"spmv": ("50.0", 100), "copy": ("5.0", 100), "axpby": ("20.0", 100),
                  "scale": ("2.0", 100), "dualStep": ("3.0", 100), "dot": ("20.0", 100)})

out = run(coverage("scala-reference") + coverage("scala-vector-8"))
# Scoped to the `- Controls` line. Searching the whole output cannot fail: the
# per-operation table lists every name unconditionally, so the assertion passed
# even with `controls_for` returning nothing extra.
controls = next((l for l in out.splitlines() if l.startswith("- Controls")), "")
check("the default coverage counts the delegated three as controls",
      all(f"`{o}`" in controls for o in ("axpby", "dualStep", "scale")), controls)

out = run(coverage("scala-reference") + coverage("scala-vector-8-all"))
controls = next((l for l in out.splitlines() if l.startswith("- Controls")), "")
check("the -all coverage does not, since it widens them",
      "`axpby`" not in controls and "`spmv`" in controls, controls)

# The per-operation table is what NOTES is meant to quote, and nothing asserted a
# cell of it. 50.0 ms over 100 calls is 500 us.
out = run(coverage("scala-reference") + coverage("scala-vector-8"))
check("the per-operation table reports per-call microseconds",
      "| `spmv` | 500.0 µs |" in out, [l for l in out.splitlines() if "spmv" in l])

# An operation present but never called must not reach the drift arithmetic.
zerocalls = (entry("scala-reference", "100.0", "100.0", 100,
                   {"spmv": ("50.0", 100), "copy": ("5.0", 100), "scale": ("0.0", 0)}) +
             entry("scala-vector-8", "50.0", "50.0", 100,
                   {"spmv": ("50.0", 100), "copy": ("5.0", 100), "scale": ("0.0", 0)}))
out = run(zerocalls)
check("an uncalled operation does not crash the drift block",
      "Traceback" not in out and "**2.00x**" in out, out)

# Trials, which had no fixture at all.
tr = (entry("scala-reference", "100.0", "100.0", 100,
            {"spmv": ("50.0", 100), "copy": ("5.0", 100)}, trials=100) +
      entry("scala-vector-8", "100.0", "100.0", 100,
            {"spmv": ("50.0", 100), "copy": ("5.0", 100)}, trials=125))
out = run(tr)
check("trials per iteration are reported", "trials per iteration" in out, out)
check("a divergent rejection rate is called a confound",
      "confounded by this" in out and "125.0% of the reference's" in out, out)

# ... and does not fire when the rates match.
out = run(entry("scala-reference", "100.0", "100.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
          entry("scala-vector-8", "50.0", "50.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}))
check("matching rejection rates raise no confound", "confounded by this" not in out, out)

# The withholding rule decides how every published ratio reads, and both real
# runs cited fall on the withheld side. Neither branch had a case.
wide = (entry("scala-reference", "100.0", "100.0", 100,
              {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
        entry("scala-vector-8", "50.0", "50.0", 100,
              {"spmv": ("40.0", 100), "copy": ("10.0", 100)}))
out = run(wide)
check("a wide control spread withholds the correction",
      "the correction is not usable" in out and "drift-corrected" not in out, out)

# Spread past 0.02 so the `width_of_spread < 0.02` shortcut cannot carry it, and
# a correction large enough that `correction < 0.005` cannot either: only the
# `2 * correction` clause is left to publish this.
narrow = (entry("scala-reference", "100.0", "100.0", 100,
                {"spmv": ("50.0", 100), "copy": ("5.0", 100)}) +
          entry("scala-vector-8", "50.0", "50.0", 100,
                {"spmv": ("58.0", 100), "copy": ("5.2", 100)}))
out = run(narrow)
check("agreeing controls keep the correction",
      "drift-corrected" in out and "the correction is not usable" not in out, out)

# The near-identity floor, which was the one branch of three with no fixture --
# in the commit whose subject was that the named feature is the untested one.
# Controls disagree wildly (0.5) but the time-weighted drift is ~0.995, so the
# correction changes nothing and must not be condemned for the spread.
# `entry`'s op tuples are (total_ms, calls), not per-call microseconds. The first
# version of this fixture was written as if they were the latter, so `copy` came
# out at 20,000 us/call, drift at 0.667 rather than 0.995, and the case was
# carried by the `2 * correction` clause -- leaving the floor deletable with all
# 22 green, which is the regression path it was added to close.
#
# 0.5 ms over 5 calls is 100 us; 1.0 ms over 5 is 200. With `spmv` identical at
# 100 us over 1000 calls, drift is 100500/101000 = 0.995, the spread is 0.5, and
# `2 * correction` is 0.0099 -- so only the floor can publish this.
identity = (entry("scala-reference", "100.0", "100.0", 100,
                  {"spmv": ("100.0", 1000), "copy": ("0.5", 5)}) +
            entry("scala-vector-8", "100.0", "100.0", 100,
                  {"spmv": ("100.0", 1000), "copy": ("1.0", 5)}))
out = run(identity)
check("a near-identity correction survives a wide spread",
      "drift **0.995**" in out and "drift-corrected" in out
      and "the correction is not usable" not in out, out)

# And the third clause, which was in the state the floor was in: no case needed
# it. Correction 0.0058 (above the floor) with a spread of 0.0151 (above
# 2 * correction, below 0.02) leaves `width_of_spread < 0.02` as the only thing
# that can publish it.
shortcut = (entry("scala-reference", "100.0", "100.0", 100,
                  {"spmv": ("100.0", 1000), "copy": ("1.0", 10)}) +
            entry("scala-vector-8", "100.0", "100.0", 100,
                  {"spmv": ("100.6", 1000), "copy": ("0.991", 10)}))
out = run(shortcut)
check("a spread under the noise budget keeps the correction",
      "drift **0.994**" in out
      and "drift-corrected" in out
      and "the correction is not usable" not in out, out)

out = run("nothing useful here\n")
check("says so when there are no runs", "No runs found" in out, out)

# Counted, not written down: the literal said 6 while eight cases ran.
print(f"\n{len(CHECKS) - len(FAILURES)}/{len(CHECKS)} cases passed")
raise SystemExit(1 if FAILURES else 0)
