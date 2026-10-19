#!/usr/bin/env bash
# self-test-save-uid-manifest.sh — the discriminating NEGATIVE CONTROL for
# scripts/check-save-uid-manifest.sh.
#
# WHAT IT PROVES: that the manifest gate actually FAILS when a class that can reach a .game
# stream changes UID — and that the pre-existing per-class gate (check-serial-uid-drift.sh) PASSES
# on the very same tree. That asymmetry is the bug the manifest gate was built to close: a class
# already pinned or allowlisted can drift through a MERGE that the per-class gate never looks at,
# because Phase 2 only inspects files changed vs a base ref.
#
# METHOD (real bytes, not a simulation): take a class that is in the manifest AND has source in
# this repo, insert one new instance field into it — exactly the "purely additive edit" that
# changed ManaCostImpl — recompile that
# single file into Mage/target/classes, then re-run BOTH gates:
#   unmodified tree : manifest gate exit 0, drift gate exit 0
#   perturbed tree  : manifest gate exit 1 naming the class, drift gate exit 0 (blind)
#   restored        : manifest gate exit 0, drift gate exit 0
#
# Restoration is byte-exact (the original file is saved and copied back), including on failure.
set -euo pipefail
cd "$(dirname "$0")/.."
WT="$(pwd)"

DRIFT=scripts/check-serial-uid-drift.sh
MANIFEST_CHECK=scripts/check-save-uid-manifest.sh
MANIFEST=scripts/serial-uid-manifest.txt
PROBE_REL=Mage/src/main/java/mage/game/CardState.java
PROBE_CLS=mage.game.CardState

die() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

[ -f "$PROBE_REL" ] || die "probe source not found: $PROBE_REL"
grep -qP "^$(printf '%s' "$PROBE_CLS" | sed 's/\./\\./g')\t" "$MANIFEST" \
  || die "probe class $PROBE_CLS is not in $MANIFEST"
grep -q "serialVersionUID" "$PROBE_REL" \
  && die "probe class $PROBE_CLS already carries a pin; the control needs an unpinned, in-scope class"

# The classpath the gates compile the perturbed probe into. Same rule as check-save-uid-manifest.sh.
build_cp() {
  local cp="" d
  for d in $(find . -maxdepth 4 -type d -path '*/target/classes' | sort); do cp="$cp:$WT/${d#./}"; done
  [ -f scripts/.serial-oracle-cp.txt ] && cp="$cp:$(cat scripts/.serial-oracle-cp.txt)"
  echo "${cp#:}"
}
CP="$(build_cp)"

cp "$PROBE_REL" "$PROBE_REL.selftest.bak"
restore() {
  [ -f "$PROBE_REL.selftest.bak" ] || return 0
  mv -f "$PROBE_REL.selftest.bak" "$PROBE_REL"
  javac -nowarn -cp "$CP" -d Mage/target/classes "$PROBE_REL" 2>/dev/null || true
}
trap restore EXIT

echo "== probe: $PROBE_CLS (manifest UID $(grep -P "^$(printf '%s' "$PROBE_CLS" | sed 's/\./\\./g')\t" "$MANIFEST" | cut -f2))"

# ---- 1. baseline: both gates must PASS -----------------------------------------------------------
echo "-- baseline (unmodified tree)"
bash "$MANIFEST_CHECK" >/tmp/selftest-baseline-new.out 2>&1 || { cat /tmp/selftest-baseline-new.out >&2; die "baseline: manifest gate FAILED on the clean tree"; }
bash "$DRIFT" >/tmp/selftest-baseline-old.out 2>&1 || { cat /tmp/selftest-baseline-old.out >&2; die "baseline: drift gate FAILED on the clean tree"; }
echo "   manifest gate: exit 0  ($(grep -m1 PASS /tmp/selftest-baseline-new.out))"
echo "   drift gate:    exit 0  ($(tail -1 /tmp/selftest-baseline-old.out))"

# ---- 2. perturb: one additive instance field ----------------------------------------------------
echo "-- perturbing: inserting an additive instance field into $PROBE_CLS"
python3 - "$PROBE_REL" <<'PY'
import re, sys
p = sys.argv[1]
lines = open(p).read().split("\n")
for i, l in enumerate(lines):
    if re.match(r'^\s*(public|abstract|final).*\bclass\b', l):
        lines[i + 1:i + 1] = [
            "    // selftest perturbation: additive field — the exact edit class that drifted ManaCostImpl",
            "    private final int selftestUidDriftProbe = 1;",
        ]
        break
else:
    raise SystemExit("no class declaration found in " + p)
open(p, "w").write("\n".join(lines))
PY
javac -nowarn -cp "$CP" -d Mage/target/classes "$PROBE_REL" \
  || die "perturbed $PROBE_CLS did not compile — cannot run the negative control"

# ---- 3. the claim: manifest gate FAILS, drift gate is BLIND -------------------------------------
echo "-- re-running both gates on the perturbed tree"
set +e
bash "$MANIFEST_CHECK" >/tmp/selftest-perturbed-new.out 2>&1; rc_new=$?
bash "$DRIFT" >/tmp/selftest-perturbed-old.out 2>&1; rc_old=$?
set -e

echo "   manifest gate: exit $rc_new"
sed -n '1,3p' /tmp/selftest-perturbed-new.out | sed 's/^/     /'
echo "   drift gate:    exit $rc_old  ($(tail -1 /tmp/selftest-perturbed-old.out))"

[ "$rc_new" = 1 ] || die "manifest gate exited $rc_new on a drifted tree — expected 1 (it did NOT catch the drift)"
grep -q 'serialVersionUID DRIFT' /tmp/selftest-perturbed-new.out \
  || die "manifest gate failed but did not report DRIFT (see /tmp/selftest-perturbed-new.out)"
grep -qP "$(printf '%s' "$PROBE_CLS" | sed 's/\./\\./g')" /tmp/selftest-perturbed-new.out \
  || die "manifest gate failure did not name the drifted class $PROBE_CLS"
[ "$rc_old" = 0 ] || die "drift gate exited $rc_old on the same tree — expected 0; the control no longer demonstrates the blind spot"
echo "   => DISCRIMINATING: the manifest gate caught the UID change; the per-class gate did not"

# ---- 4. restore and confirm both gates PASS again -----------------------------------------------
echo "-- restoring $PROBE_REL"
restore
bash "$MANIFEST_CHECK" >/tmp/selftest-restored-new.out 2>&1 || { cat /tmp/selftest-restored-new.out >&2; die "restored tree: manifest gate still FAILS"; }
bash "$DRIFT" >/tmp/selftest-restored-old.out 2>&1 || die "restored tree: drift gate still FAILS"
echo "   manifest gate: exit 0; drift gate: exit 0"

# ---- 5. --affected-saves is BOUNDED and honest --------------------------------------------------
# The corpus audit must (a) finish inside its --budget even when a save read would run away, and
# (b) never turn a save it could not read into a silent pass. Two runs over the in-repo fixture
# saves (a real .game carries a UID this build has moved, so both exit 1 by design — what is under
# test is the BOUND and the REPORT, not the verdict).
echo "-- --affected-saves budget + honesty"
SAVES="${SAVES:-}"
[ -n "$SAVES" ] || die "set SAVES to a directory of .game fixtures"
[ -d "$SAVES" ] || die "fixture saves missing: $SAVES (needed for the bounded-run control)"

# (a) a 5 s budget over the fixture saves: must return near the budget, never hang. Allow generous
# slack for JVM start-up, but far below the ~400 s timeout this fixes.
t0=$SECONDS
set +e
bash "$MANIFEST_CHECK" --affected-saves "$SAVES" --jobs 2 --save-timeout 30 --budget 5 >/tmp/selftest-affected-bounded.out 2>&1; rc=$?
set -e
el=$((SECONDS - t0))
echo "   budget=5s run: exit $rc in ${el}s"
[ "$el" -le 120 ] || die "budget=5s run took ${el}s — the --budget bound is not enforced"
grep -qE '^reference saves:|stream phase:' /tmp/selftest-affected-bounded.out \
  || { cat /tmp/selftest-affected-bounded.out >&2; die "bounded run printed no coverage accounting"; }

# (b) coverage accounting must be explicit: a run that reads every save says so, and one that skips
# names each skipped file (a silent skip is the failure mode this whole gate exists to prevent).
bash "$MANIFEST_CHECK" --affected-saves "$SAVES" --jobs 2 --save-timeout 60 --budget 120 >/tmp/selftest-affected-full.out 2>&1 || true
grep -qE 'read [0-9]+/[0-9]+ saves in' /tmp/selftest-affected-full.out \
  || { cat /tmp/selftest-affected-full.out >&2; die "full run printed no 'read N/M saves' coverage line"; }
echo "   coverage line present: $(grep -oE 'read [0-9]+/[0-9]+ saves.*' /tmp/selftest-affected-full.out | head -1)"

echo "SELFTEST PASS: negative control discriminates (drift caught by check-save-uid-manifest.sh, missed by $DRIFT); --affected-saves is bounded and reports coverage"
