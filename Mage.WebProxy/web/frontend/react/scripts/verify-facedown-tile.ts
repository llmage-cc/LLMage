/**
 * Render-level check for the face-down tile branch with the REAL component.
 * Runs through node + rolldown bundling (same technique as verify-art-fallback).
 *
 *  - SSR render CardThumb with a faceDown card (no imageUrl) → must contain
 *    div.card-thumb-facedown and NOT the text fallback box.
 *  - SSR render CardThumb with an identical card but faceDown absent → must
 *    NOT contain card-thumb-facedown (fallback box only).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createElement as h } from 'react'
// @ts-intentional: harness resolves per project aliases via rolldown
import { CardThumb } from '../src/components/CardThumb'

let failures = 0
function check(name, cond, detail = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}

const facedownCard: any = {
  id: 'fd-1', name: 'Morph', typeLine: 'Creature', manaCost: '',
  rules: [], faceDown: true, imageUrl: undefined,
}
const faceUpCard: any = {
  id: 'fu-1', name: 'Grizzly Bears', typeLine: 'Creature',
  manaCost: '{1}{G}', rules: [], imageUrl: undefined,
}

const fd = renderToStaticMarkup(h(CardThumb, { card: facedownCard, className: 'card-thumb-sm' }))
const fu = renderToStaticMarkup(h(CardThumb, { card: faceUpCard, className: 'card-thumb-sm' }))

check('facedown tile renders .card-thumb-facedown', fd.includes('card-thumb-facedown'), fd.slice(0, 240))
check('facedown tile does NOT render the text fallback box', !fd.includes('card-art-fallback-name'), fd.slice(0, 240))
check('face-up card does NOT render .card-thumb-facedown', !fu.includes('card-thumb-facedown'), fu.slice(0, 240))
check('face-up card renders the text fallback (no art in this test payload)', fu.includes('card-art-fallback-name'), fu.slice(0, 240))

const css = readFileSync(resolve(process.cwd(), '../game-renderer.css'), 'utf8')
check('game-renderer.css paints .card-thumb-facedown with the CSS-only neutral back',
  /\.card-thumb-facedown\s*\{[^}]*background-image:\s*var\(--card-back-neutral\)/.test(css) &&
  !/cardback\.(jpe?g|png|webp)/i.test(css),
  'a face-down card must paint a distinguished back from CSS, not a bundled image')
check('the neutral back layer stack is gradients only (no image asset)',
  /--card-back-neutral:[^;]*radial-gradient[^;]*linear-gradient[^;]*;/.test(css) &&
  !/--card-back-neutral:[^;]*url\(/.test(css))

console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
