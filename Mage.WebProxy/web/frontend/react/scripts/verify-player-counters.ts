/**
 * Behavioural checks for the dedicated player-counter displays.
 *
 * Run with:  bash scripts/verify-player-counters.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-endgame-presentation.sh.)
 *
 * WHAT THIS FIXES. PlayerBoard rendered ONE generic `name: count | name: count`
 * line for every counter type. The desktop player panel renders five DEDICATED,
 * labelled fields — poison, energy, experience, rad, ticket
 * (PlayerPanelExt.java:553-676, wired :290-294). The serializer already passes
 * every counter type (GameStateSerializer.java:631-641).
 *
 * Assertions:
 *  A. splitPlayerCounters: the five are pulled out in desktop order, zeros drop,
 *     case-insensitive match, a dedicated counter is never duplicated into
 *     `others`, `others` keeps input order.
 *  B. counterTooltip mirrors the desktop's "<name>: <count>".
 *  C. The REAL PlayerBoard renders a labelled field per dedicated counter
 *     (data-counter attr + "Poison: 3" text), leaves other counters in the
 *     generic line, and renders NO counters block when there are none.
 *  D. Source-scan regression + CSS chips present.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { PlayerBoard } from '../src/components/PlayerBoard'
import { splitPlayerCounters, counterTooltip, DEDICATED_COUNTERS } from '../src/utils/playerCounters'
import type { Player, PlayerCounter } from '../src/types'

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const pc = (name: string, count: number): PlayerCounter => ({ name, count })

// ── A. the split ──
check('A1: the five dedicated counters are pulled out in desktop order',
  (() => {
    const { dedicated } = splitPlayerCounters([
      pc('ticket', 2), pc('poison', 3), pc('experience', 1), pc('energy', 4), pc('rad', 5),
    ])
    return dedicated.map((d) => d.counter).join(',') === 'poison,energy,experience,rad,ticket'
  })())
check('A2: labels + glyphs are the expected values', (() => {
  const { dedicated } = splitPlayerCounters([pc('poison', 3)])
  return dedicated[0]?.label === 'Poison' && dedicated[0]?.glyph === 'P'
})())
check('A3: a zero-count dedicated counter is dropped',
  splitPlayerCounters([pc('poison', 0), pc('energy', 2)]).dedicated.map((d) => d.counter).join(',') === 'energy')
check('A4: case-insensitive name match (a hand-framed value still lands)',
  splitPlayerCounters([pc('POISON', 3)]).dedicated[0]?.counter === 'poison')
check('A5: a dedicated counter is NEVER duplicated into others',
  splitPlayerCounters([pc('poison', 3), pc('charge', 2)]).others.map((c) => c.name).join(',') === 'charge')
check('A6: others keep input order and every non-dedicated counter',
  (() => {
    const { others } = splitPlayerCounters([pc('charge', 2), pc('loyalty', 1), pc('poison', 3)])
    return others.map((c) => c.name).join(',') === 'charge,loyalty'
  })())
check('A7: undefined / empty -> both empty (no crash)',
  (() => {
    const e = splitPlayerCounters(undefined)
    const z = splitPlayerCounters([])
    return e.dedicated.length === 0 && e.others.length === 0 && z.dedicated.length === 0 && z.others.length === 0
  })())
check('A8: there are exactly the five desktop counters',
  DEDICATED_COUNTERS.map((s) => s.counter).join(',') === 'poison,energy,experience,rad,ticket')
check('A9: counterTooltip mirrors the desktop "<name>: <count>"',
  counterTooltip('Poison', 3) === 'Poison: 3')

// ── C. the real component ──
const playerWithCounters: Player = {
  name: 'QA-A', playerId: 'p1', life: 20, isMe: true, libraryCount: 40, handCount: 7,
  isActive: true, hasPriority: true, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  manaPool: null,
  counters: [pc('poison', 3), pc('energy', 4), pc('charge', 2)],
  commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
}
const html = renderToStaticMarkup(createElement(PlayerBoard, {
  player: playerWithCounters, isTopPlayer: false, isCommander: false, isActiveTurn: false, hasPriority: false,
}))
check('C1: each present dedicated counter renders its own labelled field',
  html.includes('data-counter="poison"') && html.includes('Poison: 3')
    && html.includes('data-counter="energy"') && html.includes('Energy: 4'), html)
check('C2: the generic line keeps the non-dedicated counter',
  html.includes('player-counters-generic') && html.includes('charge: 2'), html)
check('C3: the dedicated counters are NOT in the generic line',
  !/player-counters-generic[^<]*poison/i.test(html), html)
check('C4: the field carries the desktop tooltip text',
  html.includes('title="Poison: 3"'), html)

const playerNoCounters: Player = { ...playerWithCounters, counters: [] }
const emptyHtml = renderToStaticMarkup(createElement(PlayerBoard, {
  player: playerNoCounters, isTopPlayer: false, isCommander: false, isActiveTurn: false, hasPriority: false,
}))
check('C5: no counters at all -> no counters block',
  !emptyHtml.includes('player-counters"') && !emptyHtml.includes('data-counter='), emptyHtml)

const playerZeroDedicated: Player = { ...playerWithCounters, counters: [pc('poison', 0), pc('charge', 2)] }
const zeroHtml = renderToStaticMarkup(createElement(PlayerBoard, {
  player: playerZeroDedicated, isTopPlayer: false, isCommander: false, isActiveTurn: false, hasPriority: false,
}))
check('C6: a zero dedicated counter is not rendered as a field',
  !zeroHtml.includes('data-counter="poison"') && zeroHtml.includes('charge: 2'), zeroHtml)

// ── D. source-scan + CSS ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-player-counters.sh')
const boardSrc = readFileSync(`${SRC}/components/PlayerBoard.tsx`, 'utf8')
check('D1: PlayerBoard imports the split helper', boardSrc.includes('splitPlayerCounters'))
check('D2: PlayerBoard renders the dedicated field class', boardSrc.includes('player-counter--'))
const css = readFileSync(`${SRC}/../../game-renderer.css`, 'utf8')
check('D3: the CSS defines the chip + each dedicated modifier',
  css.includes('.player-counter {')
    && ['poison', 'energy', 'experience', 'rad', 'ticket'].every((c) => css.includes(`.player-counter--${c}`)))

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
