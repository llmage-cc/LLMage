package mage.webproxy;

/**
 * Cache-Control policy for the proxy's static frontend files.
 *
 * WHY this exists (bundle-identity
 * hazard): the static handler used to send NO Cache-Control at all. Without an
 * explicit directive, browsers apply heuristic freshness (typically based on
 * Last-Modified), so a tab that loaded the page before a redeploy could keep
 * resolving the OLD index.html from its cache — and, because stale hashed
 * bundles were left servable, keep running a superseded frontend. A defect that
 * has already been fixed then reads as "still happening" (the 60Hz report could
 * describe the already-fixed per-tile duplication if the reporting browser ran
 * a stale bundle).
 *
 * The policy splits by path:
 *
 *  - {@code /assets/*} — Vite content-hashed bundle/CSS filenames. The name
 *    changes whenever the bytes change, so the response can be cached
 *    immutably: no revalidation, cached forever.
 *  - everything else (index.html above all) — must revalidate on every load, so
 *    a redeploy can never leave a tab on the old entry point.
 *
 * Dependency-free on purpose: the wiring in WebProxyServer is a few lines, but
 * the DECISION is the part a harness can pin without a running proxy or Jetty
 * on the classpath.
 */
final class StaticCachePolicy {

    /** Content-hashed immutable assets (Vite emits /assets/index-<hash>.js|css). */
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /** Entry points and hand-written files: always revalidate before use. */
    static final String REVALIDATE = "no-cache";

    private StaticCachePolicy() {
    }

    /**
     * The Cache-Control value for a request path.
     *
     * @param path the request path as sent by the browser (may be null)
     * @return {@link #IMMUTABLE} for content-hashed assets, {@link #REVALIDATE}
     *         for everything else — including a null/unknown path, because the
     *         safe failure mode is revalidation, never stale content.
     */
    static String cacheControlFor(String path) {
        if (path == null) {
            return REVALIDATE;
        }
        return path.startsWith("/assets/") ? IMMUTABLE : REVALIDATE;
    }
}
