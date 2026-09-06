#!/usr/bin/env bash
# How many tests an sbt run actually reported, from a log.
#
# Prints three lines to stdout -- `suites=N`, `summary=N`, `total=N` -- and a
# note to stderr when the two counters disagree in the way that means sbt
# replayed a cached test action instead of running one.
#
# Two counters, because each is blind in a different direction and sbt 2's
# action cache is what blinds them. The blindnesses are disjoint, which is the
# whole reason taking the maximum is sound rather than a fudge:
#
#   per-suite  munit's own `finished:` lines, one per suite that reported.
#              Accurate whenever the tests run, and *absent entirely* when the
#              whole test task is replayed -- the framework never runs, so it
#              emits nothing. Observed: `primaOrtools/testFull` on JDK 25
#              printed `Passed: Total 10` with no framework lines at all, and a
#              guard reading only these called it zero tests.
#   summary    sbt's own `Passed: Total`. Present on a replay and correct there.
#              Undercounts when the cache is *partially* warm, where some tasks
#              re-ran and others did not -- observed at 613 against a true 755,
#              with whole modules reporting short, which is why the aggregate
#              step counted suites in the first place.
#
# A partial cache leaves the per-suite lines complete and the summary short; a
# full replay leaves the summary complete and the per-suite lines missing.
# Neither of the two observed states makes both wrong, so the larger is right in
# each, and zero from both is what "nothing ran" looks like.
#
# Two limits, stated rather than glossed:
#
#   - The maximum is a *lower bound*, not a count. A mixed state -- some modules
#     replayed and others partially cached -- could leave both counters short
#     and under-report. That would read as a floor failure rather than as a
#     silent pass, which is the safe direction, and the note below is what makes
#     it diagnosable.
#   - A replay means the tests did not execute in this job. They executed when
#     the cache entry was made, for inputs that hash the same, which is why this
#     is accepted rather than failed. Whether `setup-java`'s `cache: sbt` can
#     let one JDK leg of a matrix replay another's results is *not established*;
#     if it can, a matrix leg could report a pass having run nothing under its
#     own JDK. That is worth settling against the action's own cache key before
#     leaning on the matrix.

#
# Usage: count_tests.sh <log-file>
set -euo pipefail

log=${1:?usage: count_tests.sh <log-file>}

# Checked, because the failure mode otherwise is a lie. A path that does not
# exist makes every grep below match nothing, `|| true` swallows the exit, awk
# prints 0, and the caller reports "either the suite shrank or something stopped
# running" -- a diagnosis about the test suite for what is a typo in a filename.
[ -r "$log" ] || { echo "count_tests.sh: cannot read $log" >&2; exit 1; }

# `|| true` on each: a grep that matches nothing exits 1, which under `pipefail`
# and `set -e` would abort here -- and matching nothing is precisely the case
# being measured.
suites=$(grep -oE 'finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total' "$log" \
  | grep -oE '[0-9]+ total' \
  | grep -oE '^[0-9]+' \
  | awk '{ sum += $1 } END { print sum + 0 }' || true)
summary=$(grep -oE 'Passed: Total [0-9]+' "$log" \
  | grep -oE '[0-9]+$' \
  | awk '{ sum += $1 } END { print sum + 0 }' || true)

suites=${suites:-0}
summary=${summary:-0}

if [ "$suites" -ge "$summary" ]; then total=$suites; else total=$summary; fi

if [ "$suites" -eq 0 ] && [ "$summary" -gt 0 ]; then
  echo "note: no per-suite lines but sbt reported $summary -- sbt replayed a cached test action rather than running one" >&2
fi

echo "suites=$suites"
echo "summary=$summary"
echo "total=$total"
