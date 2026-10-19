/**
 * Deployment config served by the web proxy at GET /config.
 *
 * The proxy is the only component that knows how a deployment is hosted, so the
 * browser asks it (rather than guessing from its own URL) whether the connect
 * form should expose the XMage target fields. Fetched once per page load and
 * cached in a module-level variable so both the initial render path and the
 * auto-reconnect path agree on the same value.
 */

export interface ProxyConfig {
  /**
   * True when the proxy pins the XMage target itself
   * (-Dwebproxy.hideServerTarget=true + -Dwebproxy.pinnedXmageTarget): the
   * connect form hides Server/Port and the connect message omits them.
   */
  hideServerTarget: boolean
}

/** Minimal fetch shape — lets the Node harness drive failures without a server. */
export type FetchLike = (
  url: string,
  init?: { cache?: RequestCache }
) => Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>

let cached: ProxyConfig | null = null

/** The loaded config, or null when it has not been fetched yet. */
export function getProxyConfigSync(): ProxyConfig | null {
  return cached
}

/**
 * Fetch GET /config from the proxy. Fail Fast: a proxy that is too old to serve
 * /config answers with the SPA's index.html, and a non-boolean body is a
 * protocol change — both throw instead of silently falling back to showing the
 * deployment's target fields.
 */
export async function loadProxyConfig(fetchFn: FetchLike = fetch): Promise<ProxyConfig> {
  const res = await fetchFn('/config', { cache: 'no-store' })
  if (!res.ok) {
    throw new Error(`GET /config failed: HTTP ${res.status}`)
  }
  const body = await res.json()
  if (
    typeof body !== 'object' ||
    body === null ||
    typeof (body as { hideServerTarget?: unknown }).hideServerTarget !== 'boolean'
  ) {
    throw new Error('GET /config returned an unexpected body (proxy too old?)')
  }
  cached = { hideServerTarget: (body as ProxyConfig).hideServerTarget }
  return cached
}

/** Test-only: drop the module cache so a harness can exercise every state. */
export function resetProxyConfigCache(): void {
  cached = null
}
