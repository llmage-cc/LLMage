// Browser-API stubs so the real store modules can run under Node.
// Imported BEFORE the harness (bundled in), so these exist at module init.
const noop = () => {}
if (!globalThis.document) {
  globalThis.document = { createElement: () => ({ innerHTML: '', value: '', style: {} }), addEventListener: noop, removeEventListener: noop }
}
// cardFit.applyCardSizing/applyPaneCardSizing write CSS custom properties on
// document.documentElement.style — the split-scaling verifier swaps recording
// fakes in for these, so the stub must provide the shape (real style objects
// are plain records under Node).
if (!globalThis.document.documentElement) {
  globalThis.document.documentElement = { style: {} }
}
// The priority-alert focus gating reads document.hasFocus() + visibilityState at
// prompt time (store/slices/priorityDing.ts) — a browser always has both, so the
// stubs must too (hasFocus=true, visibilityState=visible = the focused case).
if (!globalThis.document) globalThis.document = {}
if (typeof globalThis.document.hasFocus !== 'function') globalThis.document.hasFocus = () => true
if (!globalThis.document.visibilityState) globalThis.document.visibilityState = 'visible'
if (typeof globalThis.CustomEvent !== 'function') {
  globalThis.CustomEvent = class CustomEvent extends Event {
    #detail
    constructor(type, opts = {}) { super(type, opts); this.#detail = opts.detail ?? null }
    get detail() { return this.#detail }
  }
}
// window === globalThis in the stubs; Node's globalThis has no EventTarget methods —
// the priority-alert fan-out dispatches PRIORITY_FLASH_EVENT on window (priorityDing.ts).
if (typeof globalThis.dispatchEvent !== 'function') {
  const listeners = new Map()
  globalThis.addEventListener = (type, fn) => {
    if (!listeners.has(type)) listeners.set(type, new Set())
    listeners.get(type).add(fn)
  }
  globalThis.removeEventListener = (type, fn) => { listeners.get(type)?.delete(fn) }
  globalThis.dispatchEvent = (ev) => {
    for (const fn of listeners.get(ev.type) ?? []) fn(ev)
    return true
  }
}
if (!globalThis.localStorage) {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  }
}
if (!globalThis.crypto) globalThis.crypto = { randomUUID: () => 'uuid' }
if (!globalThis.window) globalThis.window = globalThis
if (!globalThis.WebSocket) globalThis.WebSocket = class {}

// vite.config.ts `define` bakes __LLMAGE__ at build time, so the bare identifier
// only exists in a Vite bundle. Under Node bundling the harnesses import module
// chains that read it at evaluation time (llmBridgeClient.ts `STOCK_DISABLED`,
// reached via lobbySlice/messageHandlers since the 2026-09-25 provider-dropdown
// merge) and every one of them threw `__LLMAGE__ is not defined`. Harnesses
// exercise the LLMage build, so default to true — same as `npm run build`.
if (typeof globalThis.__LLMAGE__ === 'undefined') globalThis.__LLMAGE__ = true

// ── <textarea> entity decoder ──
// utils/textFormat.decodeEntities() decodes named entities through a real DOM
// <textarea> (its value is parsed as text, never markup). The 3-line stub above
// returns '' for ANY string containing '&', which silently erased text in a
// harness. Implement the small named table the server actually emits plus the
// numeric forms, so stripServerHtml is exercised faithfully.
const NAMED_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0',
  mdash: '\u2014', ndash: '\u2013', hellip: '\u2026', middot: '\u00b7',
  eacute: '\u00e9', uuml: '\u00fc', ouml: '\u00f6', auml: '\u00e4',
  Aring: '\u00c5', Eacute: '\u00c9', Uuml: '\u00dc', copy: '\u00a9',
  times: '\u00d7', deg: '\u00b0', laquo: '\u00ab', raquo: '\u00bb',
}
const decodeEntitiesIn = (s) => String(s)
  .replace(/&#(x?[0-9a-fA-F]+);/g, (entity, num) => {
    const cp = num[0]?.toLowerCase() === 'x' ? parseInt(num.slice(1), 16) : parseInt(num, 10)
    return Number.isNaN(cp) ? entity : String.fromCodePoint(cp)
  })
  .replace(/&([a-zA-Z][a-zA-Z0-9]*);/g, (entity, name) =>
    Object.prototype.hasOwnProperty.call(NAMED_ENTITIES, name) ? NAMED_ENTITIES[name] : entity)

if (typeof globalThis.document.createElement !== 'function') { throw new Error('document stub missing') }
globalThis.document.createElement = (tag) => {
  if (tag !== 'textarea') return { innerHTML: '', value: '', style: {} }
  let raw = ''
  const el = { style: {} }
  Object.defineProperty(el, 'innerHTML', {
    get: () => raw,
    set: (v) => { raw = String(v) },
  })
  // The real textarea's value is the parsed text: markup-looking input is NOT parsed
  // (that is what makes this decoder safe), so only entities are resolved.
  Object.defineProperty(el, 'value', { get: () => decodeEntitiesIn(raw) })
  return el
}
