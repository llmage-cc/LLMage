#!/usr/bin/env bash
# Replay retention — prune <uuid>.game / <uuid>.json replay pairs in a saved/
# directory by policy (max total bytes, max age), OLDEST FIRST (mtime).
# DRY-RUN BY DEFAULT: prints a decision table and deletes nothing; --apply
# deletes.
#
# WHY: the
# public stack's saved/ grows unbounded — nothing rotates it
# (Main.deleteSavedGames() is only called from Mage.Tests) and multi-MB .game
# saves accumulate. This is the operational half: an explicit byte budget plus
# a max-age cap, run by hand or from a timer.
#
# DECISIONS (deliberate; the invariant is "pairs stay consistent"):
#   * The unit is a REPLAY = one UUID: <uuid>.game (the save) and <uuid>.json
#     (the ReplayMetadata sidecar — display metadata only). When a replay is
#     deleted, BOTH files go together; never one-sided.
#   * Deletion order inside a pair is .json FIRST, then .game: the sidecar is
#     display-only, so the replay stays loadable until the last possible
#     moment for a browser that is already looking at it.
#   * Only names of the form <uuid>.game / <uuid>.json with a CANONICAL UUID
#     (8-4-4-4-12 hex) are ever considered. Anything else in the directory is
#     reported as SKIP and left alone whatever its age or size. (Stricter than
#     the proxy's java.util.UUID.fromString listing filter, which accepts
#     padded/loose forms — skipping those is the safe direction.)
#   * An orphan .game (no sidecar — a legacy save; the replay browser supports
#     those explicitly) IS prunable: it is a listed, loadable replay. An
#     orphan .json (no .game) is NOT prunable: invisible to the replay
#     browser, it is reported only.
#   * A replay whose .game or .json is not a regular file (symlink, directory)
#     is never pruned — retention only unlinks what the server itself wrote.
#   * In-flight guard: a replay modified within the last 120 s is never
#     deleted by either policy. The server writes .game then .json
#     back-to-back at game end; a prune landing inside that window would
#     silently lose a replay the server believes it saved. Deliberately not a
#     flag.
#
# LIVE-SERVER SAFETY: safe while the server runs — deletion is unlink(2) and a
# replay currently being loaded keeps its open file descriptor to completion.
# The one visible effect: a replay pruned between the browser listing and
# opening it fails that one open (refresh the list). Prefer an off-hours run so
# a user's listing cannot diverge from the directory.
#
# Usage:
#   scripts/replay-retention.sh [DIR] [--max-bytes SIZE] [--max-age-days N]
#                               [--keep N] [--apply] [--dry-run]
#
# Output (stdout): '#' comment lines, then TSV rows — action, game-id, bytes,
# mtime-epoch, mtime-utc, reason — then one machine-parseable line starting
# with 'SUMMARY ' (stable key=value schema; would-delete-*/deleted-* are
# populated in both modes, deleted-* stays 0 in dry-run; SKIP rows carry the
# entry NAME in the game-id column). keep-floor-protected=yes / cap-unmet=yes
# mean the policy target was not fully reached this run: the --keep floor
# protected an eligible replay, and/or the byte cap stayed above target
# (unreachable cap, in-flight guard, or floor).
#
# Exit: 0 = completed (dry-run or applied); 1 = fatal (bad flag, missing dir,
# unlink failure). Re-running is safe: deletion is idempotent.
set -euo pipefail

readonly GUARD_SECS=120
readonly UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

usage() {
    cat <<'EOF'
Usage: scripts/replay-retention.sh [DIR] [options]

Prune replay saves (<uuid>.game + <uuid>.json) in DIR, oldest first (mtime).
DRY-RUN BY DEFAULT: prints a decision table and deletes nothing; pass --apply
to delete.

  DIR              replay directory to prune (default: saved — the relative
                   path the server resolves against its working directory).
                   Run from the server's CWD, or pass an absolute path (e.g.
                   a docker volume's .../data/saved). A missing directory is
                   a hard error; there is no fallback.

Policy (at least one required):
  --max-bytes SIZE   cap the total bytes of replay pairs; delete oldest first
                     until the projected total is <= SIZE.
                     SIZE: plain bytes, or B/K/M/G/T suffix (binary, 1024-
                     based): 1234, 512M, 2G, 1TB.
  --max-age-days N   delete replays strictly older than N days.
  --keep N           never delete the N most recent replays (floor on both
                     policies). Default: 0.

Behaviour:
  --apply            actually delete (default: dry-run).
  --dry-run          explicit dry-run; conflicts with --apply.
  -h, --help         this text.

Safety rails (always on, not configurable):
  * only canonical <uuid>.game / <uuid>.json names are ever considered; every
    other directory entry is reported as SKIP and never touched;
  * a replay's .game and its .json are deleted together, never one-sided
    (.json first, then .game);
  * a replay modified in the last 120 s is never deleted (in-flight save);
  * non-regular files (symlinks, directories) are never deleted.

Output: TSV rows (action, game-id, bytes, mtime-epoch, mtime-utc, reason),
then one machine-parseable line starting with 'SUMMARY '.
Exit: 0 = completed (dry-run or applied); 1 = fatal.
EOF
}

fatal() {
    printf 'FATAL: %s\n' "$*" >&2
    exit 1
}

# parse_bytes <raw> — echoes bytes, returns 1 on bad input. Accepts
# 1234 / 1234B / 500K / 500KB / 2M / 2G / 1T (case-insensitive, 1024-based).
parse_bytes() {
    local raw="${1^^}" num unit mult max_num
    if [[ "$raw" =~ ^([0-9]+)$ || "$raw" =~ ^([0-9]+)B$ ]]; then
        num="${BASH_REMATCH[1]}"; unit=""
    elif [[ "$raw" =~ ^([0-9]+)([KMGT])B?$ ]]; then
        num="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
    else
        return 1
    fi
    while [[ "$num" == 0* && "${#num}" -gt 1 ]]; do num="${num#0}"; done
    # Digit-string range check FIRST: bash arithmetic silently wraps past
    # signed 64-bit, so huge values must be rejected before any $((…)) sees
    # them (equal-length digit strings compare correctly lexically).
    if [ "${#num}" -gt 19 ]; then return 1; fi
    if [ "${#num}" -eq 19 ] && [[ "$num" > "9223372036854775807" ]]; then return 1; fi
    case "$unit" in
        "")  mult=1 ;;
        K)   mult=1024 ;;
        M)   mult=1048576 ;;
        G)   mult=1073741824 ;;
        T)   mult=1099511627776 ;;
    esac
    max_num=$(( 9223372036854775807 / mult ))
    if (( 10#$num > max_num )); then return 1; fi
    printf '%s' "$(( (10#$num) * mult ))"
}

# ---------------------------------------------------------------- flags ----
DIR_ARG=""
DIR_SET=0
MAX_BYTES=""
MAX_AGE_DAYS=""
KEEP=0
APPLY=""
DRY_FLAG=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage; exit 0 ;;
        --apply)
            APPLY=1; shift ;;
        --dry-run)
            DRY_FLAG=1; shift ;;
        --max-bytes)
            if [ "$#" -lt 2 ] || [[ "$2" == -* ]]; then
                fatal "--max-bytes needs a value (try --help)"
            fi
            MAX_BYTES="$(parse_bytes "$2")" \
                || fatal "cannot parse --max-bytes value: '$2' (forms: 1234, 500M, 2G, 1TB)"
            shift 2 ;;
        --max-age-days)
            if [ "$#" -lt 2 ] || [[ "$2" == -* ]]; then
                fatal "--max-age-days needs a value (try --help)"
            fi
            case "$2" in
                ''|*[!0-9]*) fatal "cannot parse --max-age-days value: '$2' (non-negative integer days)" ;;
            esac
            if [ "${#2}" -gt 9 ]; then fatal "--max-age-days value out of range: '$2'"; fi
            MAX_AGE_DAYS="$((10#$2))"; shift 2 ;;
        --keep)
            if [ "$#" -lt 2 ] || [[ "$2" == -* ]]; then
                fatal "--keep needs a value (try --help)"
            fi
            case "$2" in
                ''|*[!0-9]*) fatal "cannot parse --keep value: '$2' (non-negative integer)" ;;
            esac
            if [ "${#2}" -gt 9 ]; then fatal "--keep value out of range: '$2'"; fi
            KEEP="$((10#$2))"; shift 2 ;;
        --*)
            fatal "unknown flag: '$1' (try --help)" ;;
        *)
            if [ "$DIR_SET" -ne 0 ]; then
                fatal "unexpected extra argument: '$1' (directory already given: '$DIR_ARG')"
            fi
            DIR_ARG="$1"; DIR_SET=1; shift ;;
    esac
done

if [ -n "$APPLY" ] && [ -n "$DRY_FLAG" ]; then
    fatal "--apply and --dry-run are mutually exclusive"
fi
if [ -z "$MAX_BYTES" ] && [ -z "$MAX_AGE_DAYS" ]; then
    fatal "no policy given — pass --max-bytes and/or --max-age-days (try --help)"
fi

DIR="${DIR_ARG:-saved}"
[ -d "$DIR" ] || fatal "no such directory: '$DIR' (pass the replay dir explicitly; there is no fallback)"
DIR_ABS="$(cd "$DIR" && pwd)" || fatal "cannot resolve directory: '$DIR'"

MODE="dry-run"
if [ -n "$APPLY" ]; then MODE="apply"; fi

# ---------------------------------------------------------------- scan ----
# Per directory entry: E_KIND[<stem>|<game|json>] = reg | symlink | other
#                      E_SZ / E_MT   = size / mtime-epoch (lstat; symlinks not
#                        followed, so a symlink reports its own link mtime)
# Non-matching names, non-regular entries and orphan sidecars become SKIP rows:
#   skip_rows[] = "name<TAB>size<TAB>epoch<TAB>reason"
declare -A E_KIND=() E_SZ=() E_MT=() STEM_SEEN=()
skip_rows=()
checked_files=0

entries=()
shopt -s nullglob
entries=("$DIR_ABS"/*)
shopt -u nullglob

stat_sz_mt() { stat -c '%s %Y' -- "$1" 2>/dev/null; }

add_skip() { # name size epoch reason
    skip_rows+=( "$(printf '%s\t%s\t%s\t%s' "$1" "$2" "$3" "$4")" )
}

for p in ${entries[@]+"${entries[@]}"}; do
    name="${p##*/}"
    checked_files=$((checked_files + 1))

    ext=""
    stem=""
    if [[ "$name" =~ ^(.+)\.game$ ]]; then
        stem="${BASH_REMATCH[1]}"; ext="game"
    elif [[ "$name" =~ ^(.+)\.json$ ]]; then
        stem="${BASH_REMATCH[1]}"; ext="json"
    fi

    if [ -z "$ext" ] || [[ ! "$stem" =~ $UUID_RE ]]; then
        sz=0; mt=0
        if out="$(stat_sz_mt "$p")"; then read -r sz mt <<<"$out" || true; fi
        add_skip "$name" "$sz" "$mt" "not-a-replay-name"
        continue
    fi

    if [ -L "$p" ]; then
        kind="symlink"
    elif [ -f "$p" ]; then
        kind="reg"
    else
        kind="other"
    fi

    if out="$(stat_sz_mt "$p")"; then
        read -r sz mt <<<"$out" || true
    else
        fatal "cannot stat '$p' during scan (concurrent writer? re-run)"
    fi

    E_KIND["$stem|$ext"]="$kind"
    E_SZ["$stem|$ext"]="$sz"
    E_MT["$stem|$ext"]="$mt"
    STEM_SEEN["$stem"]=1
done

# ---------------------------------------------------------------- units ----
# A prunable unit needs a regular .game and either no .json or a regular .json.
# Everything else that matched the name shape becomes SKIP rows (no one-sided
# deletions, ever).
declare -A U_MTIME=() U_SIZE=() U_HASJSON=()
unit_stems=()

sorted_stems=()
if [ "${#STEM_SEEN[@]}" -gt 0 ]; then
    mapfile -t sorted_stems < <(printf '%s\n' "${!STEM_SEEN[@]}" | LC_ALL=C sort)
fi

for stem in ${sorted_stems[@]+"${sorted_stems[@]}"}; do
    gk="${E_KIND["$stem|game"]:-}"
    jk="${E_KIND["$stem|json"]:-}"

    if [ "$gk" = "reg" ] && { [ -z "$jk" ] || [ "$jk" = "reg" ]; }; then
        mt="${E_MT["$stem|game"]}"
        sz="${E_SZ["$stem|game"]}"
        has_json="no"
        if [ -n "$jk" ]; then
            has_json="yes"
            sz=$(( sz + E_SZ["$stem|json"] ))
            if [ "${E_MT["$stem|json"]}" -gt "$mt" ]; then
                mt="${E_MT["$stem|json"]}"
            fi
        fi
        U_MTIME["$stem"]="$mt"
        U_SIZE["$stem"]="$sz"
        U_HASJSON["$stem"]="$has_json"
        unit_stems+=("$stem")
        continue
    fi

    # --- not a prunable unit: report every offending entry, touch nothing ---
    if [ -n "$gk" ]; then
        reason="not-a-regular-file"
        if [ "$gk" = "symlink" ]; then reason="symlink"; fi
        if [ "$gk" = "reg" ] && [ -n "$jk" ]; then reason="sidecar-not-regular"; fi
        add_skip "$stem.game" "${E_SZ["$stem|game"]}" "${E_MT["$stem|game"]}" "$reason"
    fi
    if [ -n "$jk" ]; then
        reason="not-a-regular-file"
        if [ "$jk" = "symlink" ]; then reason="symlink";
        elif [ "$jk" = "reg" ]; then
            if [ -z "$gk" ]; then reason="orphan-sidecar";
            else reason="paired-game-not-regular"; fi
        fi
        add_skip "$stem.json" "${E_SZ["$stem|json"]}" "${E_MT["$stem|json"]}" "$reason"
    fi
done

# Oldest-first order (ties broken by game-id for determinism).
ordered=()
if [ "${#unit_stems[@]}" -gt 0 ]; then
    mapfile -t ordered < <(
        for s in "${unit_stems[@]}"; do
            printf '%s\t%s\n' "${U_MTIME["$s"]}" "$s"
        done | LC_ALL=C sort -t$'\t' -k1,1n -k2,2 | cut -f2
    )
fi

now="$(date +%s)"

# ------------------------------------------------------------- policies ----
declare -A SEL=() SEL_REASON=() VETO=() KEEP_PROT=()
sel_count=0
sel_bytes=0
total_bytes=0
floor_blocked="no"
cap_unmet="no"

for stem in ${ordered[@]+"${ordered[@]}"}; do
    total_bytes=$(( total_bytes + U_SIZE["$stem"] ))
done

# keep-floor: the N most recent replays are never deleted.
if [ "$KEEP" -gt 0 ] && [ "${#ordered[@]}" -gt 0 ]; then
    n=${#ordered[@]}
    i=$(( n - KEEP ))
    if [ "$i" -lt 0 ]; then i=0; fi
    while [ "$i" -lt "$n" ]; do
        KEEP_PROT["${ordered[$i]}"]=1
        i=$((i + 1))
    done
fi

guard_cutoff=$(( now - GUARD_SECS ))

# age policy
if [ -n "$MAX_AGE_DAYS" ]; then
    age_limit=$(( MAX_AGE_DAYS * 86400 ))
    for stem in ${ordered[@]+"${ordered[@]}"}; do
        if [ $(( now - U_MTIME["$stem"] )) -le "$age_limit" ]; then
            continue
        fi
        if [ "${U_MTIME["$stem"]}" -gt "$guard_cutoff" ]; then
            VETO["$stem"]="in-flight-guard"
            continue
        fi
        if [ -n "${KEEP_PROT["$stem"]:-}" ]; then
            VETO["$stem"]="keep-floor"
            floor_blocked="yes"
        else
            SEL["$stem"]=1
            SEL_REASON["$stem"]="age-cap"
            sel_count=$((sel_count + 1))
            sel_bytes=$(( sel_bytes + U_SIZE["$stem"] ))
        fi
    done
fi

# bytes policy — the cap is applied to the projected post-age-prune total, so
# the two policies compose instead of double-triggering.
if [ -n "$MAX_BYTES" ]; then
    projected=$(( total_bytes - sel_bytes ))
    for stem in ${ordered[@]+"${ordered[@]}"}; do
        if [ "$projected" -le "$MAX_BYTES" ]; then
            break
        fi
        if [ -n "${SEL["$stem"]:-}" ]; then
            continue
        fi
        if [ "${U_MTIME["$stem"]}" -gt "$guard_cutoff" ]; then
            if [ -z "${VETO["$stem"]:-}" ]; then VETO["$stem"]="in-flight-guard"; fi
            continue
        fi
        if [ -n "${KEEP_PROT["$stem"]:-}" ]; then
            if [ -z "${VETO["$stem"]:-}" ]; then VETO["$stem"]="keep-floor"; fi
            floor_blocked="yes"
            continue
        fi
        SEL["$stem"]=1
        SEL_REASON["$stem"]="size-cap"
        sel_count=$((sel_count + 1))
        sel_bytes=$(( sel_bytes + U_SIZE["$stem"] ))
        projected=$(( projected - U_SIZE["$stem"] ))
    done
    if [ "$projected" -gt "$MAX_BYTES" ]; then
        cap_unmet="yes"
    fi
fi

projected_bytes=$(( total_bytes - sel_bytes ))

# --------------------------------------------------------------- output ----
emit_row() { # action id bytes epoch reason
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$1" "$2" "$3" "$4" "$(date -u -d "@$4" +%Y-%m-%dT%H:%M:%SZ)" "$5"
}

printf '# replay-retention: dir=%s mode=%s max-bytes=%s max-age-days=%s keep=%s\n' \
    "$DIR_ABS" "$MODE" "${MAX_BYTES:-none}" "${MAX_AGE_DAYS:-none}" "$KEEP"
printf '# TSV columns: action, game-id, bytes, mtime-epoch, mtime-utc, reason\n'

for stem in ${ordered[@]+"${ordered[@]}"}; do
    if [ -n "${SEL["$stem"]:-}" ]; then
        emit_row "DELETE" "$stem" "${U_SIZE["$stem"]}" "${U_MTIME["$stem"]}" "${SEL_REASON["$stem"]}"
    else
        reason="under-caps"
        if [ -n "${VETO["$stem"]:-}" ]; then reason="${VETO["$stem"]}"; fi
        emit_row "KEEP" "$stem" "${U_SIZE["$stem"]}" "${U_MTIME["$stem"]}" "$reason"
    fi
done

if [ "${#skip_rows[@]}" -gt 0 ]; then
    while IFS= read -r row; do
        [ -n "$row" ] || continue
        name="${row%%$'\t'*}"
        rest="${row#*$'\t'}"
        sz="${rest%%$'\t'*}"; rest="${rest#*$'\t'}"
        mt="${rest%%$'\t'*}"; reason="${rest#*$'\t'}"
        emit_row "SKIP" "$name" "$sz" "$mt" "$reason"
    done < <(printf '%s\n' "${skip_rows[@]}" | LC_ALL=C sort)
fi

checked_games="${#ordered[@]}"
kept_games=$(( checked_games - sel_count ))
skipped_files="${#skip_rows[@]}"

deleted_games=0
deleted_bytes=0

# ---------------------------------------------------------------- apply ----
if [ -n "$APPLY" ]; then
    for stem in ${ordered[@]+"${ordered[@]}"}; do
        if [ -z "${SEL["$stem"]:-}" ]; then
            continue
        fi
        # .json first (display-only), then .game — the replay stays loadable
        # until the last possible moment; both always go together.
        for f in "$DIR_ABS/$stem.json" "$DIR_ABS/$stem.game"; do
            if [ -e "$f" ] || [ -L "$f" ]; then
                if ! rm -f -- "$f"; then
                    fatal "cannot delete '$f' — stopping (re-run is safe/idempotent)"
                fi
            fi
        done
        if [ -e "$DIR_ABS/$stem.game" ] || [ -e "$DIR_ABS/$stem.json" ]; then
            fatal "post-delete check failed: '$stem' still present in $DIR_ABS"
        fi
        deleted_games=$((deleted_games + 1))
        deleted_bytes=$(( deleted_bytes + U_SIZE["$stem"] ))
    done
fi

# `dir=…` is deliberately the LAST field: a path may contain spaces, so
# consumers should treat everything after 'dir=' as the value. All keys before
# it are space-safe values.
printf 'SUMMARY mode=%s checked-games=%s checked-files=%s skipped-files=%s total-bytes=%s would-delete-games=%s would-delete-bytes=%s deleted-games=%s deleted-bytes=%s kept-games=%s projected-bytes=%s keep-floor-protected=%s cap-unmet=%s dir=%s\n' \
    "$MODE" "$checked_games" "$checked_files" "$skipped_files" "$total_bytes" \
    "$sel_count" "$sel_bytes" "$deleted_games" "$deleted_bytes" "$kept_games" \
    "$projected_bytes" "$floor_blocked" "$cap_unmet" "$DIR_ABS"
