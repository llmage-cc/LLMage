/**
 * Standalone behavioural check for the remembered-answer store actions
 * autoAnswerPrompt / autoAnswerReset.
 *
 * Run with:  bash scripts/verify-auto-answer.sh
 *
 * Not part of the app build (scripts/ is excluded from tsconfig "include": ["src"]).
 * Every case drives the REAL store through the REAL message handler
 * (handleMessage -> setActionPrompt), so the assertions cover the shipped code path.
 *
 * What is pinned here:
 *  1. the boolean payload's key material (original_id / auto_answer_message) reaches
 *     the prompt object verbatim;
 *  2. autoAnswerPrompt sends `auto_answer` FIRST (the memory write) and then the
 *     boolean_choice (the answer) — the desktop HelperPanel order
 *     (sendPlayerAction + clickButton in one action) — including the exact payload;
 *  3. it refuses to send when the prompt carries no key (fail fast, no dead key);
 *  4. the resets send the bare verb and touch nothing else.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage } from '../src/types'

// record the full payloads that go on the wire, in order
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null, passPending: false })
}

/** The exact boolean payload WebProxyClient.handleGameAsk writes (auto-answer fields included). */
const ask = (message: string, originalId?: string, autoAnswerMessage?: string): ServerMessage =>
  ({
    type: 'action_prompt',
    action_type: 'boolean',
    message,
    context: 'yes_no',
    yes_label: 'Yes',
    no_label: 'No',
    ...(originalId != null ? { original_id: originalId } : {}),
    ...(autoAnswerMessage != null ? { auto_answer_message: autoAnswerMessage } : {}),
  }) as unknown as ServerMessage

const ORIGINAL_ID = '11111111-2222-3333-4444-555555555555'
const KEY = 'Put {this} onto the battlefield?'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('prompt build — the key material rides the prompt object')
{
  reset()
  handleMessage(ask('Put Grizzly Bears onto the battlefield?', ORIGINAL_ID, KEY), useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  check('renders as a boolean', p?.type === 'boolean')
  check('originalId passed through verbatim', p?.type === 'boolean' && p.originalId === ORIGINAL_ID)
  check('autoAnswerMessage passed through verbatim', p?.type === 'boolean' && p.autoAnswerMessage === KEY)
}
{
  reset()
  handleMessage(ask('Mulligan down to 6 cards?'), useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  check('a source-less ask has no originalId', p?.type === 'boolean' && p.originalId === undefined)
  check('…and no invented autoAnswerMessage', p?.type === 'boolean' && p.autoAnswerMessage === undefined)
}

// ─────────────────────────────────────────────────────────────────────────────
section('autoAnswerPrompt — memory write FIRST, then the answer (desktop order)')
{
  reset()
  handleMessage(ask('Put Grizzly Bears onto the battlefield?', ORIGINAL_ID, KEY), useGameStore.getState())
  useGameStore.getState().autoAnswerPrompt('id_yes', ORIGINAL_ID, KEY, true)

  check('exactly two messages went out', sent.length === 2, JSON.stringify(sent))
  check('first is the memory write', sent[0]?.type === 'auto_answer')
  check('its action is the id variant', sent[0]?.action === 'id_yes')
  check('its originalId is the ask\u2019s', sent[0]?.originalId === ORIGINAL_ID)
  check('its message is the ask\u2019s autoAnswerMessage (NOT the displayed text)',
    sent[0]?.message === KEY)
  check('then the prompt is answered YES', sent[1]?.type === 'boolean_choice' && sent[1]?.value === true)
}
{
  reset()
  handleMessage(ask('Put Grizzly Bears onto the battlefield?', ORIGINAL_ID, KEY), useGameStore.getState())
  useGameStore.getState().autoAnswerPrompt('id_no', ORIGINAL_ID, KEY, false)
  check('id_no stores NO and answers NO', sent[0]?.action === 'id_no' && sent[1]?.value === false)
}
{
  reset()
  handleMessage(ask('Mulligan down to 6 cards?', undefined, 'Mulligan down to 6 cards?'), useGameStore.getState())
  useGameStore.getState().autoAnswerPrompt('text_no', null, 'Mulligan down to 6 cards?', false)
  check('text variant carries null originalId', sent[0]?.action === 'text_no' && sent[0]?.originalId === null)
  check('text variant keys on the message', sent[0]?.message === 'Mulligan down to 6 cards?')
}

// ─────────────────────────────────────────────────────────────────────────────
section('fail fast — a prompt with no key stores nothing (and answers nothing)')
{
  reset()
  handleMessage(ask('Pay {E}{E}{E}?'), useGameStore.getState())   // no auto_answer_message
  useGameStore.getState().autoAnswerPrompt('text_yes', null, '', true)
  check('nothing went on the wire', sent.length === 0, JSON.stringify(sent))
}
{
  reset()
  // No prompt at all: a reset-style call must not fabricate a key.
  useGameStore.getState().autoAnswerPrompt('text_yes', null, 'anything', true)
  check('no prompt → nothing sent', sent.length === 0, JSON.stringify(sent))
}

// ─────────────────────────────────────────────────────────────────────────────
section('resets — bare verb, no key material')
{
  reset()
  useGameStore.getState().autoAnswerReset('reset_all')
  check('reset_all sent', sent.length === 1 && sent[0]?.type === 'auto_answer' && sent[0]?.action === 'reset_all')
  check('resets carry null key fields', sent[0]?.originalId === null && sent[0]?.message === null)
}
{
  reset()
  useGameStore.getState().autoAnswerReset('reset_replacement')
  check('reset_replacement sent', sent.length === 1 && sent[0]?.action === 'reset_replacement')
}

// ─────────────────────────────────────────────────────────────────────────────
section('trigger-order reset rides the EXISTING verb (no duplicate)')
{
  reset()
  useGameStore.getState().triggerOrder('reset_all', null, null)
  check('still one trigger_order message', sent.length === 1 && sent[0]?.type === 'trigger_order')
  check('with action reset_all', sent[0]?.action === 'reset_all')
}

console.log(failures === 0 ? '\nPASS' : `\nFAIL (${failures})`)
if (failures > 0) process.exit(1)
