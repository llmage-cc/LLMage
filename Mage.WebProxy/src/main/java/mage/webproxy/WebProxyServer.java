package mage.webproxy;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;

import java.io.File;

/**
 * Jetty 12 based server that serves the web frontend and handles WebSocket connections.
 *
 * Three endpoints:
 * - /       → static files from web/frontend/
 * - /ws     → WebSocket upgrade
 * - /config → deployment config read by the connect form (hideServerTarget)
 *
 * Each WebSocket connection creates a WebProxySession that connects to an XMage server.
 */
public class WebProxyServer {

    private static final Logger logger = Logger.getLogger(WebProxyServer.class);

    private final int port;
    private final String bindAddress;
    private Server jettyServer;

    public WebProxyServer(int port) {
        this(port, "127.0.0.1");
    }

    public WebProxyServer(int port, String bindAddress) {
        this.port = port;
        this.bindAddress = bindAddress;
    }

    public void start() throws Exception {
        // Fail fast on a contradictory public-deployment configuration before
        // any socket is bound: hiding the target fields while letting the
        // browser choose the target would serve a form that cannot connect.
        validatePublicHostingConfig();

        // Fail fast on a malformed per-host connection cap: a typo'd
        // -Dwebproxy.maxConnectionsPerHost must refuse to boot rather than
        // silently leaving the hosted deployment unlimited.
        PerHostConnectionLimiter.connectionCap();

        // Fail fast on a broken classpath composition: the offline Scryfall maps
        // live in the sibling mage jar, and a stale staging tree can pair this
        // proxy jar with a mage jar that lacks them (2026-09-24 incident).
        // Without this check the proxy boots and dies at the first card-image
        // lookup instead of refusing to start.
        ScryfallImageMap.verifyLoaded();

        // Fail fast on (and repair the known self-empty trap of) the cards DB
        // BEFORE anything touches CardRepository — see CardsDbGuard.
        CardsDbGuard.verifyOrStamp();

        // Skip loading ~30K card classes — the proxy only reads GameView data
        CardScanner.scanned = true;

        // Create the server with a connector bound to a specific address.
        // Default bind is 127.0.0.1 (localhost only) for security — the proxy
        // must not be reachable from the network without explicit configuration.
        jettyServer = new Server();
        org.eclipse.jetty.server.ServerConnector connector =
            new org.eclipse.jetty.server.ServerConnector(jettyServer);
        connector.setHost(bindAddress);
        connector.setPort(port);
        jettyServer.addConnector(connector);

        // Find frontend directory
        File frontendDir = new File("web/frontend");
        if (!frontendDir.exists()) {
            frontendDir = new File("Mage.WebProxy/web/frontend");
        }
        if (!frontendDir.exists()) {
            throw new IllegalStateException("Frontend directory not found: " + frontendDir.getAbsolutePath());
        }
        logger.info("Serving frontend from: " + frontendDir.getAbsolutePath());

        // WebSocket container — handles /ws upgrade requests.
        ServerWebSocketContainer wsContainer = ServerWebSocketContainer.ensure(jettyServer);
        wsContainer.setIdleTimeout(java.time.Duration.ofSeconds(600)); // 10 min idle timeout (thinking time)
        // Hard caps on inbound WS message/frame size. Deck lists
        // can be large (bounded at 1 MiB in the session layer too), but frames
        // in the tens of MB are pure memory-exhaustion attacks.
        wsContainer.setMaxTextMessageSize(1024 * 1024);   // 1 MiB per text message
        wsContainer.setMaxBinaryMessageSize(1024 * 1024); // 1 MiB per binary message
        wsContainer.setMaxFrameSize(1024 * 1024);         // 1 MiB per frame
        wsContainer.addMapping("/ws", new WebSocketCreator() {
            @Override
            public Object createWebSocket(
                    org.eclipse.jetty.websocket.server.ServerUpgradeRequest req,
                    org.eclipse.jetty.websocket.server.ServerUpgradeResponse resp,
                    org.eclipse.jetty.util.Callback callback) {
                // Origin validation — reject connections from untrusted origins.
                // This is defense-in-depth alongside binding to 127.0.0.1: even if
                // the proxy is reachable, browser cross-origin WS attacks are blocked.
                String origin = req.getHeaders().get("Origin");
                if (origin == null || origin.isEmpty() || !isAllowedOrigin(origin)) {
                    logger.warn("Rejecting WebSocket connection from untrusted origin: " + origin);
                    callback.failed(new java.io.IOException("Origin not allowed: " + origin));
                    return null;
                }
                return new WebProxySession.WebProxyWebSocketEndpoint();
            }
        });

        // Wrap the wsContainer (a Request.Handler) in a Handler.Abstract so it
        // can be part of a Handler.Sequence. The wsContainer.handle() returns
        // false for non-WebSocket requests, causing fallthrough to the static handler.
        Handler.Abstract wsHandlerWrapper = new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request,
                                  org.eclipse.jetty.server.Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                return wsContainer.handle(request, response, callback);
            }
        };
        wsHandlerWrapper.setServer(jettyServer);

        // GET /config — the deployment config the connect form reads before it
        // renders (hideServerTarget). A boolean, so it needs no auth and no
        // origin check; Cache-Control: no-store so a restarted proxy's new
        // config is never served stale from a browser cache.
        // allowNullPathInContext: serve /config itself instead of Jetty's default
        // 301 redirect to /config/ (the frontend fetches the exact path).
        ContextHandler configContext = new ContextHandler("/config");
        configContext.setAllowNullPathInContext(true);
        configContext.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request,
                                  org.eclipse.jetty.server.Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                if (!HttpMethod.GET.is(request.getMethod())) {
                    org.eclipse.jetty.server.Response.writeError(request, response, callback,
                            HttpStatus.METHOD_NOT_ALLOWED_405);
                    return true;
                }
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
                Content.Sink.write(response, true, proxyConfigJson(), callback);
                return true;
            }
        });

        // Static file handler for frontend (non-WebSocket requests)
        ContextHandler staticContext = new ContextHandler("/");
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.of(staticContext).newResource(frontendDir.toPath()));
        resourceHandler.setWelcomeFiles("index.html");

        // Cache policy (bundle-identity
        // hazard): without any Cache-Control, browsers apply heuristic freshness
        // to index.html, so after a redeploy a tab could keep resolving the OLD
        // index.html — and if the old bundle is still servable, keep running a
        // superseded frontend. That is exactly how a fixed defect reads as "still
        // bugged" (a 60Hz report could describe the already-fixed per-tile
        // duplication if the reporting window ran a stale bundle).
        //   /assets/*  — content-hashed filenames: cache hard, they can never
        //                change under the same name.
        //   everything else (index.html above all) — revalidate every load.
        // The decision itself lives in StaticCachePolicy so a harness can pin it
        // without a running proxy or Jetty on the classpath.
        //
        // NOTE the wrapper must be a Handler.Wrapper (a container): wrapping the
        // ResourceHandler in a bare Handler.Abstract leaves it un-started
        // (ContentFactory null -> 500 on every static request), because Jetty
        // starts child handlers through the container's lifecycle, not through
        // whatever handler a ContextHandler was handed.
        Handler.Wrapper cacheWrapper = new Handler.Wrapper(resourceHandler) {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request,
                                  org.eclipse.jetty.server.Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                org.eclipse.jetty.http.HttpURI uri = request.getHttpURI();
                response.getHeaders().put(HttpHeader.CACHE_CONTROL,
                        StaticCachePolicy.cacheControlFor(uri == null ? null : uri.getPath()));
                return super.handle(request, response, callback);
            }
        };
        staticContext.setHandler(cacheWrapper);

        // Put wsContainer first (it handles /ws), then /config, the LLM relay
        // client-key fallback for providers without CORS — ,
        // LLMage builds only — the stock artifact has no relay at all), then the
        // static handler last.
        Handler.Sequence sequence = new Handler.Sequence();
        sequence.addHandler(wsHandlerWrapper);
        sequence.addHandler(configContext);
        ContextHandler relay = LLmageRoutes.relayHandler();
        if (relay != null) {
            sequence.addHandler(relay);
        } else {
            logger.info("Stock build: /llm-relay route not registered (llmage=false)");
        }
        sequence.addHandler(staticContext);

        jettyServer.setHandler(sequence);

        logger.info("Starting " + (LLMageBuild.enabled() ? "LLMage" : "XMage (stock)")
                + " Web Proxy on " + bindAddress + ":" + port + "...");
        jettyServer.start();
        logger.info("Web Proxy started. Open http://localhost:" + port + "/ in your browser.");

        jettyServer.join();
    }

    public void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
        // Shut down the static background executor used by WebProxySession
        // (model fetching, etc.) — otherwise it leaks across stop/start cycles
        // in the same JVM.
        WebProxySession.shutdownBackgroundExecutor();
    }

    public static void main(String[] args) {
        int port = getIntArg(args, "--port", "webproxy.port", 8090);
        String bindAddress = getStringArg(args, "--bind", "webproxy.bind", "127.0.0.1");

        try {
            new WebProxyServer(port, bindAddress).start();
        } catch (Exception e) {
            logger.error("Failed to start web proxy server", e);
            System.exit(1);
        }
    }

    private static int getIntArg(String[] args, String flag, String systemProperty, int defaultValue) {
        String val = System.getProperty(systemProperty);
        if (val != null && !val.isEmpty()) {
            return Integer.parseInt(val);
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }

    private static String getStringArg(String[] args, String flag, String systemProperty, String defaultValue) {
        String val = System.getProperty(systemProperty);
        if (val != null && !val.isEmpty()) {
            return val;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    // ── Public-hosting configuration (deployment toggles) ──

    /**
     * Deployment toggle: hide the Server/Port fields in the connect form.
     * -Dwebproxy.hideServerTarget=true (default false) is for deployments where
     * the operator pins the XMage target (-Dwebproxy.pinnedXmageTarget) and the
     * browser must neither choose nor see a host/port. Strict boolean: any other
     * value fails fast instead of silently meaning "false". Local/LAN runs keep
     * the fields (the "other situations" the toggle exists for).
     */
    static boolean hideServerTarget() {
        String raw = System.getProperty("webproxy.hideServerTarget", "false").trim().toLowerCase();
        if (!raw.equals("true") && !raw.equals("false")) {
            throw new IllegalStateException(
                    "Invalid webproxy.hideServerTarget (expected true/false): " + raw);
        }
        return raw.equals("true");
    }

    /**
     * Fail-fast check for the public-deployment properties. Hiding the target
     * fields only works when the proxy itself pins the XMage target — without a
     * pin the browser has no way to choose one and every connect would be
     * rejected. Refuse to start rather than boot a broken deployment.
     */
    static void validatePublicHostingConfig() {
        if (hideServerTarget() && System.getProperty("webproxy.pinnedXmageTarget", "").trim().isEmpty()) {
            throw new IllegalStateException(
                    "webproxy.hideServerTarget=true requires webproxy.pinnedXmageTarget=host:port"
                    + " (without a pinned target the browser cannot choose an XMage server)");
        }
    }

    /** The JSON body served at GET /config. */
    static String proxyConfigJson() {
        return "{\"hideServerTarget\":" + hideServerTarget()
                + ",\"llmage\":" + LLMageBuild.enabled() + "}";
    }

    /**
     * The XMage host/port a connect message resolves to. With a pinned target
     * (-Dwebproxy.pinnedXmageTarget=host:port) the browser's server/port fields
     * are ignored entirely and are OPTIONAL — that is what lets the connect form
     * drop them; without a pin both are required, exactly as before.
     */
    static final class ConnectTarget {
        final String host;
        final int port;
        /** Non-null when the connect fields are unusable; the message is user-facing. */
        final String error;
        /** True when the target came from -Dwebproxy.pinnedXmageTarget. */
        final boolean pinned;

        private ConnectTarget(String host, int port, String error, boolean pinned) {
            this.host = host;
            this.port = port;
            this.error = error;
            this.pinned = pinned;
        }

        static ConnectTarget pinnedTarget(String host, int port) {
            return new ConnectTarget(host, port, null, true);
        }

        static ConnectTarget clientTarget(String host, int port) {
            return new ConnectTarget(host, port, null, false);
        }

        static ConnectTarget error(String message) {
            return new ConnectTarget(null, 0, message, false);
        }
    }

    /**
     * Resolve the XMage target from a connect message. Package-private static so
     * the unit tests can pin both contracts (pinned = fields optional+ignored,
     * unpinned = fields required) without a WebSocket session.
     */
    static ConnectTarget resolveConnectTarget(com.google.gson.JsonObject json, String pinnedProperty) {
        String pinned = pinnedProperty == null ? "" : pinnedProperty.trim();
        if (!pinned.isEmpty()) {
            int colon = pinned.lastIndexOf(':');
            if (colon <= 0 || colon == pinned.length() - 1) {
                return ConnectTarget.error("Proxy misconfigured — pinned XMage target is malformed");
            }
            try {
                return ConnectTarget.pinnedTarget(pinned.substring(0, colon),
                        Integer.parseInt(pinned.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return ConnectTarget.error("Proxy misconfigured — pinned XMage target is malformed");
            }
        }
        String server = jsonStringOrNull(json, "server");
        if (server == null) {
            return ConnectTarget.error("Missing server");
        }
        String portStr = jsonStringOrNull(json, "port");
        if (portStr == null) {
            return ConnectTarget.error("Missing port");
        }
        try {
            return ConnectTarget.clientTarget(server, Integer.parseInt(portStr));
        } catch (NumberFormatException e) {
            return ConnectTarget.error("Invalid port: " + portStr);
        }
    }

    /**
     * Read a connect-message field. Gson returns numbers as their string form,
     * which is why the numeric `port` the browser sends works here too.
     */
    private static String jsonStringOrNull(com.google.gson.JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        return json.get(field).getAsString();
    }

    /**
     * Check whether the given Origin header is allowed for WebSocket connections.
     * Allowed origins are localhost variants (http and https on any port) plus any
     * explicit allowlist passed via -Dwebproxy.allowedOrigins (for deployments where
     * the proxy is bound to a non-localhost interface — see --bind). The proxy binds
     * to 127.0.0.1 by default, so only same-machine browsers should be connecting.
     * This prevents cross-origin WS hijacking attacks.
     */
    static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return false;
        // Normalize: strip trailing slash
        String normalized = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        // Public-hosting extension: explicit origin allowlist via
        // -Dwebproxy.allowedOrigins=<origin1,origin2> (comma-separated, exact
        // normalized match, never a wildcard). Checked FIRST so a public hostname
        // does not need a code change, and so localhost dev keeps working
        // regardless of what the property contains.
        String configured = System.getProperty("webproxy.allowedOrigins", "").trim();
        if (!configured.isEmpty()) {
            for (String allowed : configured.split(",")) {
                String a = allowed.trim();
                if (a.isEmpty()) continue;
                // Normalize the configured origin the same way (strip trailing slash)
                if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
                if (normalized.equals(a)) return true;
            }
        }
        return normalized.equals("http://localhost")
            || normalized.equals("http://127.0.0.1")
            || normalized.equals("https://localhost")
            || normalized.equals("https://127.0.0.1")
            || normalized.startsWith("http://localhost:")
            || normalized.startsWith("http://127.0.0.1:")
            || normalized.startsWith("https://localhost:")
            || normalized.startsWith("https://127.0.0.1:");
        // Non-localhost deployments (e.g. bound to a LAN/VPN interface via --bind):
        // add the exact origin(s) with -Dwebproxy.allowedOrigins=... — never
        // hardcode a deployment address in this list.
    }
}
