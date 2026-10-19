/**
 * Behavioural checks for the 2026-10-04 webui UX polish batch

 *
 * Run with:  bash scripts/verify-lobby-polish.sh
 *
 * Covers the LOW/polish items that were fixed frontend-only, on
 * the REAL modules (SSR of the actual components + the REAL pure rules):
 *
 *  (2) "No tables available" is a TRUE zero state — it must not render above a
 *      list that has rows. Source-structure assertion on LobbyView (the gate now
 *      also consults finishedMatches/replayFiles) + the pure fact the GamesList
 *      already owns the zero message.
 *  (3) MotD strip scope — the strip is mounted ONLY in LobbyView (never the game
 *      view), and its CSS is a bottom-anchored space-reserving flex item
 *      (margin-top:auto), not a fixed overlay.
 *  (4) Deck upload confirmation — deckUploadNoticeMessage() strings + the REAL
 *      PlayerSlotRow renders the role=status notice for its own slot only.
 *  (5) Decision popup vs phase bar — the centred branch now lifts the window
 *      above the mid-board phase strip (baseY bias), the bare centre expression
 *      is gone.
 *  (6) LLM rail empty copy — live board WITH LLM seats never claims "No LLM
 *      players in this game."; the original wording is preserved when the flag is
 *      absent/false; replay strings unchanged.
 *  (8) Users panel — the connected user's row is marked (self class + data-self
 *      + a "you" tag) and only that row; absent when the username is unknown.
 *  (9) Hand-card playable markup — CardThumb emits data-playable ONLY when the
 *      card is actually playable (absent otherwise).
 *
 * Node-only: real store, real message handler not needed, real components
 * rendered through react-dom/server. The only stub is the browser API surface
 * (scripts/verify-stubs.mjs).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement as h } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { useGameStore } from '../src/store/gameStore'
import { PlayerSlotRow } from '../src/components/PlayerSlotRow'
import UsersPanel from '../src/components/lobby/UsersPanel'
import { CardThumb } from '../src/components/CardThumb'
import { emptyLlmCopyFor } from '../src/components/RightRail'
import { deckUploadNoticeMessage } from '../src/utils/formatBytes'
import type { PlayerSlot, LobbyUser } from '../src/types'

declare const process: { exit(code: number): void; cwd(): string }

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object).
// Mirror every setState into it so server renders see the scenario — the same
// lever verify-seat-names.tsx uses.
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }
const READ = (p: string) => readFileSync(resolve(process.cwd(), p), 'utf8')

function makeSlot(type: string, isYou: boolean, extra: Partial<PlayerSlot> = {}): PlayerSlot {
  return { type, isYou, deck: '', showLLMConfig: type === 'COMPUTER_LLM', llmConfig: {}, ...extra }
}

// ═══════════════════════════════════════════════════════════════════════════
section('(2) "No tables available" is a TRUE zero state')

{
  const src = READ('src/components/LobbyView.tsx')
  check('the empty line is guarded by BOTH finishedMatches and replayFiles being empty',
    /finishedMatches\.length === 0 && replayFiles\.length === 0/.test(src))
  check('the empty line text is inside that gate (never rendered unconditionally)',
    /finishedMatches\.length === 0 && replayFiles\.length === 0[^)]*\(\s*<div[\s\S]{0,220}No tables available/.test(src))
  check('the tables grid still renders whenever live tables exist',
    /tables\.length > 0 \? \(\s*<TablesGrid \/>/.test(src))
  // The list's own empty message owns the zero case; the batch item was the
  // duplicate line ABOVE a populated list.
  const games = READ('src/components/lobby/GamesList.tsx')
  check('the games list keeps its own zero-state message',
    games.includes('No live games or replays yet.'))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(3) MotD strip is lobby-only and never overlays content')

{
  // MotdStrip must be mounted in exactly ONE place — LobbyView — and NEVER the
  // game view (the hunt reported a strip pinned to the in-game board; the
  // current App renders MotdStrip nowhere and GameView does not import it).
  const mountedIn = ['src/components/LobbyView.tsx', 'src/App.tsx', 'src/components/GameView.tsx']
    .filter((p) => /<MotdStrip\b/.test(READ(p)))
  check('MotdStrip is mounted in LobbyView only', mountedIn.length === 1 && mountedIn[0] === 'src/components/LobbyView.tsx',
    mountedIn.join(','))
  const css = READ('../styles/lobby-chat.css')
  check('.lobby-motd reserves its own space (margin-top:auto, not fixed)',
    /\.lobby-motd \{[\s\S]*?margin-top:\s*auto/.test(css) && !/\.lobby-motd \{[\s\S]*?position:\s*fixed/.test(css.split('.lobby-motd-label')[0]!))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(4) Deck-upload confirmation')

{
  check('message names the file and size', deckUploadNoticeMessage('deck.dck', 1024) === 'Loaded deck from "deck.dck" (1 KB).',
    deckUploadNoticeMessage('deck.dck', 1024))
  check('message omits the size when it is unknown', deckUploadNoticeMessage('deck.dck', null) === 'Loaded deck from "deck.dck".')
  check('a nameless file still yields a sentence', deckUploadNoticeMessage('', 512) === 'Loaded deck from the selected file (512 B).')

  setLive({
    capabilities: { llm: true, replayScrubber: true, replaySidecar: true, serverVersion: 'x', playerTypes: [] },
    proxyUsername: 'wizard',
    llmSetupWarning: null,
    deckUploadNotice: { slotIndex: 0, message: 'Loaded deck from "a.dck" (2 KB).' },
  })
  const renderRow = (slot: PlayerSlot, index: number) =>
    renderToStaticMarkup(
      h(PlayerSlotRow, { slot, index, canRemove: false, onUpdate: () => {}, onRemove: () => {}, onDeckFile: () => {} }),
    )
  const youRow = renderRow(makeSlot('HUMAN', true), 0)
  check('the matched seat row renders the confirmation', youRow.includes('data-testid="deck-upload-notice"'))
  check('the confirmation is role=status (informational, not an error)',
    /role="status"[^>]*data-testid="deck-upload-notice"|data-testid="deck-upload-notice"[^>]*role="status"/.test(youRow))
  check('the confirmation carries the message text', youRow.includes('Loaded deck from'))

  const otherRow = renderRow(makeSlot('HUMAN', false), 1)
  check('a DIFFERENT seat does not show the notice', !otherRow.includes('deck-upload-notice'))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(5) Decision popup clears the phase bar')

{
  const src = READ('src/components/DecisionPopup.tsx')
  check('the centred branch biases the window above the midline', src.includes('const stripClearY = vh / 2'))
  check('the base position is the biased value (Math.min of centre and strip-clear)', src.includes('rawY = Math.min(centreY, stripClearY)'))
  check('the old bare centre expression is gone', !/^\s*rawY = \(vh - h\) \/ 2\s*$/m.test(src))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(6) LLM rail empty copy never lies about seats')

{
  check('live WITH seats → viewer-visibility wording (never the no-LLM claim)',
    emptyLlmCopyFor(false, undefined, true, true) === 'No LLM exchanges are visible to you in this game yet.' &&
    !emptyLlmCopyFor(false, undefined, true, true).includes('No LLM players'))
  check('live with the flag ABSENT/false → the original no-LLM wording (back-compat)',
    emptyLlmCopyFor(false, undefined, true) === 'No LLM players in this game.' &&
    emptyLlmCopyFor(false, undefined, true, false) === 'No LLM players in this game.')
  check('replay strings are unchanged (full history)',
    emptyLlmCopyFor(true, true, true) === 'No LLM exchanges recorded up to this frame.')
  check('replay strings are unchanged (single frame)', emptyLlmCopyFor(true, true, false) === 'No LLM exchanges recorded at this frame.')
  check('replay of a game with no LLM seat keeps the no-LLM wording',
    emptyLlmCopyFor(true, false, true) === 'No LLM players in this game.')
  check('replay flag ABSENT → exchange wording (never the live claim)',
    emptyLlmCopyFor(true, undefined, true) === 'No LLM exchanges recorded up to this frame.')
}

// ═══════════════════════════════════════════════════════════════════════════
section('(8) Users panel marks the connected user')

{
  const user = (name: string): LobbyUser => ({
    flagName: 'us', userName: name, matchHistory: null, matchQuitRatio: 0,
    tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: '3ms',
    generalRating: 0, constructedRating: 0, limitedRating: 0,
  })
  setLive({
    proxyUsername: 'wizard',
    lobbyUsers: [user('wizard'), user('Zed')],
  })
  const html = renderToStaticMarkup(h(UsersPanel, {}))
  check('the self row carries the self class', html.includes('lobby-users-row self'))
  check('the self row carries data-self="true"', html.includes('data-self="true"'))
  check('exactly ONE row is marked self', (html.match(/data-self="true"/g) ?? []).length === 1)
  check('a "you" tag is rendered on the self row', html.includes('lobby-users-you'))
  check('exactly ONE "you" tag', (html.match(/lobby-users-you/g) ?? []).length === 1)
  check('the other user is still listed', html.includes('Zed'))

  setLive({ proxyUsername: '', lobbyUsers: [user('wizard'), user('Zed')] })
  const htmlUnknown = renderToStaticMarkup(h(UsersPanel, {}))
  check('unknown username → no self marking (fail closed)', !htmlUnknown.includes('data-self="true"') && !htmlUnknown.includes('lobby-users-you'))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(9) Hand cards carry additive playable markup')

{
  const card: any = { id: 'c-play', name: 'Lightning Bolt', typeLine: 'Instant', manaCost: '{R}', rules: [], imageUrl: undefined }
  const playable = renderToStaticMarkup(h(CardThumb, { card, className: 'card-thumb-sm', isPlayable: true }))
  const notPlayable = renderToStaticMarkup(h(CardThumb, { card, className: 'card-thumb-sm', isPlayable: false }))
  check('a playable card emits data-playable="true"', playable.includes('data-playable="true"'))
  check('a playable card keeps the card-playable class', playable.includes('card-playable'))
  check('a non-playable card has NO data-playable attribute', !notPlayable.includes('data-playable'))
  check('a non-playable card has no card-playable class', !notPlayable.includes('card-playable'))
}

console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
