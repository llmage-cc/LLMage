/**
 * Standalone behavioural check for the deployment toggle that hides the connect
 * form's Server/Port fields.
 *
 * Run with:  bash scripts/verify-connect-target-toggle.sh
 *
 * Covers the frontend half of the feature end to end, with NO browser:
 *  (a) GET /config drives whether the fields are rendered (true = hidden),
 *  (b) the connect message omits server/port when they are hidden and carries
 *      them when they are not (the proxy treats the fields as optional only
 *      with a pin — sending them unpinned is the local/LAN contract),
 *  (c) fail fast: a proxy that cannot answer /config (old proxy → index.html,
 *      500, malformed body) surfaces an error instead of guessing,
 *  (d) persisted credentials no longer require server/port.
 *
 * Same pattern as verify-trigger-order.sh: the REAL component + the REAL payload
 * builder are bundled with browser stubs and rendered through react-dom/server;
 * the fetch is stubbed because no proxy is running in this harness.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import ConnectOverlay from '../src/components/ConnectOverlay'
import { loadProxyConfig, resetProxyConfigCache, type FetchLike } from '../src/utils/proxyConfig'
import { buildConnectRequest, persistedConnectCreds, type ConnectFormState } from '../src/utils/connectPayload'

// ── browser stubs the component needs beyond verify-stubs.mjs ──
const storage = new Map<string, string>()
;(globalThis as unknown as { sessionStorage: unknown }).sessionStorage = {
  getItem: (k: string) => (storage.has(k) ? storage.get(k)! : null),
  setItem: (k: string, v: string) => storage.set(k, String(v)),
  removeItem: (k: string) => storage.delete(k),
}

// ── the WS stub the component talks to ──
const fakeWs = {
  send: () => {},
  connect: () => {},
  disconnect: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  pendingConnectData: null,
}
useGameStore.setState({ ws: fakeWs as never, connectError: '', connecting: false })

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state), so
// point it at the live state to render the store-driven parts faithfully.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/** A fetch stub returning the given body for GET /config. */
const okFetch = (body: unknown): FetchLike =>
  async () => ({ ok: true, status: 200, json: async () => body })

const statusFetch = (status: number): FetchLike =>
  async () => ({ ok: false, status, json: async () => ({}) })

/** Load a config, then render the overlay against it (the component's own order). */
async function renderWithConfig(fetchFn: FetchLike): Promise<string> {
  resetProxyConfigCache()
  await loadProxyConfig(fetchFn)
  return renderToStaticMarkup(<ConnectOverlay />)
}

const formState = (hidden: boolean, overrides: Partial<ConnectFormState> = {}): ConnectFormState => ({
  hideServerTarget: hidden,
  server: 'localhost',
  port: '17171',
  username: 'CityOfBronze',
  password: 'secret',
  authToken: '',
  ...overrides,
})

// ─────────────────────────────────────────────────────────────────────────────
section('The proxy toggles the fields — a hidden deployment renders no target inputs')

{
  const html = await renderWithConfig(okFetch({ hideServerTarget: true }))
  check('no Server field label', !html.includes('Server:'), html)
  check('no Port field label', !html.includes('Port:'), html)
  check('username + password still rendered', html.includes('Username:') && html.includes('Password:'), html)
  check('auth-token field still rendered', html.includes('Auth token'), html)
  check('no stray target values leak into the markup (field values, not prose)',
    !html.includes('value="localhost"') && !html.includes('value="17171"'), html)
}

section('Local/LAN deployment keeps the fields (the toggle is opt-in)')

{
  const html = await renderWithConfig(okFetch({ hideServerTarget: false }))
  check('Server field rendered', html.includes('Server:'), html)
  check('Port field rendered', html.includes('Port:'), html)
  check('defaults are still localhost:17171', html.includes('localhost') && html.includes('17171'), html)
}

section('Fail fast — an unusable /config never silently shows or hides fields')

{
  resetProxyConfigCache()
  let threw: unknown = null
  // An old proxy (no /config route) serves the SPA's index.html for this path.
  try { await loadProxyConfig(okFetch('<html>index.html</html>')) } catch (e) { threw = e }
  check('proxies too old to serve /config throw', threw instanceof Error, String(threw))

  resetProxyConfigCache()
  threw = null
  try { await loadProxyConfig(okFetch({ hideServerTarget: 'yes' })) } catch (e) { threw = e }
  check('a non-boolean hideServerTarget throws', threw instanceof Error, String(threw))

  resetProxyConfigCache()
  threw = null
  try { await loadProxyConfig(statusFetch(500)) } catch (e) { threw = e }
  check('an HTTP failure on /config throws', threw instanceof Error, String(threw))

  resetProxyConfigCache()
  threw = null
  try { await loadProxyConfig(async () => { throw new Error('network down') }) } catch (e) { threw = e }
  check('a network failure on /config throws', threw instanceof Error, String(threw))
}

section('The connect message omits server/port when the deployment hides them')

{
  const hidden = buildConnectRequest(formState(true))
  check('payload has no server key', !('server' in hidden), JSON.stringify(hidden))
  check('payload has no port key', !('port' in hidden), JSON.stringify(hidden))
  check('payload still carries the username', hidden.username === 'CityOfBronze', JSON.stringify(hidden))
  check('payload still carries the password (session-only, never persisted)',
    hidden.password === 'secret', JSON.stringify(hidden))

  const unpinned = buildConnectRequest(formState(false))
  check('unpinned payload keeps server/port',
    unpinned.server === 'localhost' && unpinned.port === 17171, JSON.stringify(unpinned))

  // An auth token is orthogonal to the target fields.
  const withToken = buildConnectRequest(formState(true, { authToken: 'tok' }))
  check('auth token still rides along when hidden',
    withToken.authToken === 'tok' && !('server' in withToken), JSON.stringify(withToken))
}

section('Persisted credentials never carry secrets, and drop server/port when hidden')

{
  const hiddenCreds = persistedConnectCreds(formState(true))
  check('hidden deployment persists only the username',
    hiddenCreds.username === 'CityOfBronze' && hiddenCreds.server === undefined && hiddenCreds.port === undefined,
    JSON.stringify(hiddenCreds))
  check('password is never persisted', !('password' in hiddenCreds), JSON.stringify(hiddenCreds))
  check('auth token is never persisted', !('authToken' in hiddenCreds), JSON.stringify(hiddenCreds))

  const unpinnedCreds = persistedConnectCreds(formState(false))
  check('unpinned deployment persists server/port (unchanged behavior)',
    unpinnedCreds.server === 'localhost' && unpinnedCreds.port === '17171', JSON.stringify(unpinnedCreds))

  // The auto-reconnect path rebuilds a payload from those creds alone.
  const autoPayload = buildConnectRequest(formState(
    hiddenCreds.server === undefined,
    { username: hiddenCreds.username, password: '' }
  ))
  check('auto-reconnect from username-only creds builds a valid connect message',
    autoPayload.type === 'connect' && autoPayload.username === 'CityOfBronze' && !('server' in autoPayload),
    JSON.stringify(autoPayload))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
