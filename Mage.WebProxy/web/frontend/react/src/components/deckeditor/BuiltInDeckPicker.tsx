import { useEffect, useMemo, useState } from 'react'

/**
 * Built-in deck library for the web client.
 *
 * Two sources, generated into /decks/builtin.json by
 * Mage.WebProxy/scripts/builtin-decks.sh (BuiltinDeckValidator validates every
 * deck through the web DeckLoader path + card-DB resolution, so a picked deck
 * cannot die at join time):
 *   - majors  — top-8 decks of Pro Tours / Grands Prix / Regional+National
 *               Championships / Worlds per format (mtgtop8 corpus)
 * sample  — the XMage-bundled sample decks (Commander, Decks to Beat...)
 *
 * Rendered BELOW SavedDeckPicker (saved decks stay the first list); the decks
 * are nested in submenus (source-group → event/set → deck), never one flat
 * list. The JSON is lazy-fetched the first time a picker mounts and cached for
 * the session — it is NOT part of the JS bundle.
 *
 * This component is flavor-independent: built-in decks are not LLM-specific, so
 * no __LLMAGE__ gating here.
 */

interface BuiltinDeckEntry {
  id: string
  source: 'majors' | 'sample'
  format: string
  group: string
  subcategory: string
  name: string
  author: string
  text: string
}

interface BuiltinDeckJson {
  generated: string
  counts: { majors: number; sample: number }
  decks: BuiltinDeckEntry[]
}

/** Module-level cache: one fetch per page load, shared by every picker mount. */
let cachePromise: Promise<BuiltinDeckJson> | null = null

function loadBuiltinDecks(): Promise<BuiltinDeckJson> {
  if (!cachePromise) {
    cachePromise = fetch('/decks/builtin.json').then(async (resp) => {
      if (!resp.ok) {
        throw new Error(`builtin decks fetch failed: HTTP ${resp.status}`)
      }
      return (await resp.json()) as BuiltinDeckJson
    })
  }
  return cachePromise
}

interface BuiltInDeckPickerProps {
  /** Called with the deck's paste-ready text when a built-in deck is chosen. */
  onPick: (deckText: string) => void
}

interface GroupTree {
  /** group name → subcategory → decks (subcategory '' = decks directly in the group) */
  groups: Map<string, Map<string, BuiltinDeckEntry[]>>
  order: string[]
}

/** Exported for scripts/verify-builtin-picker-tree.ts (the regression harness
 *  for the 2026-09-26 New-Game blank — see the ordering loop below). */
export function buildTree(decks: BuiltinDeckEntry[]): GroupTree {
  const groups = new Map<string, Map<string, BuiltinDeckEntry[]>>()
  for (const d of decks) {
    let subs = groups.get(d.group)
    if (!subs) {
      subs = new Map()
      groups.set(d.group, subs)
    }
    let list = subs.get(d.subcategory)
    if (!list) {
      list = []
      subs.set(d.subcategory, list)
    }
    list.push(d)
  }
  // Deterministic ordering: majors formats first, then sample folders alphabetically.
  // Rebuild each subcategory map SORTED — do not clear() the map first and re-insert
  // subs.get(k): after clear() every get(k) is undefined, so every list would be
  // destroyed (subs.set(k, undefined)) and the list.sort below would throw
  // "Cannot read properties of undefined (reading 'sort')" the moment the picker
  // first renders with data — which unmounts the whole app (no error boundary,
  // full #root wipe; found live 2026-09-26 on a merged bundle).
  const order = [...groups.keys()].sort((a, b) => {
    const am = a === 'Legacy' || a === 'Modern' || a === 'Pioneer' || a === 'Vintage' || a === 'cEDH'
    const bm = b === 'Legacy' || b === 'Modern' || b === 'Pioneer' || b === 'Vintage' || b === 'cEDH'
    if (am !== bm) return am ? -1 : 1
    return a.localeCompare(b)
  })
  for (const [groupName, subs] of [...groups.entries()]) {
    groups.set(groupName, new Map(
      [...subs.entries()]
        .sort(([a], [b]) => String(a).localeCompare(String(b)))
        .map(([k, list]) => [k, [...list].sort((a, b) => a.name.localeCompare(b.name))]),
    ))
  }
  return { groups, order }
}

export default function BuiltInDeckPicker({ onPick }: BuiltInDeckPickerProps) {
  const [data, setData] = useState<BuiltinDeckJson | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    loadBuiltinDecks()
      .then((json) => {
        if (!cancelled) setData(json)
      })
      .catch((e: Error) => {
        if (!cancelled) {
          cachePromise = null // allow a retry on the next mount
          setError(e.message)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  const tree = useMemo(() => (data ? buildTree(data.decks) : null), [data])

  const [group, setGroup] = useState('')
  const [subcategory, setSubcategory] = useState('')

  const subcategories = useMemo(() => {
    if (!tree || !group) return []
    return [...(tree.groups.get(group)?.keys() ?? [])]
  }, [tree, group])

  const deckList = useMemo(() => {
    if (!tree || !group) return []
    const subs = tree.groups.get(group)
    if (!subs) return []
    return subs.get(subcategory) ?? []
  }, [tree, group, subcategory])

  const handleGroup = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    setGroup(e.target.value)
    setSubcategory('')
  }

  const handleSubcategory = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    setSubcategory(e.target.value)
  }

  const handlePick = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    const id = e.target.value
    if (!id) return
    const deck = deckList.find((d) => d.id === id)
    if (!deck) return
    onPick(deck.text)
  }

  if (error) {
    return (
      <div className="builtin-deck-picker builtin-deck-picker-error">
        <span>Built-in decks unavailable: {error}</span>
        <button
          data-explain="builtin-decks-retry"
          className="btn btn-secondary btn-sm"
          onClick={() => {
            cachePromise = null
            setError(null)
            setGroup('')
            setSubcategory('')
          }}
        >
          Retry
        </button>
      </div>
    )
  }

  if (!tree) {
    return (
      <div className="builtin-deck-picker">
        <span className="builtin-deck-picker-hint">Loading built-in decks…</span>
      </div>
    )
  }

  const groupLabel = (g: string): string => {
    if (g === 'Legacy' || g === 'Modern' || g === 'Pioneer' || g === 'Vintage' || g === 'cEDH') {
      const n = tree.groups.get(g)
      const count = n ? [...n.values()].reduce((a, l) => a + l.length, 0) : 0
      return `${g} (${count} event decks)`
    }
    return g
  }

  const subLabel = (s: string): string => (s === '' ? '(all decks in this group)' : s)

  return (
    <div className="builtin-deck-picker">
      <label>Built-in decks:</label>
      <select value={group} onChange={handleGroup} defaultValue="">
        <option value="">— pick a library —</option>
        {tree.order.map((g) => (
          <option key={g} value={g}>
            {groupLabel(g)}
          </option>
        ))}
      </select>
      {group !== '' && subcategories.length > 1 && (
        <select value={subcategory} onChange={handleSubcategory}>
          <option value="">— pick an event/set —</option>
          {subcategories.map((s) => (
            <option key={s} value={s}>
              {subLabel(s)}
            </option>
          ))}
        </select>
      )}
      {group !== '' && deckList.length > 0 && (
        <select onChange={handlePick} defaultValue="">
          <option value="">— pick a deck —</option>
          {deckList.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
              {d.author ? ` — ${d.author}` : ''}
            </option>
          ))}
        </select>
      )}
      {group === '' && (
        <span className="builtin-deck-picker-hint">
          {tree ? (
            <>
              {data?.counts.majors ?? 0} tournament decks + {data?.counts.sample ?? 0} sample
              decks
            </>
          ) : null}
        </span>
      )}
    </div>
  )
}