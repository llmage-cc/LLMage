const { chromium } = require('playwright-core')
const fs = require('fs')
const OUT = process.env.OUT || '/tmp/llmviewer-stage/evidence/probe'
fs.mkdirSync(OUT, { recursive: true })
;(async () => {
  const b = await chromium.launch({
    executablePath: process.env.CHROME_PATH,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const ctx = await b.newContext({ viewport: { width: 1400, height: 900 } })
  const page = await ctx.newPage()
  const frames = []
  const cons = []
  page.on('console', (m) => cons.push(`${m.type()}: ${m.text()}`))
  page.on('pageerror', (e) => cons.push('PAGEERROR: ' + e))
  await page.exposeFunction('__rec', (d) => frames.push(d))
  await ctx.addInitScript(() => {
    const N = window.WebSocket
    const W = function (u, p) {
      const ws = p === undefined ? new N(u) : new N(u, p)
      ws.addEventListener('message', (e) => window.__rec({ dir: 'rx', d: String(e.data).slice(0, 4000) }))
      const s = ws.send.bind(ws)
      ws.send = (x) => { window.__rec({ dir: 'tx', d: String(x).slice(0, 4000) }); return s(x) }
      return ws
    }
    W.CONNECTING = 0; W.OPEN = 1; W.CLOSING = 2; W.CLOSED = 3; W.prototype = N.prototype
    window.WebSocket = W
  })
  await page.goto('http://127.0.0.1:8181/', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-explain="connect-submit"]', { timeout: 30000 })
  await page.screenshot({ path: OUT + '/01-overlay.png' })
  const labels = await page.evaluate(() => Array.from(document.querySelectorAll('label')).map((l) => l.textContent.trim().slice(0, 40)))
  console.log('LABELS:', JSON.stringify(labels))
  const inputs = await page.evaluate(() => Array.from(document.querySelectorAll('input')).map((i) => ({ type: i.type, ph: i.placeholder, id: i.id, name: i.name, val: i.value })))
  console.log('INPUTS:', JSON.stringify(inputs))
  await page.getByLabel('Port:').fill('17281')
  await page.getByLabel('Username:').fill('probe' + (Date.now() % 10000))
  await page.click('button[data-explain="connect-submit"]')
  await page.waitForTimeout(12000)
  await page.screenshot({ path: OUT + '/02-after-connect.png' })
  const dom = await page.evaluate(() => ({
    bodyText: document.body.innerText.slice(0, 1200),
    hasGamesList: !!document.querySelector('.games-list'),
    hasNewGame: !!document.querySelector('[data-explain="lobby-new-game"]'),
    hasOverlay: !!document.querySelector('[data-explain="connect-submit"]'),
    overlayText: (document.querySelector('[data-explain="connect-submit"]')?.closest('div')?.innerText || '').slice(0, 400),
  }))
  fs.writeFileSync(OUT + '/probe.json', JSON.stringify({ dom, cons, frames }, null, 2))
  console.log('DOM:', JSON.stringify(dom, null, 2))
  console.log('CONSOLE:', JSON.stringify(cons.slice(-15), null, 2))
  console.log('FRAMES:', JSON.stringify(frames.slice(0, 12), null, 2))
  await b.close()
})().catch((e) => { console.error('ERR', e); process.exit(3) })
