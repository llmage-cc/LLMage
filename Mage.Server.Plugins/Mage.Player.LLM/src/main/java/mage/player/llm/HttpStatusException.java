package mage.player.llm;

import java.io.IOException;

/**
 * HTTP error from an LLM chat endpoint, carrying the status code and the
 * provider's Retry-After hint (seconds) so the retry loop can pace itself
 * instead of hammering a rate-limited endpoint with fixed 2s gaps.
 *
 * Retry-After may be an integer number of seconds (the common form) or an
 * HTTP-date — non-integer values are ignored (treated as absent).
 */
public class HttpStatusException extends IOException {

    public final int status;
    /** Retry-After in seconds; -1 when the response didn't carry a usable hint. */
    public final long retryAfterSeconds;

    public HttpStatusException(int status, String responseBody, String retryAfterHeader) {
        super("API returned status " + status + ": " + responseBody);
        this.status = status;
        this.retryAfterSeconds = parseRetryAfter(retryAfterHeader);
    }

    private static long parseRetryAfter(String v) {
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;  // HTTP-date form not parsed — treated as absent
        }
    }

    /** True for rate-limit (429) and transient server errors the provider asks us to retry. */
    public boolean isRetryableWithBackoff() {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }
}
