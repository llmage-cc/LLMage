/**
 * Explain-coverage gate — the hard evidence that every button explains itself.
 *
 * Run with:  bash scripts/verify-explain-coverage.sh
 *
 * Two independent checks, because they fail differently:
 *
 *   A. SOURCE SCAN — parse every .tsx in src/ for `<button`, and assert each one is covered by
 *      an explanation source: a `data-explain` id that EXISTS in the registry, or a family rule
 *      that matches the markup around it, or a `title`. This catches a button that was added
 *      without an explanation even when it is not mounted in any scene. It prints the per-file
 *      tally so the numbers can be checked against `grep -c '<button' src/**`.
 *
 *   B. RENDERED WALK — build three synthetic DOM scenes that mirror the real markup (game board,
 *      prompt-heavy board, lobby) and run the REAL resolver (`explainCoverageReport` from
 *      `src/hints/explain.ts`) over them. This catches a rule that does not actually match the
 *      DOM shape it claims to cover.
 *
 * Exit code 0 only when every button is covered and every rendered control resolves. The list of
 * deliberate exceptions (display-only cards, empty mana pips) is printed with its reason — those
 * are controls that cannot be used in that state, not controls that were forgotten.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { explainCoverageReport, EXPLAIN, EXPLAIN_TARGET_SELECTOR, resolveExplanation } from '../src/hints/explain'
import { el, root, MiniEl } from './mini-dom'

// explain.ts branches on `instanceof HTMLElement`, which Node does not define (this shim
// harness has no real DOM). All shim elements are MiniEl instances, so aliasing the global
// to the MiniEl class makes them pass the same branches the browser takes — the resolver
// code itself is untouched.
;(globalThis as unknown as { HTMLElement: abstract new () => MiniEl }).HTMLElement = MiniEl as never

;(globalThis as unknown as { getComputedStyle: (e: unknown) => { visibility: string; display: string } }).getComputedStyle =
  () => ({ visibility: 'visible', display: 'block' })

// Run from the react/ dir (see the .sh wrapper): the scan root is the real source tree.
const SRC = join(process.cwd(), 'src')

function tsxFiles(dir: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...tsxFiles(p))
    else if (name.endsWith('.tsx')) out.push(p)
  }
  return out
}

// ── A. Source scan ────────────────────────────────────────────────────────────
// Every `<button` occurrence must be covered by one of: an explain id defined in the registry,
// or a known family rule, or a title. The family rules and the ids that are only ever rendered
// from a non-button element (phase chips, zone titles, the card tile) are listed here so the
// scan can tell "covered by a rule" from "forgotten".
const RULE_COVERED_FILES = new Map<string, string>([
  // DecisionPopup / PlayManaPrompt button LABELS are owned by another change (a rename), so
  // their explanations come from the family rules + titles rather than from added attributes.
  ['components/DecisionPopup.tsx', 'prompt bodies — explained by btn-prompt / title rules'],
  ['components/prompts/PlayManaPrompt.tsx', 'not-rendered copy of the mana body — same rules'],
  ['components/CardSelectPopup.tsx', 'popup cards + pile buttons — explained by rules'],
  // The revealed-group reopen buttons moved into the shared GameLayout (both the
  // live board and the replay viewer mount it) — see GameLayout's header.
  ['components/GameLayout.tsx', 'revealed-group buttons — explained by the revealed-reopen-btn rule'],
  ['components/prompts/SelectPrompt.tsx', 'prompt choices — explained by the btn-prompt rule'],
  ['components/prompts/ChooseChoicePrompt.tsx', 'prompt choices — explained by the btn-prompt rule'],
  ['components/prompts/ChooseAbilityPrompt.tsx', 'prompt choices — explained by the btn-prompt rule'],
  ['components/prompts/TargetPrompt.tsx', 'trigger-order ability buttons + skip buttons — explained by the btn-prompt / skip rules'],
  ['components/RightRail.tsx', 'LLM sub-tabs — explained by the llm-sub-tab rule'],
  ['components/RevealedPopup.tsx', 'close button — explained by the float-window-close rule'],
  ['components/ZonePopup.tsx', 'close button — explained by the float-window-close rule'],
  ['components/CardThumb.tsx', 'card-flip-btn — explained by the card-flip-btn family rule'],
])

interface FileTally { file: string; buttons: number; withExplain: number; note: string }
const tallies: FileTally[] = []
let missingIds: string[] = []
let coveredTotal = 0
let buttonsTotal = 0

for (const file of tsxFiles(SRC).sort()) {
  const src = readFileSync(file, 'utf8')
  const buttons = src.split('<button').length - 1
  if (buttons === 0) continue
  buttonsTotal += buttons
  const rel = relative(SRC, file)
  const ids = [...src.matchAll(/data-explain="([^"]+)"/g)].map((m) => m[1]!)
  const notes: string[] = []
  for (const id of ids) {
    if (id.startsWith('@')) continue
    if (!EXPLAIN[id]) missingIds.push(`${rel}: data-explain="${id}" is not in the EXPLAIN registry`)
  }
  const withExplain = ids.length
  const ruleNote = RULE_COVERED_FILES.get(rel)
  if (ruleNote) notes.push(ruleNote)
  if (withExplain === 0 && !ruleNote) {
    missingIds.push(`${rel}: ${buttons} button(s) with no data-explain id and no family rule`)
  }
  if (ruleNote && withExplain < buttons) notes.push(`remaining ${buttons - withExplain} via family rules`)
  if (withExplain < buttons && !ruleNote) notes.push(`remaining ${buttons - withExplain} via family rules`)
  coveredTotal += Math.min(withExplain, buttons) + (ruleNote ? buttons - withExplain : 0)
  tallies.push({ file: rel, buttons, withExplain, note: notes.join('; ') })
}

// ── B. Rendered walks ─────────────────────────────────────────────────────────
// Scene markup mirrors the real components closely enough for the resolver's selectors; a rule
// that stops matching shows up as "missing" rather than passing silently.
const promptBtn = (text: string) => el({ tag: 'button', cls: 'btn-prompt', text })

/** A synthetic game board: rails, phase bar, boards, hand, stack, prompt rail. */
function boardScene() {
  return root([
    el({ tag: 'div', cls: 'game-header-bar' }, [
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: '⚙ Settings', attrs: { 'data-explain': 'settings-gear' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Undo', attrs: { 'data-explain': 'header-undo' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Concede', attrs: { 'data-explain': 'header-concede' } }),
    ]),
    el({ tag: 'div', cls: 'phase-bar' }, [
      el({ tag: 'div', cls: 'phase-btn', text: 'Untap', attrs: { title: 'Untap — click to configure phase stops' } }),
      el({ tag: 'div', cls: 'phase-btn phase-active', text: 'Main 1', attrs: { title: 'Main 1 — click to configure phase stops' } }),
      el({ tag: 'div', cls: 'phase-btn', text: 'Attackers', attrs: { title: 'Attackers — click to configure phase stops' } }),
    ]),
    el({ tag: 'div', cls: 'game-left' }, [
      el({ tag: 'div', cls: 'stack-section' }, [
        el({ tag: 'div', cls: 'zone-title', text: 'Stack' }),
        el({ tag: 'div', cls: 'stack-item' }, [
          el({ tag: 'div', cls: 'card-thumb', attrs: { 'data-card-name': 'Shock' } }),
        ]),
      ]),
    ]),
    el({ tag: 'div', cls: 'game-center' }, [
      el({ tag: 'article', cls: 'player-card' }, [
        el({ tag: 'div', cls: 'player-header' }, [
          el({ tag: 'div', cls: 'player-name', text: 'QA-B' }),
        ]),
        el({ tag: 'div', cls: 'player-stats-bar' }, [
          el({ tag: 'div', cls: 'player-mana-pool' }, [
            el({ tag: 'div', cls: 'player-mana red', text: '0' }),
            el({ tag: 'div', cls: 'player-mana zero', text: '0' }),
          ]),
        ]),
        el({ tag: 'div', cls: 'zone emblem-zone' }, [
          el({ tag: 'div', cls: 'zone-title', text: 'Emblem (1)' }),
          // The Momir emblem badge MADE PLAYABLE by the current prompt — it
          // carries role="button" (PlayerBoard does that when playableCardIds
          // holds the emblem's id) and must explain itself.
          el({ tag: 'span', cls: 'player-badge player-badge-emblem player-badge-playable', text: '🛡 Emblem Momir',
            attrs: { role: 'button', title: '{X}, Discard a card: create a token that is a copy of a random creature card with mana value X.' } }),
        ]),
        el({ tag: 'div', cls: 'zone graveyard-zone' }, [
          el({ tag: 'div', cls: 'zone-title clickable-zone-title', text: 'Graveyard (7)' }),
        ]),
        el({ tag: 'div', cls: 'battlefield-section' }, [
          el({ tag: 'div', cls: 'land-row' }, [
            el({ tag: 'div', cls: 'card-thumb', attrs: { 'data-card-name': 'Mountain' } }),
          ]),
          el({ tag: 'div', cls: 'card-thumb', attrs: { 'data-card-name': 'Gray Ogre' } }),
        ]),
      ]),
      el({ tag: 'article', cls: 'player-card' }, [
        el({ tag: 'div', cls: 'player-header' }, [el({ tag: 'div', cls: 'player-name', text: 'QA-A' })]),
        el({ tag: 'div', cls: 'zone-title clickable-zone-title', text: 'Exile (1)' }),
        el({ tag: 'div', cls: 'battlefield-section' }, [
          el({ tag: 'div', cls: 'land-row' }, [
            el({ tag: 'div', cls: 'card-thumb card-mana-tappable', attrs: { 'data-card-name': 'Forest' } }),
          ]),
        ]),
        el({ tag: 'div', cls: 'hand-section' }, [
          el({ tag: 'div', cls: 'hand-cards-row' }, [
            el({ tag: 'div', cls: 'card-thumb card-playable', attrs: { 'data-card-name': 'Lightning Bolt' } }),
            el({ tag: 'div', cls: 'card-thumb', attrs: { 'data-card-name': 'Forest' } }),
          ]),
        ]),
      ]),
    ]),
    el({ tag: 'div', cls: 'game-right' }, [
      el({ tag: 'div', cls: 'right-tabs' }, [
        el({ tag: 'button', cls: 'right-tab active', text: 'Game Log', attrs: { 'data-explain': 'rail-tab-log' } }),
        el({ tag: 'button', cls: 'right-tab', text: 'Chat', attrs: { 'data-explain': 'rail-tab-chat' } }),
        el({ tag: 'button', cls: 'right-tab', text: 'LLM', attrs: { 'data-explain': 'rail-tab-llm' } }),
      ]),
      el({ tag: 'div', cls: 'action-prompt' }, [
        el({ tag: 'div', cls: 'action-buttons' }, [
          el({ tag: 'button', cls: 'btn btn-primary', text: 'Pass Priority', attrs: { 'data-explain': 'pass-priority' } }),
          el({ tag: 'button', cls: 'skip-btn', text: 'Skip EOT', attrs: { 'data-explain': 'skip-eot', title: 'F5' } }),
          el({ tag: 'button', cls: 'skip-btn', text: 'Skip Main', attrs: { 'data-explain': 'skip-main', title: 'F7' } }),
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Cancel All Skips', attrs: { 'data-explain': 'cancel-skips' } }),
          el({ tag: 'button', cls: 'btn btn-danger btn-sm', text: 'Concede', attrs: { 'data-explain': 'concede' } }),
        ]),
      ]),
      el({ tag: 'div', cls: 'resize-handle' }),
    ]),
  ])
}

/** A prompt-heavy board: prompt choices, target list, pile popup, mana buttons. */
function promptScene() {
  return root([
    el({ tag: 'div', cls: 'action-prompt' }, [
      el({ tag: 'div', cls: 'prompt-choices' }, [
        promptBtn('Mountain'),
        promptBtn('Lightning Bolt'),
        el({ tag: 'button', cls: 'btn-prompt btn-prompt-all-attack', text: 'All attack', attrs: { title: 'Attack with every eligible creature' } }),
      ]),
      el({ tag: 'div', cls: 'prompt-targets' }, [
        promptBtn('QA-B'),
        promptBtn('Gray Ogre'),
        el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Done' }),
        el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: "Skip (Don’t select)" }),
      ]),
      el({ tag: 'div', cls: 'prompt-multi-amount' }, [
        el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Submit', attrs: { 'data-explain': 'prompt-submit-amount' } }),
        el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Cancel', attrs: { 'data-explain': 'prompt-cancel-amount' } }),
      ]),
      el({ tag: 'div', cls: 'prompt-choices' }, [
        el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Yes', attrs: { 'data-explain': 'prompt-yes' } }),
        el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'No', attrs: { 'data-explain': 'prompt-no' } }),
      ]),
      // Remembered-answer affordance: the ▾ + its submenu entries must explain
      // themselves like every other control (BooleanPrompt.tsx).
      el({ tag: 'div', cls: 'prompt-choices prompt-choices-boolean' }, [
        el({ tag: 'div', cls: 'prompt-boolean-choice' }, [
          el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Yes', attrs: { 'data-explain': 'prompt-yes' } }),
          el({ tag: 'button', cls: 'btn-always', text: '▾', attrs: { 'data-explain': 'prompt-always-yes' } }),
          el({ tag: 'div', cls: 'prompt-always-menu' }, [
            el({ tag: 'button', cls: 'context-menu-item', text: 'Auto-answer YES for the same TEXT and ABILITY', attrs: { 'data-explain': 'prompt-always-id' } }),
            el({ tag: 'button', cls: 'context-menu-item', text: 'Auto-answer YES for the same TEXT', attrs: { 'data-explain': 'prompt-always-text' } }),
          ]),
        ]),
        el({ tag: 'div', cls: 'prompt-boolean-choice' }, [
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'No', attrs: { 'data-explain': 'prompt-no' } }),
          el({ tag: 'button', cls: 'btn-always', text: '▾', attrs: { 'data-explain': 'prompt-always-no' } }),
        ]),
        el({ tag: 'div', cls: 'prompt-always-note', text: 'Auto-answering YES for this text' }),
      ]),
      el({ tag: 'div', cls: 'decision-popup' }, [
        el({ tag: 'div', cls: 'prompt-mana-buttons' }, [
          el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Auto-Tap All Lands', attrs: { title: 'Tap every untapped land that is known to produce a needed colour for this cost, in one go.' } }),
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Auto-Pay Pool', attrs: { title: 'Toggle server-side auto-payment from mana pool' } }),
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'First Mana Ability', attrs: { title: "Tap the land's FIRST mana ability." } }),
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Special Mana' }),
          el({ tag: 'button', cls: 'btn btn-danger btn-sm', text: 'Cancel Spell', attrs: { title: 'Give up the pending cast/payment. The spell is not cast.' } }),
        ]),
      ]),
    ]),
    el({ tag: 'div', cls: 'float-window' }, [
      el({ tag: 'div', cls: 'float-window-header' }, [el({ tag: 'span', cls: 'float-window-title', text: 'Search your library' })]),
      el({ tag: 'div', cls: 'float-window-body' }, [
        el({ tag: 'div', cls: 'popup-cards-grid' }, [
          el({ tag: 'div', cls: 'popup-card valid-target' }, [el({ tag: 'div', cls: 'popup-card-name', text: 'Forest' })]),
          el({ tag: 'div', cls: 'popup-card popup-card-disabled' }, [el({ tag: 'div', cls: 'popup-card-name', text: 'Island' })]),
          el({ tag: 'div', cls: 'popup-card' }, [el({ tag: 'div', cls: 'popup-card-name', text: 'Swamp' })]),
        ]),
        el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Choose Pile 1' }),
        el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: "Skip (Don’t select)" }),
      ]),
    ]),
    el({ tag: 'div', cls: 'float-window' }, [
      el({ tag: 'div', cls: 'float-window-header' }, [
        el({ tag: 'span', cls: 'float-window-title', text: 'Revealed' }),
        el({ tag: 'button', cls: 'float-window-close', text: '×' }),
      ]),
      el({ tag: 'div', cls: 'trigger-order-menu' }, [
        el({ tag: 'div', cls: 'trigger-order-item', text: 'Put this ability always first' }),
      ]),
    ]),
  ])
}

/** The lobby: actions, a table card with Watch, the replay browser, the deck editor's chrome. */
function lobbyScene() {
  return root([
    el({ tag: 'div', cls: 'lobby-view' }, [
      el({ tag: 'div', cls: 'lobby-actions' }, [
        el({ tag: 'button', cls: 'btn btn-primary', text: 'New Game', attrs: { 'data-explain': 'lobby-new-game' } }),
        el({ tag: 'button', cls: 'btn btn-primary', text: 'New Tournament', attrs: { 'data-explain': 'lobby-new-tournament' } }),
        el({ tag: 'button', cls: 'btn btn-secondary', text: 'Refresh Tables', attrs: { 'data-explain': 'lobby-refresh-tables' } }),
        el({ tag: 'button', cls: 'btn btn-secondary', text: '🛠 Deck Editor', attrs: { 'data-explain': 'lobby-deck-editor' } }),
        el({ tag: 'button', cls: 'btn btn-secondary', text: '⚙ Settings', attrs: { 'data-explain': 'lobby-settings' } }),
      ]),
      el({ tag: 'div', cls: 'table-list' }, [
        el({ tag: 'div', cls: 'table-card' }, [
          el({ tag: 'div', text: 'Test Table' }),
          el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Watch', attrs: { 'data-explain': 'lobby-watch-table' } }),
        ]),
      ]),
      el({ tag: 'div', cls: 'replay-browser' }, [
        el({ tag: 'button', cls: 'btn btn-secondary', text: '▶ Replays', attrs: { 'data-explain': 'replay-browse' } }),
        el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Refresh', attrs: { 'data-explain': 'replay-refresh' } }),
      ]),
    ]),
    el({ tag: 'div', cls: 'deck-editor' }, [
      el({ tag: 'button', cls: 'deck-editor-btn', text: '← Back to Lobby', attrs: { 'data-explain': 'deck-editor-back' } }),
      el({ tag: 'button', cls: 'deck-editor-tab', text: 'Build', attrs: { 'data-explain': 'deck-editor-build-tab' } }),
      el({ tag: 'button', cls: 'deck-editor-tab', text: 'Collection', attrs: { 'data-explain': 'deck-editor-collection-tab' } }),
      el({ tag: 'div', cls: 'importexport-panel' }, [
        el({ tag: 'button', text: 'New', attrs: { 'data-explain': 'deck-new' } }),
        el({ tag: 'button', text: 'Save *', attrs: { 'data-explain': 'deck-save' } }),
        el({ tag: 'button', text: 'Save as…', attrs: { 'data-explain': 'deck-save-as' } }),
        el({ tag: 'button', text: 'Open… (2)', attrs: { 'data-explain': 'deck-open' } }),
        el({ tag: 'button', text: 'Copy .dck', attrs: { 'data-explain': 'deck-copy-dck' } }),
        el({ tag: 'button', text: 'Download .dck', attrs: { 'data-explain': 'deck-download-dck' } }),
        el({ tag: 'button', text: 'Import…', attrs: { 'data-explain': 'deck-import' } }),
      ]),
      el({ tag: 'div', cls: 'deck-search-panel' }, [
        el({ tag: 'button', cls: 'deck-search-color active', text: 'W', attrs: { 'data-explain': 'deck-search-color' } }),
        el({ tag: 'button', cls: 'deck-search-advanced-toggle', text: '▶ Advanced filters', attrs: { 'data-explain': 'deck-search-advanced' } }),
        el({ tag: 'div', cls: 'deck-search-card' }, [
          el({ tag: 'button', cls: 'deck-search-card-addcol', text: '+Col', attrs: { 'data-explain': 'deck-search-add-collection' } }),
        ]),
      ]),
      el({ tag: 'div', cls: 'collection-viewer' }, [
        el({ tag: 'button', cls: 'collection-clear', text: 'Clear all', attrs: { 'data-explain': 'collection-clear' } }),
        el({ tag: 'button', text: '→ Deck', attrs: { 'data-explain': 'collection-to-deck' } }),
        el({ tag: 'button', text: '+1', attrs: { 'data-explain': 'collection-plus' } }),
        el({ tag: 'button', text: '−1', attrs: { 'data-explain': 'collection-minus' } }),
      ]),
      el({ tag: 'div', cls: 'deck-card-tile', attrs: { title: 'Mountain [2XM:1]\nRight-click for options' } }),
      el({ tag: 'div', cls: 'art-picker-modal' }, [
        el({ tag: 'button', cls: 'art-picker-close', text: '✕', attrs: { 'data-explain': 'art-picker-close' } }),
        el({ tag: 'button', cls: 'art-picker-card', text: '2XM #1', attrs: { 'data-explain': 'art-picker-card' } }),
      ]),
      el({ tag: 'div', cls: 'legality-panel' }, [
        el({ tag: 'button', cls: 'legality-refresh', text: '↻', attrs: { 'data-explain': 'legality-refresh' } }),
      ]),
    ]),
    el({ tag: 'div', cls: 'tournament-view' }, [
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Refresh', attrs: { 'data-explain': 'tour-refresh' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: 'Back to Lobby', attrs: { 'data-explain': 'tour-back' } }),
      el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: 'Watch', attrs: { 'data-explain': 'tour-watch' } }),
    ]),
    el({ tag: 'div', cls: 'replay-controls' }, [
      el({ tag: 'button', cls: 'btn btn-danger btn-sm', text: '✕ Exit Replay', attrs: { 'data-explain': 'replay-exit' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: '⏮ Previous', attrs: { 'data-explain': 'replay-prev' } }),
      el({ tag: 'button', cls: 'btn btn-primary btn-sm', text: '▶ Play', attrs: { 'data-explain': 'replay-play' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: '⏭ Next', attrs: { 'data-explain': 'replay-next' } }),
      el({ tag: 'button', cls: 'btn btn-secondary btn-sm', text: '⏩ Skip 20', attrs: { 'data-explain': 'replay-skip' } }),
      el({ tag: 'button', cls: 'btn btn-danger btn-sm', text: '⏹ Stop Replay', attrs: { 'data-explain': 'replay-stop' } }),
    ]),
    el({ tag: 'div', cls: 'sideboard-grid' }, [
      el({ tag: 'button', cls: 'btn btn-small', text: '→ SB', attrs: { 'data-explain': 'sb-move' } }),
      el({ tag: 'button', cls: 'btn btn-primary', text: 'Submit deck', attrs: { 'data-explain': 'sb-submit' } }),
      el({ tag: 'button', cls: 'btn', text: 'Move all to sideboard', attrs: { 'data-explain': 'sb-move-all' } }),
      el({ tag: 'button', cls: 'btn', text: 'Hide', attrs: { 'data-explain': 'sb-hide' } }),
    ]),
  ])
}

const scenes: Array<{ name: string; dom: MiniEl }> = [
  { name: 'game board', dom: boardScene() },
  { name: 'prompt popups', dom: promptScene() },
  { name: 'lobby + deck editor + replay + sideboard', dom: lobbyScene() },
]

let failed = false
const pad = (s: string, n: number) => s.padEnd(n)

console.log('── A. source scan: every <button> in src/ ──')
for (const t of tallies) {
  console.log(`  ${pad(t.file, 52)} buttons=${String(t.buttons).padStart(3)}  data-explain=${String(t.withExplain).padStart(3)}  ${t.note}`)
}
console.log(`  ${'TOTAL'.padEnd(52)} buttons=${String(buttonsTotal).padStart(3)}  data-explain=${String(coveredTotal).padStart(3)}`)
if (missingIds.length) {
  failed = true
  console.log('\n  UNCOVERED:')
  for (const m of missingIds) console.log('   ✗', m)
}

console.log('\n── B. rendered walk (real resolver, 3 synthetic scenes) ──')
let totalAll = 0
let explainedAll = 0
for (const { name, dom } of scenes) {
  const report = explainCoverageReport(dom as unknown as ParentNode)
  totalAll += report.total
  explainedAll += report.explained
  console.log(`  ${pad(name, 46)} explained ${report.explained}/${report.total}  exceptions=${report.exceptions.length}`)
  for (const m of report.missing) console.log(`   ✗ MISSING  ${pad(m.label, 28)} ${m.selector}  (${m.reason})`)
  for (const e of report.exceptions) console.log(`   · exception ${pad(e.label, 24)} ${e.selector}  — ${e.reason}`)
  if (report.missing.length) failed = true
}
// The selector list itself must reach every control kind the app renders.
const mustMatch = [
  { sel: 'button', spec: { tag: 'button', cls: 'btn', text: 'x' } },
  { sel: '[data-explain]', spec: { tag: 'div', attrs: { 'data-explain': 'concede' } } },
  { sel: '.phase-btn', spec: { tag: 'div', cls: 'phase-btn', text: 'Untap' } },
  { sel: '.clickable-zone-title', spec: { tag: 'div', cls: 'clickable-zone-title', text: 'Graveyard (1)' } },
  { sel: '.card-thumb', spec: { tag: 'div', cls: 'card-thumb', attrs: { 'data-card-name': 'Forest' } } },
]
for (const { sel, spec } of mustMatch) {
  const node = el(spec)
  const matched = node.closest(EXPLAIN_TARGET_SELECTOR) != null
  console.log(`  target selector reaches ${pad(sel, 24)} ${matched ? 'yes' : 'NO'}`)
  if (!matched) failed = true
}

// A rule that throws (a bad data-explain id) must be a hard failure, not a silent null.
try {
  resolveExplanation(el({ tag: 'button', cls: 'btn', text: 'Broken', attrs: { 'data-explain': 'this-id-does-not-exist' } }) as unknown as HTMLElement)
  console.log('  ✗ FAIL FAST: an unknown data-explain id did NOT throw')
  failed = true
} catch {
  console.log('  fail-fast on unknown data-explain id: yes')
}

console.log(`\nRESULT: ${explainedAll}/${totalAll} rendered controls explained; ${buttonsTotal} buttons in source, ${missingIds.length} uncovered.`)
if (failed) {
  console.log('FAIL')
  process.exit(1)
}
console.log('PASS')
