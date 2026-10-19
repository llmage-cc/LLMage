package mage.webproxy;

/**
 * Build-time LLMage feature switch.
 *
 * The web proxy ships in two flavors from the SAME source tree:
 * - LLMage build (default): every LLMage feature compiled in — the /llm-relay
 *   route, LLM seat plumbing, replay scrubber, sidecar metadata. Behavior is
 * still gated per-server by the runtime capability probe, so even
 *   this build is safe to point at official servers.
 * - Stock build (-Pstock or -Dllmage=false): LLM-only code is EXCLUDED from
 *   the jar (LLmRelayHandler is compile-time absent) and LLMAGE_BUILD=false is
 *   baked in; the frontend bundle is built with VITE_LLMAGE=false so the LLM
 *   UI is tree-shaken out. For the official-server artifact.
 *
 * Fail Fast: when LLMAGE_BUILD is false, any code path that reaches an
 * LLMage-only feature must throw/report — it can only be reached by a bug or
 * a crafted message, never by a legitimate flow (the UI does not render it and
 * the capability gates reject the request paths).
 */
public final class LLMageBuild {

    /**
     * True for the default (LLMage) build. Resolution order:
     * 1. -Dllmage.build JVM property (deployment override),
     * 2. the jar manifest's LLMage-Build entry (stock profile bakes "false"),
     * 3. default true (the LLMage build).
     */
    public static final boolean ENABLED = resolveEnabled();

    private static boolean resolveEnabled() {
        String prop = System.getProperty("llmage.build");
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        String fromManifest = manifestValue(LLMageBuild.class);
        if (fromManifest != null) {
            return Boolean.parseBoolean(fromManifest.trim());
        }
        // No manifest entry (running from target/classes, tests, or an older
        // jar): the LLMage build is the default.
        return true;
    }

    /** Read this class's jar manifest LLMage-Build entry, null when unreadable. */
    private static String manifestValue(Class<?> c) {
        try {
            java.util.jar.JarFile jar = new java.util.jar.JarFile(
                    new java.io.File(c.getProtectionDomain().getCodeSource().getLocation().toURI()), false);
            try {
                java.util.jar.Attributes attrs = jar.getManifest().getMainAttributes();
                return attrs.getValue("LLMage-Build");
            } finally {
                jar.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private LLMageBuild() {
    }

    /** Wire value for /config: lets the frontend know the artifact flavor. */
    public static boolean enabled() {
        return ENABLED;
    }
}