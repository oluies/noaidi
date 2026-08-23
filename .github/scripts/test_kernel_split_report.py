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


def entry(backend, wall, kernels, iterations, ops):
    lines = [f"backend: {backend}", f"iterations {iterations}",
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
check("drift is time-weighted, not an unweighted mean",
      "drift **0.999**" in drift_line or "drift **1.000**" in drift_line, drift_line)

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

out = run("nothing useful here\n")
check("says so when there are no runs", "No runs found" in out, out)

# Counted, not written down: the literal said 6 while eight cases ran.
print(f"\n{len(CHECKS) - len(FAILURES)}/{len(CHECKS)} cases passed")
raise SystemExit(1 if FAILURES else 0)
