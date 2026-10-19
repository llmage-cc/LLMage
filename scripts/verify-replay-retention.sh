#!/usr/bin/env bash
# Verify scripts/replay-retention.sh end to end on LOCAL synthetic fixtures.
# No server, no docker, no network — the harness mktemp's its own tree under
# $REPLAY_RETENTION_VERIFY_ROOT, else $TMPDIR, else /tmp; it never reads or
# writes a real server corpus.
#
#   bash scripts/verify-replay-retention.sh
#
# What it proves, in order:
#   [1]  dry-run on a full fixture: exact decision table (DELETE/KEEP/SKIP
#        rows with reasons; age-cap, under-caps, orphan-sidecar, symlink,
#        not-a-regular-file, not-a-replay-name), exact SUMMARY key=value
#        values, and a byte-identical on-disk snapshot before/after;
#   [1b] byte-cap ordering on the same fixture (tighter cap): oldest non-age
#        replays pruned oldest-first as size-cap, the fresh ones vetoed by the
#        120 s in-flight guard, cap-unmet=yes when the cap is unreachable;
#   [2]  --apply: exactly the predicted files gone, survivors byte-identical,
#        non-replay / non-regular / orphan-sidecar entries untouched;
#   [3]  --apply again on the pruned tree: idempotent, exit 0, 0 deletions;
#   [4]  flag/argument discipline: missing dir, no policy, unknown flag, bad or
#        overflowing numeric flags, conflicting --apply/--dry-run, stray
#        positional, valueless flag — all exit 1 with FATAL; empty dir exits 0;
#   [5]  safety rails: just-written pairs survive even an impossible cap,
#        symlinks are never deleted;
#   [6]  keep-floor semantics: with 5 old pairs and --keep 2 the two newest are
#        KEEP keep-floor, the rest DELETE age-cap; apply matches dry-run bytes.
#
# Fixture [1]: u1..u9 are .game+.json pairs, u10 an orphan .game (legacy save),
# u11 an orphan .sidecar .json, plus non-uuid names, a directory and symlinks
# with uuid-shaped names. Byte sizes (game+json): u1 1020, u2 820, u3 620,
# u4 520, u5 420, u6 320, u7..u9 220, u10 150 — total 4530.
# --max-bytes 3000 --max-age-days 30 --keep 2: age deletes u3-u6+u10 (2030 B),
# projected 2500 <= 3000, no size deletions.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/replay-retention.sh"
[ -f "$SCRIPT" ] || { echo "FAIL: script not found: $SCRIPT"; exit 1; }
command -v md5sum >/dev/null || { echo "FAIL: md5sum missing"; exit 1; }

FIXTURE_ROOT="${REPLAY_RETENTION_VERIFY_ROOT:-${TMPDIR:-/tmp}}"
[ -d "$FIXTURE_ROOT" ] || FIXTURE_ROOT=/tmp
WORK="$(mktemp -d "$FIXTURE_ROOT/verify-replay-retention.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

failures=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; if [ -n "${2:-}" ]; then printf '        %s\n' "$2"; fi; failures=$((failures + 1)); }

# Canonical uuid factory: deterministic, valid 8-4-4-4-12 hex shape.
u() { printf '00000000-0000-4000-8000-%012d' "$1"; }
set_age() { touch -d "@$(( $(date +%s) - $2 ))" -- "$1"; }

make_pair() { # dir uuid secs_ago game_bytes [json_bytes default 120]
    local dir="$1" id="$2" ago="$3" gb="$4" jb="${5:-120}"
    head -c "$gb" /dev/urandom > "$dir/$id.game"
    # sidecar-shaped JSON padded with spaces to an exact byte size
    printf '{"gameId":"%s","turns":7}' "$id" > "$dir/$id.json.tmp"
    head -c "$jb" "$dir/$id.json.tmp" > "$dir/$id.json"
    rm -f "$dir/$id.json.tmp"
    local cur; cur=$(stat -c %s "$dir/$id.json")
    if [ "$cur" -lt "$jb" ]; then head -c $(( jb - cur )) /dev/zero | tr '\0' ' ' >> "$dir/$id.json"; fi
    set_age "$dir/$id.game" "$ago"
    set_age "$dir/$id.json" "$ago"
}

# Full-listing snapshot: type, size, mtime, path, md5(regular files only).
snapshot() { # dir
    (
        cd "$1" || exit 1
        find . -mindepth 1 -printf '%P\t%y\t%s\t%T@\n' | LC_ALL=C sort \
        | while IFS=$'\t' read -r p y s t; do
            m=""
            if [ "$y" = "f" ]; then m="$(md5sum -- "$p" | cut -d' ' -f1)"; fi
            printf '%s\t%s\t%s\t%s\t%s\n' "$p" "$y" "$s" "$t" "$m"
        done
    )
}
rows() { grep -v '^#' "$1" | grep -v '^SUMMARY' || true; }
row_for() { rows "$1" | awk -F'\t' -v id="$2" '$2==id {print $1"\t"$6; exit}'; }
count_action() { rows "$1" | awk -F'\t' -v a="$2" '$1==a {n++} END{print n+0}'; }
sum_kv() { # file key -> exact value of key in the SUMMARY line
    grep '^SUMMARY' "$1" | tr ' ' '\n' | awk -F= -v k="$2" '$1==k {print $2; found=1; exit} END{if (!found) print "__MISSING__"}'
}
check_sum() { # file key=value
    local key="${2%%=*}" want="${2#*=}" got
    got="$(sum_kv "$1" "$key")"
    [ "$got" = "$want" ] && pass "summary: $2" || fail "summary: $2" "got '$key=$got' | $(grep '^SUMMARY' "$1")"
}
check_row() { # file id want_action want_reason label
    local got want
    got="$(row_for "$1" "$2")"
    printf -v want '%s\t%s' "$3" "$4"
    [ "$got" = "$want" ] && pass "row: $5 -> $3 ($4)" \
        || fail "row: $5" "want '$3	$4' got '$got'"
}

echo "── replay-retention.sh fixture verification (local only, no server contact) ──"
echo "   script:  $SCRIPT"
echo "   fixture: $WORK"
echo

# ===================================================== [1] dry-run table ===
echo "[1] dry-run, full fixture (--max-bytes 3000 --max-age-days 30 --keep 2)"
DIR="$WORK/saved"; mkdir -p "$DIR"
# fresh (written now — 120 s guard territory)
make_pair "$DIR" "$(u 1)" 10 900
make_pair "$DIR" "$(u 2)" 30 700
# old pairs (age-cap targets)
make_pair "$DIR" "$(u 3)" $((40*86400)) 500
make_pair "$DIR" "$(u 4)" $((50*86400)) 400
make_pair "$DIR" "$(u 5)" $((60*86400)) 300
make_pair "$DIR" "$(u 6)" $((70*86400)) 200
# young pairs (under both caps)
make_pair "$DIR" "$(u 7)" $((1*86400)) 100
make_pair "$DIR" "$(u 8)" $((2*86400)) 100
make_pair "$DIR" "$(u 9)" $((3*86400)) 100
# orphan .game, old: a legacy save — prunable
head -c 150 /dev/urandom > "$DIR/$(u 10).game"
set_age "$DIR/$(u 10).game" $((45*86400))
# orphan .json, old: sidecar without a save — NOT prunable
printf '{}\n' > "$DIR/$(u 11).json"
set_age "$DIR/$(u 11).json" $((80*86400))
# non-replay names: never touched
printf 'junk' > "$DIR/not-a-uuid.game"
printf 'junk' > "$DIR/readme.txt"
printf 'junk' > "$DIR/$(u 12).jsonx"
# non-regular entries with uuid-shaped names: never deleted
mkdir -p "$DIR/$(u 13).game"
ln -s /nonexistent-replay "$DIR/$(u 14).game"
ln -s "$DIR/$(u 3).json" "$DIR/$(u 15).json"

SNAP_BEFORE="$WORK/snap-before.txt"; snapshot "$DIR" > "$SNAP_BEFORE"

OUT="$WORK/run1.out"
set +e; bash "$SCRIPT" "$DIR" --max-bytes 3000 --max-age-days 30 --keep 2 > "$OUT" 2>"$WORK/run1.err"; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit code $rc" "$(cat "$WORK/run1.err")"

check_row "$OUT" "$(u 6)"  DELETE age-cap  "u6 (70d)"
check_row "$OUT" "$(u 5)"  DELETE age-cap  "u5 (60d)"
check_row "$OUT" "$(u 4)"  DELETE age-cap  "u4 (50d)"
check_row "$OUT" "$(u 10)" DELETE age-cap  "u10 (45d, orphan .game)"
check_row "$OUT" "$(u 3)"  DELETE age-cap  "u3 (40d)"
check_row "$OUT" "$(u 9)"  KEEP under-caps "u9 (3d)"
check_row "$OUT" "$(u 8)"  KEEP under-caps "u8 (2d)"
check_row "$OUT" "$(u 7)"  KEEP under-caps "u7 (1d)"
check_row "$OUT" "$(u 2)"  KEEP under-caps "u2 (30s)"
check_row "$OUT" "$(u 1)"  KEEP under-caps "u1 (10s)"
check_row "$OUT" "$(u 11).json"    SKIP orphan-sidecar     "u11 orphan sidecar"
check_row "$OUT" "$(u 12).jsonx"   SKIP not-a-replay-name  "u12 .jsonx"
check_row "$OUT" "$(u 13).game"    SKIP not-a-regular-file "u13 directory"
check_row "$OUT" "$(u 14).game"    SKIP symlink            "u14 dangling symlink"
check_row "$OUT" "$(u 15).json"    SKIP symlink            "u15 symlink to sidecar"
check_row "$OUT" "not-a-uuid.game" SKIP not-a-replay-name  "non-uuid .game"
check_row "$OUT" "readme.txt"      SKIP not-a-replay-name  "readme.txt"

[ "$(count_action "$OUT" DELETE)" -eq 5 ] && pass "exactly 5 DELETE rows" \
    || fail "DELETE row count" "got $(count_action "$OUT" DELETE)"
[ "$(count_action "$OUT" KEEP)" -eq 5 ] && pass "exactly 5 KEEP rows" \
    || fail "KEEP row count" "got $(count_action "$OUT" KEEP)"
for kv in "mode=dry-run" "checked-games=10" "checked-files=26" "skipped-files=7" \
          "total-bytes=4530" "would-delete-games=5" "would-delete-bytes=2030" \
          "deleted-games=0" "deleted-bytes=0" "kept-games=5" \
          "projected-bytes=2500" "keep-floor-protected=no" "cap-unmet=no"; do
    check_sum "$OUT" "$kv"
done

SNAP_AFTER="$WORK/snap-after.txt"; snapshot "$DIR" > "$SNAP_AFTER"
cmp -s "$SNAP_BEFORE" "$SNAP_AFTER" && pass "dry-run: on-disk snapshot identical (types, sizes, mtimes, md5s)" \
    || fail "dry-run modified the tree" "$(diff "$SNAP_BEFORE" "$SNAP_AFTER" | head -10)"

# ================================================= [1b] byte-cap ordering ===
echo
echo "[1b] byte-cap ordering (same fixture, --max-bytes 1600 --max-age-days 30 --keep 2)"
OUT="$WORK/run1b.out"
set +e; bash "$SCRIPT" "$DIR" --max-bytes 1600 --max-age-days 30 --keep 2 > "$OUT" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit code $rc" "$(cat "$OUT")"
check_row "$OUT" "$(u 9)" DELETE size-cap "u9 (oldest survivor)"
check_row "$OUT" "$(u 8)" DELETE size-cap "u8"
check_row "$OUT" "$(u 7)" DELETE size-cap "u7"
check_row "$OUT" "$(u 2)" KEEP in-flight-guard "u2 (fresh, guard vetoes)"
check_row "$OUT" "$(u 1)" KEEP in-flight-guard "u1 (fresh, guard vetoes)"
for kv in "would-delete-games=8" "would-delete-bytes=2690" "projected-bytes=1840" \
          "cap-unmet=yes" "deleted-games=0"; do
    check_sum "$OUT" "$kv"
done
snapshot "$DIR" > "$WORK/snap-1b.txt"
cmp -s "$SNAP_BEFORE" "$WORK/snap-1b.txt" && pass "still nothing on disk (dry-run)" \
    || fail "dry-run modified the tree"

# ============================================================= [2] apply ===
echo
echo "[2] --apply (policy from [1]: --max-bytes 3000 --max-age-days 30 --keep 2)"
OUT="$WORK/run2.out"
set +e; bash "$SCRIPT" "$DIR" --max-bytes 3000 --max-age-days 30 --keep 2 --apply > "$OUT" 2>"$WORK/run2.err"; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit code $rc" "$(cat "$WORK/run2.err")"
for kv in "mode=apply" "deleted-games=5" "deleted-bytes=2030" "projected-bytes=2500"; do check_sum "$OUT" "$kv"; done

bad=0
for i in 3 4 5 6; do
    id="$(u "$i")"
    [ -e "$DIR/$id.game" ] && { bad=1; fail "still present: $id.game"; }
    [ -e "$DIR/$id.json" ] && { bad=1; fail "still present: $id.json"; }
done
[ -e "$DIR/$(u 10).game" ] && { bad=1; fail "still present: u10.game"; }
[ "$bad" -eq 0 ] && pass "predicted DELETE pairs fully gone (u3-u6, orphan u10)"

bad=0
for i in 1 2 7 8 9; do
    id="$(u "$i")"
    [ -e "$DIR/$id.game" ] || { bad=1; fail "missing survivor: $id.game"; }
    [ -e "$DIR/$id.json" ] || { bad=1; fail "missing survivor sidecar: $id.json"; }
done
[ "$bad" -eq 0 ] && pass "predicted KEEP pairs intact with sidecars"

ok=1
for f in "not-a-uuid.game" "readme.txt" "$(u 11).json" "$(u 12).jsonx" "$(u 13).game"; do
    [ -e "$DIR/$f" ] || { ok=0; fail "clobbered: $f"; }
done
# u14/u15 are symlinks: assert the LINK still exists (u15 now dangles because
# its target, u3's sidecar, was legitimately pruned — the link itself must
# survive, since retention never deletes symlinks).
[ -L "$DIR/$(u 14).game" ] || { ok=0; fail "clobbered symlink: u14.game"; }
[ -L "$DIR/$(u 15).json" ] || { ok=0; fail "clobbered symlink: u15.json"; }
[ "$ok" -eq 1 ] && pass "non-replay / non-regular / orphan-sidecar entries untouched"

ok=1
for i in 1 2 7 8 9; do
    id="$(u "$i")"
    for ext in game json; do
        before="$(awk -F'\t' -v n="$id.$ext" '$1==n {print $5}' "$SNAP_BEFORE")"
        now="$(md5sum "$DIR/$id.$ext" | cut -d' ' -f1)"
        [ "$before" = "$now" ] || { ok=0; fail "survivor content changed: $id.$ext"; }
    done
done
[ "$ok" -eq 1 ] && pass "survivor contents byte-identical (md5)"

# ======================================================= [3] idempotency ===
echo
echo "[3] --apply again on the pruned tree (idempotent)"
OUT="$WORK/run3.out"
set +e; bash "$SCRIPT" "$DIR" --max-bytes 3000 --max-age-days 30 --keep 2 --apply > "$OUT" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit code $rc" "$(cat "$OUT")"
check_sum "$OUT" "mode=apply"
check_sum "$OUT" "deleted-games=0"
check_sum "$OUT" "would-delete-games=0"

# ==================================================== [4] flag discipline ===
echo
echo "[4] flag / argument discipline"
try_fatal() { # label args...
    local label="$1"; shift
    set +e; bash "$SCRIPT" "$@" >"$WORK/f.out" 2>"$WORK/f.err"; local rc=$?; set -e
    if [ "$rc" -eq 1 ] && grep -q '^FATAL' "$WORK/f.err"; then pass "$label -> exit 1 + FATAL"
    else fail "$label" "rc=$rc stderr=$(head -1 "$WORK/f.err")"; fi
}
try_fatal "missing dir"      "$WORK/nope" --max-age-days 1
try_fatal "no policy"        "$DIR"
try_fatal "unknown flag"     "$DIR" --max-bytes 1M --wat
try_fatal "bad size unit"    "$DIR" --max-bytes 2Q
try_fatal "size overflow"    "$DIR" --max-bytes 99999999999999999999
try_fatal "negative days"    "$DIR" --max-age-days -1
try_fatal "non-numeric days" "$DIR" --max-age-days 7d
try_fatal "non-numeric keep" "$DIR" --max-age-days 1 --keep two
try_fatal "apply+dry-run"    "$DIR" --max-age-days 1 --apply --dry-run
try_fatal "extra positional" "$DIR" saved2 --max-age-days 1
try_fatal "valueless flag"   "$DIR" --max-bytes --apply

EMPTY="$WORK/empty"; mkdir -p "$EMPTY"
OUT="$WORK/run4.out"
set +e; bash "$SCRIPT" "$EMPTY" --max-age-days 1 --apply > "$OUT" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "empty dir -> exit 0" || fail "empty dir exit $rc" "$(cat "$OUT")"
check_sum "$OUT" "checked-games=0"

# ======================================================= [5] safety rails ===
echo
echo "[5] safety rails (fresh fixture: in-flight guard, unreachable cap, symlink)"
DIR2="$WORK/saved2"; mkdir -p "$DIR2"
make_pair "$DIR2" "$(u 21)" 1 500
make_pair "$DIR2" "$(u 22)" 2 5000
ln -s /nonexistent-replay "$DIR2/$(u 23).game"

OUT="$WORK/run5.out"
set +e; bash "$SCRIPT" "$DIR2" --max-bytes 1 --max-age-days 0 --apply > "$OUT" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit $rc" "$(cat "$OUT")"
check_sum "$OUT" "deleted-games=0"
check_sum "$OUT" "cap-unmet=yes"
check_row "$OUT" "$(u 21)" KEEP in-flight-guard "u21 (fresh)"
check_row "$OUT" "$(u 22)" KEEP in-flight-guard "u22 (fresh)"
check_row "$OUT" "$(u 23).game" SKIP symlink "u23 symlink"
[ -e "$DIR2/$(u 21).game" ] && [ -e "$DIR2/$(u 22).game" ] && [ -L "$DIR2/$(u 23).game" ] \
    && pass "fresh pairs + symlink all still on disk" || fail "guard/symlink rail failed"

# ==================================================== [5b] irregular pairs ===
echo
echo "[5b] irregular pairs: regular .game + symlinked .json and vice versa"
DIR4="$WORK/saved4"; mkdir -p "$DIR4"
make_pair "$DIR4" "$(u 51)" 1 100                      # regular pair, fresh
set_age "$DIR4/$(u 51).game" $((90*86400))             # make it old
set_age "$DIR4/$(u 51).json" $((90*86400))
ln -sf /nonexistent "$DIR4/$(u 51).json.new"           # (scratch link, unused)
rm -f "$DIR4/$(u 51).json.new"
# u51: .game regular.json replaced by a symlink -> neither may be deleted
rm -f "$DIR4/$(u 51).json"
ln -s /nonexistent "$DIR4/$(u 51).json"
# u52: .json regular.game a symlink -> neither may be deleted
printf '{}\n' > "$DIR4/$(u 52).json"
set_age "$DIR4/$(u 52).json" $((90*86400))
ln -s /nonexistent "$DIR4/$(u 52).game"

OUT="$WORK/run5b.out"
set +e; bash "$SCRIPT" "$DIR4" --max-age-days 1 --apply > "$OUT" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "exit 0" || fail "exit $rc" "$(cat "$OUT")"
check_row "$OUT" "$(u 51).game" SKIP sidecar-not-regular "u51 .game (old, sidecar is a symlink)"
check_row "$OUT" "$(u 51).json" SKIP symlink             "u51 .json symlink"
check_row "$OUT" "$(u 52).game" SKIP symlink             "u52 .game symlink"
check_row "$OUT" "$(u 52).json" SKIP paired-game-not-regular "u52 .json (old, game is a symlink)"
check_sum "$OUT" "deleted-games=0"
[ -e "$DIR4/$(u 51).game" ] && [ -L "$DIR4/$(u 51).json" ] && [ -L "$DIR4/$(u 52).game" ] && [ -e "$DIR4/$(u 52).json" ] \
    && pass "irregular pairs left untouched (no one-sided deletes)" || fail "irregular pair was modified"

# ========================================================= [6] keep-floor ===
echo
echo "[6] keep-floor semantics (5 old pairs 37-41d, --max-age-days 30 --keep 2)"
DIR3="$WORK/saved3"; mkdir -p "$DIR3"
for i in 41 42 43 44 45; do
    make_pair "$DIR3" "$(u "$i")" $(((81 - i)*86400)) 200   # u41 40d (oldest) .. u45 36d (newest)
done

OUT5="$WORK/run6-dry.out"
set +e; bash "$SCRIPT" "$DIR3" --max-age-days 30 --keep 2 > "$OUT5" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "dry-run exit 0" || fail "dry-run exit $rc" "$(cat "$OUT5")"
check_row "$OUT5" "$(u 45)" KEEP keep-floor "u45 (newest, floored)"
check_row "$OUT5" "$(u 44)" KEEP keep-floor "u44 (2nd newest, floored)"
check_row "$OUT5" "$(u 43)" DELETE age-cap "u43"
check_row "$OUT5" "$(u 42)" DELETE age-cap "u42"
check_row "$OUT5" "$(u 41)" DELETE age-cap "u41 (oldest)"
check_sum "$OUT5" "would-delete-games=3"
check_sum "$OUT5" "would-delete-bytes=960"
check_sum "$OUT5" "keep-floor-protected=yes"

OUT6="$WORK/run6-apply.out"
set +e; bash "$SCRIPT" "$DIR3" --max-age-days 30 --keep 2 --apply > "$OUT6" 2>&1; rc=$?; set -e
[ "$rc" -eq 0 ] && pass "apply exit 0" || fail "apply exit $rc" "$(cat "$OUT6")"
check_sum "$OUT6" "deleted-games=3"
check_sum "$OUT6" "deleted-bytes=960"
[ -e "$DIR3/$(u 45).game" ] && [ -e "$DIR3/$(u 45).json" ] && [ -e "$DIR3/$(u 44).game" ] \
    && pass "floored pairs survived" || fail "floored pairs wrong"
[ ! -e "$DIR3/$(u 41).game" ] && [ ! -e "$DIR3/$(u 41).json" ] && [ ! -e "$DIR3/$(u 43).game" ] \
    && pass "oldest pairs deleted together" || fail "oldest pair state wrong"

# =============================================================== verdict ===
echo
if [ "$failures" -eq 0 ]; then
    echo "RESULT: PASS"
    exit 0
fi
echo "RESULT: FAIL ($failures)"
exit 1
