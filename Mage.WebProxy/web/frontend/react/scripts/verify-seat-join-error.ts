/**
 * Harness: a seat-join failure reaches the player with the SERVER's own reason

 *
 * Run: bash scripts/verify-seat-join-error.sh   (self-locking — do not wrap it)
 *
 * The defect: WebProxySession's create-table auto-join read only
 * `xmageSession.getLastError()` (set ONLY on connect failures) after a refused
 * seat join, so the browser got `Seat 0 failed to join: ` with an EMPTY reason and
 * the player saw nothing actionable. The server's real reason rides a
 * SHOW_USERMESSAGE callback, which the proxy must forward.
 *
 * Covers, through the REAL modules (store + messageHandlers, no browser) and the
 * REAL Java source:
 *  1  a `Seat N failed to join: <reason>` proxy `error` frame raises a VISIBLE
 *     surface carrying the reason VERBATIM (not swallowed into the console) —
 *     the frontend half of "shown to the player";
 *  2  the same for a tournament seat;
 *  3  the reason is never empty: the frame the proxy sends always has text after
 *     the colon (the reported defect's exact shape is `…join: ` with nothing);
 *  4  a join error is NOT misattributed to a models fetch — models state is
 *     untouched when no fetch is pending (the guard that keeps an unrelated error
 *     from clobbering the seat form);
 *  5  the proxy source really forwards the server text for BOTH create paths and
 *     no longer emits the bare empty-reason frame (a regression in the Java side
 *     fails HERE, not only in the Java unit test).
 *
 * scripts/ is outside tsconfig "include", so this is not part of the vite build.
 * This harness does NOT touch the network/browser and only inspects the Java
 * source textually (same pattern as verify-models-error.ts's source cross-check).
 */
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage } from '../src/types'

let failures = 0
let passed = 0
function check(name: string, ok: boolean, detail = '') {
  if (ok) {
    passed++
    console.log(`PASS  ${name}`)
  } else {
    failures++
    console.log(`FAIL  ${name}${detail ? '  — ' + detail : ''}`)
  }
}
function section(t: string) {
  console.log(`\n## ${t}`)
}

// A live ws stand-in so the store's senders work; records what they send.
const sent: Array<Record<string, unknown>> = []
useGameStore.setState({
  ws: {
    send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
    onStateVersionReceived: () => {},
    requestResync: () => {},
    clearResyncFlag: () => {},
    noteConnectAccepted: () => {},
    disconnect: () => {},
  } as never,
})
const feed = (m: unknown) => handleMessage(m as ServerMessage, useGameStore.getState())
const st = () => useGameStore.getState()

// The exact server sentence TableController.joinTable attaches to a refused join.
const SERVER_REASON =
  'You can join a table only one time.'
const JOIN_FRAME = `Seat 0 failed to join: ${SERVER_REASON}`

// ─────────────────────────────────────────────────────────────────────────────
section('A refused seat join reaches the player with the server reason verbatim')

{
  st().setAvailableModels(['stable-model'])
  st().setModelsError('') // reset the visible surface
  useGameStore.setState({ errorToastVisible: false, errorMessage: '' } as never)

  feed({ type: 'error', message: JOIN_FRAME } as ServerMessage)

  check('the proxy error raises a VISIBLE surface (not console-only)',
    st().errorToastVisible === true)
  check('the visible text is the frame’s message verbatim',
    st().errorMessage === JOIN_FRAME, st().errorMessage)
  check('the reason after the colon is present (never the bare "…join: ")',
    st().errorMessage.includes(': ') && st().errorMessage.trim().endsWith(SERVER_REASON),
    st().errorMessage)
  check('the server sentence is forwarded unchanged',
    st().errorMessage.slice(st().errorMessage.indexOf(': ') + 2) === SERVER_REASON,
    st().errorMessage)
}

// ─────────────────────────────────────────────────────────────────────────────
section('The tournament seat path behaves the same')

{
  useGameStore.setState({ errorToastVisible: false, errorMessage: '' } as never)
  const tournamentFrame = 'Tournament seat 1 failed to join: No available seats.'

  feed({ type: 'error', message: tournamentFrame } as ServerMessage)

  check('a tournament seat failure is surfaced verbatim too',
    st().errorMessage === tournamentFrame && st().errorToastVisible === true,
    st().errorMessage)
  check('…with a non-empty reason after the colon',
    st().errorMessage.slice(st().errorMessage.indexOf(': ') + 2).length > 0,
    st().errorMessage)
}

// ─────────────────────────────────────────────────────────────────────────────
section('A join error never disturbs models state (no pending fetch)')

{
  st().setAvailableModels(['keep-me'])
  const before = {
    modelsLoading: st().modelsLoading,
    availableModels: [...st().availableModels],
    pendingFetch: st().pendingFetch,
    setupError: st().setupError,
  }

  feed({ type: 'error', message: JOIN_FRAME } as ServerMessage)

  check('the models spinner is untouched by a join error',
    st().modelsLoading === before.modelsLoading)
  check('the fetched model list survives a join error',
    JSON.stringify(st().availableModels) === JSON.stringify(before.availableModels))
  check('no pending-fetch marker is created by a join error',
    st().pendingFetch === before.pendingFetch, String(st().pendingFetch))
  check('the join error does not invent a setup-dialog error when none is open',
    st().setupError === before.setupError, st().setupError)
}

// ─────────────────────────────────────────────────────────────────────────────
section('The proxy source really forwards the server reason (fix cross-check)')

{
  const repoRoot = process.env.XMAGE_REPO_ROOT
  if (!repoRoot) {
    throw new Error('XMAGE_REPO_ROOT is not set — run via scripts/verify-seat-join-error.sh')
  }
  const session = readFileSync(
    `${repoRoot}/Mage.WebProxy/src/main/java/mage/webproxy/WebProxySession.java`,
    'utf8',
  )

  // Both create paths (match + tournament) must build their failure text from the
  // shared server-reason helper, not from a bare getLastError().
  check('create_table joins its failure reason from joinFailureReason',
    /Seat "\s*\+\s*i\s*\+\s*" failed to join: "\s*\+\s*rawOrEmpty\(reason\)/.test(session),
    'handleCreateTable seat-join failure no longer reads the shared reason')
  check('create_tournament joins its failure reason from joinFailureReason',
    /Tournament seat "\s*\+\s*i\s*\+\s*" failed to join: "\s*\+\s*rawOrEmpty\(reason\)/.test(session),
    'handleCreateTournament seat-join failure no longer reads the shared reason')

  // The bare empty-reason frame the incident reported must be gone.
  check('no site emits the bare "failed to join: " + getLastError() frame',
    !/failed to join: "\s*\+\s*rawOrEmpty\(e\s*!=\s*null\s*\?\s*e\s*:\s*""\)/.test(session),
    'an empty-reason seat-join frame is still present')

  // The helper consults the server's own message FIRST and has a fail-visible
  // fallback so the reason can never be empty.
  check('joinFailureReason waits for the server SHOW_USERMESSAGE text',
    /waitForServerMessageSince\(joinStartedAt,\s*JOIN_REFUSAL_REASON_WAIT_MS\)/.test(session))
  check('joinFailureReason has a fail-visible non-empty fallback',
    session.includes('The server refused the seat (no reason text arrived'),
    'the no-reason fallback is missing')

  // The frontend contract this harness drives: the store surfaces the frame text.
  check('messageHandlers surfaces the proxy error text into the store',
    readFileSync(`${repoRoot}/Mage.WebProxy/web/frontend/react/src/network/messageHandlers.ts`, 'utf8')
      .includes("store.showError(stripServerHtml(msg.message || 'Unknown error'))"))
}

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-seat-join-error: ALL CHECKS PASS')
