/**
 * Account registration form — the wire payload, its validation,
 * and the confirmation-match check, separated from the dialog so all three are
 * provable under Node (scripts/verify-register.sh).
 *
 * ── WHAT THE DESKTOP DOES (the reference) ──
 * Mage.Client .../dialog/RegisterUserDialog.java: fields server, port, username,
 * password, password-confirmation, email ("used for password reset and sending
 * initial password"). Its Register button:
 *   1. refuses when password !== confirmation ("Passwords don't match."),
 *   2. builds a `Connection` with host/port/username/password/EMAIL,
 *   3. calls SessionHandler.register(connection) — a SEPARATE RPC from login
 *      (`sendAuthRegister` → server `authRegister`), NOT the connect handshake.
 * The server generates the account's real 10-char password and mails it; the
 * typed password never becomes the credential. The dialog's own status line
 * reports "Registration succeeded" / "Could not register".
 *
 * ── THE WEB CONTRACT ──
 * Same fields, same order, same confirmation check. server/port follow the
 * connect message's deployment rule verbatim (utils/connectPayload.ts): sent only
 * when the form owns them, omitted when the proxy pins the target. The answer
 * arrives as `register_success` / `register_error` (see messageHandlers.ts).
 */

/** The registration form's fields — mirrors the desktop dialog's boxes. */
export interface RegisterFormState {
  hideServerTarget: boolean
  server: string
  port: string
  username: string
  password: string
  passwordConfirmation: string
  email: string
  /** Optional shared-secret for proxy-level auth (-Dwebproxy.authToken). */
  authToken?: string
}

/**
 * The server's account-name rule, mirrored for inline feedback (see
 * WebProxySession.validateRegisterUsername — that is the proxy's copy of the
 * SAME server rule: config.xml minUserNameLength/maxUserNameLength +
 * invalidUserNamePattern `[^a-z0-9_]`). The proxy is the authority on the wire;
 * this lets the dialog answer the common rejections without a dial.
 */
export const REGISTER_USERNAME_MIN_LENGTH = 3
export const REGISTER_USERNAME_MAX_LENGTH = 14
export const REGISTER_USERNAME_ALLOWED = /^[a-zA-Z0-9_]+$/

/**
 * Client-side validation of the registration form. Returns the first problem as a
 * user-facing message, or null when the form may be sent.
 *
 * Fail Fast, and deliberately NOT silently-normalizing: a value is either
 * accepted as typed (trimmed) or refused with the rule that refused it. The
 * desktop's only client-side check is the password confirmation — the name/email
 * rules here exist because on the web a round-trip costs seconds and the server's
 * refusals were previously invisible.
 *
 * Email is checked for the server's own minimum (non-empty, no spaces) plus an
 * '@' — the server's validateEmail only rejects blank/duplicate, so anything
 * stricter here would refuse addresses the server would accept.
 */
export function validateRegisterForm(form: RegisterFormState): string | null {
  const username = form.username.trim()
  if (username.length === 0) return 'Username is required'
  if (username.length < REGISTER_USERNAME_MIN_LENGTH) {
    return `User name may not be shorter than ${REGISTER_USERNAME_MIN_LENGTH} characters`
  }
  if (username.length > REGISTER_USERNAME_MAX_LENGTH) {
    return `User name may not be longer than ${REGISTER_USERNAME_MAX_LENGTH} characters`
  }
  if (!REGISTER_USERNAME_ALLOWED.test(username)) {
    return `User name '${username}' includes not allowed characters: use a-z, A-Z and 0-9`
  }
  // The desktop's own check, verbatim in intent: passwords must match.
  if (form.password !== form.passwordConfirmation) return 'Passwords don\u2019t match.'
  const email = form.email.trim()
  if (email.length === 0) return 'Email is required — the server sends the initial password to it'
  if (/\s/.test(email) || !email.includes('@')) return 'That does not look like an email address'
  return null
}

/**
 * Build the `register` message for the proxy.
 *
 * server/port ride along only when the form shows them (`hideServerTarget` from
 * GET /config): sending a target the form never showed would misrepresent it, and
 * a pinned proxy ignores client-supplied values anyway — identical to
 * buildConnectRequest. authToken is included only when non-empty (the proxy's
 * -Dwebproxy.authToken deployments; the register frame carries it so a fresh user
 * can register before any successful connect exists).
 */
export function buildRegisterRequest(form: RegisterFormState): {
  type: 'register'
  username: string
  password: string
  email: string
  authToken?: string
  server?: string
  port?: number
} {
  const base = {
    type: 'register' as const,
    username: form.username.trim(),
    password: form.password,
    email: form.email.trim(),
    ...(form.authToken ? { authToken: form.authToken } : {}),
  }
  if (form.hideServerTarget) {
    return base
  }
  return { ...base, server: form.server, port: parseInt(form.port) }
}
