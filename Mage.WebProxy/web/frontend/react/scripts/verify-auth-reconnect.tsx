/**
 * Standalone behavioural check for the auth-reject reconnect-loop fixes
 * client half) —  fixes 1 + 3.
 *
 * Run with:  bash scripts/verify-auth-reconnect.sh
 *
 * Drives the REAL GameWebSocket manager with a fake WebSocket class whose
 * open/close are fired manually — the exact loop a stuck auth-reject tab
 * lives through, but with clock + socket under test control.
 *
 *  (a) FIX 1 — backoff counter not reset on socket open: repeated open→close
 *      cycles WITHOUT a `connected` frame double the reconnect delay each time
 *      up to the 30s cap; a `connected` frame resets it.
 *  (b) FIX 1 — forceReconnect (liveness-probe / online recovery) must NOT
 *      reset the counter: a failing forced reconnect keeps backing off.
 *  (c) FIX 1 — noteConnectAccepted is wired through the real store: a
 *      `connected` message through handleMessage resets the counter.
 *  (d) FIX 3 — onclose with the proxy's 4401 close code schedules NO
 *      reconnect; other close codes reconnect as before.
 */
import { GameWebSocket } from '../src/network/websocket'
import { handleMessage } from '../src/network/messageHandlers'
import { useGameStore } from '../src/store/gameStore'
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
}

;(globalThis as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket

const realSetTimeout = globalThis.setTimeout
/** Let pending wrapped timers (reconnect schedules, liveness probes) run. */
async function tick() {
  await new Promise<void>((resolve) => realSetTimeout(resolve, 0))
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/**
 * A manager whose reconnect schedules are recorded, not waited for: every
 * setTimeout is collapsed to ~0 so cycles run in real ticks, while the DELAY
 * the manager computed is pushed to `delays` — the thing being asserted.
 */
function freshManager(): { mgr: GameWebSocket; delays: number[] } {
  FakeWebSocket.instances = []
  const delays: number[] = []
  ;(globalThis as unknown as { setTimeout: typeof realSetTimeout }).setTimeout =
    ((fn: () => void, ms?: number) => {
      delays.push(Number(ms ?? 0))
      return realSetTimeout(fn, 0)
    }) as typeof realSetTimeout
  const mgr = new GameWebSocket({
    url: 'ws://test/ws',
    // App.tsx parity: messages route through the REAL handler + store slices —
    // that is the fix-1 wiring under test (connected → noteConnectAccepted).
    onMessage: (msg) => handleMessage(msg, useGameStore.getState()),
    onStatusChange: () => {},
    onError: () => {},
  })
  return { mgr, delays }
}

function restoreTimers() {
  ;(globalThis as unknown as { setTimeout: typeof realSetTimeout }).setTimeout = realSetTimeout
}

/** End a section cleanly: cancel any pending reconnect timer + handlers. */
function endSection(mgr: GameWebSocket) {
  mgr.disconnect()
  restoreTimers()
}

/** Open the next pending socket (created by connect()) and mark it open. */
function openNext(): FakeWebSocket {
  const s = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
  if (s.readyState !== FakeWebSocket.OPEN) s.fireOpen()
  return s
}

// ═══ Section A — FIX 1: open→close cycles without `connected` back off ═══
{
  section('FIX 1 — no reset on open: open→close cycles back off to the 30s cap')
  const { mgr, delays } = freshManager()
  mgr.connect()
  check('connect created a socket', FakeWebSocket.instances.length === 1)

  // Simulate the auth-reject loop: socket opens, proxy rejects + closes —
  // never a `connected` frame. The delay must DOUBLE each cycle, not stay 1s.
  let expected = 1000
  while (expected <= 30000) {
    const s = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    s.fireOpen()
    s.fireClose(1006)
    const got = delays[delays.length - 1]
    check(`rejection #${delays.length} schedules ${expected / 1000}s backoff`, got === expected, `got ${got}`)
    if (expected === 30000) break
    expected = Math.min(expected * 2, 30000)
    await tick() // let the recorded reconnect timer fire → connect()
  }
  check('delay sequence reached the 30s cap', delays[delays.length - 1] === 30000)

  // Deliver `connected` on the NEXT socket: counter must reset to 1s again.
  await tick()
  const accepted = openNext()
  mgr.noteConnectAccepted()
  accepted.fireClose(1006)
  const afterReset = delays[delays.length - 1]
  check('after noteConnectAccepted the next failure backs off at 1s again', afterReset === 1000, `got ${afterReset}`)

  endSection(mgr)
}

// ═══ Section B — FIX 1: forceReconnect (recovery paths) does not reset ═══
{
  section('FIX 1 — recovery-path forceReconnect does not reset the backoff counter')
  const { mgr, delays } = freshManager()
  mgr.connect()
  let s = FakeWebSocket.instances[0]
  s.fireOpen()
  s.fireClose(1006) // 1s
  await tick()
  s = openNext()
  s.fireClose(1006) // 2s — rejected at the message level, socket open is transport-only
  check('two rejections produced growing backoff', delays[0] === 1000 && delays[1] === 2000)

  // forceReconnect is private and triggered by the liveness probe / online
  // recovery paths; invoke it directly to pin its contract: immediate retry,
  // NO counter reset. (The visibility handler is torn down on close, so the
  // DOM event itself cannot be driven from a closed-socket state.)
  ;(mgr as unknown as { forceReconnect: (reason: string) => void }).forceReconnect('harness')
  await tick()
  const forced = openNext()
  forced.fireClose(1006) // rejected again — never opened/accepted
  const afterForce = delays[delays.length - 1]
  check('after a forced reconnect the backoff keeps growing (4s, not 1s)', afterForce === 4000, `got ${afterForce}`)

  endSection(mgr)
}

// ═══ Section C — FIX 1: `connected` frame through the real store resets ═══
{
  section('FIX 1 — `connected` message through the real store resets the counter')
  const { mgr, delays } = freshManager()
  useGameStore.setState({ ws: mgr as never } as never)
  mgr.connect()
  let s = FakeWebSocket.instances[0]
  s.fireOpen()
  s.fireClose(1006) // 1s
  await tick()
  s = openNext()
  s.fireClose(1006) // 2s — rejected at the message level, socket open is transport-only
  check('two rejections produced growing backoff', delays[0] === 1000 && delays[1] === 2000)

  // The server accepts: `connected` frame arrives on the open socket, through
  // the REAL handleMessage + store slices (the fix-1 wiring end to end).
  await tick()
  const accepted = openNext()
  accepted.onmessage?.({
    data: JSON.stringify({ type: 'connected', username: 'tester' } as ServerMessage),
  })
  check('store marks the session connected', useGameStore.getState().connected === true)
  accepted.fireClose(1006)
  const afterReset = delays[delays.length - 1]
  check('connected frame reset the counter (next failure backs off at 1s)', afterReset === 1000, `got ${afterReset}`)

  endSection(mgr)
}

// ═══ Section D — FIX 3: close 4401 schedules NO reconnect ═══
{
  section('FIX 3 — close code 4401 (auth rejected) schedules no reconnect')
  const { mgr, delays } = freshManager()
  mgr.connect()
  const s = FakeWebSocket.instances[0]
  s.fireOpen()
  // Proxy sends connect_error, then closes 4401.
  s.onmessage?.({
    data: JSON.stringify({
      type: 'connect_error',
      message: 'Bad or missing auth token',
    } as unknown as ServerMessage),
  })
  s.fireClose(4401)
  await tick()
  check('close 4401 scheduled no reconnect', delays.length === 0, `delays: ${delays.join(',')}`)

  // And the form state was surfaced by connect_error (existing plumbing).
  check('connectError surfaced in the store', useGameStore.getState().connectError === 'Bad or missing auth token')
  check('connecting flag reset (form re-enabled)', useGameStore.getState().connecting === false)

  endSection(mgr)
}

// ═══ Section D — other close codes still reconnect (regression guard) ═══
{
  section('FIX 3 — other close codes (1006 server restart) still reconnect')
  const { mgr, delays } = freshManager()
  mgr.connect()
  const s = FakeWebSocket.instances[0]
  s.fireOpen()
  s.fireClose(1006)
  check('close 1006 still schedules a reconnect (1s)', delays.length === 1 && delays[0] === 1000)

  endSection(mgr)
}

if (failures > 0) {
  console.log(`\nFAILED: ${failures} check(s)`)
  // eslint-disable-next-line no-restricted-globals
  ;(globalThis as unknown as { process?: { exit(code: number): void } }).process?.exit(1)
}
console.log('\nALL CHECKS PASSED')