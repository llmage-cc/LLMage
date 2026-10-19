#!/usr/bin/env bash
# Materialize plugins/ symlinks from the built release JARs.
#
# The server resolves plugin JARs from the repo-root plugins/ dir (relative to
# CWD). A fresh checkout — or a rebuilt tree with new/renamed JARs — can end up
# with missing or stale links, which manifests as "FATAL Unknown player type:
# Computer - mad" / "Waiting for another player" hangs (see
#  #1). This script makes plugins/
# self-healing: run it before server start.
#
# Idempotent: recreates every derivable link each run. Hand-made aliases that
# don't correspond to a release JAR (e.g. mage-game-momir.jar -> momirfreeforall)
# are left untouched.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p plugins
created=0
for jar in Mage.Server/release/lib/mage-player-*.jar \
           Mage.Server/release/lib/mage-game-*.jar \
           Mage.Server/release/lib/mage-tournament-*.jar \
           Mage.Server/release/lib/mage-deck-*.jar \
           Mage.Server/release/plugins/mage-player-*.jar; do
    [ -e "$jar" ] || continue
    base="$(basename "$jar")"
    name="$(printf '%s' "$base" | sed -E 's/-[0-9][0-9.]*\.jar$//; s/\.jar$//')"
    rm -f "plugins/$name.jar"
    ln -s "../$jar" "plugins/$name.jar"
    created=$((created+1))
done

# Fail loudly on any dangling link — a broken plugin silently downgrades the server.
dangling=0
for link in plugins/*.jar; do
    [ -L "$link" ] || continue
    if [ ! -e "$link" ]; then
        echo "DANGLING: $link -> $(readlink "$link")" >&2
        dangling=$((dangling+1))
    fi
done
if [ "$dangling" -gt 0 ]; then
    echo "FAIL: $dangling dangling plugin symlinks" >&2
    exit 1
fi
echo "plugins/: $created symlinks materialized, all resolve"