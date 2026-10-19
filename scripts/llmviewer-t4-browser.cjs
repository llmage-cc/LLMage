/**
 * Browser leg for the format-correction replay — the REPLAY tab's rendered user message for a
 * format-corrected exchange.
 *
 * The Java probe (llmviewer-t4-driver.py) asserts the replay DATA. This leg
 * asserts what the webui actually RENDERS from that data: open the T4 game's
 * replay, go to the LLM rail tab, and read the message body of a block whose
 * summary carries the `-corrected` promptType suffix.
 *
 * Pass criteria:
 *   - the corrected block's user message body starts with `Retry of the `
 *   - the literal string `format correction` never appears as a message body
 *   - the `-corrected` suffix IS still present in the block summary
 *
 * Usage: node llmviewer-t4-browser.cjs
 * Env: PROXY_PORT(8191) HOST(127.0.0.1) SERVER_PORT(17291) EVID GAME (uuid)
 */
const path = require('path')
const fs = require('fs')
const { chromium } = require('playwright-core')

const HOST = process.env.HOST || '127.0.0.1'
const PROXY = process.env.PROXY_PORT || '8191'
const SERVER_PORT = process.env.SERVER_PORT || '17291'
const EVID = process.env.EVID || '/tmp/llmviewer-t4/evidence/t4browser'
const GAME = process.env.GAME
const USER = `llmt4b${Date.now() % 100000}`

fs.mkdirSync(EVID, { recursive: true })
const results = []
const consoleMsgs = []
const pageErrors = []

function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: cond ? '' : String(detail || '') })
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${cond ? '' : '  -- ' + detail}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  if (!GAME) { console.error('[harness-error] GAME uuid not set'); process.exit(3) }
  const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 950 } })
  const page = await ctx.newPage()
  page.on('console', (m) => consoleMsgs.push(`${m.type()}: ${m.text()}`))
  page.on('pageerror', (e) => pageErrors.push(String(e)))

  await ctx.addInitScript(() => {
    const Native = window.WebSocket
    const W = function (url, protocols) {
      const ws = protocols === undefined ? new Native(url) : new Native(url, protocols)
      try {
        ws.addEventListener('message', (ev) => {
          try { window.__tx.push({ dir: 'rx', data: String(ev.data).slice(0, 200000) }) } catch (e) {}
        })
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

  const started = await page.evaluate((gid) => {
    const btns = Array.from(document.querySelectorAll('[data-explain="games-list-watch-replay"]'))
    const target = btns.find((b) => (b.getAttribute('title') || '').includes(gid))
    if (target) { target.click(); return true }
    return btns.length
  }, GAME)
  check('(T4b setup) Watch Replay button for the T4 save exists', started === true,
        `watch-replay buttons found: ${started}`)
  if (started !== true) { await browser.close(); return }

  await page.waitForSelector('.replay-view, [data-explain="replay-play"]', { timeout: 60000 })
  await sleep(1500)
  await page.waitForSelector('[data-explain="rail-tab-llm"]', { timeout: 30000 })
  await page.click('[data-explain="rail-tab-llm"]')
  await sleep(1200)

  // Seek to the end with "Show entire history" ON so every bucket is merged.
  const toggleSel = '[data-explain="replay-llm-history-toggle"]'
  const toggleOn = await page.isChecked(toggleSel).catch(() => null)
  if (toggleOn === false) await page.check(toggleSel).catch(() => {})
  await page.evaluate(() => {
    const r = document.querySelector('[data-explain="replay-scrubber"]')
    if (r) { r.value = r.max; r.dispatchEvent(new Event('input', { bubbles: true })) }
  })
  await sleep(1800)

  // Collect every rendered block: summary + full body text, per panel.
  const state = await page.evaluate(() => {
    const panels = []
    const content = document.querySelector('.llm-content')
    if (content) {
      for (const el of content.children) {
        const line = el.querySelector('div')
        if (!line) continue
        const txt = line.textContent || ''
        if (!/Messages:\s*\d+/.test(txt)) continue
        const blocks = Array.from(el.querySelectorAll('details')).map((d) => ({
          summary: (d.querySelector('summary')?.textContent || '').slice(0, 120),
          body: (d.textContent || '').slice(0, 4000),
        }))
        panels.push({ header: txt.trim(), blocks })
      }
    }
    return { panels }
  })
  fs.writeFileSync(path.join(EVID, 'replay-blocks.json'), JSON.stringify(state, null, 2))

  const allBlocks = state.panels.flatMap((p) => p.blocks)
  const corrected = allBlocks.filter((b) => /-corrected\b/.test(b.summary))
  check('(T4b.1) the replay tab renders at least one `-corrected` exchange block',
        corrected.length >= 1, `blocks=${allBlocks.length} corrected=${corrected.length}`)

  const bodiesWithLiteral = allBlocks.filter((b) => /\bformat correction\b/.test(b.body))
  check('(T4b.2) no rendered block body contains the literal "format correction"',
        bodiesWithLiteral.length === 0,
        bodiesWithLiteral.slice(0, 2).map((b) => b.summary).join('; '))

  const descriptive = corrected.filter((b) => /Retry of the /.test(b.body))
  check('(T4b.3) every rendered `-corrected` block body reads "Retry of the <type> decision"',
        corrected.length >= 1 && descriptive.length === corrected.length,
        `${descriptive.length}/${corrected.length} descriptive; sample=${corrected.slice(0, 1).map((b) => b.body.slice(0, 200)).join('')}`)

  check('(T4b.4) no page errors during the T4 replay run',
        pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)))

  await page.screenshot({ path: path.join(EVID, 'replay-llm-t4.png') })
  fs.writeFileSync(path.join(EVID, 'browser-console.json'),
    JSON.stringify({ console: consoleMsgs.slice(-300), pageErrors }, null, 2))

  await browser.close()
  const failed = results.filter((r) => !r.pass)
  fs.writeFileSync(path.join(EVID, 'browser-results.json'),
    JSON.stringify({ results, failed: failed.length, correctedBlocks: corrected.length }, null, 2))
  console.log()
  if (failed.length) {
    console.log(`RESULT: FAIL (${failed.length}): ` + failed.map((f) => f.name).join('; '))
    process.exitCode = 2
  } else {
    console.log('RESULT: PASS (all T4 browser checks)')
  }
}

main().catch((e) => { console.error('[harness-error]', e); process.exit(3) })
