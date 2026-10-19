#!/usr/bin/env bash
# Verify run-bot-game.sh's host default — no server, no docker, no JAR build.
#
#   bash scripts/verify-run-bot-game-host.sh
#
# : the runner's own
# default host is "localhost", and Mage.Common Connection.getURI() substitutes
# that with the FIRST non-loopback IPv4 (a docker bridge gateway on a
# docker-heavy host) -> the connect fails. run-bot-game.sh now appends
# `--host 127.0.0.1` when the caller passed none. This harness proves the arg
# assembly by running the REAL script byte-for-byte against a stubbed `java` on
# PATH which records the argv it was exec'd with.
#
# Layout: a temp "repo root" whose scripts/ SYMLINKS the real scripts dir (so
# the shipped file is the one executed, $0 included) and whose
# Mage.Server.Console/target/ holds one dummy console JAR that satisfies the
# script's pre-check. The script's own ROOT="$(dirname $0)/.." logic therefore
# lands in the temp root — the real checkout is never written to. The only
# stubbed thing is java itself; nothing here connects anywhere.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$REPO_ROOT/scripts/run-bot-game.sh" ] || { echo "FAIL: run-bot-game.sh not found"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/root/Mage.Server.Console/target" "$WORK/bin"
ln -s "$REPO_ROOT/scripts" "$WORK/root/scripts"
: > "$WORK/root/Mage.Server.Console/target/mage-server-console-0.0.0-verify.jar"

# Stub java: record argv (one arg per line), then exit 0.
cat > "$WORK/bin/java" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$JAVA_ARGV_LOG"
STUB
chmod +x "$WORK/bin/java"

failures=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; echo "        $2"; failures=$((failures + 1)); }

# Everything the script passed to java AFTER the BotGameRunner class name.
program_args() { sed -n '/^mage\.server\.console\.BotGameRunner$/,$p' "$1" | tail -n +2; }

run_script() { # run_script <argv-log> [script args...]
  local log="$1"; shift
  JAVA_ARGV_LOG="$log" PATH="$WORK/bin:$PATH" bash "$WORK/root/scripts/run-bot-game.sh" "$@" >/dev/null 2>&1 || true
  [ -s "$log" ] || { echo "FAIL: stub java was never exec'd (script exited before exec)"; exit 1; }
}

echo "── run-bot-game.sh host default (stubbed java; no server contact) ──"

run_script "$WORK/argv-none"
got="$(program_args "$WORK/argv-none" | tr '\n' ' ')"
[ "$got" = "--host 127.0.0.1 " ] \
  && pass "no --host given -> '--host 127.0.0.1' appended" \
  || fail "no --host given -> expected '--host 127.0.0.1', got '$got'"

run_script "$WORK/argv-host" --host 10.0.0.9 --bo3
got="$(program_args "$WORK/argv-host" | tr '\n' ' ')"
[ "$got" = "--host 10.0.0.9 --bo3 " ] \
  && pass "explicit --host 10.0.0.9 kept, not overridden" \
  || fail "explicit --host was overridden or reordered: '$got'"

run_script "$WORK/argv-host-loop" --host 127.0.0.1
got="$(program_args "$WORK/argv-host-loop" | tr '\n' ' ')"
[ "$got" = "--host 127.0.0.1 " ] \
  && pass "an explicit 127.0.0.1 is not duplicated" \
  || fail "explicit 127.0.0.1 duplicated: '$got'"

run_script "$WORK/argv-help" --help
got="$(program_args "$WORK/argv-help" | tr '\n' ' ')"
[ "$got" = "--help " ] \
  && pass "--help alone forwarded, no injected --host" \
  || fail "--help got an injected --host: '$got'"

run_script "$WORK/argv-h" -h
got="$(program_args "$WORK/argv-h" | tr '\n' ' ')"
[ "$got" = "-h " ] \
  && pass "-h alone forwarded, no injected --host" \
  || fail "-h got an injected --host: '$got'"

run_script "$WORK/argv-mixed" --bo3 --timeout-minutes 5 --deck1 a.dck
got="$(program_args "$WORK/argv-mixed" | tr '\n' ' ')"
[ "$got" = "--bo3 --timeout-minutes 5 --deck1 a.dck --host 127.0.0.1 " ] \
  && pass "user args verbatim, --host 127.0.0.1 appended" \
  || fail "user args mangled: '$got'"

# The script must still forward args it has no opinion about, unchanged in count.
run_script "$WORK/argv-count" --bo3
n="$(program_args "$WORK/argv-count" | wc -l | tr -d ' ')"
[ "$n" = "3" ] \
  && pass "--bo3 alone yields exactly 3 program args (--bo3 --host 127.0.0.1)" \
  || fail "--bo3 alone yielded $n program args"

if [ "$failures" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL ($failures)"
exit 1
