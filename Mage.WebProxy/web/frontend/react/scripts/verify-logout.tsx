/**
 * Standalone behavioural check for the lobby "Log out" control (store-level).
 *
 * Run with:  bash scripts/verify-logout.sh
 *
 * Covers the frontend half of logout with NO browser:
 *  (a) saved credentials (sessionStorage 'xmage-connect-creds') are cleared —
 *      this is what breaks ConnectOverlay's auto-connect-on-mount loop,
 *  (b) the WebSocket is torn down but the GameWebSocket MANAGER stays in the
 *      store (App.tsx constructs it once per page load; nulling it leaves the
 *      connect overlay permanently "Connection manager not ready"),
 *  (c) lobby seat state (joinWaiting / myTableId / isTableOwner /
 *      pendingJoinTableId) resets — the same fields game_over clears,
 *  (d) routing falls to 'connect' immediately and a NEW session (setConnected)
 *      routes back to 'lobby' on the same manager,
 *  (e) logging out twice in a row is safe.
 *
 * Same pattern as verify-connect-target-toggle.tsx: the REAL store slices are
 * bundled with browser stubs; the GameWebSocket is a recording stub because no
 * proxy runs in this harness.
 */
import { useGameStore } from '../src/store/gameStore'
import { selectCurrentView } from '../src/store/selectors'

// ── browser stubs the slice needs beyond verify-stubs.mjs ──
const storage = new Map<string, string>()
;(globalThis as unknown as { sessionStorage: unknown }).sessionStorage = {
  getItem: (k: string) => (storage.has(k) ? storage.get(k)! : null),
  setItem: (k: string, v: string) => storage.set(k, String(v)),
  removeItem: (k: string) => storage.delete(k),
}

// ── the WS stub the store talks to (records calls) ──
let disconnects = 0
let connects = 0
const fakeWs = {
  send: () => {},
  connect: () => { connects++ },
  disconnect: () => { disconnects++ },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  pendingConnectData: null,
}
useGameStore.setState({
  ws: fakeWs as never,
  connected: true,
  connecting: false,
  connStatus: '',
  proxyUsername: 'Seated',
})

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── seated logout (the confirmed case: seated ⇒ confirm dialog → logout()) ──
section('Seated logout: creds cleared, socket down, manager kept, seat state reset')
useGameStore.setState({
  joinWaiting: true,
  pendingJoinTableId: 'table-1',
  myTableId: 'table-1',
  isTableOwner: true,
})
storage.set(
  'xmage-connect-creds',
  JSON.stringify({ username: 'Seated', server: '127.0.0.1', port: '17171' }),
)

useGameStore.getState().logout()

check('saved credentials cleared (auto-reconnect loop broken)', !storage.has('xmage-connect-creds'))
check('socket teardown requested exactly once', disconnects === 1)
check('ws MANAGER kept in the store (constructed once per page load)', useGameStore.getState().ws === (fakeWs as never))
check('connected=false', useGameStore.getState().connected === false)
check('connStatus cleared', useGameStore.getState().connStatus === '')
check('joinWaiting reset', useGameStore.getState().joinWaiting === false)
check('myTableId reset', useGameStore.getState().myTableId === null)
check('isTableOwner reset', useGameStore.getState().isTableOwner === false)
check('pendingJoinTableId reset', useGameStore.getState().pendingJoinTableId === null)
check('view routes to connect immediately', selectCurrentView(useGameStore.getState()) === 'connect')

// ── a NEW session works on the same manager ──
section('Connect again after logout (same manager, fresh session)')
const wsAfter = useGameStore.getState().ws
if (wsAfter) wsAfter.connect()
useGameStore.getState().setConnected('FreshSeat')
check('reconnect registered on the kept manager', connects === 1)
check('view routes to lobby after the new connect', selectCurrentView(useGameStore.getState()) === 'lobby')
check('session username is the new one', useGameStore.getState().proxyUsername === 'FreshSeat')

// ── idempotence ──
section('Logging out twice in a row is safe')
useGameStore.getState().logout()
check('second logout tears the new socket down again', disconnects === 2)
check('still routed to connect, no throw', selectCurrentView(useGameStore.getState()) === 'connect')
check('creds stay cleared', !storage.has('xmage-connect-creds'))

if (failures > 0) {
  console.log(`\nFAILED: ${failures} check(s)`)
  process.exit(1)
}
console.log('\nALL CHECKS PASSED')