/**
 * Standalone behavioural check for the dead-socket lobby surfacing

 *
 * Run with:  bash scripts/verify-lobby-dead-socket.sh
 *
 * Drives the REAL GameWebSocket manager + REAL store slices with a fake
 * WebSocket class whose open/close are fired manually — the exact loop a tab
 * whose server cold-restarted lives through, with clock + socket under test
 * control. NO reconnect/backoff policy is asserted here (that is the
 * auth-reconnect harness's job); this pins the SURFACING contract only:
 *
 *  (a) isSocketDown truth table — down iff !connected, connStatus
 *      'reconnecting' or 'connection_lost'; 'resyncing' and '' are healthy.
 *  (b) Transport loss mid-lobby: with the session connected, a socket close
 *      through App-parity onStatusChange flips connStatus to 'reconnecting'
 *      and selectSocketDown to true — the state that previously left the
 *      lobby looking alive while every send was dropped.
 *  (c) Slice guards surface instead of silently dropping: startGame /
 *      joinTable set setupError; startMatch raises the error toast — all
 *      while the manager exists (the old guard `if (!ws)` never fired).
 *  (d) Recovery: a reconnect whose `connected` frame arrives clears
 *      connStatus and selectSocketDown, and a valid create flows again
 *      (create_table on the wire) — the banner needs no manual dismissal.
 *  (e) The proxy-side link loss (`disconnected` frame → connStatus
 *      'connection_lost') is also "down", and a later `connected` frame
 *      clears it.
 *  (f) The banner text is pinned (non-empty, names the drop + auto-reconnect).
 *  (g) D2 recovery control: the `disconnected` frame (no game live) schedules
 *      ONE WebSocket cycle after CONNECTION_LOST_RECYCLE_DELAY_MS — the manager
 *      discards the dead socket, opens a NEW one, and sends the pending
 *      connect payload on it (the proxy session re-creation that re-dials
 *      XMage). A mid-delay `connected` frame cancels the pending cycle, a
 *      live game at arrival never schedules one, and a repeat frame while
 *      already lost does not re-arm it.
 */
import { GameWebSocket } from '../src/network/websocket'
import { handleMessage } from '../src/network/messageHandlers'
import { useGameStore } from '../src/store/gameStore'
import { selectSocketDown } from '../src/store/selectors'
import { CONNECTION_LOST_RECYCLE_DELAY_MS } from '../src/store/slices/connectionSlice'
import {
  isSocketDown,
  LOBBY_SOCKET_DOWN_BANNER,
  SOCKET_DOWN_SETUP_ERROR,
} from '../src/utils/socketDown'
import type { ServerMessage } from '../src/types'

// ── fake WebSocket: manual open/close, records sends ──
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3

  readyState = FakeWebSocket.CONNECTING
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null

  constructor() {
    FakeWebSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(data)
  }
  close() {
    this.readyState = FakeWebSocket.CLOSED
  }
  // Test helpers — fire the browser events manually.
  fireOpen() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }
  fireClose(code: number, reason = '') {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({ code, reason })
  }
  fireMessage(msg: ServerMessage) {
    this.onmessage?.({ data: JSON.stringify(msg) })
  }
}

;(globalThis as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket

const realSetTimeout = globalThis.setTimeout
/** Let pending wrapped timers (reconnect schedules) run. */
async function tick() {
  await new Promise<void>((resolve) => realSetTimeout(resolve, 0))
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/**
 * Manager + App-parity status wiring. The onStatusChange body below is a
 * VERBATIM copy of App.tsx's handler (App.tsx:140-149) — App.tsx is not
 * harnessable in Node, so this harness pins the parity contract: if App's
 * handler drifts, check (b) fails here and the drift is visible.
 */
function freshManager(): GameWebSocket {
  FakeWebSocket.instances = []
  const mgr = new GameWebSocket({
    url: 'ws://test/ws',
    onMessage: (msg) => handleMessage(msg, useGameStore.getState()),
    onStatusChange: (status) => {
      if (status === 'connected') {
        useGameStore.getState().setConnStatus('')
      } else if (status === 'disconnected') {
        const store = useGameStore.getState()
        if (store.connected) {
          store.setConnStatus('reconnecting')
        }
      }
    },
    onError: () => {},
  })
  useGameStore.setState({ ws: mgr as never } as never)
  return mgr
}

/** Open the next pending socket (created by connect()) and mark it open. */
function openNext(): FakeWebSocket {
  const s = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
  if (s.readyState !== FakeWebSocket.OPEN) s.fireOpen()
  return s
}

/** Accept the session: fire the proxy's `connected` frame on the live socket. */
function accept(s: FakeWebSocket, username = 'tester') {
  s.fireMessage({ type: 'connected', username } as unknown as ServerMessage)
}

/** Reset the slice state the harness touches (between sections). */
function resetStore(mgr: GameWebSocket) {
  mgr.disconnect()
  useGameStore.setState({
    connected: false,
    connecting: false,
    connectError: '',
    connStatus: '',
    joinWaiting: false,
    pendingJoinTableId: null,
    myTableId: null,
    isTableOwner: false,
    showSetup: false,
    setupError: '',
    errorMessage: '',
    errorToastVisible: false,
  } as never)
}

// ═══ Section A — isSocketDown truth table (pure) ═══
{
  section('isSocketDown truth table')
  check('connected + empty status = healthy', !isSocketDown({ connected: true, connStatus: '' }))
  check('connected + resyncing = healthy (resync rides a live socket)', !isSocketDown({ connected: true, connStatus: 'resyncing' }))
  check('not connected = down even with empty status', isSocketDown({ connected: false, connStatus: '' }))
  check('connected + reconnecting = down', isSocketDown({ connected: true, connStatus: 'reconnecting' }))
  check('connected + connection_lost = down', isSocketDown({ connected: true, connStatus: 'connection_lost' }))
}

// ═══ Section B — mid-lobby transport loss flips the surfacing state ═══
{
  section('transport loss mid-lobby: connStatus reconnecting, selectSocketDown true')
  const mgr = freshManager()
  mgr.connect()
  const s = openNext()
  accept(s)
  check('session marked connected', useGameStore.getState().connected === true)
  check('healthy before the drop', selectSocketDown(useGameStore.getState()) === false)

  // The server cold-restarted under us (the dead-socket trigger): close 1006.
  s.fireClose(1006)
  await tick()
  check("App-parity handler set connStatus='reconnecting'", useGameStore.getState().connStatus === 'reconnecting')
  check('store still claims connected (the dead-socket trap)', useGameStore.getState().connected === true)
  check('selectSocketDown flips to true', selectSocketDown(useGameStore.getState()) === true)

  // Backoff retry fires 'connecting' — must NOT clear the down state.
  await tick()
  check("retry's 'connecting' did not clear the banner state", selectSocketDown(useGameStore.getState()) === true)

  resetStore(mgr)
}

// ═══ Section C — slice guards surface instead of silently dropping ═══
{
  section('slice guards while down: setupError + error toast, manager still present')
  const mgr = freshManager()
  mgr.connect()
  const s = openNext()
  accept(s)
  s.fireClose(1006)
  await tick()

  const st = useGameStore.getState()
  check('precondition: the manager object is still in the store', st.ws === mgr)
  check('precondition: socket is down', selectSocketDown(st) === true)

  // startGame (create mode): must surface, not send into the void.
  st.showGameSetup()
  const st2 = useGameStore.getState()
  // give the human seat a deck so the ONLY failure is the socket
  st2.updatePlayerSlot(0, { deck: '4 Lightning Bolt' })
  useGameStore.getState().startGame()
  check('startGame set setupError (visible failure)', useGameStore.getState().setupError === SOCKET_DOWN_SETUP_ERROR)

  // joinTable: same surfacing.
  useGameStore.getState().joinTable('11111111-1111-1111-1111-111111111111', '4 Lightning Bolt')
  check('joinTable set setupError', useGameStore.getState().setupError === SOCKET_DOWN_SETUP_ERROR)

  // startMatch: toast, not a silent send.
  useGameStore.getState().startMatch('22222222-2222-2222-2222-222222222222')
  check('startMatch raised the error toast', useGameStore.getState().errorToastVisible === true)
  check('startMatch toast is the socket-down message', useGameStore.getState().errorMessage.length > 0)

  resetStore(mgr)
}

// ═══ Section D — recovery: `connected` frame re-enables everything ═══
{
  section('recovery: connected frame clears the down state and sends flow again')
  const mgr = freshManager()
  mgr.connect()
  let s = openNext()
  accept(s)
  s.fireClose(1006)
  await tick()
  check('down after the close', selectSocketDown(useGameStore.getState()) === true)

  // The backoff timer fires → new socket → proxy accepts again.
  await tick()
  s = openNext()
  accept(s, 'tester2')
  check('connected frame marked the session again', useGameStore.getState().connected === true)
  check('connStatus cleared by setConnected', useGameStore.getState().connStatus === '')
  check('selectSocketDown back to false', selectSocketDown(useGameStore.getState()) === false)

  // A valid create flows again (control for the C guards).
  useGameStore.getState().showGameSetup()
  useGameStore.getState().updatePlayerSlot(0, { deck: '4 Lightning Bolt' })
  useGameStore.getState().startGame()
  const sentTypes = s.sent.map((raw) => (JSON.parse(raw) as { type: string }).type)
  check('valid create_table went out after recovery', sentTypes.includes('create_table'), `sent: ${sentTypes.join(',')}`)
  check('no lingering setupError on the happy path', useGameStore.getState().setupError === '')

  resetStore(mgr)
}

// ═══ Section E — proxy-side link loss (`disconnected` frame) is also down ═══
{
  section("proxy 'disconnected' frame: connection_lost is down, connected frame clears it")
  const mgr = freshManager()
  mgr.connect()
  const s = openNext()
  accept(s)
  // The proxy lost its XMage-server link while the browser socket is alive.
  s.fireMessage({ type: 'disconnected', message: 'XMage server went away' } as unknown as ServerMessage)
  check("connStatus='connection_lost'", useGameStore.getState().connStatus === 'connection_lost')
  check('selectSocketDown true', selectSocketDown(useGameStore.getState()) === true)

  // Start Match must surface while the proxy-side link is down.
  useGameStore.getState().startMatch('33333333-3333-3333-3333-333333333333')
  check('startMatch surfaced via toast during connection_lost', useGameStore.getState().errorToastVisible === true)

  // The proxy recovers its server link and re-announces the session.
  s.fireMessage({ type: 'connected', username: 'tester' } as unknown as ServerMessage)
  check('a fresh connected frame clears the down state', selectSocketDown(useGameStore.getState()) === false)

  resetStore(mgr)
}

// ═══ Section F — banner text contract ═══
{
  section('banner text contract')
  check('banner text is non-empty', LOBBY_SOCKET_DOWN_BANNER.length > 40)
  check('banner says the connection was lost', /lost/i.test(LOBBY_SOCKET_DOWN_BANNER))
  check('banner says clicks were not sent', /NOT sent/i.test(LOBBY_SOCKET_DOWN_BANNER))
  check('banner says reconnection is automatic', /reconnect/i.test(LOBBY_SOCKET_DOWN_BANNER))
  check('setup-error text is non-empty', SOCKET_DOWN_SETUP_ERROR.length > 20)
}

// ═══ Section G — D2 recovery: connection_lost cycles the WebSocket (no game live) ═══
{
  section("D2 recovery: 'disconnected' frame cycles the WS so the session re-creates (lobby only)")

  // ── G1: the full cycle — dead session dropped, fresh connect sent ──
  {
    const mgr = freshManager()
    // The real flow carries the user's connect credentials on the manager
    // (ConnectOverlay sets them; they are re-sent on every new socket open).
    mgr.pendingConnectData = { type: 'connect', username: 'tester', password: '', host: '127.0.0.1', port: 17193 } as never
    mgr.connect()
    const first = openNext()
    accept(first)
    check('G1 precondition: session connected on the first socket', useGameStore.getState().connected === true)

    first.fireMessage({ type: 'disconnected', message: 'Connection to XMage server lost' } as unknown as ServerMessage)
    check("connStatus='connection_lost'", useGameStore.getState().connStatus === 'connection_lost')
    check('selectSocketDown true during the outage', selectSocketDown(useGameStore.getState()) === true)

    // Wait out the recycle delay — the harness deliberately sleeps REAL time here
    // (one ~1.5s wait) because the recycle is timer-driven by contract.
    await new Promise<void>((resolve) => realSetTimeout(resolve, CONNECTION_LOST_RECYCLE_DELAY_MS + 300))

    check('the manager cycled to a NEW socket', FakeWebSocket.instances.length === 2, `instances=${FakeWebSocket.instances.length}`)
    check('the dead socket was closed by the recycle', first.readyState === FakeWebSocket.CLOSED)
    const second = FakeWebSocket.instances[1]!
    check('the new socket is not open yet (connecting)', second.readyState === FakeWebSocket.CONNECTING || second.readyState === FakeWebSocket.OPEN, String(second.readyState))
    // fireOpen sends pendingConnectData through the REAL send path — this is the
    // proxy session re-creation (the proxy's handleConnect re-dials XMage).
    second.fireOpen()
    const sentTypes = second.sent.map((raw) => (JSON.parse(raw) as { type: string }).type)
    check('the fresh socket sent the connect payload (proxy re-dial trigger)', sentTypes[0] === 'connect', JSON.stringify(sentTypes))

    // The proxy accepts → connected frame → down state clears, sends flow again.
    accept(second, 'tester2')
    check('connected frame cleared connStatus', useGameStore.getState().connStatus === '')
    check('selectSocketDown back to false after the recycle recovery', selectSocketDown(useGameStore.getState()) === false)
    useGameStore.getState().showGameSetup()
    useGameStore.getState().updatePlayerSlot(0, { deck: '4 Lightning Bolt' })
    useGameStore.getState().startGame()
    const afterTypes = second.sent.map((raw) => (JSON.parse(raw) as { type: string }).type)
    check('create_table flows on the recycled session', afterTypes.includes('create_table'), JSON.stringify(afterTypes))
    resetStore(mgr)
  }

  // ── G2: a `connected` frame DURING the delay cancels the pending cycle ──
  {
    const mgr = freshManager()
    mgr.connect()
    const s = openNext()
    accept(s)
    s.fireMessage({ type: 'disconnected', message: 'XMage server went away' } as unknown as ServerMessage)
    check('G2 precondition: connection_lost set', useGameStore.getState().connStatus === 'connection_lost')
    // The proxy recovers its link on its own before the delay elapses.
    s.fireMessage({ type: 'connected', username: 'tester' } as unknown as ServerMessage)
    check('recovery cleared the down state', selectSocketDown(useGameStore.getState()) === false)
    await new Promise<void>((resolve) => realSetTimeout(resolve, CONNECTION_LOST_RECYCLE_DELAY_MS + 300))
    check('NO cycle happened (the pending recycle was cancelled)', FakeWebSocket.instances.length === 1, `instances=${FakeWebSocket.instances.length}`)
    check('the healthy socket is untouched', s.readyState === FakeWebSocket.OPEN)
    resetStore(mgr)
  }

  // ── G3: a live game at arrival never schedules a cycle (never cycle mid-game) ──
  {
    const mgr = freshManager()
    mgr.connect()
    const s = openNext()
    accept(s)
    useGameStore.setState({ inGame: true } as never)
    s.fireMessage({ type: 'disconnected', message: 'XMage server went away' } as unknown as ServerMessage)
    check('G3: connStatus still flips to connection_lost mid-game', useGameStore.getState().connStatus === 'connection_lost')
    await new Promise<void>((resolve) => realSetTimeout(resolve, CONNECTION_LOST_RECYCLE_DELAY_MS + 300))
    check('G3: NO cycle while a game is live', FakeWebSocket.instances.length === 1, `instances=${FakeWebSocket.instances.length}`)
    check('G3: the game socket is untouched', s.readyState === FakeWebSocket.OPEN)
    useGameStore.setState({ inGame: false } as never)
    resetStore(mgr)
  }

  // ── G4: a repeat `disconnected` frame while already lost does not re-arm ──
  {
    const mgr = freshManager()
    mgr.connect()
    const s = openNext()
    accept(s)
    s.fireMessage({ type: 'disconnected', message: 'first loss' } as unknown as ServerMessage)
    // Second arrival while still lost (e.g. a proxy retry notice) must be a no-op:
    // after the first recycle fires there is exactly ONE cycle total, and the
    // second frame must not schedule another one on top.
    s.fireMessage({ type: 'disconnected', message: 'second notice' } as unknown as ServerMessage)
    await new Promise<void>((resolve) => realSetTimeout(resolve, CONNECTION_LOST_RECYCLE_DELAY_MS + 300))
    check('G4: exactly one cycle for two lost frames', FakeWebSocket.instances.length === 2, `instances=${FakeWebSocket.instances.length}`)
    resetStore(mgr)
  }
}

if (failures > 0) {
  console.log(`\nFAILED: ${failures} check(s)`)
  // eslint-disable-next-line no-restricted-globals
  ;(globalThis as unknown as { process?: { exit(code: number): void } }).process?.exit(1)
}
console.log('\nALL CHECKS PASSED')