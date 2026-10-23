#!/usr/bin/env bash
# Deploy freshly-built LLMage (XMage fork) JARs into Mage.Server/release/{lib,plugins}/.
#
# This fork ships the web-proxy jar in FLAVORS from one source
# tree (-Pllmage / -Pstock / minimal). This deploy ships the LLMage flavor:
# build with `mvn clean install -DskipTests -Pllmage` before running it
# (build-stock.sh produces the stock pair for official-server artifacts).
#
# Source of truth for the dependency set: the Maven assembly output
# (Mage.Server/target/mage-server.zip) — it contains the full runtime lib/ and
# plugins/ set.
#
# LAYOUT RULE (why plugins land in release/lib/): the repo's bootstrap-plugins.sh
# symlinks Mage.Server/release/lib/mage-player-*.jar, mage-game-*.jar,
# mage-tournament-*.jar and mage-deck-*.jar into the repo-root plugins/ dir that
# PlayerFactory resolves player/game types from. The Maven assembly instead puts
# those same jars in its own plugins/ dir, so copying them to release/lib/ keeps
# bootstrap-plugins.sh working.
# release/plugins/ holds the two jars bootstrap can find there: the LLM player
# plugin and the web proxy.
#
# EXCEPTION — the web proxy must NEVER be copied into release/lib/: both
# mage-server and mage-web-proxy carry a root log4j.properties, the JVM
# resolves it by classpath enumeration order (lib/*
# precedes plugins/*, order within a dir is not sorted), and with the proxy jar
# in lib/ the server has booted with the PROXY's config (rootLogger=INFO,stdout)
# — nothing written to mageserver.log, monitor scripts reading false zeros.
# The jar-list loop below skips it and step 3b fails the deploy if one is ever
# found in lib/ again. release/plugins/ keeps its copy (step 5): plugins/* is
# strictly after lib/*, so the server's own config then wins deterministically.
#
# Run from the repo root after: mvn clean install -DskipTests
#   and: (cd Mage.Server && mvn package assembly:single -DskipTests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Artifact version = the root pom's <mage-version> (single source of truth).
V="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' pom.xml 2>/dev/null || true)"
[ -n "$V" ] || { echo "FATAL: cannot read <mage-version> from $ROOT/pom.xml" >&2; exit 1; }
BUILD_DIR=/tmp/xmage-assembly
ZIP=Mage.Server/target/mage-server.zip

[ -f "$ZIP" ] || { echo "FATAL: $ZIP missing — run: mvn clean install -DskipTests && (cd Mage.Server && mvn package assembly:single -DskipTests)" >&2; exit 1; }

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ---------------------------------------------------------------------------
# WHY THIS DOES NOT USE THE ASSEMBLY'S lib/ (2026-09-16)
#
# mage-server.zip's lib/*.jar entries are CORRUPTED: their bytes have been run
# through a UTF-8 text path, so every non-UTF-8 byte in the JAR becomes U+FFFD
# (EF BF BD). Measured on mage-common-1.4.61.jar inside the zip: entry size
# 468,622 bytes vs the real 268,619, first divergence at byte 10, and both
# `jar tf` and zipfile reject it ("Bad offset for central directory"). A deploy
# that trusted this zip replaced 62 of 88 healthy JARs in release/lib/ with
# mangled ones (caught 2026-09-16 23:47 — the running server survived only
# because it had the old files open).
#
# The assembly is still useful for its FILE LIST (which jars the runtime needs);
# the BYTES now come from each artifact's authoritative source: the module's
# target/ for the project's own jars, and ~/.m2 for third-party deps. Every
# copied jar is verified to be openable before it is accepted, so a bad source
# fails loudly here instead of silently breaking the classpath.
# ---------------------------------------------------------------------------
python3 - "$ZIP" "$BUILD_DIR" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = [n for n in z.namelist() if n.endswith('.jar')]
with open(sys.argv[2] + '/jar-list.txt', 'w') as f:
    for n in names:
        f.write(n + '\n')
print("assembly lists", len(names), "runtime jars")
PYEOF

# Is a file a loadable JAR? Java is the authority (zipfile is stricter than the
# JVM in some cases, but a jar that `jar tf` cannot open is unusable on this
# classpath, and that is the failure we actually hit).
jar_ok() {
    jar tf "$1" >/dev/null 2>&1
}

mkdir -p Mage.Server/release/lib Mage.Server/release/plugins

# 1. Clean old versioned JARs (stale versions on the classpath cause subtle breakage).
find Mage.Server/release/lib     -maxdepth 1 -name '*-1.4.5*.jar' -delete
find Mage.Server/release/lib     -maxdepth 1 -name '*-1.4.6*.jar' -delete
find Mage.Server/release/plugins -maxdepth 1 -name '*-1.4.5*.jar' -delete
find Mage.Server/release/plugins -maxdepth 1 -name '*-1.4.6*.jar' -delete

# 2/3. Every jar the runtime needs, sourced from an authoritative copy and verified.
#      Project jars come from their module target/; everything else from ~/.m2.
M2="${M2_REPO:-$HOME/.m2/repository}"
copied=0; from_target=0; from_m2=0; missing=0
while read -r entry; do
    base=$(basename "$entry")
    # Guard: the web proxy must never reach release/lib/ (its log4j.properties
    # would hijack the server's — see the LAYOUT RULE header). It ships from the
    # assembly's plugins/ list; step 5 copies it to release/plugins/ explicitly.
    case "$base" in
        mage-web-proxy-*.jar)
            echo "skip $base → release/plugins/ only (log4j.properties would shadow the server's)"
            continue ;;
    esac
    src=""
    case "$base" in
        mage-"$V".jar)          src="Mage/target/$base" ;;
        mage-common-"$V".jar)   src="Mage.Common/target/$base" ;;
        mage-sets-"$V".jar)     src="Mage.Sets/target/$base" ;;
        mage-server-"$V".jar)   src="Mage.Server/target/$base" ;;
        mage-player-llm.jar|mage-player-llm-"$V".jar)
            src="Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar" ;;
        # The upstream 1.4.61 merge added Mage.Player.Human as a runtime plugin
        # (assembly ships plugins/mage-player-human[-1.4.61].jar). Without an
        # entry here the jar is not in ~/.m2 under this name and the whole
        # deploy aborts (hit 2026-10-01). Bare and versioned names both map to
        # the module's target/ jar.
        mage-player-human.jar|mage-player-human-"$V".jar)
            src="Mage.Server.Plugins/Mage.Player.Human/target/mage-player-human-$V.jar" ;;
        # Argentum-P1 policy-net seat. Same mandatory reason as the human case above:
        # step 1 deletes every *-1.4.6*.jar in release/lib/, so the jar must be sourced here.
        mage-player-p1.jar|mage-player-p1-"$V".jar)
            src="Mage.Server.Plugins/Mage.Player.P1/target/mage-player-p1.jar" ;;
        mage-web-proxy-"$V".jar)  src="Mage.WebProxy/target/$base" ;;
    esac
    if [ -n "$src" ] && [ -f "$src" ]; then
        from_target=$((from_target+1))
    else
        # third-party (or a module jar with no target/ copy): find it in ~/.m2 by name
        src=$(find "$M2" -name "$base" -type f 2>/dev/null | head -1)
        if [ -n "$src" ]; then from_m2=$((from_m2+1)); else
            echo "FATAL: no authoritative source for $base (not in any module target/, not in $M2)" >&2
            missing=$((missing+1)); continue
        fi
    fi
    if ! jar_ok "$src"; then
        echo "FATAL: source jar is corrupt: $src" >&2
        missing=$((missing+1)); continue
    fi
    cp "$src" Mage.Server/release/lib/"$base"
    cp "$src" "$BUILD_DIR/$base"    # stage for the md5 pass below
    copied=$((copied+1))
done < "$BUILD_DIR/jar-list.txt"
echo "sourced $copied jars (target/=$from_target, m2=$from_m2, failures=$missing)"
[ "$missing" -eq 0 ] || { echo "FATAL: $missing jar(s) could not be sourced" >&2; exit 1; }

# 2b. GUARD: exactly one version per org.mage artifact. The assembly's fileSet copies
#     the release tree as it stands, so a tree left over from the previous release makes
#     the zip list BOTH versions; both then reach the classpath and which one wins is
#     directory order (hit 2026-10-20: 33 jars at 1.4.61 alongside 1.4.62). If the tree
#     is contaminated, clean Mage.Server/release/{lib,plugins} of versioned jars and
#     rebuild the assembly before re-running this script.
dups="$(find Mage.Server/release/lib -maxdepth 1 -name 'mage-*-[0-9]*.jar' -printf '%f\n' \
        | sed -E 's/-[0-9]+(\.[0-9]+)+\.jar$//' | sort | uniq -d)"
if [ -n "$dups" ]; then
    echo "FATAL: multiple versions of the same artifact in release/lib:" >&2
    echo "$dups" | sed 's/^/  /' >&2
    exit 1
fi

# 3b. GUARD: the web proxy must not be present in release/lib/ — its
#     log4j.properties competes with the server's for the root logger (the JVM
#     resolves log4j.properties by classpath enumeration and lib/* comes before
#     plugins/*), which silently kills mageserver.log. A stale proxy jar here
#     (e.g. from an older deploy) is deleted loudly and re-checked; it is
#     deployed to release/plugins/ by step 5.
if compgen -G "Mage.Server/release/lib/mage-web-proxy-*.jar" >/dev/null; then
    echo "WARN: stray web-proxy jar(s) in release/lib/ — removing (log4j hijack):" >&2
    compgen -G "Mage.Server/release/lib/mage-web-proxy-*.jar" | sed 's/^/  /' >&2
    rm -f Mage.Server/release/lib/mage-web-proxy-*.jar || true
fi
if compgen -G "Mage.Server/release/lib/mage-web-proxy-*.jar" >/dev/null; then
    echo "FATAL: mage-web-proxy-*.jar still present in Mage.Server/release/lib/ after cleanup:" >&2
    compgen -G "Mage.Server/release/lib/mage-web-proxy-*.jar" | sed 's/^/  /' >&2
    echo "       Its log4j.properties would shadow the server's config (no mageserver.log)." >&2
    exit 1
fi
echo "OK   release/lib/ has no mage-web-proxy-*.jar (server log4j config wins)"

# 4. LLM player plugin → release/plugins/ (bootstrap globs it there).
cp Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar \
   Mage.Server/release/plugins/mage-player-llm-${V}.jar

# 4b. Refresh the BARE-NAME copies of the two player plugins in release/plugins/.
#     build.sh copies release/plugins/mage-player-*.jar over lib/ later, and the
#     glob sorts versioned-then-bare — so a stale bare jar shipped the OLD plugin
#     while lib/ had the new one (caught 2026-10-01 in the spare image: lib md5
#     new, plugins md5 = the previous build). Keep both copies canonical.
cp Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar \
   Mage.Server/release/plugins/mage-player-llm.jar
if [ -f Mage.Server.Plugins/Mage.Player.Human/target/mage-player-human-${V}.jar ]; then
    cp Mage.Server.Plugins/Mage.Player.Human/target/mage-player-human-${V}.jar \
       Mage.Server/release/plugins/mage-player-human.jar
fi

# 5. Web proxy (standalone jar; also sits on the server's plugin classpath).
cp Mage.WebProxy/target/mage-web-proxy-${V}.jar \
   Mage.Server/release/plugins/mage-web-proxy-${V}.jar

# 6. Verify: every project jar deployed must be byte-identical to its build output,
#    and every deployed jar must be loadable.
echo "--- integrity checks ---"
for pair in \
    "Mage/target/mage-${V}.jar Mage.Server/release/lib/mage-${V}.jar" \
    "Mage.Sets/target/mage-sets-${V}.jar Mage.Server/release/lib/mage-sets-${V}.jar" \
    "Mage.Server/target/mage-server-${V}.jar Mage.Server/release/lib/mage-server-${V}.jar" \
    "Mage.Common/target/mage-common-${V}.jar Mage.Server/release/lib/mage-common-${V}.jar" \
    "Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar Mage.Server/release/plugins/mage-player-llm-${V}.jar" \
    "Mage.WebProxy/target/mage-web-proxy-${V}.jar Mage.Server/release/plugins/mage-web-proxy-${V}.jar"; do
    set -- $pair
    a=$(md5sum "$1" | cut -d' ' -f1)
    b=$(md5sum "$2" | cut -d' ' -f1)
    if [ "$a" = "$b" ]; then echo "OK   $(basename "$2")"; else echo "FAIL $(basename "$2")  $a != $b"; exit 1; fi
done

# 7. The plugins the server resolves by bare jar name must all be symlinkable.
echo "--- plugin jar sanity ---"
have_jar() {
    # any match in either directory counts (glob no-match must not trip set -e)
    compgen -G "Mage.Server/release/lib/$1-*.jar" >/dev/null && return 0
    compgen -G "Mage.Server/release/plugins/$1-*.jar" >/dev/null && return 0
    return 1
}
for p in mage-player-human mage-player-ai-mad mage-player-ai-mcts mage-player-ai-draftbot \
         mage-player-llm mage-player-p1 mage-game-twoplayerduel mage-deck-constructed mage-tournament-constructed; do
    if have_jar "$p"; then
        echo "OK   $p"
    else
        echo "MISSING $p"; exit 1
    fi
done

echo "--- counts ---"
echo "lib:     $(ls Mage.Server/release/lib/*.jar | wc -l) jars"
echo "plugins: $(ls Mage.Server/release/plugins/*.jar | wc -l) jars"

# This fork fails the deploy if the proxy jar is the WRONG flavor.
P_MANIFEST="$(unzip -p Mage.Server/release/plugins/mage-web-proxy-${V}.jar META-INF/MANIFEST.MF 2>/dev/null || true)"
if echo "$P_MANIFEST" | grep -q "LLMage-Build: false"; then
    echo "FATAL: deployed mage-web-proxy is the STOCK flavor (LLMage-Build: false) —" >&2
    echo "       rebuild with: mvn clean install -DskipTests -Pllmage -pl Mage.WebProxy" >&2
    exit 1
fi
if ! echo "$P_MANIFEST" | grep -q "LLMage-Build: true"; then
    echo "FATAL: deployed mage-web-proxy has NO LLMage flavor marker (minimal/test" >&2
    echo "       build?) — rebuild with: mvn clean install -DskipTests -Pllmage -pl Mage.WebProxy" >&2
    exit 1
fi
echo "OK   mage-web-proxy flavor: LLMage (manifest LLMage-Build: true)"
echo "release deploy complete (version ${V})"
