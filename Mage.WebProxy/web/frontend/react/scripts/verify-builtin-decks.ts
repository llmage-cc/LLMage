/**
 * "Every built-in deck parses" check.
 *
 * Run with:  bash scripts/verify-builtin-decks.sh
 *
 * Validates the GENERATED artifact web/frontend/decks/builtin.json — the file
 * the built-in deck picker lazy-fetches — before it is committed/shipped:
 *   (a) the JSON parses and carries the expected schema (generated/counts/
 *       groups/decks with the exact field names BuiltInDeckPicker.tsx reads);
 *   (b) every deck entry's text matches the shapes the web DeckLoader accepts
 *       (the mirrored DCK_CARD_LINE grammar): NAME:/AUTHOR: headers, '#'/'//'
 *       comments, "N [SET:NUM] Name" / "N Name" / "SB: ..." card lines, and
 *       (sample decks only) LAYOUT lines the loader skips;
 *   (c) majors entries NEVER carry EVENT: or LAYOUT lines (the generator
 *       strips EVENT:) and always carry a NAME: header (the generator adds it);
 *   (d) split-card names are in the DB form "A // B", never "A / B" (the web
 *       path resolves "A // B"; "A / B" would die at join time with
 *       "Card not found");
 *   (e) ids are unique and counts/groups match the decks array.
 *
 * The authoritative per-card validation (parser + card-DB resolution) runs in
 * Java at generation time (Mage.WebProxy/scripts/BuiltinDeckValidator.java);
 * this harness is the structural check over the artifact.
 *
 * Not part of the app build (scripts/ is outside tsconfig "include": ["src"]).
 */

const DCK_CARD_LINE =
  /^(SB:)?\s*(\d+)\s*(?:\[([^]:]+):([^]:]+)\])?\s*(.+?)\s*$/

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failures++
    console.log(`  FAIL  ${name} ${detail}`)
  }
}

interface DeckEntryJson {
  id: string
  source: string
  format: string
  group: string
  subcategory: string
  name: string
  author: string
  text: string
}

interface BuiltinJson {
  generated: string
  counts: { majors: number; sample: number }
  groups: Record<string, number>
  decks: DeckEntryJson[]
}

async function main(): Promise<void> {
  const path = process.argv[2] ?? '../decks/builtin.json'
  const { readFileSync } = await import('fs')
  const raw = readFileSync(path, 'utf-8')

  console.log('## builtin.json parses and matches the picker schema')
  let data: BuiltinJson
  try {
    data = JSON.parse(raw) as BuiltinJson
    check('JSON.parse succeeds', true)
  } catch (e) {
    check('JSON.parse succeeds', false, String(e))
    process.exit(1)
  }
  check('has generated', typeof data.generated === 'string' && data.generated.length > 0)
  check(
    'has counts.majors/sample',
    typeof data.counts?.majors === 'number' && typeof data.counts?.sample === 'number',
  )
  check('has groups', data.groups !== undefined && data.groups !== null)
  check('decks is a non-empty array', Array.isArray(data.decks) && data.decks.length > 0)
  if (!data.decks || data.decks.length === 0) process.exit(1)
  const decks = data.decks

  console.log(`## ${decks.length} deck entries: schema fields + ids`)
  {
    const missing = decks.filter(
      (d) =>
        !d.id ||
        !d.source ||
        !d.group ||
        d.subcategory === undefined ||
        !d.name ||
        !d.text,
    )
    check('every entry has id/source/group/subcategory/name/text', missing.length === 0,
      `first missing: ${missing[0]?.id ?? '(none)'}`)
    const sources = new Set(decks.map((d) => d.source))
    check('sources are majors|sample',
      [...sources].every((s) => s === 'majors' || s === 'sample'), [...sources].join(','))
    const ids = new Set<string>()
    let dupes = 0
    for (const d of decks) {
      if (ids.has(d.id)) dupes++
      ids.add(d.id)
    }
    check('ids unique', dupes === 0, `${dupes} duplicates`)
    const majorsCount = decks.filter((d) => d.source === 'majors').length
    const sampleCount = decks.filter((d) => d.source === 'sample').length
    check('counts match decks array',
      data.counts.majors === majorsCount && data.counts.sample === sampleCount,
      `counts=${JSON.stringify(data.counts)} actual=${majorsCount}/${sampleCount}`)
  }

  console.log('## every deck text parses under the mirrored DeckLoader grammar')
  {
    let badLine = ''
    let badDeck = ''
    for (const d of decks) {
      for (const rawLine of d.text.split('\n')) {
        const line = rawLine.trim()
        if (line === '') continue
        if (line.startsWith('#') || line.startsWith('//')) continue
        if (line.startsWith('NAME:') || line.startsWith('AUTHOR:')) continue
        if (line.startsWith('LAYOUT ')) {
          if (d.source === 'majors') {
            badDeck = d.id
            badLine = line
            break
          }
          continue
        }
        if (line.startsWith('EVENT:')) {
          badDeck = d.id
          badLine = line
          break
        }
        if (!DCK_CARD_LINE.test(line)) {
          badDeck = d.id
          badLine = line
          break
        }
      }
      if (badDeck) break
    }
    check('all card lines match the DeckLoader grammar', !badDeck,
      badDeck ? `${badDeck}: "${badLine}"` : '')
  }

  console.log('## majors entries carry a NAME: header; no single-slash split names')
  {
    const noName = decks.filter(
      (d) => d.source === 'majors' && !d.text.split('\n').some((l) => l.startsWith('NAME:')),
    )
    check('majors entries have NAME: header', noName.length === 0,
      noName[0]?.id ?? '')
    const singleSlash = decks.filter(
      (d) =>
        d.source === 'majors' &&
        d.text.split('\n').some((l) => {
          const t = l.trim()
          if (t === '' || t.startsWith('#') || t.startsWith('//') ||
              t.startsWith('NAME:') || t.startsWith('AUTHOR:') || t.startsWith('LAYOUT ')) {
            return false
          }
          const m = DCK_CARD_LINE.exec(t)
          if (!m) return false
          // mtgtop8 writes "A / B"; the DB stores "A // B". A single-slash name
          // surrounded by spaces inside the card name is a bug.
          const name = m[5] ?? ''
          return /(^|[^/])\s\/\s([^/]|$)/.test(name)
        }),
    )
    check('majors split names use the "A // B" form', singleSlash.length === 0,
      singleSlash[0]?.id ?? '')
  }

  if (failures > 0) {
    console.log(`FAILED: ${failures} check(s)`)
    process.exit(1)
  }
  console.log('ALL PASS')
}

void main()