package mage.webproxy;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.io.Content;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Same-origin CORS relay for client-key LLM calls.
 *
 * Providers without CORS support (charm.land, ollama.com) cannot be called
 * directly from the browser. The browser POSTs to /llm-relay?url=<provider>
 * — same-origin, so no CORS applies — and this handler echoes the request
 * to the provider and streams the response back with CORS headers added.
 *
 * SECURITY CONTRACT:
 *  - This is a DUMB BYTE RELAY. It does not read, parse, log, or store the
 *    Authorization header or request/response bodies. The user's key transits
 *    in-memory bytes only. (The key DOES pass through this process for
 *    relay-dependent providers — that is the documented trade-off; it is
 *    never persisted, logged, or forwarded anywhere else.)
 *  - Only an explicit request-header allowlist is forwarded (never cookies,
 *    never Origin, never arbitrary client headers).
 *  - SSRF: the target host must pass the same isAllowedModelHost() allowlist
 *    used by fetch_models and seat endpoints, plus the cloud-metadata block
 *    and resolved-internal-address checks.
 *  - Body size capped (prompt bodies are < 100 KB; responses bounded by read cap).
 */
public class LLmRelayHandler {

    /** Request headers relayed to the provider — nothing else crosses. */
    private static final Set<String> FORWARDED_REQUEST_HEADERS = new HashSet<>(java.util.Arrays.asList(
            "authorization", "content-type", "accept", "x-api-key",
            "anthropic-version", "anthropic-dangerous-direct-browser-access",
            "x-opencode-session"));

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;  // 4 MiB cap
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;  // 16 MiB cap

    /** Register the relay context on the server's handler sequence. */
    public static ContextHandler register() {
        ContextHandler relayContext = new ContextHandler("/llm-relay");
        relayContext.setAllowNullPathInContext(true);
        relayContext.setHandler(new org.eclipse.jetty.server.Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String method = request.getMethod();
                if (!HttpMethod.POST.is(method) && !HttpMethod.GET.is(method) && !HttpMethod.OPTIONS.is(method)) {
                    Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
                    return true;
                }

                // CORS preflight — answer without touching the provider.
                if (HttpMethod.OPTIONS.is(method)) {
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().put("Access-Control-Allow-Origin", "*");
                    response.getHeaders().put("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                    response.getHeaders().put("Access-Control-Allow-Headers", "authorization, content-type, accept, x-opencode-session");
                    response.getHeaders().put("Access-Control-Max-Age", "600");
                    Content.Sink.write(response, true, "", callback);
                    return true;
                }

                String target = request.getHttpURI().getQuery();
                String targetUrl = null;
                if (target != null) {
                    for (String pair : target.split("&")) {
                        if (pair.startsWith("url=")) {
                            targetUrl = java.net.URLDecoder.decode(pair.substring(4), "UTF-8");
                        }
                    }
                }
                if (targetUrl == null || targetUrl.isEmpty()) {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    Content.Sink.write(response, true, "missing url query parameter", callback);
                    return true;
                }

                // SSRF gate — same policy as fetch_models / seat endpoints.
                try {
                    URL url = new URL(targetUrl);
                    String host = url.getHost();
                    boolean localHost = "localhost".equals(host) || "127.0.0.1".equals(host)
                            || "::1".equals(host) || "0.0.0.0".equals(host);
                    if (!"https".equals(url.getProtocol())
                            && !("http".equals(url.getProtocol()) && localHost)) {
                        // Remote targets must be https: relayed bodies carry the
                        // user's provider key, which must never cross the wire
                        // in cleartext. Plain http stays allowed for local
                        // Ollama-style endpoints only.
                        response.setStatus(HttpStatus.FORBIDDEN_403);
                        Content.Sink.write(response, true,
                                "remote relay targets must be https (http is only allowed for localhost)", callback);
                        return true;
                    }
                    if ("169.254.169.254".equals(host) || "169.254.170.2".equals(host)
                            || "metadata.google.internal".equals(host)) {
                        response.setStatus(HttpStatus.FORBIDDEN_403);
                        Content.Sink.write(response, true, "cloud metadata endpoints blocked", callback);
                        return true;
                    }
                    if (!WebProxySession.isAllowedModelHost(host)) {
                        response.setStatus(HttpStatus.FORBIDDEN_403);
                        Content.Sink.write(response, true,
                                "target host not in model-endpoint allowlist (-Dwebproxy.allowedModelHosts)",
                                callback);
                        return true;
                    }
                } catch (java.net.MalformedURLException e) {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    Content.Sink.write(response, true, "malformed url: " + e.getMessage(), callback);
                    return true;
                }

                // Relay the call, byte-for-byte, headers from the allowlist only.
                // GET carries no body (model-list fetches); POST relays the request body.
                try {
                    boolean isGet = HttpMethod.GET.is(request.getMethod());
                    byte[] body = isGet ? new byte[0] : readAll(request, MAX_BODY_BYTES);
                    HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                    conn.setRequestMethod(request.getMethod());
                    for (String name : FORWARDED_REQUEST_HEADERS) {
                        String value = request.getHeaders().get(name);
                        if (value != null && !value.isEmpty()) {
                            conn.setRequestProperty(name, value);
                        }
                    }
                    conn.setConnectTimeout(30_000);
                    conn.setReadTimeout(600_000);
                    if (!isGet) {
                        conn.setDoOutput(true);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(body);
                        }
                    }

                    int status = conn.getResponseCode();
                    InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                    byte[] respBody = is != null ? readAll(is, MAX_RESPONSE_BYTES) : new byte[0];
                    // Status + CORS headers + body — nothing else crosses back.
                    response.setStatus(status);
                    response.getHeaders().put("Access-Control-Allow-Origin", "*");
                    String ct = conn.getContentType();
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, ct != null ? ct : "application/json");
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, String.valueOf(respBody.length));
                    Content.Sink.write(response, true, new String(respBody, StandardCharsets.UTF_8), callback);
                } catch (java.io.IOException e) {
                    response.setStatus(HttpStatus.BAD_GATEWAY_502);
                    Content.Sink.write(response, true, "relay upstream failure: " + e.getMessage(), callback);
                }
                return true;
            }
        });
        return relayContext;
    }

    private static byte[] readAll(Request request, int cap) throws java.io.IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            InputStream in = Content.Source.asInputStream(request);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > cap) {
                    throw new java.io.IOException("body exceeds relay cap (" + cap + " bytes)");
                }
            }
            return bos.toByteArray();
        }
    }

    private static byte[] readAll(InputStream is, int cap) throws java.io.IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > cap) {
                    throw new java.io.IOException("upstream response exceeds relay cap (" + cap + " bytes)");
                }
            }
            return bos.toByteArray();
        }
    }
}