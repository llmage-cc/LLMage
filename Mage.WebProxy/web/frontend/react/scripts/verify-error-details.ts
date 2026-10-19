/**
 * Behavioural checks for the error-display upgrade.
 *
 * Run with:  bash scripts/verify-error-details.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-endgame-presentation.sh.)
 *
 * WHAT THIS ADDS. ErrorToast auto-hid at 5 s and showed the message only. It now
 * has COPY-to-clipboard, a COLLAPSIBLE details/stack section (the proxy's error
 * frames may carry `details`), and the auto-hide is SUSPENDED while the cursor
 * is over the toast or the details are expanded (store `errorToastHeld`) so the
 * Copy button and the stack stay reachable.
 *
 * Assertions:
 *  A. store: showError keeps its message-only contract (clears details); the
 *     NEW showErrorWithDetails sets details; the auto-hide timer is 5 s and
 *     actually hides (fake-timer probe).
 *  B. store: the HOLD suspends auto-hide — with errorToastHeld true NO timer is
 *     armed; releasing it re-arms a fresh one.
 *  C. messageHandlers: an `error` frame WITH `details` routes to
 *     showErrorWithDetails; without details it stays message-only.
 *  D. The REAL ErrorToast renders the Copy button always, the Details toggle
 *     only when details exist, and the <pre> stack only once expanded.
 *  E. Source-scan: ErrorToast wires clipboard + the details toggle; the proxy
 *     jsonError carries an optional details field.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import ErrorToast from '../src/components/ErrorToast'
import { handleMessage } from '../src/network/messageHandlers'

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── fake timers so the 5 s auto-hide is testable without waiting ──
const realSetTimeout = globalThis.setTimeout
const realClearTimeout = globalThis.clearTimeout
type FakeTimer = { id: number; fn: () => void; ms: number; cleared: boolean }
let timers: FakeTimer[] = []
let nextId = 1
;(globalThis as unknown as { setTimeout: unknown }).setTimeout = ((fn: () => void, ms?: number) => {
  const t: FakeTimer = { id: nextId++, fn, ms: ms ?? 0, cleared: false }
  timers.push(t)
  return t.id
}) as unknown
;(globalThis as unknown as { clearTimeout: unknown }).clearTimeout = ((id: number) => {
  const t = timers.find((x) => x.id === id)
  if (t) t.cleared = true
}) as unknown
const armed = () => timers.filter((t) => !t.cleared)

// ── A. store contract + auto-hide ──
useGameStore.setState({ errorToastVisible: false, errorMessage: '', errorDetails: '', errorToastHeld: false })
timers = []
useGameStore.getState().showError('Boom')
check('A1: showError sets the message and shows the toast',
  useGameStore.getState().errorMessage === 'Boom' && useGameStore.getState().errorToastVisible === true)
check('A2: showError clears any previous details (message-only contract kept)',
  useGameStore.getState().errorDetails === '')
check('A3: the auto-hide timer is 5 s', armed().length === 1 && armed()[0]!.ms === 5000,
  JSON.stringify(armed().map((t) => t.ms)))
armed()[0]!.fn()
check('A4: firing the timer hides the toast', useGameStore.getState().errorToastVisible === false)

timers = []
useGameStore.getState().showErrorWithDetails('Boom', 'at mage.webproxy.Foo(bar)')
check('A5: showErrorWithDetails sets the details',
  useGameStore.getState().errorDetails.includes('mage.webproxy') && useGameStore.getState().errorToastVisible === true)

// ── B. hold suspends the auto-hide ──
useGameStore.setState({ errorToastVisible: false, errorToastHeld: false })
timers = []
useGameStore.getState().setErrorToastHeld(true)
useGameStore.getState().showErrorWithDetails('Boom', 'stack line')
check('B1: while HELD no auto-hide timer is armed',
  useGameStore.getState().errorToastVisible === true && armed().length === 0,
  JSON.stringify(armed().map((t) => t.ms)))
useGameStore.getState().setErrorToastHeld(false)
check('B2: releasing the hold arms a fresh full timer',
  armed().length === 1 && armed()[0]!.ms === 5000)
armed()[0]!.fn()
check('B3: the re-armed timer hides the toast', useGameStore.getState().errorToastVisible === false)

// ── C. message handler routing ──
useGameStore.setState({ errorToastVisible: false, errorMessage: '', errorDetails: '' })
timers = []
handleMessage({ type: 'error', message: 'Proxy refused', details: 'Caused by: java.io.IOException' }, useGameStore.getState())
check('C1: an `error` frame WITH details routes to showErrorWithDetails',
  useGameStore.getState().errorMessage === 'Proxy refused'
    && useGameStore.getState().errorDetails.includes('java.io.IOException'))
timers = []
handleMessage({ type: 'error', message: 'Proxy refused' }, useGameStore.getState())
check('C2: an `error` frame WITHOUT details stays message-only',
  useGameStore.getState().errorDetails === '' && useGameStore.getState().errorMessage === 'Proxy refused')

// ── D. the real component ──
// FIXTURE NOTE: the .sh runner rewrites zustand's SSR snapshot call to
// api.getState() (React 19 useSyncExternalStore), else the component reads
// creation-time state.
const withDetailsHtml = renderToStaticMarkup(createElement(ErrorToast, { message: 'Boom', details: 'at Foo:42' }))
check('D1: the Copy button always renders', withDetailsHtml.includes('error-toast-copy') && withDetailsHtml.includes('Copy'))
check('D2: the Details toggle renders when details exist', withDetailsHtml.includes('error-toast-details'))
check('D3: the stack <pre> is NOT rendered until expanded (collapsed by default)',
  !withDetailsHtml.includes('error-toast-stack'), withDetailsHtml)
check('D4: the toggle advertises collapsed state (▸ + aria-expanded=false)',
  withDetailsHtml.includes('▸ Details') && withDetailsHtml.includes('aria-expanded="false"'))

const noDetailsHtml = renderToStaticMarkup(createElement(ErrorToast, { message: 'Boom' }))
check('D5: with no details the Details toggle is absent (Copy still there)',
  !noDetailsHtml.includes('error-toast-details') && noDetailsHtml.includes('error-toast-copy'))
check('D6: with no details no stack section is present', !noDetailsHtml.includes('error-toast-stack'))

// ── E. source-scan ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-error-details.sh')
const toastSrc = readFileSync(`${SRC}/components/ErrorToast.tsx`, 'utf8')
check('E1: ErrorToast calls navigator.clipboard.writeText', toastSrc.includes('navigator.clipboard'))
check('E2: ErrorToast toggles the details section', toastSrc.includes('setExpanded'))
check('E3: ErrorToast tells the store to hold on hover/expand', toastSrc.includes('setErrorToastHeld'))
const proxySrc = readFileSync(
  `${SRC}/../../../../src/main/java/mage/webproxy/WebProxyClient.java`, 'utf8')
check('E4: the proxy jsonError carries an optional details field',
  proxySrc.includes('jsonError(String type, String message, String details)')
    && proxySrc.includes('msg.put("details", details)'))

// restore the real timers
;(globalThis as unknown as { setTimeout: unknown }).setTimeout = realSetTimeout
;(globalThis as unknown as { clearTimeout: unknown }).clearTimeout = realClearTimeout

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
