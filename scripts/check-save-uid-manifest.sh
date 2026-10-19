#!/usr/bin/env bash
# check-save-uid-manifest.sh — the MERGE-PROOF half of the replay-orphaning serialVersionUID gate.
#
# WHY THIS EXISTS (the gap it closes):
#   scripts/check-serial-uid-drift.sh is a PER-CLASS pin check. Phase 1 only asks "is this class
#   pinned or allowlisted?"; Phase 2 only verifies classes CHANGED-vs-BASE. Neither notices a
#   class whose UID drifted because of a MERGE (an upstream edit, a superclass change, a field
#   added to a type it embeds) — the drift arrives through a commit the author never touched, so
#   there is nothing in "changed vs BASE" to look at. With 3,641 allowlist rows, every one is a
#   chance to orphan the replay corpus on the next rebuild
#   (a deployment once could load none of its own 35
#   saves; three mutually exclusive save generations exist because three builds each re-derived
#   a different UID).
#
# WHAT IT DOES:
#   scripts/serial-uid-manifest.txt is a persisted {class -> pinned serialVersionUID} map covering
#   every class that can reach a .game stream. This script recomputes that map from the JVM's own
#   ObjectStreamClass oracle and FAILS on ANY difference:
#     - a UID that changed        (drift: this build would orphan saves carrying the old value)
#     - a class that disappeared  (stale row — a rename would silently drop coverage)
#     - a class that appeared     (a new save-surface class with no deliberate pin)
#     - a row that no longer resolves on the classpath
#   There is no accept path and no tolerance: a differing computed value is always a failure.
#   Regenerate deliberately with --write-manifest and review the resulting diff in the commit.
#
# USAGE:
#   scripts/check-save-uid-manifest.sh                          # enforce (CI / pre-commit)
#   scripts/check-save-uid-manifest.sh --write-manifest        # regenerate after a deliberate pin
#   scripts/check-save-uid-manifest.sh --write-manifest --reference DIR
#                                                              # fold a reference .game corpus in
#   scripts/check-save-uid-manifest.sh --affected-saves DIR...  # would this build break THESE saves?
#   scripts/check-save-uid-manifest.sh --affected-saves DIR --jobs N --save-timeout S --budget S
#                                                              # bound the corpus audit (see below)
#   scripts/check-save-uid-manifest.sh --self-test              # discriminating negative control
#   scripts/check-save-uid-manifest.sh --debug                  # also print the scope diagnostics
#                                                              # (refused subtype roots, observed rows)
#
#   --affected-saves is the operational proof of the guarantee: for every class a reference save
#   carries, it compares the save's stream UID with the UID this build computes. Exits 1 on any
#   mismatch or missing class.
#
#   --affected-saves is BOUNDED. It reads each save in its OWN oracle process under a hard
#   `timeout` (default 90 s, --save-timeout), runs --jobs of them concurrently (default nproc),
#   and abandons the remainder once an overall wall-clock budget elapses (default 300 s,
#   --budget). A save it could not read is never silently dropped: each one is named on stderr
#   in a SKIPPED/UNREADABLE list and counted in the verdict line. Rationale: --stream-uids
#   deserializes each save graph with a real ObjectInputStream (it must, to drive the
#   descriptor resolveClass hook), and a single large modern save's readObject walk can run for
#   minutes — before that bound existed, one 72-save run did not finish in >400 s (a jstack of the
#   hung JVM showed main RUNNABLE inside ObjectInputStream.readSerialData).
#
# Exit codes: 0 = pass, 1 = violation, 2 = usage/build error.
# Requires: JDK (javac/java). No server, no network. Build the gate modules first:
#   mvn -pl Mage,Mage.Common,Mage.Server -am install -DskipTests
set -euo pipefail
cd "$(dirname "$0")/.."
WT="$(pwd)"

ORACLE=scripts/SerialSaveScope.java
ORACLE_BIN="scripts/.SerialSaveScope.bin"
MANIFEST="scripts/serial-uid-manifest.txt"
DEP_CP_FILE="scripts/.serial-oracle-cp.txt"
# Reference corpus roots for --write-manifest (one path per line; '#' comments ignored).
ROOTS_FILE="scripts/save-uid-roots.txt"

# The classpath the oracle resolves the save surface against. Every module that can contribute a
# class to a save must be here: the engine, the server, the game-type plugins (a .game carries its
# GameType subclass, e.g. mage.game.CommanderDuel), the deck builders and the player plugins.
build_classpath() {
  local cp="" d
  for d in $(find . -maxdepth 4 -type d -path '*/target/classes' | sort); do
    cp="$cp:$WT/${d#./}"
  done
  # NOTE: plain `if`, not `[ -f ... ] && cp=...`. The && form leaves this function's exit status
  # at 1 when the file is absent, which under `set -e` kills the caller with NO output at all —
  # a silent failure of exactly the kind this gate exists to prevent (found 2026-10-02).
  if [ -f "$DEP_CP_FILE" ]; then
    cp="$cp:$(cat "$DEP_CP_FILE")"
  fi
  echo "${cp#:}"
}

mode=check
reference=()
saves=()
debug=0
jobs="$(nproc 2>/dev/null || echo 4)"
save_timeout=90
budget=300
# Fail-Fast on the numeric flags: an unparseable value must stop the run, never silently fall back.
require_uint() { case "$1" in ''|*[!0-9]*) echo "ERROR: $2 needs a positive integer, got '$1'" >&2; exit 2 ;; esac; }
while [ $# -gt 0 ]; do
  case "$1" in
    --write-manifest) mode=write; shift ;;
    --affected-saves) mode=affected; shift ;;
    --self-test) mode=selftest; shift ;;
    --debug) debug=1; shift ;;
    --jobs) require_uint "${2:-}" "--jobs"; jobs="$2"; shift 2 ;;
    --save-timeout) require_uint "${2:-}" "--save-timeout"; save_timeout="$2"; shift 2 ;;
    --budget) require_uint "${2:-}" "--budget"; budget="$2"; shift 2 ;;
    --reference) reference+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,56p' "$0"; exit 0 ;;
    -*) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    *) saves+=("$1"); shift ;;
  esac
done

CP="$(build_classpath)"
[ -n "$CP" ] || { echo "ERROR: no target/classes found — build the gate modules first" >&2; exit 2; }
mkdir -p "$ORACLE_BIN"
javac -nowarn -cp "$CP" -d "$ORACLE_BIN" "$ORACLE" 2>/dev/null \
  || { echo "ERROR: $ORACLE did not compile against the current classpath." >&2
       echo "  Build the gate modules first: mvn -pl Mage,Mage.Common,Mage.Server -am install -DskipTests" >&2
       exit 2; }
# Run the oracle. stderr goes to $ORACLE_ERR when the caller has set it (a file the caller KEEPS —
# it must NOT be one of the trap-deleted temp files, or a failure message would be swallowed with
# it: that is how this script silently exited 2 during development, 2026-10-02). Without
# ORACLE_ERR, stderr passes straight through to the user.
run_oracle() {
  local rc=0
  if [ -n "${ORACLE_ERR:-}" ]; then
    java -cp "$CP:$ORACLE_BIN" SerialSaveScope "$@" 2>>"$ORACLE_ERR" || rc=$?
  else
    java -cp "$CP:$ORACLE_BIN" SerialSaveScope "$@" || rc=$?
  fi
  if [ "$rc" != 0 ]; then
    echo "ERROR: SerialSaveScope exited $rc — the oracle could not complete." >&2
    [ -n "${ORACLE_ERR:-}" ] && { echo "  Oracle stderr:" >&2; sed 's/^/    /' "$ORACLE_ERR" >&2; }
    echo "  Likely cause: the dependency classpath is missing or stale." >&2
    echo "  Fix: bash scripts/gen-serial-oracle-cp.sh  (and build the gate modules)" >&2
    exit 2
  fi
}

# --------------------------------------------------------------------------- regenerate
if [ "$mode" = write ]; then
  # Seeding from the EXISTING manifest is what makes regeneration lossless and deterministic:
  # every class already covered stays in scope, so the walk is a superset of the old map and no
  # row can be dropped by accident. Without a corpus, previously corpus-derived rows simply
  # re-derive from their own source instead of from stream evidence.
  seed=()
  [ -f "$MANIFEST" ] && seed=(--seed "$MANIFEST")
  refs=()
  for r in "${reference[@]}"; do refs+=(--reference "$r"); done
  # Nothing passed on the command line: fall back to the recorded corpus roots (missing paths are
  # skipped by the oracle with an ERROR, so filter them here and say what was used).
  if [ ${#refs[@]} -eq 0 ] && [ -f "$ROOTS_FILE" ]; then
    while IFS= read -r line; do
      line="${line%%#*}"; line="$(echo "$line" | xargs || true)"
      [ -n "$line" ] || continue
      if [ -e "$line" ]; then refs+=(--reference "$line"); echo "  reference: $line" >&2
      else echo "  reference SKIPPED (absent): $line" >&2; fi
    done < "$ROOTS_FILE"
  fi
  # Iterate to a fixpoint: each pass adds the walk-closure of the previous manifest to the seed,
  # so a class newly pulled into scope by the added rows is itself added next pass. Regeneration is
  # therefore LOSSLESS — the result is always a superset of the manifest it started from, and the
  # --manifest command itself is idempotent, so the loop converges (asserted below).
  prev="$(mktemp)"; cur="$(mktemp)"
  trap 'rm -f "$prev" "$cur" "$MANIFEST.new"' EXIT
  [ -f "$MANIFEST" ] && cp "$MANIFEST" "$prev" || : > "$prev"
  for pass in 1 2 3 4 5 6; do
    seed=()
    [ -s "$prev" ] && seed=(--seed "$prev")
    {
      echo "# serial-uid-manifest — every class that can reach a .game replay stream, with the"
      echo "# serialVersionUID this tree computes."
      echo "# Regenerate: scripts/check-save-uid-manifest.sh --write-manifest [--reference DIR]"
      echo "# Enforced by: scripts/check-save-uid-manifest.sh   (compares columns 1 and 2)"
      echo "# Scope + documented gaps: "
      echo "# Columns: <class> <serialVersionUID> <reached|subtype|observed>. Do not hand-edit."
      run_oracle --manifest "${seed[@]}" "${refs[@]}" | grep -vE '^NOTE:|^WARN:|^SKIP:'
    } > "$cur.tmp"
    if [ -f "$prev" ] && diff -q <(grep -vE '^#|^$' "$prev") <(grep -vE '^#|^$' "$cur.tmp") >/dev/null; then
      mv "$cur.tmp" "$MANIFEST"
      echo "Manifest written: $MANIFEST ($(grep -cvE '^#|^$' "$MANIFEST") rows, converged pass $pass)"
      exit 0
    fi
    cp "$cur.tmp" "$prev"
  done
  echo "ERROR: manifest generation did not converge in 6 passes — the scope is oscillating" >&2
  exit 2
fi

# --------------------------------------------------------------------------- self-test
if [ "$mode" = selftest ]; then
  exec bash "$(dirname "$0")/self-test-save-uid-manifest.sh"
fi

# --------------------------------------------------------------------------- affected-saves
if [ "$mode" = affected ]; then
  [ ${#saves[@]} -gt 0 ] || { echo "ERROR: --affected-saves needs a .game file or directory" >&2; exit 2; }
  for s in "${saves[@]}"; do [ -e "$s" ] || { echo "ERROR: no such save path: $s" >&2; exit 2; }; done

  # Work entirely inside a scratch dir: the reads are read-only, but deserializing a save can run
  # CardRepository.<clinit> (via ChooseACardNameEffect$TypeOfName), which mkdirs ./db and opens
  # cards.h2 — that side effect must never land in the worktree or next to the caller's corpus.
  scratch="$(mktemp -d)"
  trap 'rm -rf "$scratch"' EXIT
  files="$scratch/files.txt"
  : > "$files"
  # Save paths are canonicalised to ABSOLUTE here: each read runs in its own scratch cwd (see the
  # worker), so a relative path like the self-test's `the earlier test evidence` would stop resolving after
  # the cd and the oracle would exit 2, which the coverage report would (correctly) call unreadable.
  abspath() { local p; p="$(realpath -m -- "$1" 2>/dev/null)" || p=""; [ -n "$p" ] && printf '%s\n' "$p" || printf '%s/%s\n' "$WT" "${1#./}"; }
  n_saves=0
  for s in "${saves[@]}"; do
    if [ -d "$s" ]; then
      while IFS= read -r -d '' g; do abspath "$g" >> "$files"; done \
        < <(find "$s" -name '*.game' -print0 | sort -z)
    else
      abspath "$s" >> "$files"
    fi
  done
  n_saves=$(wc -l < "$files")
  [ "$n_saves" -gt 0 ] || { echo "ERROR: no .game files under: ${saves[*]}" >&2; exit 2; }

  # ---- phase 1: per-save stream UIDs, each in its own oracle process, bounded and concurrent ----
  # Why per-process: --stream-uids deserializes the save with a real ObjectInputStream (that is how
  # it drives Class.forName/resolveClass and records each descriptor's stream UID), so its cost is
  # a full object-graph read and is UNBOUNDED for a large modern save. One pathological 72-save
  # corpus run did not finish in >400 s; a jstack of the hung JVM showed main RUNNABLE inside
  # ObjectInputStream.readSerialData. So every save gets a hard `timeout`, `--jobs` run at once, and
  # the wall-clock `--budget` stops us launching more once it is spent. Anything not read is
  # named explicitly — a skip is never a silent pass (AGENTS.md Fail-Fast).
  #
  # The budget is TOTAL: a reserve is kept for the (small, bounded) scope phase, and each job's own
  # timeout is clamped to the time left before the phase-1 deadline, so phase 1 can finish late by
  # at most one in-flight read and the whole run lands inside `budget` bar scheduling jitter.
  start="$(date +%s)"
  # Budget split: keep a reserve for the (small, bounded) scope phase and a SIGKILL grace, both
  # scaled to the budget so a small --budget still leaves room for at least one read attempt.
  scope_reserve=$(( budget / 5 )); [ "$scope_reserve" -lt 5 ] && scope_reserve=5
  [ "$scope_reserve" -gt "$(( budget / 3 ))" ] && scope_reserve=$(( budget / 3 ))
  [ "$scope_reserve" -lt 1 ] && scope_reserve=1
  # SIGKILL grace: `timeout` alone only sends SIGTERM and then WAITS for the child to exit. A JVM
  # mid-deserialization can fail to act on SIGTERM, so the worker never returned and the phase hung
  # past its per-save timeout (observed: save-timeout=3s, workers still alive at 50s). `timeout -k`
  # escalates to SIGKILL after the grace, which is what actually guarantees the bound.
  kill_grace=$(( budget / 10 )); [ "$kill_grace" -lt 2 ] && kill_grace=2; [ "$kill_grace" -gt 10 ] && kill_grace=10
  deadline=$((start + budget - scope_reserve - kill_grace))
  [ "$deadline" -le "$start" ] && deadline=$((start + 1))
  idx=0
  while IFS= read -r g; do
    printf '%s\t%s\n' "$idx" "$g" > "$scratch/job.$idx"
    idx=$((idx + 1))
  done < "$files"

  mkdir -p "$scratch/rows" "$scratch/st" "$scratch/wd"
  cat > "$scratch/worker.sh" <<'WORKER'
#!/usr/bin/env bash
# One bounded oracle read of one save. Writes status (0 ok | 124 timeout | 70 unreadable |
# 71 budget-not-attempted) to $S/st/$i and rows to $S/rows/$i. Never touches the caller's tree.
set -uo pipefail
job="$1"; i="${job##*.}"; S="$WORK"
IFS=$'\t' read -r _ save < "$job"
now="$(date +%s)"
if [ "$now" -ge "$DEADLINE" ]; then echo 71 > "$S/st/$i"; exit 0; fi
# Clamp this read's timeout to the time left before the deadline: an in-flight read can then never
# push the phase past DEADLINE, so the total run stays inside the caller's budget.
jt="$SAVE_TIMEOUT"; left=$((DEADLINE - now)); [ "$jt" -gt "$left" ] && jt="$left"
[ "$jt" -lt 1 ] && jt=1
# Each read gets its OWN cwd: deserializing a save can run CardRepository.<clinit>, which mkdirs
# ./db and opens ./db/cards.h2. Sharing one cwd made all --jobs processes contend for the same H2
# file (org.h2.jdbc.JdbcSQLException: "Lock file recently modified"), which both slowed the phase
# and spammed the report. A per-read cwd keeps the DB private, and it lives under the scratch dir
# so no ./db is ever created in the worktree or beside the caller's corpus.
mkdir -p "$S/wd/$i"
rc=0
# `timeout -k $KILL_GRACE`: SIGTERM, then SIGKILL after the grace. The plain form waits forever for
# a child that ignores SIGTERM (a JVM mid-deserialization does), which is how the bound was lost.
( cd "$S/wd/$i" && timeout -k "$KILL_GRACE" "$jt" java -cp "$CP:$ORACLE_BIN" SerialSaveScope \
    --stream-uids "$save" --out "$S/rows/$i" ) >"$S/out.$i" 2>"$S/err.$i" || rc=$?
# A read that died from the timeout is 124 (SIGTERM) or 137 (SIGKILL from `timeout -k`); any other
# non-zero is a hard oracle failure. Both are reported, never assumed to be "no classes".
case "$rc" in
  0) : ;;
  124|137) rc=124 ;;
  *) rc=70 ;;
esac
echo "$rc" > "$S/st/$i"
WORKER
  export WORK="$scratch" CP ORACLE_BIN="$WT/$ORACLE_BIN" SAVE_TIMEOUT="$save_timeout" DEADLINE="$deadline" KILL_GRACE="$kill_grace"

  echo "reference saves: $n_saves file(s) across ${#saves[@]} path(s) (jobs=$jobs, per-save timeout=${save_timeout}s, budget=${budget}s)"
  ls "$scratch"/job.* | xargs -P "$jobs" -I{} bash "$scratch/worker.sh" {}
  stream_wall=$(($(date +%s) - start))

  # Deterministic merge: iterate the SORTED file list, first occurrence of a class wins, so the
  # result does not depend on completion order (the old single-process read was sorted too).
  # NOTE: plain `if`, not `[ -s ... ] && cat ...`. Under `set -e`+`pipefail` the && form leaves the
  # pipeline's status at 1 when the last file is empty and kills the whole run with no output —
  # the exact silent-failure class this gate exists to prevent (same defect as 2026-10-02).
  : > "$scratch/stream"
  for ((i = 0; i < idx; i++)); do
    if [ -s "$scratch/rows/$i" ]; then cat "$scratch/rows/$i"; fi
  done | awk -F'\t' 'NF>=2 && !seen[$1]++' > "$scratch/stream"

  # ---- phase 2: resolve each carried class's UID on THIS build -----------------------------------
  build="$scratch/build"
  remaining=$((budget - stream_wall))
  [ "$remaining" -lt 1 ] && remaining=1
  if [ -s "$scratch/stream" ]; then
    mkdir -p "$scratch/scope-wd"
    ( cd "$scratch/scope-wd" && timeout "$remaining" java -cp "$CP:$ORACLE_BIN" SerialSaveScope \
        --reachable --seed "$scratch/stream" --out "$build" ) >"$scratch/scope.out" 2>"$scratch/scope.err" || {
        echo "ERROR: scope resolution did not complete within the remaining budget (${remaining}s)." >&2
        sed 's/^/    /' "$scratch/scope.err" >&2
        exit 2
      }
  else
    : > "$build"
  fi

  n_stream=$(wc -l < "$scratch/stream")
  ok=0; skipped=0
  : > "$scratch/skip.log"
  for ((i = 0; i < idx; i++)); do
    rc="$(cat "$scratch/st/$i" 2>/dev/null || echo 70)"
    save="$(cut -f2 "$scratch/job.$i")"
    case "$rc" in
      0) ok=$((ok + 1)) ;;
      124) skipped=$((skipped + 1)); printf '  UNREADABLE (timeout %ss)  %s\n' "$save_timeout" "$save" >> "$scratch/skip.log" ;;
      71) skipped=$((skipped + 1)); printf '  NOT ATTEMPTED (budget %ss exhausted)  %s\n' "$budget" "$save" >> "$scratch/skip.log" ;;
      *) skipped=$((skipped + 1)); printf '  UNREADABLE (oracle exit %s)  %s\n' "$rc" "$save" >> "$scratch/skip.log" ;;
    esac
  done

  total_wall=$(($(date +%s) - start))
  echo "  stream phase: ${stream_wall}s (${ok}/${n_saves} saves read); $n_stream class(es) carried"

  bad=0
  while IFS=$'\t' read -r cls stream_uid; do
    [ -n "$cls" ] || continue
    build_uid="$(awk -F'\t' -v c="$cls" '$1==c{print $2; exit}' "$build")"
    if [ -z "$build_uid" ]; then
      echo "  MISSING  $cls  save=$stream_uid  (not on this build's classpath)"
      bad=$((bad+1))
    elif [ "$build_uid" != "$stream_uid" ]; then
      echo "  CHANGED  $cls  save=$stream_uid  build=$build_uid"
      bad=$((bad+1))
    fi
  done < "$scratch/stream"

  # The skip list is ALWAYS printed (never only under --debug): a corpus audit whose coverage is
  # not stated is exactly the silent pass this gate exists to prevent.
  if [ "$skipped" -gt 0 ]; then
    echo "NOTE: $skipped of $n_saves save(s) could NOT be read — their classes are NOT evidence:" >&2
    sed 's/^/  /' "$scratch/skip.log" >&2
  fi
  # Zero coverage is not a pass. If not one save could be read, the audit proved nothing and must
  # say so: "none of the 0 UIDs changed" would be a green light with no evidence behind it.
  if [ "$ok" -eq 0 ]; then
    echo "ERROR: 0 of $n_saves save(s) could be read — nothing was audited, so this is NOT a pass." >&2
    echo "  Raise --save-timeout (now ${save_timeout}s) and/or --budget (now ${budget}s)." >&2
    exit 1
  fi
  if [ "$bad" -gt 0 ]; then
    echo "FAIL --affected-saves: $bad class(es) this save depends on would change on this build." >&2
    echo "  (read $ok/$n_saves saves in ${total_wall}s; $skipped skipped)" >&2
    exit 1
  fi
  echo "PASS --affected-saves: none of the $n_stream UIDs these saves depend on change on this build"
  echo "  read $ok/$n_saves saves in ${total_wall}s (budget ${budget}s, jobs ${jobs}); $skipped skipped"
  exit 0
fi

# --------------------------------------------------------------------------- enforce
[ -f "$MANIFEST" ] || { echo "ERROR: $MANIFEST missing — generate with --write-manifest" >&2; exit 2; }

computed="$(mktemp)"; declared="$(mktemp)"; err="$(mktemp)"
trap 'rm -f "$computed" "$declared" "$err"' EXIT
# Seed the walk with the manifest itself: the check asks "does the scope my manifest claims to
# cover still compute to the same UIDs?", so the seed must be exactly those classes. A class that
# vanished from the walk then shows up as a stale row, not as a silent scope reduction.
ORACLE_ERR="$err" run_oracle --reachable --seed "$MANIFEST" --out "$computed"
grep -vE '^#|^$' "$MANIFEST" | awk -F'\t' 'NF>=2{print $1"\t"$2}' | sort > "$declared"
sort -o "$computed" "$computed"

changed="$(awk -F'\t' 'NR==FNR{d[$1]=$2; next} ($1 in d) && d[$1]!=$2 {printf "  %s declared=%s computed=%s\n", $1, d[$1], $2; delete d[$1]}
  END{ }' "$declared" "$computed")"
appeared="$(comm -23 <(cut -f1 "$computed") <(cut -f1 "$declared"))"
gone="$(comm -13 <(cut -f1 "$computed") <(cut -f1 "$declared"))"
stale="$(grep '^NOTE: seed class not on this classpath' "$err" | sed 's/.*: //' || true)"

fail=0
if [ -n "$changed" ]; then
  echo "FAIL: serialVersionUID DRIFT — this build computes a different UID than the manifest:" >&2
  echo "$changed" >&2
  echo "  A save written by an earlier build carries 'declared'; this build writes 'computed'." >&2
  echo "  Fix: re-pin deliberately (a format break) or restore the pre-drift class shape." >&2
  fail=1
fi
if [ -n "$appeared" ]; then
  echo "FAIL: NEW save-surface classes with no manifest row (silent additions are not accepted):" >&2
  echo "$appeared" | sed 's/^/  /' >&2
  echo "  Fix: give each a deliberate serialVersionUID, then re-run --write-manifest." >&2
  fail=1
fi
if [ -n "$gone" ]; then
  echo "FAIL: STALE manifest rows — these classes are no longer in the save scope:" >&2
  echo "$gone" | sed 's/^/  /' >&2
  echo "  A rename/removal silently drops coverage; re-run --write-manifest to ratify." >&2
  fail=1
fi
if [ -n "$stale" ]; then
  echo "FAIL: manifest rows that no longer resolve on the classpath:" >&2
  echo "$stale" | sed 's/^/  /' >&2
  fail=1
fi

if [ "$fail" = 1 ]; then
  # --debug: show the scope diagnostics even on a failure, so the refusal list is never hidden.
  [ "$debug" = 1 ] && grep -E '^(WARN|NOTE|SKIP):' "$err" >&2
  exit 1
fi
echo "PASS manifest: $(wc -l < "$computed") save-surface classes, all UIDs match $MANIFEST"
# The refusal list is printed on EVERY run, not only under --debug: a scope limitation the reader
# is not told about is the silent pass this whole gate exists to prevent.
grep -E '^(WARN|NOTE|SKIP):' "$err" >&2 || true
if [ "$debug" = 1 ] && [ -s "$err" ]; then
  echo "--- oracle stderr (--debug) ---" >&2
  cat "$err" >&2
fi
exit 0
