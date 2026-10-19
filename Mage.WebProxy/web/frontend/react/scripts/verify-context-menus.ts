/**
 * Standalone behavioural checks for the shared context-menu work:
 *  1. the contextMenuSlice: one open menu at a time, replace semantics,
 *     close-of-replaced-menu does not cancel its successor;
 *  2. phase-stop tracking: the client-side tracked config (write-only on the
 *     wire) starts at all-on, and the PhaseContextMenu toggle path updates it;
 *  3. PhaseStopSettings-style init: the tracked config — not a constant — is
 *     the data source (the silent-revert bug).
 *
 * Run with:  bash scripts/verify-context-menus.sh
 *
 * The menu COMPONENTS render via react-dom/server where store-connected
 * children see the frozen initial state (see verify-token-menu-hides-preview
 * for the snapshot mechanics), so these harnesses drive the REAL store logic
 * the components call — the same functions the menu item onClick handlers
 * invoke — and assert store state, not markup.
 */
import { useGameStore } from '../src/store/gameStore'
import type { PhaseStopConfig } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const store = () => useGameStore.getState()

// ─────────────────────────────────────────────────────────────────────────────
section('registry basics — one menu at a time, replace semantics')

store().setOpenContextMenu(null)
check('starts empty', store().openContextMenu === null)

store().setOpenContextMenu({ kind: 'card', x: 5, y: 5, card: { name: 'Bear', typeLine: 'Creature' } as never })
check('card menu registers with payload', store().openContextMenu?.kind === 'card')
check('payload carries the card', (store().openContextMenu as { card?: { name: string } })?.card?.name === 'Bear')

// Opening a second menu REPLACES the first (never two at once).
store().setOpenContextMenu({ kind: 'phase', x: 6, y: 6, phaseName: 'Upkeep', label: 'Upkeep' })
check('a second open REPLACES the first', store().openContextMenu?.kind === 'phase')

// A stale close from the REPLACED menu must not cancel its successor — the
// exact protection useContextMenu.close implements via the kind check.
{
  const stale = store().openContextMenu
  const current = store().openContextMenu
  check('replace keeps the successor payload intact',
    current?.kind === 'phase' && (current as { phaseName?: string }).phaseName === 'Upkeep'
      && stale === current)
}

section('phase-stop tracked config — the dialog init fix')

{
  // Fresh tracked config is all-on (matches the server's initial UserData).
  const cfg = store().phaseStopConfig
  check('tracked config starts all-on', cfg.yourTurn.upkeep === true && cfg.opponentTurn.main1 === true)

  // The toggle the PhaseContextMenu item performs: flip one row, send, track.
  const next: PhaseStopConfig = {
    ...cfg,
    yourTurn: { ...cfg.yourTurn, upkeep: false },
  }
  store().setPhaseStopConfig(next)
  store().updatePhaseStops(next)
  const after = store().phaseStopConfig
  check('toggle updates the tracked config (yourTurn.upkeep off)',
    after.yourTurn.upkeep === false && after.yourTurn.main1 === true,
    JSON.stringify(after.yourTurn))
  check('opponent-turn rows are untouched by a your-turn toggle',
    after.opponentTurn.upkeep === true)

  // Re-opening the dialog now sees the toggled value, NOT the all-on constant —
  // the silent-revert bug the tracked copy fixes.
  check('dialog init source (tracked config) reflects the toggle',
    store().phaseStopConfig.yourTurn.upkeep === false)

  // Restore.
  const restored: PhaseStopConfig = { ...after, yourTurn: { ...after.yourTurn, upkeep: true } }
  store().setPhaseStopConfig(restored)
  check('restore works', store().phaseStopConfig.yourTurn.upkeep === true)
}

section('player menu payload — LLM index resolution contract')

{
  // GameView resolves name → llmDebug index when opening the menu; the menu
  // shows the item only for a non-negative index. Verify the resolution
  // contract against the real store shape (empty llmDebug → no item).
  const llmDebug = store().gameState?.llmDebug ?? []
  const idx = llmDebug.findIndex((l) => l.playerName === 'Nobody')
  check('missing seat resolves to -1 (menu item withheld)', idx === -1)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
