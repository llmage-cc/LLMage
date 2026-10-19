/**
 * Player counter display model.
 *
 * The desktop player panel renders five DEDICATED, labelled counter fields —
 * poison, energy, experience, rad, ticket — each with an icon and a number, and
 * a tooltip "<name>: <count>" (PlayerPanelExt.java:553-676
 * `setTextForLabel("poison", poisonLabel, poison, counterOfName(player, "poison"), …)`
 * … through `ticket`, wired at :290-294). The web board rendered a single generic
 * `name: count | name: count` line for ALL counter types, so those five had no
 * dedicated, recognisable display.
 *
 * The serializer already passes every counter type
 * (GameStateSerializer.java:631-641 — player counters as `{name, count}[]`), so
 * this is a pure presentation split: pull the five known counters into dedicated
 * fields and leave everything else in the generic list. Kept pure so the harness
 * asserts the exact split the board renders.
 */

import type { PlayerCounter } from '@/types'

/**
 * The dedicated counters, in the desktop's own order (PlayerPanelExt.initComponents:
 * poison, energy, experience, rad, ticket). `counter` is the engine's counter name
 * (`CounterType.toString()` — the serializer sends `counter.getName()`), `label`
 * the desktop label and `glyph` a text-safe marker (the desktop uses PNG icons
 * from /info/*.png; a web client on a Linux host cannot rely on an emoji font —
 * see the game-end dialog's glyph-safety note — so these are plain letters).
 */
export interface DedicatedCounterSpec {
  counter: string
  label: string
  glyph: string
}

export const DEDICATED_COUNTERS: readonly DedicatedCounterSpec[] = [
  { counter: 'poison', label: 'Poison', glyph: 'P' },
  { counter: 'energy', label: 'Energy', glyph: 'E' },
  { counter: 'experience', label: 'Experience', glyph: 'X' },
  { counter: 'rad', label: 'Rad', glyph: 'R' },
  { counter: 'ticket', label: 'Ticket', glyph: 'T' },
]

export interface DedicatedCounterValue extends DedicatedCounterSpec {
  count: number
}

export interface PlayerCounterSplit {
  /** The five dedicated counters that are present with a count > 0, in spec order. */
  dedicated: DedicatedCounterValue[]
  /** Every other counter (and any dedicated one that is 0/absent stays out). */
  others: PlayerCounter[]
}

/**
 * Split a player's counters into the dedicated five and the rest.
 *
 *  - a dedicated counter with count <= 0 never renders (the desktop hides a zero
 *    field — `counterOfName` returns 0 and the label shows 0 but the desktop
 *    layout only draws it in the counters row; the web board's own convention is
 *    to drop zero-count entries, PlayerBoard's `filter(c => c.count > 0)`),
 *  - the name match is case-insensitive (the wire is lower-case today, but a
 *    hand-framed or future value must not silently miss its dedicated field),
 *  - `others` preserves input order and includes duplicate names verbatim,
 *  - a dedicated counter is NEVER duplicated into `others`.
 */
export function splitPlayerCounters(counters: readonly PlayerCounter[] | undefined): PlayerCounterSplit {
  const list = counters ?? []
  const dedicated: DedicatedCounterValue[] = []
  const others: PlayerCounter[] = []

  for (const spec of DEDICATED_COUNTERS) {
    const hit = list.find((c) => (c.name ?? '').toLowerCase() === spec.counter)
    if (hit && hit.count > 0) {
      dedicated.push({ ...spec, count: hit.count })
    }
  }

  for (const c of list) {
    const isDedicated = DEDICATED_COUNTERS.some((s) => s.counter === (c.name ?? '').toLowerCase())
    if (!isDedicated) others.push(c)
  }

  return { dedicated, others }
}

/** The desktop tooltip text for a counter ("<name>: <count>", PlayerPanelExt:198). */
export function counterTooltip(label: string, count: number): string {
  return `${label}: ${count}`
}
