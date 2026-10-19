const { chromium } = require('playwright-core')
const fs = require('fs')
const OUT = process.env.OUT || '/tmp/llmviewer-stage/evidence/domprobe'
fs.mkdirSync(OUT, { recursive: true })
;(async () => {
  const b = await chromium.launch({
    executablePath: process.env.CHROME_PATH,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const ctx = await b.newContext({ viewport: { width: 1500, height: 950 } })
  const page = await ctx.newPage()
  const cons = []
  page.on('console', (m) => cons.push(`${m.type()}: ${m.text().slice(0, 300)}`))
  page.on('pageerror', (e) => cons.push('PAGEERROR: ' + String(e).slice(0, 300)))
  await page.goto('http://127.0.0.1:8181/', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-explain="connect-submit"]', { timeout: 30000 })
  await page.getByLabel('Port:').fill('17281')
  await page.getByLabel('Username:').fill('domprobe' + (Date.now() % 10000))
  await page.click('button[data-explain="connect-submit"]')
  for (const t of [3000, 8000, 15000]) {
    await page.waitForTimeout(t === 3000 ? 3000 : t - 3000)
    const s = await page.evaluate(() => ({
      explains: Array.from(document.querySelectorAll('[data-explain]')).map((e) => e.getAttribute('data-explain')).slice(0, 60),
      watchBtns: document.querySelectorAll('[data-explain="games-list-watch-replay"]').length,
      text: document.body.innerText.slice(0, 700),
    }))
    fs.writeFileSync(`${OUT}/snap-${t}.json`, JSON.stringify(s, null, 2))
    console.log(`--- t=${t}ms watchBtns=${s.watchBtns}`)
    console.log('explains:', JSON.stringify(s.explains))
  }
  await page.screenshot({ path: OUT + '/lobby.png', fullPage: true })
  fs.writeFileSync(OUT + '/console.json', JSON.stringify(cons, null, 2))
  console.log('CONSOLE tail:', JSON.stringify(cons.slice(-10), null, 2))
  await b.close()
})().catch((e) => { console.error('ERR', e); process.exit(3) })
