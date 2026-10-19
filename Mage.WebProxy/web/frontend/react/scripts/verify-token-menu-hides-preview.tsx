/**
 * Standalone behavioural check: the token-art right-click menu suppresses the
 * hover card preview while it is open.
 *
 * Run with:  bash scripts/verify-token-menu-hides-preview.sh
 *
 * The token browser (TokenArtMenu) renders UNDER the hover card
 * (#card-preview z-index 9999 vs the menu's inline 3000), so right-clicking a
 * token showed the menu hidden behind/under the preview panel. The fix: a store
 * flag (tokenArtMenuOpen) that CardThumb sets when the menu opens and clears
 * when it closes; CardPreview renders nothing while the flag is up.
 *
 * Everything here drives the REAL modules: the real store slice, the real
 * CardPreview and TokenArtMenu components (rendered with react-dom/server, so
 * no browser is needed). The handler-side wiring (CardThumb setting/clearing
 * the flag) is asserted by the wrapping shell script, because react-dom/server
 * strips event handlers from SSR markup — same reason verify-card-grid-preview
 * greps render sites instead of asserting markup.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import CardPreview from '../src/components/CardPreview'
import { TokenArtMenu } from '../src/components/TokenArtMenu'
import { menuOwnedByTile } from '../src/utils/menuScope'
import type { Permanent } from '../src/types'

// rootZoom() (utils/viewport.ts) reads getComputedStyle(document.documentElement).zoom.
// The Node stubs provide documentElement but not getComputedStyle; identity zoom (1)
// is the correct answer for a headless render — it is what an unzoomed page reports.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

// react-dom/server reads zustand's getServerSnapshot, which is
// `selector(api.getInitialState())` — the store's INITIAL state object, NOT the
// live state (zustand v5 react.mjs wires the third useSyncExternalStore argument
// to api.getInitialState). Assigning useGameStore.getInitialState does not reach
// the api closure, so the working lever is the snapshot object itself: it is
// returned by reference, so mutating it (in lockstep with the live setState)
// is what makes each server render below see the delivered scenario.
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

const SOLDIER: Permanent = {
  id: 'token-1',
  name: 'Soldier',
  typeLine: 'Token Creature — Soldier',
  power: '2',
  toughness: '2',
} as unknown as Permanent

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const resetScenario = () => setLive({ cardPreview: null, openContextMenu: null })
const renderPreview = (): string => renderToStaticMarkup(<CardPreview />)

// ─────────────────────────────────────────────────────────────────────────────
section('store registry exists and defaults to closed')

resetScenario()
check('openContextMenu defaults null', useGameStore.getState().openContextMenu === null)
useGameStore.getState().setOpenContextMenu({ kind: 'card', x: 10, y: 10, card: SOLDIER })
check('setOpenContextMenu raises the entry (live state)',
  useGameStore.getState().openContextMenu?.kind === 'card')
useGameStore.getState().setOpenContextMenu(null)
check('setOpenContextMenu(null) clears it (live state)',
  useGameStore.getState().openContextMenu === null)

section('baseline — the hover preview still renders when no menu is open')

resetScenario()
setLive({ cardPreview: { card: SOLDIER, face: 'front', x: 100, y: 100 } })
const baseline = renderPreview()
check('hover preview renders (#card-preview) with no menu open',
  baseline.includes('card-preview') && baseline.includes('Soldier'), baseline.slice(0, 120))

section('the fix — preview does not render while ANY context menu is open')

setLive({ openContextMenu: { kind: 'card', x: 10, y: 10, card: SOLDIER } })
const suppressed = renderPreview()
check('preview renders NOTHING while a card menu is open (no #card-preview markup)',
  suppressed === '', JSON.stringify(suppressed))
check('the preview DATA is still in the store (menu close restores it, not a data wipe)',
  useGameStore.getState().cardPreview?.card?.name === 'Soldier')
setLive({ openContextMenu: null })
check('closing the menu brings the preview back',
  renderPreview().includes('card-preview'))

section('every menu kind suppresses the preview (one registry, all menus)')

for (const kind of ['token-art', 'trigger-order', 'deck-card', 'phase', 'player', 'chat', 'llm'] as const) {
  setLive({ openContextMenu: { kind, x: 10, y: 10 } })
  check(`kind=${kind} suppresses the hover preview`, renderPreview() === '')
}
setLive({ openContextMenu: null })
check('clearing the registry restores the preview after the kind sweep',
  renderPreview().includes('card-preview'))

section('the panel is ONE mount point driven by the store payload')
{
  // The duplication bug:
  // the panel used to be rendered BY EACH CardThumb with a GLOBAL `isOpen` guard, so
  // every tile on the board rendered its own panel — 34 overlapping menus, each
  // titled with its own tile's card name. The fix moves the token + engine set into
  // the registry payload and mounts the panel once (GameView/ReplayView).
  //
  // Asserted here at the component level: the panel renders ONLY when the open
  // menu IS the token-art menu, and its title comes from the payload — never from
  // a prop (so a non-owner mount point cannot render a stale panel).
  setLive({
    openContextMenu: {
      kind: 'token-art',
      x: 40,
      y: 40,
      tokenName: 'Soldier',
      engineSet: 'm19',
    },
  })
  const html = renderToStaticMarkup(<TokenArtMenu />)
  check('token-art entry in the registry renders the panel',
    html.includes('token-art-menu'), html.slice(0, 160))
  check('panel renders the full-screen transparent overlay (modal-over-the-board shape)',
    html.includes('modal-overlay'), html.slice(0, 160))
  check('panel title comes from the store payload',
    html.includes('Token art — Soldier'), html.slice(0, 200))
  check('panel carries the payload token as its aria-label',
    html.includes('aria-label="Token art — Soldier"'), html.slice(0, 240))
}

section('a different menu kind renders NO token panel (the duplication guard)')
{
  // This is what a per-tile render site got wrong: `isOpen` was `open !== null`,
  // true for ANY kind, so every tile rendered a panel on any right-click. The
  // hoisted component must render nothing unless the open kind is token-art.
  for (const kind of ['card', 'phase', 'player', 'trigger-order', 'chat', 'llm'] as const) {
    setLive({ openContextMenu: { kind, x: 10, y: 10 } })
    const out = renderToStaticMarkup(<TokenArtMenu />)
    check(`kind=${kind} renders nothing`, out === '', JSON.stringify(out.slice(0, 120)))
  }
  setLive({ openContextMenu: null })
  check('no open menu renders nothing', renderToStaticMarkup(<TokenArtMenu />) === '')
}

section('a second token-art open re-targets the SAME single panel')
{
  setLive({ openContextMenu: { kind: 'token-art', x: 10, y: 10, tokenName: 'Elemental Token' } })
  const first = renderToStaticMarkup(<TokenArtMenu />)
  setLive({ openContextMenu: { kind: 'token-art', x: 500, y: 300, tokenName: 'Treasure' } })
  const second = renderToStaticMarkup(<TokenArtMenu />)
  check('one panel, retitled to the newly clicked token',
    first.includes('Token art — Elemental Token') && second.includes('Token art — Treasure')
      && !second.includes('Elemental Token'),
    second.slice(0, 200))
  check('the second open is still exactly one panel',
    (second.match(/token-art-menu-panel/g) ?? []).length === 1)
  check('the second open is exactly one overlay',
    (second.match(/modal-overlay/g) ?? []).length === 1)
  setLive({ openContextMenu: null })
}

section('no cross-contamination — an ordinary preview cycle is unaffected')

resetScenario()
setLive({ cardPreview: { card: SOLDIER, face: 'front', x: 0, y: 0 } })
setLive({ cardPreview: null })
check('setCardPreview(null) still clears the preview', renderPreview() === '')

// ─────────────────────────────────────────────────────────────────────────────
// Unmount cleanup scoping: the registry is global, so a tile unmounting must only clear a
// menu it OWNS. The unscoped form ("any tile unmounts while any menu is open")
// let an unrelated tile's unmount close a menu still anchored on another tile —
// live-measured during replay playback, an unrelated Morska unmount closed the
// open Clue Token art menu at a board push. CardThumb's cleanup calls
// menuOwnedByTile; these checks pin its semantics so a regression back to the
// unscoped guard fails here.
section('unmount cleanup ownership — only the owning tile may clear the registry')

// token-art menus are NAME-scoped: the payload carries only the token name, and
// a board push can re-key a token tile (new id, same name) within one commit.
check('token-art menu owned by a tile with the same token name',
  menuOwnedByTile({ kind: 'token-art', tokenName: 'Clue Token' }, 'Clue Token', 'any-id'))
check('token-art menu NOT owned by a different token name (Mechanism 1: unrelated unmount must not close it)',
  !menuOwnedByTile({ kind: 'token-art', tokenName: 'Clue Token' }, 'Morska, Undersea Sleuth', 'morska-1'))
check('token-art menu NOT owned by a NON-token tile (Mountain unmount keeps the menu)',
  !menuOwnedByTile({ kind: 'token-art', tokenName: 'Clue Token' }, 'Mountain', 'mountain-1'))

// card menus are INSTANCE-scoped: same name, different id must not match.
check('card menu owned by the same card id',
  menuOwnedByTile({ kind: 'card', card: { id: 'c1', name: 'Soldier' } }, 'Soldier', 'c1'))
check('card menu NOT owned by a different card instance with the same name',
  !menuOwnedByTile({ kind: 'card', card: { id: 'c1', name: 'Soldier' } }, 'Soldier', 'c2'))
check('card menu falls back to name when the id is absent',
  menuOwnedByTile({ kind: 'card', card: { name: 'Soldier' } }, 'Soldier', null) &&
  !menuOwnedByTile({ kind: 'card', card: { name: 'Soldier' } }, 'Goblin', null))
check('no open menu is never owned',
  !menuOwnedByTile(null, 'Clue Token', 'x') && !menuOwnedByTile(undefined, 'Clue Token', 'x'))
check('other menu kinds are not owned by a tile (phase/player/chat close on their own paths)',
  !menuOwnedByTile({ kind: 'phase' }, 'Clue Token', 'x') &&
  !menuOwnedByTile({ kind: 'player' }, 'Clue Token', 'x'))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
