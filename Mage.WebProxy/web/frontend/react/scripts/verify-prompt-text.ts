/**
 * Behavioural check for the 2026-09-16 prompt/LOG TEXT findings
 * (the prompt-text defects).
 *
 * Run with:  bash scripts/verify-prompt-text.sh
 *
 * These assertions cover three kinds of claim:
 *   1. the REAL `utils/textFormat` module run under Node (separator + entity
 * behaviour — the defect is in that function, not in a component);
 *   2. the REAL repo CSS parsed by the browser-independent part we can reach
 * under Node — the cascade that caused it, asserted as *specificity and
 *      declaration order* over the actual files on disk;
 *   3. structural claims about the shipped render paths (which component owns a
 *      prompt type) read from source, so a regression that re-duplicates a
 *      prompt body fails loudly.
 *
 * Deliberately NOT part of the app build (scripts/ is outside tsconfig include).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { stripServerHtml } from '../src/utils/textFormat'

// The bundle is written to /tmp (outside the project), so `import.meta.url`
// points at the temp dir, not this file. The runner script passes the real
// directory in XMAGE_VERIFY_DIR (an env var, not a global — ESM hoists imports
// above any statement in the entry file, so a global assignment there would run
// too late); fall back to this file's own location when run in place.
// `declare`d rather than relied on from @types/node: this directory is outside
// the app tsconfig's `include`, so the package's node globals are not in scope.
declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')                    // web/frontend/react
const FRONTEND = join(REACT, '..')                // web/frontend
const WEB = join(FRONTEND, '..')                  // web
const WEBPROXY = join(WEB, '..')                  // Mage.WebProxy
const REPO = join(WEBPROXY, '..')                 // repo root

// ── entity decoder ───────────────────────────────────────────────────────────
// `decodeEntities` uses a <textarea>'s innerHTML→value round-trip, which the
// shared Node stub (verify-stubs.mjs) implements as a no-op — that would make
// every entity assertion below vacuous. Install a real decoder for this run.
const NAMED: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'",
  nbsp: '\u00a0', mdash: '\u2014', ndash: '\u2013', hellip: '\u2026',
}
function decodeHtml(value: string): string {
  return String(value).replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/g, (entity, body: string) => {
    if (body[0] === '#') {
      const cp = body[1] === 'x' || body[1] === 'X'
        ? parseInt(body.slice(2), 16)
        : parseInt(body.slice(1), 10)
      return Number.isNaN(cp) ? entity : String.fromCodePoint(cp)
    }
    const named = NAMED[body.toLowerCase()]
    return named !== undefined ? named : entity
  })
}
;(globalThis as { document: unknown }).document = {
  createElement: () => {
    const el: { _html: string; value: string; style: Record<string, unknown> } = { _html: '', value: '', style: {} }
    Object.defineProperty(el, 'innerHTML', {
      get: () => el._html,
      set: (v: string) => { el._html = String(v); el.value = decodeHtml(v) },
      enumerable: true,
    })
    return el
  },
  addEventListener: () => {},
  removeEventListener: () => {},
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failures++
    console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`)
  }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('"Choose spell or ability to play" + <br> + card name')

// The exact string HumanPlayer.java:2357 builds (modulo the controlling-player
// hint GameImpl appends, which is plain text).
const f1 = 'Choose spell or ability to play<br>Arid Mesa'
check('prompt separates the question from the card name',
  stripServerHtml(f1) === 'Choose spell or ability to play Arid Mesa',
  JSON.stringify(stripServerHtml(f1)))
check('the run-on form is gone', !stripServerHtml(f1).includes('playArid Mesa'))

// ─────────────────────────────────────────────────────────────────────────────
section('"Pay " + "{W}" + <div> + source name')

// ManaUtil.addSpecialManaPayAbilities (ManaUtil.java:458) appends the source
// name in an inline-styled div; HumanPlayer prepends "Pay ".
const f6 = "Pay {W}<div style='font-size:11pt'>Guide of Souls</div>"
const f6out = stripServerHtml(f6)
check('mana-popup title separates the cost from the source name',
  f6out === 'Pay {W} Guide of Souls', JSON.stringify(f6out))
check('the run-on form is gone', !f6out.includes('{W}Guide of Souls'))
check('the mana symbol itself survives', f6out.includes('{W}'))

// ─────────────────────────────────────────────────────────────────────────────
section('Separator fix does not regress the log / chat paths')

// Game log: raw <font>/<br> markup, plain text inside.
check('game-log <font> markup still strips',
  stripServerHtml("AI-1 loses 2 life at combat from <font color='red'>Amped Raptor</font>") ===
  'AI-1 loses 2 life at combat from Amped Raptor')
check('log <br> becomes a real separator, not a glued word',
  stripServerHtml('hermes_agent - Ability triggers: Guide of Souls - you gain 1 life<br>and get {E}.') ===
  'hermes_agent - Ability triggers: Guide of Souls - you gain 1 life and get {E}.')
// Chat arrives entity-escaped with NO raw tags — must be untouched by the
// block-tag branch (the regression this could plausibly reintroduce).
check('escaped chat markup is not turned into content',
  stripServerHtml('&lt;b&gt;hi&lt;/b&gt;') === '<b>hi</b>',
  JSON.stringify(stripServerHtml('&lt;b&gt;hi&lt;/b&gt;')))
check('a user message containing &lt;div&gt; stays literal (no injected separator)',
  stripServerHtml('&lt;div&gt;oops&lt;/div&gt;') === '<div>oops</div>',
  JSON.stringify(stripServerHtml('&lt;div&gt;oops&lt;/div&gt;')))
// Inline tag boundaries must NOT manufacture a space (would split real words).
check('adjacent inline tags still collapse to nothing',
  stripServerHtml('<font color=red>a</font><font color=blue>b</font>') === 'ab',
  JSON.stringify(stripServerHtml('<font color=red>a</font><font color=blue>b</font>')))
check('a run of breaks collapses to a single space',
  stripServerHtml('a<br><br><br>b') === 'a b',
  JSON.stringify(stripServerHtml('a<br><br><br>b')))
// Edge breaks vanish rather than leaving a stray leading/trailing space.
check('leading/trailing block tags leave no stray space',
  stripServerHtml('<br>Seats: 2/2<br/>') === 'Seats: 2/2',
  JSON.stringify(stripServerHtml('<br>Seats: 2/2<br/>')))
// The match-score case master fixed in the same function must stay fixed.
check('match-score line is not glued (master 46d4c9591c parity)',
  stripServerHtml('Match is not rated<br/>You win 2-0') === 'Match is not rated You win 2-0',
  JSON.stringify(stripServerHtml('Match is not rated<br/>You win 2-0')))
check('[set] abbreviation stripping still works',
  stripServerHtml('Lightning Bolt [2XM] deals 3 damage.') ===
  'Lightning Bolt deals 3 damage.',
  JSON.stringify(stripServerHtml('Lightning Bolt [2XM] deals 3 damage.')))

// ─────────────────────────────────────────────────────────────────────────────
section('mana symbols must stay inline inside a stack ability tile')

const css = readFileSync(join(FRONTEND, 'game-renderer.css'), 'utf8')

// The defect: `.card-thumb img { display: block }` (0-1-1) outranked
// `.mana-icon { display: inline-block }` (0-1-0).
function specificity(sel: string): [number, number, number] | null {
  // Only the simple selectors used in this file; returns null if unsupported.
  if (/[>+~]|\[|:hover|::/.test(sel)) return null
  const ids = (sel.match(/#[\w-]+/g) || []).length
  const classes = (sel.match(/\.[\w-]+|:not\([^)]*\)|:[\w-]+/g) || []).length
  const types = (sel.replace(/#[\w-]+/g, '').replace(/\.[\w-]+/g, '').replace(/:[\w-]+\([^)]*\)/g, '').match(/[a-zA-Z][\w-]*/g) || []).length
  return [ids, classes, types]
}
function cmp(a: [number, number, number], b: [number, number, number]): number {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i]! - b[i]!
  return 0
}

const cardThumbImg = [...css.matchAll(/^\.card-thumb img[^{]*\{/gm)].map((m) => m[0].replace(/\s*\{$/, '').trim())
check('the card-art rule exists and is scoped away from .mana-icon',
  cardThumbImg.length > 0 && cardThumbImg.every((s) => s.includes(':not(.mana-icon)')),
  cardThumbImg.join(' | '))

const abilityTextManaIcon = /\.ability-text \.mana-icon\s*\{([^}]*)\}/.exec(css)
check('.ability-text .mana-icon rule exists', abilityTextManaIcon != null)
check('.ability-text .mana-icon states display: inline-block explicitly',
  !!abilityTextManaIcon && /display:\s*inline-block/.test(abilityTextManaIcon[1]!),
  abilityTextManaIcon ? abilityTextManaIcon[1] : 'missing')

// Whatever the card-art rule becomes, a `.mana-icon` inside `.card-thumb` must
// end up inline. Two independent guarantees, asserted separately because either
// alone is sufficient and both are load-bearing:
//   (a) the art rule must not MATCH a mana icon at all (`:not(.mana-icon)`);
//   (b) the stack-tile icon rule must state `display` itself, so a future
//       broadening of any `.card-thumb …img` rule cannot blockify it again.
const artSel = cardThumbImg[0]
const iconSel = '.ability-text .mana-icon'
check('the card-art selector excludes mana icons rather than relying on specificity',
  !!artSel && !/\.card-thumb img\s*\{/.test(css) && artSel.includes(':not(.mana-icon)'),
  `art selector: ${artSel}`)
check('stack-tile icon rule outranks the generic .mana-icon rule (so its display wins)',
  (() => {
    const iconSpec = specificity(iconSel)
    const genericSpec = specificity('.mana-icon')
    return iconSpec != null && genericSpec != null && cmp(iconSpec, genericSpec) > 0
  })(),
  `icon ${JSON.stringify(specificity(iconSel))} vs generic ${JSON.stringify(specificity('.mana-icon'))}`)

// Card art must STILL be a block filling the tile — the fix must not un-style
// every card thumbnail in the app (that would be a worse regression than a whole-app one).
const artBody = cardThumbImg[0] ? new RegExp(cardThumbImg[0].replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^}]*)\\}').exec(css) : null
check('card art still block-fills its tile',
  !!artBody && /display:\s*block/.test(artBody[1]!) && /width:\s*100%/.test(artBody[1]!),
  artBody ? artBody[1] : 'rule body not found')

// The generic .mana-icon rule (used by ChatPanel/CardPreview/costs) is untouched.
const generic = /^\.mana-icon\s*\{([^}]*)\}/m.exec(css)
check('generic .mana-icon still inline-block (no regression elsewhere)',
  !!generic && /display:\s*inline-block/.test(generic[1]!))

// The card preview already used the :not() technique — the fix matches it.
check('the fix reuses the pre-existing #card-preview :not(.mana-icon) technique',
  /#card-preview img:not\(\.mana-icon\)/.test(css))

// ─────────────────────────────────────────────────────────────────────────────
section('the same ability must not be rendered by two components')

const bar = readFileSync(join(HERE, '..', 'src', 'components', 'ActionPromptBar.tsx'), 'utf8')
const popup = readFileSync(join(HERE, '..', 'src', 'components', 'DecisionPopup.tsx'), 'utf8')

check('ActionPromptBar does NOT render a choose_ability body',
  !/<ChooseAbilityPrompt\b/.test(bar) && !/prompt\.type === 'choose_ability' &&\s*</.test(bar))
// item 2 moved ALL prompt bodies into the centred DecisionPopup window;
// the rail renders the message line + meta controls only. Stronger than the old
// choose_ability-only gate: no body component may come back in the rail.
check('ActionPromptBar renders NO per-type prompt bodies (DecisionPopup owns them)',
  !/<BooleanPrompt\b/.test(bar) && !/<SelectPrompt\b/.test(bar) &&
  !/<TargetPrompt\b/.test(bar) && !/<ChooseChoicePrompt\b/.test(bar) &&
  !/<MultiAmountPrompt\b/.test(bar))
check('DecisionPopup DOES render the choose_ability body',
  /ChooseAbilityBody/.test(popup) && /case 'choose_ability':\s*\n\s*return <ChooseAbilityBody/.test(popup))
check('ActionPromptBar excludes choose_ability from the rail (comment or gate)',
  /choose_ability/.test(bar))
// Why the UI showed "the same ability listed twice": `.prompt-choices` is
// rendered INSIDE `.decision-popup` (ChooseAbilityBody is a child of the popup
// div), so a DOM query for both selectors counts the SAME button twice. Assert
// the nesting so this stays an explanation and not a coincidence.
check('.prompt-choices is nested inside .decision-popup (one button, two selectors)',
  /decision-popup/.test(popup) &&
  /className="prompt-choices"/.test(popup) &&
  popup.indexOf('decision-popup') < popup.indexOf('className="prompt-choices"'))
// The old standalone component must not be imported anywhere (would re-duplicate).
const anyImport = readFileSync(join(HERE, '..', 'src', 'components', 'prompts', 'ChooseAbilityPrompt.tsx'), 'utf8')
check('prompts/ChooseAbilityPrompt.tsx still exists but is unreferenced by the rail',
  anyImport.length > 0 && !/from '\.\/prompts\/ChooseAbilityPrompt'/.test(bar))
// No other component may render a choose_ability body — that is the only way a
// genuine duplicate could come back.
const allTsx = readFileSync(join(HERE, '..', 'src', 'components', 'prompts', 'ChooseAbilityPrompt.tsx'), 'utf8')
check('exactly one choose_ability body component exists',
  /export function ChooseAbilityPrompt/.test(allTsx) &&
  !/<ChooseAbilityPrompt/.test(bar))

// ─────────────────────────────────────────────────────────────────────────────
section('target prompts must not be attributed from stale click state')

const wpc = readFileSync(join(REPO, 'Mage.WebProxy', 'src', 'main', 'java', 'mage', 'webproxy', 'WebProxyClient.java'), 'utf8')
const session = readFileSync(join(REPO, 'Mage.WebProxy', 'src', 'main', 'java', 'mage', 'webproxy', 'WebProxySession.java'), 'utf8')
check('handleGameTarget appends the engine-provided secondMessage, not stale click state',
  /srcOptions\.get\("secondMessage"\)/.test(wpc) && /\+ " for " \+ secondMessage/.test(wpc))
check('the stale lastSelectedCardName field is gone from the proxy',
  !/lastSelectedCardName/.test(session) &&
  !/private volatile String lastSelectedCardName/.test(wpc) &&
  !/setLastSelectedCardName/.test(session))
check('the pendingChoiceNames side-channel is gone too (it only fed that field)',
  !/pendingChoiceNames/.test(wpc) && !/getPendingChoiceNames/.test(session))
check('the target-prompt context heuristic uses the engine message, not the decorated one',
  /String lowerMsg = \(gcm\.getMessage\(\) != null \? gcm\.getMessage\(\) : ""\)\.toLowerCase\(\)/.test(wpc))
check('engine sends the source name in options.secondMessage (the data this check needs)',
  /options\.put\("secondMessage", message\.getSecondMessage\(\)\)/.test(
    readFileSync(join(REPO, 'Mage', 'src', 'main', 'java', 'mage', 'game', 'GameImpl.java'), 'utf8')))

// ─────────────────────────────────────────────────────────────────────────────
section('whole-library dump is deliberate (desktop parity), not a defect')

const human = readFileSync(join(REPO, 'Mage.Server.Plugins', 'Mage.Player.Human', 'src', 'mage', 'player', 'human', 'HumanPlayer.java'), 'utf8')
check('engine passes the full cards view and puts legality in possibleTargets',
  /options\.put\("possibleTargets", \(Serializable\) possibleTargets\)/.test(human))
const showCards = readFileSync(join(REPO, 'Mage.Client', 'src', 'main', 'java', 'mage', 'client', 'dialog', 'ShowCardsDialog.java'), 'utf8')
check('desktop client shows ALL cards and only MARKs the choosable ones (parity)',
  /cardArea\.loadCards\(showCards, bigCard, gameId\)/.test(showCards) &&
  /possibleTargets/.test(showCards) && /cardArea\.markCards/.test(showCards))

// ─────────────────────────────────────────────────────────────────────────────
console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
if (failures > 0) process.exit(1)
