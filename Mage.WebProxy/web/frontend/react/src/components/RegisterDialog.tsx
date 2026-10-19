import { useEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { getProxyConfigSync } from '@/utils/proxyConfig'
import {
  buildRegisterRequest,
  validateRegisterForm,
  REGISTER_USERNAME_MAX_LENGTH,
  type RegisterFormState,
} from '@/utils/registerForm'

/**
 * Register dialog — create an XMage account before logging in.
 *
 * Desktop reference: Mage.Client .../dialog/RegisterUserDialog.java. Same fields
 * in the same order (server, port, username, password, password confirmation,
 * email), same client-side check (the two passwords must match), and the same
 * note on the email box: the server generates the initial password and mails it
 * there. The registration is a SEPARATE RPC from login on the desktop too
 * (SessionHandler.register → sendAuthRegister → server authRegister) — the web
 * flow mirrors that with the proxy's `register` verb.
 *
 * Where the state lives: the request/answer pair is store state (`registering`,
 * `registerResult`, connectionSlice) because the answer arrives as a WebSocket
 * frame handled in messageHandlers. The form's own fields are component state —
 * a password must never outlive the dialog.
 *
 * Server/port: shown only when the connect form owns them (`hideServerTarget`
 * from GET /config); a pinned deployment registers against the proxy's own target
 * and the payload omits them, exactly like the connect payload.
 */
export default function RegisterDialog({ onClose }: { onClose: () => void }) {
  const registering = useGameStore((s) => s.registering)
  const registerResult = useGameStore((s) => s.registerResult)
  const registerAccount = useGameStore((s) => s.registerAccount)
  const clearRegisterResult = useGameStore((s) => s.clearRegisterResult)

  // Seed server/port from the connect form's own stored credentials so the two
  // dialogs agree on the target; the deployment toggle decides whether they are
  // sent at all (getProxyConfigSync — already loaded by the connect overlay).
  const stored = readStoredCreds()

  const [server, setServer] = useState(stored.server ?? '127.0.0.1')
  const [port, setPort] = useState(stored.port ?? '17171')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [email, setEmail] = useState('')
  // Local, synchronous problems (validation / confirmation mismatch) — shown
  // inline without touching the store, so a typo never looks like a server error.
  const [localError, setLocalError] = useState('')

  const firstFieldRef = useRef<HTMLInputElement | null>(null)

  // A dialog opens onto a fresh form: drop any answer from a previous attempt.
  useEffect(() => {
    clearRegisterResult()
    firstFieldRef.current?.focus()
  }, [clearRegisterResult])

  const hideServerTarget = getProxyConfigSync()?.hideServerTarget ?? false
  const busy = registering
  // The success state is terminal for this dialog: the account exists, the
  // server has (in test mode) handed over the generated password in the message,
  // and the only next step is logging in — which the Close button returns to.
  const succeeded = registerResult?.ok === true

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (busy || succeeded) return
    const form: RegisterFormState = {
      hideServerTarget,
      server,
      port,
      username,
      password,
      passwordConfirmation,
      email,
    }
    const problem = validateRegisterForm(form)
    if (problem) {
      setLocalError(problem)
      return
    }
    setLocalError('')
    registerAccount(buildRegisterRequest(form))
  }

  return (
    <div
      id="register-overlay"
      role="dialog"
      aria-label="Register"
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'rgba(0, 0, 0, 0.55)',
        zIndex: 1100,
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '10px',
          padding: '24px',
          background: 'var(--accent-color)',
          borderRadius: '8px',
          minWidth: '340px',
          maxWidth: '420px',
        }}
      >
        <h3 style={{ textAlign: 'center', marginBottom: '4px' }}>Register an account</h3>

        {/* Server/Port mirror the connect overlay: deployment-toggled, hidden when
            the proxy pins the XMage target (-Dwebproxy.hideServerTarget=true). */}
        {hideServerTarget === false && (
          <>
            <label>
              Server:
              <input
                type="text"
                value={server}
                onChange={(e) => setServer(e.target.value)}
                disabled={busy || succeeded}
                style={{ width: '100%', padding: '6px', marginTop: '2px' }}
              />
            </label>
            <label>
              Port:
              <input
                type="text"
                value={port}
                onChange={(e) => setPort(e.target.value)}
                disabled={busy || succeeded}
                style={{ width: '100%', padding: '6px', marginTop: '2px' }}
              />
            </label>
          </>
        )}

        <label>
          User name:
          <input
            ref={firstFieldRef}
            type="text"
            data-explain="register-username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            disabled={busy || succeeded}
            maxLength={REGISTER_USERNAME_MAX_LENGTH}
            style={{ width: '100%', padding: '6px', marginTop: '2px' }}
          />
        </label>

        <label>
          Password:
          <input
            type="password"
            data-explain="register-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={busy || succeeded}
            style={{ width: '100%', padding: '6px', marginTop: '2px' }}
          />
        </label>

        <label>
          Password (confirmation):
          <input
            type="password"
            data-explain="register-password-confirmation"
            value={passwordConfirmation}
            onChange={(e) => setPasswordConfirmation(e.target.value)}
            disabled={busy || succeeded}
            style={{ width: '100%', padding: '6px', marginTop: '2px' }}
          />
        </label>

        <label>
          Email:
          <input
            type="email"
            data-explain="register-email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={busy || succeeded}
            style={{ width: '100%', padding: '6px', marginTop: '2px' }}
          />
        </label>
        <div style={{ fontSize: '0.75rem', opacity: 0.8 }}>
          (used for password reset and sending the initial password)
        </div>

        {localError && (
          <div role="alert" style={{ color: 'var(--error-color)', fontSize: '0.875rem' }}>
            {localError}
          </div>
        )}

        {/* The proxy's answer. A failure keeps the form editable (the user fixes
            the name/email and resubmits); a success replaces it with the server's
            own text — which in the server's test mode carries the generated
            initial password. */}
        {registerResult && (
          <div
            role={registerResult.ok ? 'status' : 'alert'}
            style={{
              color: registerResult.ok ? 'var(--text-color)' : 'var(--error-color)',
              fontSize: '0.875rem',
              whiteSpace: 'pre-wrap',
            }}
          >
            {registerResult.message}
          </div>
        )}

        <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
          <button
            type="button"
            data-explain="register-cancel"
            onClick={onClose}
            style={{ padding: '8px 14px', cursor: 'pointer' }}
          >
            {succeeded ? 'Close' : 'Cancel'}
          </button>
          {!succeeded && (
            <button
              type="submit"
              data-explain="register-submit"
              disabled={busy}
              style={{
                padding: '8px 14px',
                cursor: busy ? 'not-allowed' : 'pointer',
                opacity: busy ? 0.6 : 1,
              }}
            >
              {busy ? 'Registering…' : 'Register'}
            </button>
          )}
        </div>
      </form>
    </div>
  )
}

/**
 * The connect form's persisted (non-secret) target, so both dialogs start on the
 * same server. Parsing failures are ignored on purpose — a corrupt entry must not
 * break the register dialog, and the fields are on screen with defaults anyway.
 */
function readStoredCreds(): { server?: string; port?: string } {
  try {
    const raw = sessionStorage.getItem('xmage-connect-creds')
    if (!raw) return {}
    const parsed = JSON.parse(raw) as { server?: string; port?: string }
    const out: { server?: string; port?: string } = {}
    if (typeof parsed.server === 'string') out.server = parsed.server
    if (typeof parsed.port === 'string') out.port = parsed.port
    return out
  } catch {
    return {}
  }
}
