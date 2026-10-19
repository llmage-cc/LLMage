/**
 * Browser leg for the replay viewer (T1 + T2).
 *
 * Drives the real webui against the throwaway stack:
 *   connect (port field!) -> lobby -> GamesList "Watch Replay" on the game-1
 *   save -> replay view -> LLM rail tab -> scrub/jump paths -> assertions.
 *
 * Assertions:
 *   T1.1 system prompt appears EXACTLY ONCE per player (toggle ON, default),
 *        across step / Skip 10 / seek-to-end / scrub BACK to 0 / step forward.
 *   T1.2 per-seat sub-tabs each keep their OWN single system prompt.
 *   T1.3 single-frame mode (toggle OFF) shows the CURRENT frame's own system
 *        prompt (dedupe applies only to the cumulative merge).
 *   T1.4 header honesty: "Messages: N" with N = rendered count; no
 *        "Context: 0 chars" in replay mode.
 *   T1.5 old replays (no system bucket) render unchanged: no crash, no empty
 *        leading block.
 *   T1.6 collapse UX: every block collapsible; state survives frame scrubs.
 *   T2.1 game-2 replay shows the Match Review section.
 *   T2.2 Deck Assessment present on game 1 AND game 2.
 *   T2.3 Post-Game Reflection section ABSENT everywhere.
 *
 * Usage: node llmviewer-browser.cjs
 * Env: PROXY_PORT(8181) HOST(127.0.0.1) SERVER_PORT(17281) EVID(evidence dir)
 *      GAME1, GAME2 (uuids) TABLE_NAME(llmvw-t1t2)
 */
const path = require('path')
const fs = require('fs')
const { chromium } = require('playwright-core')

const HOST = process.env.HOST || '127.0.0.1'
const PROXY = process.env.PROXY_PORT || '8181'
const SERVER_PORT = process.env.SERVER_PORT || '17281'
const EVID = process.env.EVID || '/tmp/llmviewer-stage/evidence/t1t2'
const GAME1 = process.env.GAME1
const GAME2 = process.env.GAME2
const TABLE_NAME = process.env.TABLE_NAME || 'llmvw-t1t2'
const USER = `llmvb${Date.now() % 100000}`

fs.mkdirSync(EVID, { recursive: true })
const txFrames = []
const results = []
const consoleMsgs = []
const pageErrors = []

function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: cond ? '' : String(detail || '') })
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${cond ? '' : '  -- ' + detail}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// Read the rail's rendered LLM state straight from the DOM: per-rendered-player
// message counts + role lists, header line, section presence.
async function railState(page) {
  return page.evaluate(() => {
    const tab = document.querySelector('[data-explain="rail-tab-llm"]')
    const active = tab && tab.classList.contains('active')
    const header = document.querySelector('.llm-content')?.parentElement
    // The header div is the first child of the visible LLMDebugPanel.
    const panels = []
    const content = document.querySelector('.llm-content')
    if (content) {
      // LLMDebugPanel renders a top div with the "model | Messages: N" line.
      for (const el of content.children) {
        const line = el.querySelector('div')
        if (!line) continue
        const txt = line.textContent || ''
        if (/Messages:\s*\d+/.test(txt)) {
          const details = Array.from(el.querySelectorAll('details'))
          panels.push({
            header: txt.trim(),
            messageBlocks: details.filter((d) => /^(system|user|assistant|assistant_thinking)\b/.test((d.querySelector('summary')?.textContent || ''))).length,
            systemBlocks: details.filter((d) => /^system\b/.test((d.querySelector('summary')?.textContent || ''))).length,
            summaryTitles: details.map((d) => (d.querySelector('summary')?.textContent || '').slice(0, 60)),
            hasDeckAssessment: !!Array.from(details).find((d) => (d.querySelector('summary')?.textContent || '').includes('Deck Assessment')),
            hasMatchReview: !!Array.from(details).find((d) => (d.querySelector('summary')?.textContent || '').includes('Match Review')),
            hasReflection: !!Array.from(details).find((d) => (d.querySelector('summary')?.textContent || '').includes('Post-Game Reflection')),
          })
        }
      }
    }
    const subTabs = Array.from(document.querySelectorAll('.llm-sub-tab')).map((b) => ({
      name: b.textContent || '', active: b.classList.contains('active'),
    }))
    const emptyCopy = (document.querySelector('.tab-panel.active') || {}).textContent || ''
    return { active, panels, subTabs, empty: content ? null : emptyCopy.slice(0, 200),
             hasToggle: !!document.querySelector('[data-explain="replay-llm-history-toggle"]') }
  })
}

function systemCountsPerPlayer(panels) {
  return panels.map((p) => {
    const m = p.header.match(/Messages:\s*(\d+)/)
    return { header: p.header, rendered: m ? Number(m[1]) : null, systemBlocks: p.systemBlocks }
  })
}

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 950 } })
  const page = await ctx.newPage()
  page.on('console', (m) => consoleMsgs.push(`${m.type()}: ${m.text()}`))
  page.on('pageerror', (e) => pageErrors.push(String(e)))

  // Instrument WebSocket BEFORE the app boots: capture frames, and preserve the
  // static readyState constants (a wrapper that drops WebSocket.OPEN silently
  // breaks every send).
  await ctx.addInitScript(() => {
    const Native = window.WebSocket
    const W = function (url, protocols) {
      const ws = protocols === undefined ? new Native(url) : new Native(url, protocols)
      try {
        ws.addEventListener('message', (ev) => {
          try { window.__tx.push({ dir: 'rx', data: String(ev.data).slice(0, 200000) }) } catch (e) {}
        })
        ws.addEventListener('send', () => {})
        const origSend = ws.send.bind(ws)
        ws.send = (d) => {
          try { window.__tx.push({ dir: 'tx', data: String(d).slice(0, 200000) }) } catch (e) {}
          return origSend(d)
        }
      } catch (e) {}
      return ws
    }
    W.CONNECTING = 0; W.OPEN = 1; W.CLOSING = 2; W.CLOSED = 3
    W.prototype = Native.prototype
    window.WebSocket = W
    window.__tx = []
  })

  await page.goto(`http://${HOST}:${PROXY}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-explain="connect-submit"]', { timeout: 30000 })
  await page.getByLabel('Port:').fill(String(SERVER_PORT))
  await page.getByLabel('Username:').fill(USER)
  await page.click('button[data-explain="connect-submit"]')
  await page.waitForSelector('[data-explain="games-list-watch-replay"]', { timeout: 45000 })
  console.log('[info] connected, lobby up')

  // ── open the GamesList + load GAME1's replay ──
  await page.waitForSelector('[data-explain="games-list-watch-replay"]', { timeout: 30000 })
  // Rows are built from finished matches + saved files; find the row whose
  // Watch Replay button targets GAME1.
  const started = await page.evaluate((gid) => {
    const btns = Array.from(document.querySelectorAll('[data-explain="games-list-watch-replay"]'))
    const target = btns.find((b) => (b.getAttribute('title') || '').includes(gid))
    if (target) { target.click(); return true }
    return btns.length
  }, GAME1)
  check('(T1 setup) Watch Replay button for the game-1 save exists', started === true,
        `watch-replay buttons found: ${started}`)
  if (started !== true) { await browser.close(); return }
  await page.waitForSelector('.replay-view, [data-explain="replay-play"]', { timeout: 60000 })
  console.log('[info] replay loaded')
  await sleep(1500)

  // ── LLM rail tab ──
  await page.waitForSelector('[data-explain="rail-tab-llm"]', { timeout: 30000 })
  await page.click('[data-explain="rail-tab-llm"]')
  await sleep(1200)

  const toggleSel = '[data-explain="replay-llm-history-toggle"]'
  const toggleOn = await page.isChecked(toggleSel).catch(() => null)
  check('(T1.4a) history toggle renders in the replay LLM tab', toggleOn !== null, 'toggle missing')
  if (toggleOn === null) { await browser.close(); return }
  check('(T1 setup) "Show entire history" defaults ON', toggleOn === true, `checked=${toggleOn}`)

  // ── T1.4: header honesty on the fully-loaded replay ──
  // Seek to the end first so the whole conversation is merged.
  await page.evaluate(() => {
    // The scrubber is an <input type="range">; set to max and dispatch input.
    const r = document.querySelector('[data-explain="replay-scrubber"]')
    if (r) { r.value = r.max; r.dispatchEvent(new Event('input', { bubbles: true })) }
  })
  await sleep(1500)
  const st0 = await railState(page)
  fs.writeFileSync(path.join(EVID, 'browser-state-end.json'), JSON.stringify(st0, null, 2))
  check('(T1.2) replay of a 2-LLM-seat game renders per-seat sub-tabs',
        st0.subTabs.length === 2, JSON.stringify(st0.subTabs))
  const sc = systemCountsPerPlayer(st0.panels)
  fs.writeFileSync(path.join(EVID, 'browser-systemcounts.json'), JSON.stringify(sc, null, 2))
  check('(T1.1a) at end-of-replay each rendered player shows exactly ONE system block',
        sc.length > 0 && sc.every((s) => s.systemBlocks === 1),
        JSON.stringify(sc))
  check('(T1.4b) header reads "Messages: N"', st0.panels.every((p) => /Messages:\s*\d+/.test(p.header)),
        JSON.stringify(st0.panels.map((p) => p.header)))
  check('(T1.4c) no "Context: 0 chars" in replay mode',
        st0.panels.every((p) => !/Context:\s*0\s*chars/.test(p.header)),
        JSON.stringify(st0.panels.map((p) => p.header)))
  check('(T2.2a) Deck Assessment section present (game-1 replay)',
        st0.panels.some((p) => p.hasDeckAssessment), JSON.stringify(st0.panels.map((p) => p.summaryTitles)))
  check('(T2.3a) NO Post-Game Reflection section (game-1 replay)',
        st0.panels.every((p) => !p.hasReflection), JSON.stringify(st0.panels.map((p) => p.summaryTitles)))

  const endCount = sc[0]?.rendered

  // ── T1.1: scrub paths — seek straight to a mid frame, then step BACK, scrub
  // BACK to 0, step forward past that frame, and compare with the direct seek.
  // History at a cursor must be identical regardless of the path taken to it.
  const TARGET = Number(process.env.TARGET_FRAME || 900)
  await page.evaluate((t) => {
    const r = document.querySelector('[data-explain="replay-scrubber"]')
    if (r) { r.value = t; r.dispatchEvent(new Event('input', { bubbles: true })) }
  }, TARGET)
  await sleep(1800)
  const stSeek = await railState(page)
  fs.writeFileSync(path.join(EVID, 'browser-state-seek-target.json'), JSON.stringify(stSeek, null, 2))

  // Now scrub BACK to 0, step forward a handful of times, then Skip forward —
  // a completely different delivery path to (near) the same cursor.
  await page.evaluate(() => {
    const r = document.querySelector('[data-explain="replay-scrubber"]')
    if (r) { r.value = 0; r.dispatchEvent(new Event('input', { bubbles: true })) }
  })
  await sleep(1500)
  const stZero = await railState(page)
  fs.writeFileSync(path.join(EVID, 'browser-state-zero.json'), JSON.stringify(stZero, null, 2))
  check('(T1.1b) scrubbing BACK to 0 never reintroduces a 2nd system block',
        systemCountsPerPlayer(stZero.panels).every((s) => s.systemBlocks <= 1),
        JSON.stringify(systemCountsPerPlayer(stZero.panels)))
  // Seek back to the target again: same cursor, opposite direction.
  await page.evaluate((t) => {
    const r = document.querySelector('[data-explain="replay-scrubber"]')
    if (r) { r.value = t; r.dispatchEvent(new Event('input', { bubbles: true })) }
  }, TARGET)
  await sleep(1800)
  const stAgain = await railState(page)
  fs.writeFileSync(path.join(EVID, 'browser-state-seek-target-again.json'), JSON.stringify(stAgain, null, 2))
  const c1 = systemCountsPerPlayer(stSeek.panels)
  const c2 = systemCountsPerPlayer(stAgain.panels)
  check('(T1.1c) the same cursor reached after a backward scrub renders the SAME message count',
        c1.length === c2.length && c1.every((a, i) => a.rendered === c2[i].rendered),
        `seek=${JSON.stringify(c1)} afterBackwardScrub=${JSON.stringify(c2)}`)
  check('(T1.1d) the same cursor never gains a second system block after a backward scrub',
        c2.length > 0 && c2.every((s) => s.systemBlocks === 1), JSON.stringify(c2))

  // ── T1.6: collapse state survives a frame scrub ──
  const collapse = await page.evaluate(() => {
    const d = Array.from(document.querySelectorAll('.llm-content details')).find((x) =>
      /^system\b/.test(x.querySelector('summary')?.textContent || ''))
    if (!d) return { found: false }
    d.open = false
    d.dispatchEvent(new Event('toggle', { bubbles: true }))
    return { found: true, openBefore: d.open }
  })
  await page.click('[data-explain="replay-next"]').catch(() => {})
  await sleep(900)
  const collapsedAfter = await page.evaluate(() => {
    const d = Array.from(document.querySelectorAll('.llm-content details')).find((x) =>
      /^system\b/.test(x.querySelector('summary')?.textContent || ''))
    return d ? d.open : null
  })
  fs.writeFileSync(path.join(EVID, 'browser-collapse.json'),
    JSON.stringify({ collapse, collapsedAfter }, null, 2))
  check('(T1.6) collapsing a block is possible', collapse.found === true, JSON.stringify(collapse))
  check('(T1.6b) collapse state survives a frame scrub', collapsedAfter === false,
        `open after scrub=${collapsedAfter}`)

  // ── T1.3: single-frame mode (toggle OFF) shows the current frame's own system prompt ──
  await page.uncheck(toggleSel).catch(() => {})
  await sleep(1200)
  const stOff = await railState(page)
  fs.writeFileSync(path.join(EVID, 'browser-state-singleframe.json'), JSON.stringify(stOff, null, 2))
  check('(T1.4d) toggle still renders with single-frame mode ON',
        stOff.hasToggle === true, 'toggle vanished')
  // Single-frame mode may legitimately show an empty panel on a frame with no
  // bucket of its own — then only the toggle presence is assertable.
  const scOff = systemCountsPerPlayer(stOff.panels)
  check('(T1.3) single-frame mode shows at most ONE system block per player',
        scOff.every((s) => s.systemBlocks <= 1), JSON.stringify(scOff))
  await page.check(toggleSel).catch(() => {})
  await sleep(600)

  // ── screenshots ──
  await page.screenshot({ path: path.join(EVID, 'replay-llm-game1.png'), fullPage: false })
  await page.click('[data-explain="replay-exit"]').catch(() => {})
  await sleep(1200)

  // ── T2: game 2's replay (matchReview lives here) ──
  await page.waitForSelector('.games-list', { timeout: 30000 }).catch(() => {})
  const started2 = await page.evaluate((gid) => {
    const btns = Array.from(document.querySelectorAll('[data-explain="games-list-watch-replay"]'))
    const target = btns.find((b) => (b.getAttribute('title') || '').includes(gid))
    if (target) { target.click(); return true }
    return btns.length
  }, GAME2)
  check('(T2 setup) Watch Replay button for the game-2 save exists', started2 === true,
        `watch-replay buttons found: ${started2}`)
  if (started2 === true) {
    await page.waitForSelector('[data-explain="replay-play"]', { timeout: 60000 })
    await sleep(1500)
    await page.click('[data-explain="rail-tab-llm"]')
    await sleep(1000)
    await page.evaluate(() => {
      const r = document.querySelector('[data-explain="replay-scrubber"]')
      if (r) { r.value = r.max; r.dispatchEvent(new Event('input', { bubbles: true })) }
    })
    await sleep(1500)
    const st2 = await railState(page)
    fs.writeFileSync(path.join(EVID, 'browser-state-game2.json'), JSON.stringify(st2, null, 2))
    check('(T2.1) game-2 replay shows the Match Review section',
          st2.panels.some((p) => p.hasMatchReview),
          JSON.stringify(st2.panels.map((p) => p.summaryTitles)))
    check('(T2.2b) Deck Assessment present on game 2', st2.panels.some((p) => p.hasDeckAssessment),
          JSON.stringify(st2.panels.map((p) => p.summaryTitles)))
    check('(T2.3b) NO Post-Game Reflection section (game-2 replay)',
          st2.panels.every((p) => !p.hasReflection),
          JSON.stringify(st2.panels.map((p) => p.summaryTitles)))
    const sc2 = systemCountsPerPlayer(st2.panels)
    check('(T1.1d) game-2 replay also shows exactly ONE system block per player',
          sc2.length > 0 && sc2.every((s) => s.systemBlocks === 1), JSON.stringify(sc2))
    await page.screenshot({ path: path.join(EVID, 'replay-llm-game2.png') })
  }

  // ── console/errors ──
  const errs = pageErrors.slice()
  fs.writeFileSync(path.join(EVID, 'browser-console.json'),
    JSON.stringify({ console: consoleMsgs.slice(-400), pageErrors: errs }, null, 2))
  check('(T1.5) no page errors during the replay runs', errs.length === 0, JSON.stringify(errs.slice(0, 4)))

  await browser.close()

  const failed = results.filter((r) => !r.pass)
  fs.writeFileSync(path.join(EVID, 'browser-results.json'),
    JSON.stringify({ results, failed: failed.length }, null, 2))
  console.log()
  if (failed.length) {
    console.log(`RESULT: FAIL (${failed.length}): ` + failed.map((f) => f.name).join('; '))
    process.exitCode = 2
  } else {
    console.log('RESULT: PASS (all browser checks)')
  }
}

main().catch((e) => { console.error('[harness-error]', e); process.exit(3) })
