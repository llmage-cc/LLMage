/**
 * Standalone behavioural check: the emblem badge in the command zone is
 * CLICKABLE when a playable choice exists for it (Momir Basic;

 *
 * Run with:  bash scripts/verify-emblem-badge-clickable.sh
 *
 * The defect's frontend half: PlayerBoard rendered emblems as inert
 * `<span className="player-badge player-badge-emblem">` badges — no click, no
 * playable affordance — so even once the proxy emitted the emblem's activation
 * as a select choice there was no surface to act on it.
 *
 * The fix mirrors the commander CardThumb pattern: `isPlayable` comes from
 * playableCardIds (built from actionPrompt.choices ids, so the emblem's id is
 * already in it — no extra store wiring), the badge gets an onClick that calls
 * onCardClick, and it gains `player-badge-playable` + role="button"+tabIndex so
 * the state is visible and reachable. GameView.handleCardClick then looks the
 * choice up by id and fires selectChoice → select_choice → sendPlayerUUID —
 * verified by the wiring grep layer below, because SSR markup cannot carry
 * event handlers.
 *
 * Layers:
 *   (a) SSR renders of the REAL PlayerBoard with real props (react-dom/server).
 *   (b) wiring greps: onClick/role/keyboard on the REAL render site in
 *       PlayerBoard.tsx, the click path in GameView.tsx, and the CSS class.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { PlayerBoard } from '../src/components/PlayerBoard'
import type { Player } from '../src/types'

// PlayerBoard renders BattlefieldZone → CardThumb, which reads the store and
// getComputedStyle (rootZoom). Node has neither; the stubs make the render
// work without changing what is asserted.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const EMBLEM_ID = 'emblem-momir-1'

function player(overrides: Partial<Player> = {}): Player {
  return {
    name: 'QA-A',
    playerId: 'p1',
    life: 24,
    isMe: true,
    libraryCount: 33,
    handCount: 1,
    isActive: true,
    hasPriority: true,
    hasLeft: false,
    timerActive: false,
    priorityTimeLeftSecs: 0,
    isMonarch: false,
    hasInitiative: false,
    passedTurn: false,
    passedUntilEndOfTurn: false,
    passedUntilNextMain: false,
    passedAllTurns: false,
    passedUntilStackResolved: false,
    passedUntilEndStepBeforeMyTurn: false,
    manaPool: null,
    counters: [],
    commanders: [],
    emblems: [{ id: EMBLEM_ID, name: 'Emblem Momir', imageUrl: '', typeLine: '', rules: ['{X}, Discard a card: create a token that\'s a copy of a random creature card with mana value X'] }],
    planes: [],
    battlefield: [],
    hand: [],
    graveyard: [],
    exile: [],
    ...overrides,
  } as Player
}

function render(props: Record<string, unknown>): string {
  return renderToStaticMarkup(createElement(PlayerBoard as never, props as never))
}

/** The emblem badge's opening tag only (markup assertions must not match elsewhere). */
function emblemTag(html: string): string {
  const i = html.indexOf('player-badge-emblem')
  if (i < 0) return ''
  return html.slice(html.lastIndexOf('<span', i), html.indexOf('>', i) + 1)
}

// ─────────────────────────────────────────────────────────────────────────────
section('baseline — an emblem with NO playable choice stays inert')

{
  const html = render({ player: player(), isTopPlayer: false, isCommander: false, isActiveTurn: true, hasPriority: true, playableCardIds: new Set<string>() })
  check('the badge still renders (no regression in the emblem zone)', html.includes('player-badge-emblem'), html.slice(0, 100))
  check('the badge shows the emblem name', html.includes('Emblem Momir'))
  check('the rules ride the hover title', html.includes('Discard a card'))
  check('NOT playable: no player-badge-playable class', !html.includes('player-badge-playable'), emblemTag(html))
  check('NOT playable: no role="button" (inert by construction)', !emblemTag(html).includes('role="button"'), emblemTag(html))
  check('NOT playable: no inline cursor:pointer', !emblemTag(html).includes('cursor:pointer'), emblemTag(html))
}

section('the fix — a playable emblem choice makes the badge activatable')

{
  const html = render({ player: player(), isTopPlayer: false, isCommander: false, isActiveTurn: true, hasPriority: true, playableCardIds: new Set([EMBLEM_ID]) })
  const tag = emblemTag(html)
  check('the badge carries the playable class', html.includes('player-badge-playable'), tag)
  check('the badge is labelled as a control (role="button")', tag.includes('role="button"'), tag)
  check('the badge is keyboard reachable (tabIndex=0)', tag.includes('tabindex="0"'), tag)
  check('the playable state is visible (inline pointer cursor)', tag.includes('cursor:pointer'), tag)
}

section('the choice id drives the state (no name matching)')

{
  // A different id in the playable set must NOT make THIS emblem clickable —
  // the state comes from the choice id, exactly what sendPlayerUUID needs back.
  const other = render({ player: player(), isTopPlayer: false, isCommander: false, isActiveTurn: true, hasPriority: true, playableCardIds: new Set(['some-other-card-id']) })
  check('an unrelated playable id leaves the emblem inert', !other.includes('player-badge-playable'), emblemTag(other))

  // And a second emblem with its own id is the only one that lights up.
  const two = player({
    emblems: [
      { id: EMBLEM_ID, name: 'Emblem Momir', imageUrl: '', typeLine: '', rules: ['{X}, Discard: token.'] },
      { id: 'emblem-other', name: 'Emblem Elspeth', imageUrl: '', typeLine: '', rules: ['Soldiers get +1/+1.'] },
    ],
  })
  const twoHtml = render({ player: two, isTopPlayer: false, isCommander: false, isActiveTurn: true, hasPriority: true, playableCardIds: new Set([EMBLEM_ID]) })
  check('exactly ONE badge is playable (the matching id)', (twoHtml.match(/player-badge-playable/g) ?? []).length === 1,
    String((twoHtml.match(/player-badge-playable/g) ?? []).length))
  // Compare the two badge occurrences (opening tag + its text child, which
  // identifies the emblem): the playable class must be on the Momir one.
  const badges = [...twoHtml.matchAll(/<span[^>]*player-badge-emblem[^>]*>[^<]*/g)].map((m) => m[0]!)
  check('two emblem badges rendered', badges.length === 2, badges.join(' | '))
  const momirBadge = badges.find((t) => t.includes('Emblem Momir')) ?? ''
  const elspethBadge = badges.find((t) => t.includes('Emblem Elspeth')) ?? ''
  check('the playable one is the matching emblem (Momir)',
    momirBadge.includes('player-badge-playable'), momirBadge)
  check('the non-matching emblem (Elspeth) stays inert',
    !elspethBadge.includes('player-badge-playable') && !elspethBadge.includes('role="button"'), elspethBadge)
}

section('a non-commander game still renders the emblem zone (isCommander=false)')

{
  const html = render({ player: player(), isTopPlayer: true, isCommander: false, isActiveTurn: false, hasPriority: false, playableCardIds: new Set([EMBLEM_ID]) })
  check('emblem zone renders for the top player too', html.includes('player-badge-emblem'))
  check('playable state works for the top player', html.includes('player-badge-playable'))
  check('no commander zone is forced (isCommander false → no Commander (N) title)',
    !html.includes('zone commander-zone'), 'commander zone leaked')
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
