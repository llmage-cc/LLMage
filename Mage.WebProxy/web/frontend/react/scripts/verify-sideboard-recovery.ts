/**
 * Store-level behavioural checks for the Bo3 winsNeeded + sideboard rejection
 * recovery fixes (

 *
 * Run with:  bash scripts/verify-sideboard-recovery.sh
 *
 * Every case drives the REAL store through the REAL message handler, so the
 * assertions cover the shipped code path. Component-side behaviour covered here
 * is the store state the SideboardView watches (submitRejectedAt -> setSubmitted(false)
 * effect lives in SideboardView.tsx:40-44 and is asserted by code review; the
 * store cannot click its own buttons).
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failures++
    console.log(`  FAIL  ${name} ${detail}`)
  }
}

const sent: unknown[] = []
const fakeWs = {
  send: (m: unknown) => { sent.push(m) },
  disconnect: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
}

const sideboardPromptMsg = {
  type: 'sideboard_prompt',
  tableId: 'table-1',
  parentTableId: null,
  time: 180,
  construct: false,
  deckName: 'Freeform Deck',
  cards: [{ name: 'Grizzly Bears', setCode: '9ED', cardNumber: '250', qty: 40 }],
  sideboard: [{ name: 'Hurloon Minotaur', setCode: '2ED', cardNumber: '219', qty: 2 }],
} as unknown as ServerMessage

console.log('## a sideboard prompt opens the modal state')
handleMessage(sideboardPromptMsg, useGameStore.getState())
let s = useGameStore.getState()
check('sideboardPrompt is set', s.sideboardPrompt?.tableId === 'table-1')

console.log('## the server INVALID-DECK rejection (show_usermessage) re-enables the modal')
s = useGameStore.getState()
const stampBefore = s.sideboardSubmitRejectedAt
handleMessage({ type: 'show_usermessage', message: 'Invalid deck for the selected Constructed - Freeform format' } as unknown as ServerMessage, useGameStore.getState())
s = useGameStore.getState()
check('rejection stamp bumped', s.sideboardSubmitRejectedAt > stampBefore,
  `before=${stampBefore} after=${s.sideboardSubmitRejectedAt}`)
check('error toast carries the server text', s.errorMessage.includes('Invalid deck'), s.errorMessage)
check('the modal itself stays open for fixing + resubmit', s.sideboardPrompt?.tableId === 'table-1')

console.log('## the TOO-LATE rejection (proxy error) also re-enables the modal')
const stampBefore2 = s.sideboardSubmitRejectedAt
// The two rejections must land in different milliseconds — Date.now() is the
// stamp source, and a same-ms double rejection is not observable in state.
// A plain `while (Date.now() <= stampBefore2) {}` busy-wait here is NOT enough:
// rolldown tree-shakes the empty-body loop away, and same-ms collisions then
// flake this check ~15% of runs. Pin the clock across the second rejection so
// the bumped stamp is deterministic.
const realNow = Date.now
Date.now = () => stampBefore2 + 5
try {
  handleMessage({ type: 'error', message: 'Failed to submit deck: too late' } as unknown as ServerMessage, useGameStore.getState())
} finally {
  Date.now = realNow
}
s = useGameStore.getState()
check('rejection stamp bumped again', s.sideboardSubmitRejectedAt > stampBefore2,
  `before=${stampBefore2} after=${s.sideboardSubmitRejectedAt}`)

console.log('## submitDeck fails fast when the WebSocket is down')
useGameStore.setState({ ws: null } as never)
handleMessage({ type: 'start_game', gameId: 'g-pre' } as unknown as ServerMessage, useGameStore.getState()) // clear modal from stamp checks? no — start_game CLEARS the modal; re-open it
handleMessage(sideboardPromptMsg, useGameStore.getState())
s = useGameStore.getState()
check('modal re-opened', s.sideboardPrompt?.tableId === 'table-1')
s.submitDeck({ cards: [{ name: 'Grizzly Bears', setCode: '9ED', cardNumber: '250', qty: 40 }], sideboard: [] })
s = useGameStore.getState()
check('no submit_deck went out', !sent.some((m) => (m as { type?: string }).type === 'submit_deck'))
check('connection-down error surfaced', (s.errorMessage || '').includes('connection to the server is down'), s.errorMessage)
check('modal still open (player can retry once reconnected)', s.sideboardPrompt?.tableId === 'table-1')

console.log('## submitDeck sends the full payload when the socket is up')
useGameStore.setState({ ws: fakeWs } as never)
s = useGameStore.getState()
s.submitDeck({ cards: [{ name: 'Grizzly Bears', setCode: '9ED', cardNumber: '250', qty: 42 }], sideboard: [{ name: 'Hurloon Minotaur', setCode: '2ED', cardNumber: '219', qty: 2 }] })
const submitted = sent.find((m) => (m as { type?: string }).type === 'submit_deck') as { tableId?: string; deck?: { cards: unknown[]; sideboard: unknown[] } } | undefined
check('submit_deck went out', submitted !== undefined)
check('tableId matches the prompt', submitted?.tableId === 'table-1')
check('deck payload carried', Array.isArray(submitted?.deck?.cards) && Array.isArray(submitted?.deck?.sideboard))

console.log('## a NEW start_game clears a stale sideboard modal (late-submit case)')
s = useGameStore.getState()
check('modal open before the new game', s.sideboardPrompt?.tableId === 'table-1')
handleMessage({ type: 'start_game', gameId: 'g2' } as unknown as ServerMessage, useGameStore.getState())
s = useGameStore.getState()
check('sideboardPrompt cleared by start_game', s.sideboardPrompt === null)
check('inGame true', s.inGame === true)

console.log('## game_over also clears a lingering modal (match ends during sideboarding)')
handleMessage(sideboardPromptMsg, useGameStore.getState())
s = useGameStore.getState()
check('modal re-opened', s.sideboardPrompt?.tableId === 'table-1')
handleMessage({ type: 'game_over', result: 'wins', complete: false } as unknown as ServerMessage, useGameStore.getState())
s = useGameStore.getState()
check('sideboardPrompt cleared by game_over', s.sideboardPrompt === null)

console.log('## a fresh sideboard_prompt after game_over re-opens the modal (normal Bo3 flow)')
handleMessage(sideboardPromptMsg, useGameStore.getState())
s = useGameStore.getState()
check('modal open for game 2 sideboarding', s.sideboardPrompt?.tableId === 'table-1')

console.log()
console.log(failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
