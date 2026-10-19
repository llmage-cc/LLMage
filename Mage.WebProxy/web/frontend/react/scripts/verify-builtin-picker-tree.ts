/**
 * Regression harness for the New-Game full-app blank (2026-09-26).
 *
 * Root cause (captured live in a browser against the merged bundle):
 * BuiltInDeckPicker.buildTree's ordering loop called `subs.clear()` and then
 * re-inserted `subs.set(k, subs.get(k)!)` — but after clear() every get(k) is
 * undefined, so every subcategory LIST was destroyed (set to undefined) and the
 * following `list.sort(...)` threw
 *   TypeError: Cannot read properties of undefined (reading 'sort')
 * during the FIRST render with data. React had no error boundary, so the throw
 * unmounted the entire app (#root children → 0) — clicking New Game blanked the
 * whole client. The dialog's mount effects (fetch_models/fetch_personalities,
 * the deck-library GET) fired right before the wipe, which made the models
 * fetch look like the suspect; it is incidental — the models fetch legitimately
 * fails without a local Ollama and renders its own error state fine.
 *
 * This harness bundles the REAL component module (not a re-implemented copy of
 * its logic) and drives buildTree over the REAL generated
 * web/frontend/decks/builtin.json:
 *   (a) buildTree never throws (pre-fix it threw on the first data render);
 *   (b) the ordering contract holds: majors-format groups first, then
 *       alphabetical; every group's subcategories sorted; every list
 *       name-sorted;
 *   (c) no deck is lost or duplicated by the tree build (all N entries
 *       reachable, exactly once);
 *   (d) every deck carries non-empty .dck text (the pick-fill payload);
 *   (e) the '' subcategory (decks directly in their group) survives.
 *
 * Run with:  bash scripts/verify-builtin-picker-tree.sh [path-to-builtin.json]
 * Node-only (no browser): buildTree is a pure module function; the project has
 * no jsdom dependency (see verify-random-deck.sh for the same bundling
 * approach). Not part of the app build (scripts/ is outside tsconfig).
 */

import { readFileSync } from 'node:fs'
import { buildTree } from '../src/components/deckeditor/BuiltInDeckPicker'

const fail = (msg: string): never => {
  console.error(`  FAIL  ${msg}`)
  process.exit(1)
}

// ── Load the REAL artifact the picker lazy-fetches ──────────────────────────
const jsonPath = process.argv[2] ?? "../decks/builtin.json"
const json = JSON.parse(readFileSync(jsonPath, 'utf8')) as {
  counts: { majors: number; sample: number }
  decks: Array<{ id: string; group: string; subcategory: string; name: string; text: string }>
}
console.log(`builtin.json: ${json.decks.length} decks (majors ${json.counts.majors}, sample ${json.counts.sample})`)

// ── (a) buildTree over the REAL artifact never throws ───────────────────────
let tree: ReturnType<typeof buildTree>
try {
  tree = buildTree(json.decks)
} catch (e) {
  fail(`buildTree threw: ${(e as Error).stack ?? e}`)
}
console.log('  PASS  buildTree does not throw on the real artifact')

// ── (b) ordering contract ───────────────────────────────────────────────────
const MAJORS = ["cEDH", "Legacy", "Modern", "Pioneer", "Vintage"]
const order = tree.order
// Majors groups first, in the canonical order (then everything alphabetical).
const majorsInTree = MAJORS.filter((m) => order.includes(m))
for (let i = 0; i < majorsInTree.length; i++) {
  if (order[i] !== majorsInTree[i]) {
    fail(`group order broken: expected majors-first, got [${order.slice(0, 6).join(', ')}…]`)
  }
}
for (let i = majorsInTree.length; i < order.length; i++) {
  // Sample-deck groups are calendar years (1998…2026): the shipped order puts
  // them AFTER the majors, still ahead of alphabetical folders — so only
  // compare consecutive non-majors pairwise, not against the majors block.
  void i
}
for (let i = majorsInTree.length + 1; i < order.length; i++) {
  const a = order[i - 1]!
  const b = order[i]!
  if (MAJORS.includes(a)) continue
  if (String(a).localeCompare(String(b)) > 0) {
    fail(`group order broken: non-majors not alphabetical (${a} > ${b})`)
  }
}
console.log(`  PASS  group ordering: majors first, then alphabetical (${order.length} groups)`)

for (const g of order) {
  const subs = tree.groups.get(g)
  if (!subs) fail(`group ${g} missing from groups map`)
  const keys = [...subs.keys()]
  for (let i = 1; i < keys.length; i++) {
    if (String(keys[i - 1]).localeCompare(String(keys[i]!)) > 0) {
      fail(`group ${g}: subcategories not sorted`)
    }
  }
  for (const [sub, list] of subs.entries()) {
    for (let i = 1; i < list.length; i++) {
      if (list[i - 1]!.name.localeCompare(list[i]!.name) > 0) {
        fail(`group ${g}/${sub}: deck list not name-sorted`)
      }
    }
  }
}
console.log('  PASS  every group\'s subcategories sorted, every deck list name-sorted')

// ── (c) no deck lost or duplicated ──────────────────────────────────────────
let totalSeen = 0
const seenIds = new Set<string>()
for (const [, subs] of tree.groups) {
  for (const [, list] of subs) {
    for (const d of list) {
      if (seenIds.has(d.id)) fail(`deck ${d.id} appears twice in the tree`)
      seenIds.add(d.id)
      totalSeen++
    }
  }
}
if (totalSeen !== json.decks.length) {
  fail(`tree holds ${totalSeen} decks but the artifact has ${json.decks.length}`)
}
console.log(`  PASS  all ${totalSeen} decks reachable exactly once`)

// ── (d) every deck carries non-empty .dck text ──────────────────────────────
for (const d of json.decks) {
  if (!d.text || d.text.length === 0) fail(`deck ${d.id} (${d.name}) has empty text`)
}
console.log('  PASS  every deck carries non-empty .dck text (pick-fill payload exists)')

// ── (e) the '' subcategory survives the sort path ───────────────────────────
// 515 decks (mostly sample decks) live directly in their group with
// subcategory '' — the pre-fix code dropped EVERY list; assert that one
// explicitly (its size is stable in the generator contract: the sample decks
// whose folder IS the group).
let emptySubDecks = 0
for (const [, subs] of tree.groups) {
  for (const [sub, list] of subs) {
    if (sub === '') emptySubDecks += list.length
  }
}
if (emptySubDecks === 0) {
  fail("'' subcategory lost — the 'decks directly in their group' lists were dropped")
}
console.log(`  PASS  '' subcategory retained (${emptySubDecks} decks directly in their group)`)

console.log('\nRESULT: ALL PASS')