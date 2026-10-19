#!/usr/bin/env bash
# Static-asset cache policy harness (bundle-identity
# hazard: a stale cached index.html made a FIXED per-tile
# duplication defect keep reporting as live — the reporting browser must never
# keep running a superseded bundle).
#
#   bash Mage.WebProxy/scripts/verify-static-cache-policy.sh
#
# Two layers, house pattern (see verify-lobby-proxy-units.sh):
#   1. Standalone assertions on StaticCachePolicy — the decision, no Jetty/
#      classpath needed.
#   2. Wiring greps: WebProxyServer must actually install the policy on the
#      static handler (a decision class nobody calls protects nothing).
#   3. OPTIONAL live layer: if a proxy is reachable (default 127.0.0.1:8112,
#      override PROXY_URL), assert the real response headers obey the policy —
#      hashed assets immutable, index.html revalidating.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../src/main/java/mage/webproxy"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

cp "$SRC/StaticCachePolicy.java" "$OUT/"
cat > "$OUT/VerifyStaticCachePolicy.java" <<'EOF'
package mage.webproxy;

/** Standalone assertions for StaticCachePolicy. No JUnit dependency. */
public final class VerifyStaticCachePolicy {

    private static int failures = 0;

    private static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + " " + detail);
        }
    }

    public static void main(String[] args) {
        System.out.println("\n## StaticCachePolicy — the frontend must never be served stale");

        // The entry point above all: a cached index.html is what keeps a tab on
        // a superseded bundle after a redeploy.
        check("index.html revalidates",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/index.html")),
                StaticCachePolicy.cacheControlFor("/index.html"));
        check("the welcome-file root (/) revalidates",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/")),
                StaticCachePolicy.cacheControlFor("/"));
        check("hand-written CSS revalidates (it is not content-hashed)",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/game-renderer.css"))
                        && StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/styles/context-menu.css")));
        check("a null path revalidates (safe failure mode, never stale)",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor(null)));
        check("an unknown path revalidates",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/favicon.ico")));

        // Content-hashed Vite assets: the name changes with the bytes, so
        // immutable caching is correct AND removes the revalidation round-trip.
        check("hashed bundle is immutable",
                StaticCachePolicy.IMMUTABLE.equals(StaticCachePolicy.cacheControlFor("/assets/index-CkQYevcN.js"))
                        && StaticCachePolicy.cacheControlFor("/assets/index-CkQYevcN.js").contains("max-age=31536000"));
        check("hashed CSS is immutable",
                StaticCachePolicy.IMMUTABLE.equals(StaticCachePolicy.cacheControlFor("/assets/index-C6BTQu4G.css")));
        check("nested asset paths are immutable",
                StaticCachePolicy.IMMUTABLE.equals(StaticCachePolicy.cacheControlFor("/assets/fonts/x.woff2")));

        // Near-misses must NOT be treated as hashed assets.
        check("a path that merely CONTAINS 'assets' does not get immutable caching",
                StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/myassets/index.js"))
                        && StaticCachePolicy.REVALIDATE.equals(StaticCachePolicy.cacheControlFor("/assetsx/index.js")));

        System.out.println("\n" + (failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF

javac -d "$OUT/classes" "$OUT/StaticCachePolicy.java" "$OUT/VerifyStaticCachePolicy.java"
java -cp "$OUT/classes" mage.webproxy.VerifyStaticCachePolicy

# ── wiring on the real server ──
echo
fail=0
fail() { echo "FAIL: $*"; fail=1; }
SRV="$HERE/../src/main/java/mage/webproxy/WebProxyServer.java"
grep -q 'StaticCachePolicy.cacheControlFor' "$SRV" \
  || fail "WebProxyServer.java does not consult StaticCachePolicy for static responses"
grep -q 'HttpHeader.CACHE_CONTROL' "$SRV" \
  || fail "WebProxyServer.java does not set a Cache-Control header on the static handler"
[ "$fail" -eq 0 ] && echo "wiring checked: WebProxyServer.java installs StaticCachePolicy on the static handler"

# ── optional live layer ──
PROXY_URL="${PROXY_URL:-}"
if [ -z "$PROXY_URL" ]; then
  curl -s -o /dev/null --max-time 2 http://127.0.0.1:8112/ && PROXY_URL=http://127.0.0.1:8112
fi
if [ -n "$PROXY_URL" ]; then
  echo
  echo "## live headers at $PROXY_URL"
  entry_cc=$(curl -sI --max-time 3 "$PROXY_URL/" | tr -d '\r' | grep -i '^cache-control:' | head -1 | cut -d' ' -f2-)
  if echo "$entry_cc" | grep -qi 'no-cache'; then
    echo "  PASS  live / revalidates (Cache-Control: $entry_cc)"
  else
    fail "live / does not revalidate (got: ${entry_cc:-<none>}) — stale-entry-point hazard"
  fi
  bundle=$(curl -s --max-time 3 "$PROXY_URL/" | grep -o 'assets/index-[^"]*\.js' | head -1)
  if [ -n "$bundle" ]; then
    asset_cc=$(curl -sI --max-time 3 "$PROXY_URL/$bundle" | tr -d '\r' | grep -i '^cache-control:' | head -1 | cut -d' ' -f2-)
    if echo "$asset_cc" | grep -qi 'max-age=31536000'; then
      echo "  PASS  live /$bundle is immutable (Cache-Control: $asset_cc)"
    else
      fail "live /$bundle is not immutably cached (got: ${asset_cc:-<none>})"
    fi
  fi
else
  echo
  echo "live layer skipped: no proxy reachable on 127.0.0.1:8112 (set PROXY_URL to force)"
fi

exit $fail
