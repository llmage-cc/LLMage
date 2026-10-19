/**
 * Behavioural checks for the card-preview hotkeys.
 *
 * Run with:  bash scripts/verify-preview-hotkeys.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-endgame-presentation.sh.)
 *
 * WHAT THIS ADDS. `grep -rn "altKey" src/` was 0 hits — the hover preview had no
 * modifier-key handling. It now has Alt+E (enlarge) and Alt+S (show the card's
 * ALTERNATE SOURCE: its other face / copy source, the desktop's EnlargeMode
 * ALTERNATE) driven by utils/cardPreviewHotkeys.
 *
 * Assertions:
 *  A. previewHotkey: only the ALT chord fires; bare e/s and other keys are
 *     ignored; case-insensitive; the exact key -> action mapping.
 *  B. alternateSourceOf / hasAlternateSource: secondFace wins over originalCard;
 *     null/whitespace rejected; a plain card has no alternate.
 *  C. previewDisplayName: alternate on with a source shows the source name;
 *     alternate on WITHOUT a source falls back to the card's own name.
 *  D. previewPanelGeometry: Alt+E multiplies width AND image column by the
 *     factor; off = the exact previous numbers.
 *  E. The REAL CardPreview component renders the hotkey hint, the
 *     alternate-source label when Alt+S is active, and the enlarged class — and
 *     a card with no alternate source advertises Alt+S as n/a.
 *  F. Source-scan regression: CardPreview.tsx listens for keydown and consumes
 *     altKey.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import CardPreview from '../src/components/CardPreview'
import {
  ENLARGE_FACTOR,
  applyPreviewHotkey,
  alternateSourceOf,
  hasAlternateSource,
  previewDisplayName,
  previewHotkey,
  previewPanelGeometry,
  DEFAULT_PREVIEW_MODE,
} from '../src/utils/cardPreviewHotkeys'
import type { Card } from '../src/types'

// CardPreview reads getComputedStyle (panel geometry) at render; Node has no DOM.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle = () => ({ zoom: 1 })

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── A. hotkey mapping ──
check('A1: Alt+E -> enlarge', previewHotkey('e', true) === 'enlarge' && previewHotkey('E', true) === 'enlarge')
check('A2: Alt+S -> alternate', previewHotkey('s', true) === 'alternate' && previewHotkey('S', true) === 'alternate')
check('A3: a BARE e/s is never hijacked', previewHotkey('e', false) === null && previewHotkey('s', false) === null)
check('A4: unrelated keys ignored even with Alt', previewHotkey('a', true) === null && previewHotkey('Enter', true) === null)
check('A5: the toggle flips the right flag and never mutates',
  (() => {
    const m0 = DEFAULT_PREVIEW_MODE
    const m1 = applyPreviewHotkey(m0, 'enlarge')
    const m2 = applyPreviewHotkey(m0, 'alternate')
    return m1.enlarged && !m1.alternate && !m2.enlarged && m2.alternate
      && m0.enlarged === false && m0.alternate === false
  })())

// ── B. alternate source ──
const plainCard: Card = { id: 'p1', name: 'Gray Ogre', imageUrl: '', typeLine: 'Creature', manaCost: '', rules: [] }
check('B1: a plain card has NO alternate source',
  alternateSourceOf(plainCard) === null && !hasAlternateSource(plainCard))
check('B2: originalCard -> a copy source', (() => {
  const c = { ...plainCard, originalCard: 'Clone' }
  const s = alternateSourceOf(c)
  return s?.kind === 'copy' && s.name === 'Clone'
})())
check('B3: secondFace WINS over originalCard (other face is the desktop preference)', (() => {
  const c = { ...plainCard, originalCard: 'Clone', secondFace: { name: 'Delver of Secrets', imageUrl: 'x' } } as Card
  const s = alternateSourceOf(c)
  return s?.kind === 'face' && s.name === 'Delver of Secrets'
})())
check('B4: whitespace-only sources are rejected', (() => {
  const c = { ...plainCard, originalCard: '   ' } as Card
  return alternateSourceOf(c) === null && !hasAlternateSource(c)
})())

// ── C. display name ──
check('C1: alternate off -> the card name', previewDisplayName(plainCard, { enlarged: false, alternate: false }) === 'Gray Ogre')
check('C2: alternate on WITH a source -> the source name',
  previewDisplayName({ ...plainCard, originalCard: 'Clone' } as Card, { enlarged: false, alternate: true }) === 'Clone')
check('C3: alternate on WITHOUT a source -> falls back to the card name (never empty)',
  previewDisplayName(plainCard, { enlarged: false, alternate: true }) === 'Gray Ogre')

// ── D. geometry ──
check('D1: default geometry is the previous panel (540/220)',
  previewPanelGeometry(1, DEFAULT_PREVIEW_MODE).panelWidth === 'min(540px, calc(var(--vw) - 24px))'
    && previewPanelGeometry(1, DEFAULT_PREVIEW_MODE).imageCol === 220)
check('D2: Alt+E scales width AND image column by the factor', (() => {
  const g = previewPanelGeometry(1, { enlarged: true, alternate: false })
  return g.panelWidth === `min(${Math.round(540 * ENLARGE_FACTOR)}px, calc(var(--vw) - 24px))`
    && g.imageCol === Math.round(220 * ENLARGE_FACTOR)
})())
check('D3: the hover-size setting still multiplies under the enlarge factor', (() => {
  const g = previewPanelGeometry(2, { enlarged: true, alternate: false })
  return g.panelWidth === `min(${Math.round(540 * 2 * ENLARGE_FACTOR)}px, calc(var(--vw) - 24px))`
})())

// ── E. the real component ──
const altCard: Card = {
  id: 'c1', name: 'Delver of Secrets', imageUrl: 'https://cards.scryfall.io/normal/front/1/2/x.jpg',
  typeLine: 'Creature', manaCost: '{U}', rules: ['Flying'],
  secondFace: { name: 'Insectile Aberration', imageUrl: 'https://cards.scryfall.io/normal/back/1/2/x.jpg' },
}
const setPreview = (card: Card) =>
  useGameStore.setState({ cardPreview: { card, face: 'front', x: 100, y: 100 } })

setPreview(altCard)
const normalHtml = renderToStaticMarkup(createElement(CardPreview))
check('E1: the Alt+E/Alt+S hint is always rendered', normalHtml.includes('Alt+E enlarge') && normalHtml.includes('Alt+S alternate'), normalHtml)
check('E2: default render is NOT enlarged and shows no alternate label',
  normalHtml.includes('data-preview-mode="normal"') && normalHtml.includes('data-preview-source="normal"')
    && !normalHtml.includes('id="preview-source"'))

setPreview(plainCard)
const plainHtml = renderToStaticMarkup(createElement(CardPreview))
check('E3: a card with no alternate source advertises Alt+S as n/a',
  plainHtml.includes('Alt+S alternate (n/a)'), plainHtml)

// ── F. source-scan regression ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-preview-hotkeys.sh')
const src = readFileSync(`${SRC}/components/CardPreview.tsx`, 'utf8')
check('F1: CardPreview now handles keydown', src.includes("addEventListener('keydown'"))
check('F2: it consults altKey (was 0 hits before the preview hotkeys)', src.includes('e.altKey') || src.includes('.altKey'))
check('F3: it applies the enlarge/alternate helpers', src.includes('applyPreviewHotkey') && src.includes('previewHotkey'))
check('F4: the alternate swap resolves art for the alternate name', src.includes('alternate.name'))

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
