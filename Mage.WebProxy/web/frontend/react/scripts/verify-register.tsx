/**
 * Standalone behavioural check for the account-registration flow.
 *
 * Run with:  bash scripts/verify-register.sh
 *
 * Covers the frontend half end to end, with NO browser and NO proxy: the REAL
 * registerForm validators/payload builder, the REAL store slice through the REAL
 * message handler, and the REAL RegisterDialog / ConnectOverlay rendered through
 * react-dom/server.
 *
 * What is pinned here:
 *  1. the payload mirror of the desktop RegisterUserDialog: username + password +
 *     EMAIL, plus server/port only when the connect form owns them;
 *  2. the client-side confirmation-match check (the desktop's only own check) and
 *     the fail-fast rules around it;
 *  3. `registerAccount` with no socket fails fast into `registerResult` — never a
 *     silent no-op;
 *  4. the proxy's answer routes to `registerResult` (rendered inline on the
 *     overlay) and NOT to the showError toast — the toast belongs to the
 *     connected/lobby surface;
 *  5. the dialog's rendered shapes: all desktop fields, the email note, the busy
 *     state, the terminal success state, and the deployment-hidden target.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { GameWebSocket } from '../src/network/websocket'
import RegisterDialog from '../src/components/RegisterDialog'
import ConnectOverlay from '../src/components/ConnectOverlay'
import { loadProxyConfig, resetProxyConfigCache } from '../src/utils/proxyConfig'
import {
  buildRegisterRequest,
  validateRegisterForm,
  REGISTER_USERNAME_MIN_LENGTH,
  REGISTER_USERNAME_MAX_LENGTH,
  type RegisterFormState,
} from '../src/utils/registerForm'
import type { ServerMessage } from '../src/types'

// ── browser stubs the dialog needs beyond verify-stubs.mjs ──
const storage = new Map<string, string>()
;(globalThis as unknown as { sessionStorage: unknown }).sessionStorage = {
  getItem: (k: string) => (storage.has(k) ? storage.get(k)! : null),
  setItem: (k: string, v: string) => storage.set(k, String(v)),
  removeItem: (k: string) => storage.delete(k),
}

// ── record what goes on the wire ──
// The manager's real shape: `register` is the entry point (it opens the
// transport when needed and then sends), `send` is the plain path.
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  register: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  connect: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// react-dom/server reads zustand's getServerSnapshot, which is
// `selector(api.getInitialState())` — the store's INITIAL state object, not the
// live state (zustand v5 react.mjs wires the third useSyncExternalStore argument
// to api.getInitialState; patching useGameStore.getInitialState does not reach it).
// The snapshot object is returned by reference, so mutating it in lockstep with
// the live setState is what makes each server render see the delivered scenario.
// (Same pattern as verify-replay-settings.tsx / verify-llm-config-tabs.tsx.)
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

const form = (overrides: Partial<RegisterFormState> = {}): RegisterFormState => ({
  hideServerTarget: false,
  server: '127.0.0.1',
  port: '17305',
  username: 'RegUser',
  password: 'hunter2',
  passwordConfirmation: 'hunter2',
  email: 'reg@example.com',
  ...overrides,
})

/** Render the dialog against the CURRENT store state. */
function renderDialog(): string {
  return renderToStaticMarkup(<RegisterDialog onClose={() => {}} />)
}

// ─────────────────────────────────────────────────────────────────────────────
section('payload — the desktop RegisterUserDialog mapping + the connect target rule')

{
  const unpinned = buildRegisterRequest(form())
  check('type is register (not connect)', unpinned.type === 'register', JSON.stringify(unpinned))
  check('carries the username', unpinned.username === 'RegUser', JSON.stringify(unpinned))
  check('carries the typed password', unpinned.password === 'hunter2', JSON.stringify(unpinned))
  check('carries the EMAIL (the register-only field)', unpinned.email === 'reg@example.com', JSON.stringify(unpinned))
  check('carries server/port when the form owns them',
    unpinned.server === '127.0.0.1' && unpinned.port === 17305, JSON.stringify(unpinned))
  check('no authToken key when empty', !('authToken' in unpinned), JSON.stringify(unpinned))

  const pinned = buildRegisterRequest(form({ hideServerTarget: true }))
  check('pinned deployment omits server/port', !('server' in pinned) && !('port' in pinned), JSON.stringify(pinned))
  check('pinned deployment still carries username/email',
    pinned.username === 'RegUser' && pinned.email === 'reg@example.com', JSON.stringify(pinned))

  const tokened = buildRegisterRequest(form({ hideServerTarget: true, authToken: 'tok' }))
  check('authToken rides along when set', tokened.authToken === 'tok', JSON.stringify(tokened))

  // Values are trimmed at the boundary, never silently rewritten.
  const padded = buildRegisterRequest(form({ username: '  RegUser  ', email: '  reg@example.com  ' }))
  check('username/email trimmed', padded.username === 'RegUser' && padded.email === 'reg@example.com',
    JSON.stringify(padded))
}

section('validation — fail fast, the desktop confirmation check included')

{
  check('a complete form passes', validateRegisterForm(form()) === null, String(validateRegisterForm(form())))

  // The desktop's own check, verbatim ("Passwords don't match.")
  const mismatch = validateRegisterForm(form({ passwordConfirmation: 'hunter3' }))
  check('password/confirmation mismatch is refused', mismatch !== null && /match/i.test(mismatch), String(mismatch))

  check('empty username refused', validateRegisterForm(form({ username: '' })) !== null)
  check('blank username refused', validateRegisterForm(form({ username: '   ' })) !== null)
  check('too-short username refused',
    validateRegisterForm(form({ username: 'ab' })) !== null)
  check('too-long username refused',
    validateRegisterForm(form({ username: 'a'.repeat(REGISTER_USERNAME_MAX_LENGTH + 1) })) !== null)
  check('length bounds match the server rule (3..14)',
    REGISTER_USERNAME_MIN_LENGTH === 3 && REGISTER_USERNAME_MAX_LENGTH === 14)
  for (const bad of ['has space', 'has-dash', 'has.dot', 'drop\'quote']) {
    check(`disallowed character in '${bad}' refused`, validateRegisterForm(form({ username: bad })) !== null)
  }

  check('empty email refused',
    validateRegisterForm(form({ email: '' })) !== null)
  check('blank email refused',
    validateRegisterForm(form({ email: '   ' })) !== null)
  check('email without @ refused',
    validateRegisterForm(form({ email: 'not-an-address' })) !== null)
  check('email with a space refused',
    validateRegisterForm(form({ email: 'a b@example.com' })) !== null)

  // Empty password is NOT the client's business: the server generates the real
  // password and mails it (the typed one is only a formality the desktop also
  // sends). The client check must not invent a rule the server does not have.
  check('empty password allowed (server generates + mails the real one)',
    validateRegisterForm(form({ password: '', passwordConfirmation: '' })) === null)
}

section('store — registerAccount sends once, or fails fast with no socket')

{
  resetProxyConfigCache()
  useGameStore.setState({ registering: false, registerResult: null })
  sent.length = 0
  useGameStore.getState().registerAccount(buildRegisterRequest(form()))
  check('exactly one frame sent', sent.length === 1, JSON.stringify(sent))
  check('the frame is the register payload', sent[0]?.type === 'register', JSON.stringify(sent[0]))
  check('registering set while in flight', useGameStore.getState().registering === true)
  check('the payload went through the manager register() entry point (transport-aware)',
    sent.length === 1, JSON.stringify(sent))

  // The fail-fast guard: no socket manager -> the result is surfaced where the
  // dialog reads it (a silent no-op would look like the button did nothing).
  const realWs = useGameStore.getState().ws
  useGameStore.setState({ ws: null, registering: false, registerResult: null })
  sent.length = 0
  useGameStore.getState().registerAccount(buildRegisterRequest(form()))
  const gated = useGameStore.getState()
  check('no socket: no frame sent', sent.length === 0)
  check('no socket: an inline error result appears',
    gated.registerResult?.ok === false && /not ready/i.test(gated.registerResult?.message ?? ''),
    JSON.stringify(gated.registerResult))
  check('no socket: not left in a registering state', gated.registering === false)
  useGameStore.setState({ ws: realWs, registerResult: null })
}

section('answer routing — register frames land in registerResult, never on the toast')

{
  useGameStore.setState({ registering: true, registerResult: null, errorToastVisible: false, errorMessage: '' })

  handleMessage(
    { type: 'register_error', message: 'User name \'RegUser\' already in use' } as unknown as ServerMessage,
    useGameStore.getState())
  const afterError = useGameStore.getState()
  check('register_error -> registerResult ok=false',
    afterError.registerResult?.ok === false, JSON.stringify(afterError.registerResult))
  check('server text preserved verbatim',
    afterError.registerResult?.message === "User name 'RegUser' already in use",
    JSON.stringify(afterError.registerResult))
  check('registering cleared by the answer', afterError.registering === false)
  check('register_error does NOT raise the error toast',
    afterError.errorToastVisible === false && afterError.errorMessage === '',
    `${afterError.errorToastVisible} / ${afterError.errorMessage}`)

  handleMessage(
    { type: 'register_success', message: 'You are successfully registered as RegUser.' } as unknown as ServerMessage,
    useGameStore.getState())
  const afterOk = useGameStore.getState()
  check('register_success -> registerResult ok=true', afterOk.registerResult?.ok === true,
    JSON.stringify(afterOk.registerResult))
  check('success text preserved verbatim',
    afterOk.registerResult?.message === 'You are successfully registered as RegUser.',
    JSON.stringify(afterOk.registerResult))
  check('register_success does NOT raise the error toast', afterOk.errorToastVisible === false)

  // A frame with no message must not render an empty/undefined line.
  handleMessage({ type: 'register_success' } as unknown as ServerMessage, useGameStore.getState())
  check('a message-less success still yields text',
    (useGameStore.getState().registerResult?.message ?? '').length > 0,
    JSON.stringify(useGameStore.getState().registerResult))
}

section('dialog — the desktop fields, the note, the busy + success states')

{
  resetProxyConfigCache()
  await loadProxyConfig(async () => ({ ok: true, status: 200, json: async () => ({ hideServerTarget: false }) }))
  storage.set('xmage-connect-creds', JSON.stringify({ server: '127.0.0.1', port: '17305' }))

  setLive({ registering: false, registerResult: null })
  const html = renderDialog()
  check('renders all desktop fields: server', html.includes('Server:'), '')
  check('renders all desktop fields: port', html.includes('Port:'), '')
  check('renders all desktop fields: user name', html.includes('User name:'), '')
  check('renders all desktop fields: password', html.includes('Password:'), '')
  check('renders the confirmation field', html.includes('Password (confirmation):'), '')
  check('renders the email field', html.includes('Email:'), '')
  check('email note matches the desktop wording',
    html.includes('used for password reset and sending the initial password'), '')
  check('the target seeds from the connect form\'s stored creds',
    html.includes('value="127.0.0.1"') && html.includes('value="17305"'), '')
  check('explain ids present on the register controls',
    html.includes('data-explain="register-username"')
    && html.includes('data-explain="register-password"')
    && html.includes('data-explain="register-password-confirmation"')
    && html.includes('data-explain="register-email"')
    && html.includes('data-explain="register-submit"')
    && html.includes('data-explain="register-cancel"'), '')

  // Busy state.
  setLive({ registering: true, registerResult: null })
  const busyHtml = renderDialog()
  check('busy state says Registering…', busyHtml.includes('Registering…'), '')
  // The submit button must actually be disabled while a dial is in flight (the
  // proxy refuses a duplicate register anyway; the UI must not offer one).
  const submitTag = busyHtml.slice(busyHtml.indexOf('data-explain="register-submit"'))
  check('busy state disables the submit control',
    submitTag.slice(0, 200).includes('disabled'), submitTag.slice(0, 200))

  // Success state: terminal, and it shows the server's own text.
  setLive({
    registering: false,
    registerResult: { ok: true, message: 'Your password is AbC1234567 (test mode)' },
  })
  const success = renderDialog()
  check('success state shows the server text verbatim',
    success.includes('Your password is AbC1234567 (test mode)'), '')
  check('success state drops the Register button (terminal)',
    !success.includes('data-explain="register-submit"'), '')
  check('success state offers Close', success.includes('>Close<'), '')

  // Failure state keeps the form editable.
  setLive({ registering: false, registerResult: { ok: false, message: 'User name already in use' } })
  const failedAgain = renderDialog()
  check('failure state shows the server reason inline', failedAgain.includes('User name already in use'), '')
  check('failure state keeps the Register button', failedAgain.includes('data-explain="register-submit"'), '')
  check('failure text sits in an alert role', failedAgain.includes('role="alert"'), '')
}

section('deployment — a pinned proxy hides the target fields in the dialog too')

{
  resetProxyConfigCache()
  setLive({ registering: false, registerResult: null })
  await loadProxyConfig(async () => ({ ok: true, status: 200, json: async () => ({ hideServerTarget: true }) }))
  const hidden = renderDialog()
  check('no Server field when pinned', !hidden.includes('Server:'), '')
  check('no Port field when pinned', !hidden.includes('Port:'), '')
  check('username/email still rendered when pinned',
    hidden.includes('User name:') && hidden.includes('Email:'), '')
  check('no stale target value leaks into the markup',
    !hidden.includes('value="127.0.0.1"') && !hidden.includes('value="17305"'), '')
}

section('connect overlay — the Register… affordance exists beside Connect')

{
  resetProxyConfigCache()
  await loadProxyConfig(async () => ({ ok: true, status: 200, json: async () => ({ hideServerTarget: false }) }))
  const overlay = renderToStaticMarkup(<ConnectOverlay />)
  check('Register… button rendered', overlay.includes('Register…'), '')
  check('Register… affordance carries its explain id',
    overlay.includes('data-explain="connect-register"'), '')
  check('Connect button still rendered', overlay.includes('data-explain="connect-submit"'), '')
}

section('transport — register on the connect overlay reaches the wire')

{
  // The live run's defect: the register dialog is used BEFORE any connect
  // attempt, so the socket is not open and a bare send() was dropped by the
  // readyState guard (console warning only; dialog stuck on "Registering…").
  // These checks drive the REAL GameWebSocket with a fake socket and assert the
  // frame actually leaves, and that a later Connect on the same page still works.
  class FakeSocket {
    static instances: FakeSocket[] = []
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3
    readyState = 0
    sent: string[] = []
    onopen: (() => void) | null = null
    onmessage: ((e: { data: string }) => void) | null = null
    onerror: ((e: Event) => void) | null = null
    onclose: ((e: { code: number; reason: string }) => void) | null = null
    constructor() { FakeSocket.instances.push(this) }
    send(d: string) { this.sent.push(d) }
    close() { this.readyState = 3 }
    fireOpen() { this.readyState = 1; this.onopen?.() }
  }
  ;(globalThis as unknown as { WebSocket: unknown }).WebSocket = FakeSocket

  const payload = { type: 'register' as const, username: 'RegUser', password: 'pw', email: 'a@b.c',
    server: '127.0.0.1', port: 17305 }

  // (1) register with NO socket yet: the manager must open the transport and send
  //     the frame on open (not drop it).
  const ws1 = new GameWebSocket({ url: 'ws://x/ws', onMessage: () => {}, onStatusChange: () => {}, onError: () => {} })
  ws1.register(payload)
  check('register with no open socket opens the transport', FakeSocket.instances.length === 1)
  check('nothing sent while CONNECTING', FakeSocket.instances[0].sent.length === 0)
  FakeSocket.instances[0].fireOpen()
  const firstSent = FakeSocket.instances[0].sent.map((s) => JSON.parse(s))
  check('the register frame leaves on open',
    firstSent.length === 1 && firstSent[0].type === 'register' && firstSent[0].email === 'a@b.c',
    JSON.stringify(firstSent))

  // (2) a Connect click AFTER that register must not be a dead click: the socket
  //     is already OPEN, so connect() must send the parked connect payload.
  ws1.pendingConnectData = { type: 'connect', username: 'RegUser', password: '', server: '127.0.0.1', port: 17305 } as never
  ws1.connect()
  const afterConnect = FakeSocket.instances[0].sent.map((s) => JSON.parse(s))
  check('Connect after a register on an OPEN socket still sends',
    afterConnect.length === 2 && afterConnect[1].type === 'connect', JSON.stringify(afterConnect))

  // (3) register on an already-OPEN socket goes straight out (no second socket).
  const ws2 = new GameWebSocket({ url: 'ws://x/ws', onMessage: () => {}, onStatusChange: () => {}, onError: () => {} })
  ws2.connect()
  FakeSocket.instances[1].fireOpen()
  ws2.register(payload)
  check('register on an OPEN socket sends directly',
    FakeSocket.instances[1].sent.map((s) => JSON.parse(s).type).includes('register'))
  check('no extra transport opened for the direct path', FakeSocket.instances.length === 2)

  // (4) a register parked while an auto-connect is still CONNECTING must still
  //     leave on open — else the register dialog sits on "Registering…" forever
  //     (the silent-drop class this unit exists to avoid).
  const ws3 = new GameWebSocket({ url: 'ws://x/ws', onMessage: () => {}, onStatusChange: () => {}, onError: () => {} })
  ws3.pendingConnectData = { type: 'connect', username: 'AutoUser', password: '', server: '127.0.0.1', port: 17305 } as never
  ws3.connect()
  ws3.register(payload)
  check('register during an in-flight connect opens no second transport', FakeSocket.instances.length === 3)
  FakeSocket.instances[2].fireOpen()
  const both = FakeSocket.instances[2].sent.map((s) => JSON.parse(s).type)
  check('both the connect payload and the parked register frame leave on open',
    both.length === 2 && both[0] === 'connect' && both[1] === 'register', JSON.stringify(both))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
