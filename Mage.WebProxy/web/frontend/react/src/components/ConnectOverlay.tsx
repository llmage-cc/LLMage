import { useState, useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { loadProxyConfig, getProxyConfigSync } from '@/utils/proxyConfig'
import { buildConnectRequest, persistedConnectCreds } from '@/utils/connectPayload'
import RegisterDialog from './RegisterDialog'

/**
 * Connect overlay — shown when not connected to an XMage server.
 * Ported from index.html connect-overlay div + app.js connect method.
 *
 * Username (never the password) is saved to sessionStorage so page refreshes
 * can pre-fill the form. The password is deliberately NOT persisted — it lives
 * only in component state for this connection, and the WS
 * manager keeps it in pendingConnectData until the socket opens.
 *
 * Auth token: optional shared-secret for proxy-level auth (-Dwebproxy.authToken).
 * Only needed when the proxy is hosted beyond localhost. Also session-only.
 *
 * Deployment toggle: when the proxy reports hideServerTarget (GET /config — set
 * with -Dwebproxy.hideServerTarget=true on a deployment that pins the XMage
 * target), the Server/Port fields are not rendered and the connect message omits
 * them. Local/LAN runs keep the fields. The config is fetched before the form
 * becomes interactive so a hidden-target deployment never flashes the fields.
 */
export default function ConnectOverlay() {
  const ws = useGameStore((s) => s.ws)
  const connectError = useGameStore((s) => s.connectError)
  const connecting = useGameStore((s) => s.connecting)
  const setConnecting = useGameStore((s) => s.setConnecting)
  const setConnectError = useGameStore((s) => s.setConnectError)
  // Avatar: the form carries my chosen avatar into the connect message, so
  // the seat's PlayerView carries it from the first state push. Subscribed (not
  // read via getState at submit time) because the auto-reconnect effect below
  // also needs the CURRENT value, and a picker change is a re-render anyway.
  const avatarId = useGameStore((s) => s.avatarId)

  // Default to 127.0.0.1, NOT localhost: on dual-stack hosts localhost resolves
  // ::1 first while the XMage server listens IPv4-only, and the bisocket lease
  // then redirects to a bridge IP that refuses (repo AGENTS.md pitfall —
  // 'localhost' in this field is never what anyone means). Auto-reconnect and
  // form submit both build their payload from
  // this same state, so one default covers all paths.
  const [server, setServer] = useState('127.0.0.1')
  const [port, setPort] = useState('17171')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [authToken, setAuthToken] = useState('')
  const [autoConnecting, setAutoConnecting] = useState(false)
  // The register dialog is a sibling surface of the connect form (the
  // desktop client's Connect button row has the same pair).
  const [showRegister, setShowRegister] = useState(false)
  // null = config not loaded yet — the form renders a waiting state, never the
  // target fields, until the proxy has answered. Seeded from the module cache so
  // a remount (after a failed connect, or a view switch) renders the real form
  // immediately instead of flashing "Loading...".
  const [hideServerTarget, setHideServerTarget] = useState<boolean | null>(
    () => getProxyConfigSync()?.hideServerTarget ?? null
  )

  // Load deployment config (/config) before anything else. Fail Fast: a fetch
  // failure is surfaced in the form; it must NOT silently fall back to showing
  // fields the deployment may have hidden (a wrong guess in either direction
  // breaks the connect form).
  useEffect(() => {
    let cancelled = false
    loadProxyConfig()
      .then((config) => {
        if (!cancelled) setHideServerTarget(config.hideServerTarget)
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setConnectError(
            'The web proxy did not answer GET /config — restart the proxy on this'
            + ' build, then reload the page, and try again.'
            + (err instanceof Error ? ` (${err.message})` : '')
          )
        }
      })
    return () => { cancelled = true }
  }, [setConnectError])

  // Load saved credentials from sessionStorage on mount
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem('xmage-connect-creds')
      if (saved) {
        const creds = JSON.parse(saved)
        if (creds.server) setServer(creds.server)
        if (creds.port) setPort(creds.port)
        if (creds.username) setUsername(creds.username)
        // Password is intentionally not restored — it is not saved (see below).
        // Auto-connect if we have a username. The connect payload no longer
        // carries server/port at all (the proxy pins its target), so this path
        // is identical whether or not the fields are hidden.
        if (creds.username) {
          if (!ws) {
            // Fail Fast: manager not mounted yet — surface instead of a dead form
            setConnectError('Connection manager not ready — reload the page and try again.')
          } else {
            setAutoConnecting(true)
            setConnecting(true)
            setConnectError('')
            // Build the SAME payload the form submit does: server/port are still
            // required by an unpinned proxy, so the auto-reconnect must carry the
            // persisted values (a bare username was rejected "Missing server" on
            // every dev-rig refresh, 2026-09-20 live QA).
            ws.pendingConnectData = buildConnectRequest({
              hideServerTarget: !!hideServerTarget,
              server: creds.server ?? server,
              port: creds.port ?? port,
              username: creds.username,
              password: '',
              authToken: '',
              avatarId,
            })
            ws.connect()
          }
        }
      }
    } catch { /* ignore parse errors */ }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ws])

  const handleConnect = (e: React.FormEvent) => {
    e.preventDefault()
    // Fail Fast: a null ws means the WebSocket manager never mounted (store
    // connect never ran). Previously this returned silently — the live session
    // observed dead Connect buttons with zero feedback (B11-class dead lobby,
    // 2026-09-12 webclient-live-verification NEW finding 4).
    if (!ws) {
      setConnectError('Connection manager not ready — reload the page and try again.')
      return
    }
    if (hideServerTarget === null) {
      setConnectError('Deployment config is still loading — try again in a moment.')
      return
    }
    if (!username.trim()) {
      setConnectError('Username is required')
      return
    }
    setConnecting(true)
    setConnectError('')
    const form = { hideServerTarget, server, port, username, password, authToken, avatarId }
    // Only non-secret fields are persisted for refresh convenience.
    try {
      sessionStorage.setItem('xmage-connect-creds', JSON.stringify(persistedConnectCreds(form)))
    } catch { /* ignore */ }
    // Store the connect data on the WS instance — it will be sent as the
    // first message when the WebSocket opens (in the onopen handler).
    // server/port ride along only when the form owns them (see buildConnectRequest).
    ws.pendingConnectData = buildConnectRequest(form)
    ws.connect()
  }

  return (
    <div
      id="connect-overlay"
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg-color)',
        zIndex: 1000,
      }}
    >
      <form
        onSubmit={handleConnect}
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '12px',
          padding: '32px',
          background: 'var(--accent-color)',
          borderRadius: '8px',
          minWidth: '320px',
        }}
      >
        <h2 style={{ textAlign: 'center', marginBottom: '8px' }}>
          {/* Neutral branding in stock builds — the
              official-server artifact must not carry fork branding ("leaves
              branding neutral"). Compile-time gate: both branches
              collapse to one string per bundle; LLMage builds keep LLMage. */}
          {autoConnecting ? 'Reconnecting to XMage' : __LLMAGE__ ? 'Connect to LLMage' : 'Connect to XMage'}
        </h2>

        {/* Server/Port are deployment-toggled: hidden when the proxy pins the
            XMage target (-Dwebproxy.hideServerTarget=true). See /config. */}
        {hideServerTarget === false && (
          <>
            <label>
              Server:
              <input
                type="text"
                value={server}
                onChange={(e) => setServer(e.target.value)}
                disabled={connecting}
                style={{ width: '100%', padding: '8px', marginTop: '4px' }}
              />
            </label>

            <label>
              Port:
              <input
                type="text"
                value={port}
                onChange={(e) => setPort(e.target.value)}
                disabled={connecting}
                style={{ width: '100%', padding: '8px', marginTop: '4px' }}
              />
            </label>
          </>
        )}

        <label>
          Username:
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            disabled={connecting}
            autoFocus
            style={{ width: '100%', padding: '8px', marginTop: '4px' }}
          />
        </label>

        <label>
          Password:
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={connecting}
            style={{ width: '100%', padding: '8px', marginTop: '4px' }}
          />
        </label>

        <label>
          Auth token (optional — only for hosted proxies):
          <input
            type="password"
            value={authToken}
            onChange={(e) => setAuthToken(e.target.value)}
            disabled={connecting}
            placeholder="(not needed on localhost)"
            style={{ width: '100%', padding: '8px', marginTop: '4px' }}
          />
        </label>

        {connectError && (
          <div
            role="alert"
            style={{ color: 'var(--error-color)', fontSize: '0.875rem' }}
          >
            {connectError}
          </div>
        )}

        <button
          type="submit"
          data-explain="connect-submit"
          disabled={connecting || hideServerTarget === null}
          style={{
            padding: '10px 16px',
            fontSize: '1rem',
            cursor: connecting ? 'not-allowed' : 'pointer',
            opacity: connecting ? 0.6 : 1,
          }}
        >
          {connecting ? 'Connecting...' : hideServerTarget === null ? 'Loading...' : 'Connect'}
        </button>

        {/* Account creation lives beside Connect, the way the desktop
            client's Register button sits next to Connect (ConnectDialog →
            RegisterUserDialog). Only offered once the deployment config resolved:
            the register payload carries the same server/port rule as connect. */}
        <button
          type="button"
          data-explain="connect-register"
          disabled={connecting || hideServerTarget === null}
          onClick={() => setShowRegister(true)}
          style={{
            padding: '8px 16px',
            fontSize: '0.9rem',
            cursor: connecting ? 'not-allowed' : 'pointer',
            opacity: connecting ? 0.6 : 1,
          }}
        >
          Register…
        </button>
      </form>

      {showRegister && <RegisterDialog onClose={() => setShowRegister(false)} />}
    </div>
  )
}
