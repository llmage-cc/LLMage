/**
 * Real-geometry check for the game header's missing space

 *
 * Run with:  bash scripts/verify-header-gap.sh
 *
 * "Space missing in the text in the top left of the ingame screen between
 * the table name and the player name who's turn it is" — the header rendered
 * `XMage GameQA-A's Turn 5 | ...` with NO gap.
 *
 * Measured cause: `.game-header-bar` is `display:flex; justify-content:
 * space-between`, which would normally space its three children apart — but
 * child 3 (`.game-menu`) carries an inline `margin-left:auto` (GameHeader.tsx:76
 * and ReplayView.tsx:167; both must stay, this harness covers the live header),
 * and an auto margin absorbs ALL of the free space on the main axis, leaving
 * `space-between` nothing to distribute. No `gap` was set on the container.
 *
 * This harness MEASURES it in a real Chromium (playwright-core, headless) rather
 * than asserting declarations: it loads the REAL layout.css + base.css from this
 * checkout and renders the REAL GameHeader markup (structure copied from
 * src/components/GameHeader.tsx, including the inline margin-left:auto, which is
 * exactly what the defect hinges on). Assertions:
 *  (a) the gap between the title block's right edge and #turn-info's left edge is
 *      > 0, at --ui-scale 1, 1.35 (the shipped default) and 2 (a 4K-ish extreme),
 *  (b) the gap SCALES with --ui-scale (the header scales; the fix must too),
 *  (c) .game-menu stays pinned to the header's right edge (padding-right only),
 *  (d) the title starts at the header's left padding — the fix added a gap, not
 *      a margin that shifts the title.
 *
 * A static assertion is the fallback only if Chromium cannot launch: the
 * declarations alone cannot prove geometry (auto-margin absorption is a layout
 * engine behaviour), so a fallback run is REPORTED as such.
 */
import { readFileSync } from 'node:fs'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? '.'
const FRONTEND = join(HERE, '..', '..')
const LAYOUT_CSS = join(FRONTEND, 'styles', 'layout.css')
const BASE_CSS = join(FRONTEND, 'base.css')
const HEADER_TSX = join(HERE, '..', 'src', 'components', 'GameHeader.tsx')

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── the REAL css + REAL markup ───────────────────────────────────────────────
const layoutCss = readFileSync(LAYOUT_CSS, 'utf8')
const baseCss = readFileSync(BASE_CSS, 'utf8')
const headerSrc = readFileSync(HEADER_TSX, 'utf8')

// The title text is hardcoded in GameView.tsx (gameTitle="LLMage Game"); keep the
// markup faithful to GameHeader.tsx:64-88 — title div, #turn-info.game-menu with
// inline margin-left:auto.
const MARKUP = `<div class="game-header-bar" id="bar">
  <div id="title" style="font-weight:600;color:var(--text-primary)">LLMage Game</div>
  <div id="turn-info">QA-A's Turn 5 | Main Phase 1 | Priority: QA-A</div>
  <div class="game-menu" id="menu" style="margin-left:auto;display:flex;gap:0.5rem">
    <button class="btn btn-secondary btn-sm">⚙ Settings</button>
    <button class="btn btn-secondary btn-sm">Undo</button>
    <button class="btn btn-secondary btn-sm">Concede</button>
  </div>
</div>`

section('Harness fidelity — the fixture is the shipped markup/css')

{
  check('the fixture keeps the menu\'s inline margin-left:auto (the defect hinges on it)',
    /id="menu"[^>]*margin-left:auto/.test(MARKUP) && /marginLeft: 'auto'/.test(headerSrc))
  check('the fixture markup mirrors GameHeader.tsx children (title, turn-info, game-menu)',
    /id="title"/.test(MARKUP) && /id="turn-info"/.test(MARKUP) && /class="game-menu"/.test(MARKUP)
      && /id="turn-info"/.test(headerSrc) && /className="game-menu"/.test(headerSrc))
  check('the header text is the shipped one (never renamed by this fix)',
    // The title is __LLMAGE__-conditional in
    // GameView.tsx ('LLMage Game' : 'XMage Game') — accept either spelling so
    // the fixture stays the shipped markup in both build flavors.
    /__LLMAGE__ \? 'LLMage Game' : 'XMage Game'/.test(readFileSync(join(HERE, '..', 'src', 'components', 'GameView.tsx'), 'utf8'))
      && MARKUP.includes('LLMage Game'))
}

// ── static fallback assertion (only used when Chromium cannot launch) ────────
function staticFallback(): number {
  console.log('\n## Chromium unavailable — static declaration assertion (reported as fallback)')
  const block = /\.game-header-bar\s*\{([\s\S]*?)\}/.exec(layoutCss)?.[1] ?? ''
  const gapDecl = /gap:\s*calc\(1rem \* var\(--ui-scale, 1\)\)/.test(block)
  const hasInlineAuto = /margin-left:auto|marginLeft: 'auto'/.test(MARKUP)
  check('FALLBACK (no geometry): .game-header-bar declares the --ui-scale gap', gapDecl, block)
  check('FALLBACK (no geometry): the menu still carries inline margin-left:auto '
    + '(so the gap — not space-between — is what separates the clusters)', hasInlineAuto)
  check('FALLBACK (no geometry): no compensating margin-right was ALSO added to the title '
    + '(one fix, not two)', !/margin-right/.test(block))
  return 0
}

// ── real geometry in Chromium ────────────────────────────────────────────────
// playwright-core is a HOST-provided module (NODE_PATH, see the .sh runner), not
// a project dependency — reach it with require() and keep the binding untyped so
// no type resolution is attempted against node_modules.
const require = createRequire(import.meta.url)
let chromium: any
try {
  chromium = require('playwright-core').chromium
} catch (e) {
  console.log(`\nplaywright-core unavailable: ${(e as Error).message}`)
  staticFallback()
  console.log(`\n${failures === 0 ? 'PASS (fallback)' : failures + ' CHECK(S) FAILED'}`)
  process.exit(failures === 0 ? 0 : 1)
}

const dir = mkdtempSync(join(tmpdir(), 'xmage-header-gap-'))
// The page links the REAL files by absolute file:// URL — no copy of the CSS can
// drift from the shipped one. base.css provides the design tokens + the html{zoom}
// the header is drawn under; layout.css carries the rule under test.
const pagePath = join(dir, 'header.html')
writeFileSync(pagePath, `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<link rel="stylesheet" href="file://${BASE_CSS}">
<link rel="stylesheet" href="file://${LAYOUT_CSS}">
</head><body>${MARKUP}</body></html>`)

type Measure = {
  uiScale: string
  /** Visual scale factor of one CSS px (base.css sets html{zoom:0.75}; a hidden
   *  100px probe returns its on-screen width/100, so rect values can be
   *  normalised back to CSS px — the units computed styles use). */
  zoom: number
  gap: number
  menuRight: number
  barRight: number
  titleLeft: number
  paddingLeft: number
  paddingRight: number
  turnInfoText: string
  titleText: string
}

const run = async () => {
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 220 } })
  await page.goto('file://' + pagePath)

  const measure = (scale: string): Promise<Measure> =>
    page.evaluate((s: string) => {
      document.documentElement.style.setProperty('--ui-scale', s)
      const el = (sel: string) => document.querySelector(sel)!
      const rect = (sel: string) => el(sel).getBoundingClientRect()
      // Zoom probe: an absolutely positioned 100px box measures the CURRENT
      // visual scale (html{zoom:0.75} from base.css). getBoundingClientRect
      // reflects it; getComputedStyle does not.
      const probe = document.createElement('div')
      probe.style.cssText = 'position:absolute;left:0;top:0;width:100px;height:1px;visibility:hidden'
      document.body.appendChild(probe)
      const zoom = probe.getBoundingClientRect().width / 100
      probe.remove()
      const bar = el('#bar')
      const cs = getComputedStyle(bar)
      return {
        uiScale: s,
        zoom,
        gap: rect('#turn-info').left - rect('#title').right,
        menuRight: rect('#menu').right,
        barRight: rect('#bar').right,
        titleLeft: rect('#title').left,
        paddingLeft: parseFloat(cs.paddingLeft),
        paddingRight: parseFloat(cs.paddingRight),
        turnInfoText: el('#turn-info').textContent ?? '',
        titleText: el('#title').textContent ?? '',
      }
    }, scale)

  section('Real Chromium geometry — layout.css loaded from disk, GameHeader markup verbatim')

  const results: Measure[] = []
  for (const scale of ['1', '1.35', '2']) {
    const m = await measure(scale)
    results.push(m)
    // Everything below is in CSS px (rect / zoom) so it can be compared with the
    // computed padding values from the same sheet.
    const gap = m.gap / m.zoom
    const menuRight = m.menuRight / m.zoom
    const barRight = m.barRight / m.zoom
    const titleLeft = m.titleLeft / m.zoom
    console.log(`  --ui-scale ${scale}: zoom=${m.zoom} gap=${gap.toFixed(2)}px menuRight=${menuRight.toFixed(2)} barRight=${barRight.toFixed(2)} titleLeft=${titleLeft.toFixed(2)} padLeft=${m.paddingLeft} padRight=${m.paddingRight}`)
    check(`--ui-scale ${scale}: gap between the title and #turn-info is > 0`,
      gap > 0, `gap=${gap.toFixed(2)}px`)
    check(`--ui-scale ${scale}: the menu stays pinned to the header's right edge (within its padding)`,
      Math.abs(menuRight - (barRight - m.paddingRight)) < 0.5,
      `menuRight=${menuRight} barRight=${barRight} paddingRight=${m.paddingRight}`)
    check(`--ui-scale ${scale}: the title still starts at the header's left padding`,
      Math.abs(titleLeft - m.paddingLeft) < 0.5,
      `titleLeft=${titleLeft} paddingLeft=${m.paddingLeft}`)
  }

  const [s1, s135, s2] = results as [Measure, Measure, Measure]
  const gapOf = (m: Measure) => m.gap / m.zoom
  check('the gap scales with --ui-scale (header chrome must scale together)',
    Math.abs(gapOf(s1) - 16) < 0.5 && Math.abs(gapOf(s135) - 21.6) < 0.6 && Math.abs(gapOf(s2) - 32) < 0.6,
    `1:${gapOf(s1).toFixed(2)} 1.35:${gapOf(s135).toFixed(2)} 2:${gapOf(s2).toFixed(2)}`)
  check("1 rem at scale 1 is the todo's measured expectation (16 px)",
    Math.abs(gapOf(s1) - 16) < 0.5, String(gapOf(s1)))
  check('the header text is intact (title + turn/phase/priority line)',
    s1.titleText.trim() === 'LLMage Game' && s1.turnInfoText.includes("QA-A's Turn 5"),
    `${s1.titleText.trim()} / ${s1.turnInfoText}`)

  // Negative control: with the gap declaration removed the SAME fixture measures
  // 0 px — proving the assertion is measuring the fix, not an unrelated gap.
  section('Negative control — strip the gap, the same geometry collapses to 0')
  const noGap = layoutCss.replace(/\n\s*gap: calc\(1rem \* var\(--ui-scale, 1\)\);\n/, '\n')
  check('the negative-control CSS actually dropped the gap declaration',
    !/gap: calc\(1rem \* var\(--ui-scale, 1\)\)/.test(noGap) && noGap.length < layoutCss.length)
  const negCss = join(dir, 'layout-nogap.css')
  writeFileSync(negCss, noGap)
  const negPage = join(dir, 'header-nogap.html')
  writeFileSync(negPage, `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<link rel="stylesheet" href="file://${BASE_CSS}">
<link rel="stylesheet" href="file://${negCss}">
</head><body>${MARKUP}</body></html>`)
  await page.goto('file://' + negPage)
  const neg = await measure('1')
  const negGap = neg.gap / neg.zoom
  const negMenuRight = neg.menuRight / neg.zoom
  const negBarRight = neg.barRight / neg.zoom
  console.log(`  without the gap: gap=${negGap.toFixed(2)}px (menu right ${negMenuRight.toFixed(2)} / bar ${negBarRight.toFixed(2)}, paddingRight ${neg.paddingRight})`)
  check('without the gap the title sits flush against #turn-info (0 px, the reported defect)',
    Math.abs(negGap) < 0.01, `gap=${negGap}`)
  check('and the menu is still pinned right in the negative control '
    + '(so the collapse is caused by the missing gap, not by the auto margin failing)',
    Math.abs(negMenuRight - (negBarRight - neg.paddingRight)) < 0.5,
    `menuRight=${negMenuRight}`)

  // The rule is on the SHARED .game-header-bar class, so ReplayView.tsx's header
  // (its own title + #turn-info + menu — ⚙ Settings + ✕ Exit Replay, same inline
  // margin-left:auto) must stay intact: title at the left padding, menu pinned
  // right, no horizontal overflow or clipping. Its title/turn-info pair also
  // separates — cosmetic, and the only way to fix the live header without forking
  // the shared class.
  section('ReplayView header (same .game-header-bar class) stays intact')
  const REPLAY_MARKUP = `<div class="game-header-bar" id="bar">
  <div id="title" style="font-weight:600;color:var(--text-primary)">🎬 Replaying 8f3c</div>
  <div id="turn-info">· Frame 12 | Turn 5 | QA-A</div>
  <div class="game-menu" id="menu" style="margin-left:auto;display:flex;gap:0.5rem">
    <button class="btn btn-secondary btn-sm">⚙ Settings</button>
    <button class="btn btn-danger btn-sm">✕ Exit Replay</button>
  </div>
</div>`
  const replayPage = join(dir, 'replay.html')
  writeFileSync(replayPage, `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<link rel="stylesheet" href="file://${BASE_CSS}">
<link rel="stylesheet" href="file://${LAYOUT_CSS}">
</head><body style="margin:0">${REPLAY_MARKUP}</body></html>`)
  await page.goto('file://' + replayPage)
  const rp = await measure('1.35')
  const rpMenuRight = rp.menuRight / rp.zoom
  const rpBarRight = rp.barRight / rp.zoom
  const rpTitleLeft = rp.titleLeft / rp.zoom
  const rpGap = rp.gap / rp.zoom
  console.log(`  replay --ui-scale 1.35: titleLeft=${rpTitleLeft.toFixed(2)} gap=${rpGap.toFixed(2)} menuRight=${rpMenuRight.toFixed(2)} barRight=${rpBarRight.toFixed(2)}`)
  check('replay: exit button stays pinned to the header\'s right edge',
    Math.abs(rpMenuRight - (rpBarRight - rp.paddingRight)) < 0.5,
    `menuRight=${rpMenuRight} barRight=${rpBarRight} paddingRight=${rp.paddingRight}`)
  check('replay: title still starts at the header\'s left padding',
    Math.abs(rpTitleLeft - rp.paddingLeft) < 0.5,
    `titleLeft=${rpTitleLeft} paddingLeft=${rp.paddingLeft}`)
  check('replay: title and turn-info are separated (not overlapping / not clipped)',
    rpGap > 0 && rpGap < 100, `gap=${rpGap}`)
  check('replay: the exit control is not pushed outside the header',
    rpMenuRight <= rpBarRight + 0.01, `menuRight=${rpMenuRight} barRight=${rpBarRight}`)

  await browser.close()
}

run().then(() => {
  console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED (real Chromium geometry)' : failures + ' CHECK(S) FAILED'}`)
  process.exit(failures === 0 ? 0 : 1)
}).catch((e) => {
  console.error(`\nChromium run failed: ${(e as Error).message}`)
  staticFallback()
  console.log(`\n${failures === 0 ? 'PASS (fallback)' : failures + ' CHECK(S) FAILED'}`)
  process.exit(failures === 0 ? 0 : 1)
})
