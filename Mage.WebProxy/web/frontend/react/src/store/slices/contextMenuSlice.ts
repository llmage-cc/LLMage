import type { StateCreator } from 'zustand'
import type { GameStore } from '@/store/types'

/**
 * Which component owns the open context menu. One value at a time — the whole
 * point of the central registry is that CardPreview (and any future overlay)
 * can ask "is ANY context menu up?" instead of each menu shipping its own flag
 * (the tokenArtMenuOpen bug shape: every new menu reintroduced the
 * hover-card-covers-the-menu defect one flag at a time).
 */
export type ContextMenuKind =
  | 'token-art'
  | 'trigger-order'
  | 'deck-card'
  | 'card'
  | 'phase'
  | 'player'
  | 'chat'
  | 'llm'

/**
 * Per-kind payloads, discriminated by `kind` so a menu component can only read
 * the shape its own opener registered (`s.menuPayload` narrows via the kind in
 * the same object). Callers that need no payload pass none.
 */
export interface CardMenuData {
  card: import('@/types').Card | import('@/types').Permanent | import('@/types').Commander
  /** Left-click dispatch (play/target/tap) — omitted for read-only zones. */
  onClick?: ((cardId: string | null) => void) | undefined
  /** Whether the tile rendered as playable (Play/Cast item enabled). */
  isPlayable?: boolean | undefined
}

export interface PhaseMenuData {
  /** The phase key from PhaseBar's PHASES table ('Upkeep', 'Main1', …). */
  phaseName: string
  /** Display label shown in the menu title. */
  label: string
  /** True while a replay is loaded — the phase menu is not offered. */
  readOnly?: boolean | undefined
}

export interface PlayerMenuData {
  playerName: string
  /** LLM sub-tab index for this seat, when the seat has an LLM debug entry. */
  llmPlayerIdx?: number | undefined
}

/**
 * The token whose art is being picked, plus its engine set.
 *
 * This payload is what lets TokenArtMenu be mounted ONCE (in GameView/ReplayView)
 * instead of by every CardThumb. The previous shape kept the payload in the
 * opener's local state and rendered the panel per tile with a GLOBAL
 * `isOpen` guard, so every tile on the board rendered its own menu — 34
 * overlapping panels, each titled with its own card's name (see

 */
export interface TokenArtMenuData {
  tokenName: string
  /** The token's own set from the engine — marks which art is the default. */
  engineSet?: string | undefined
}

/** The open menu: kind discriminator + its payload + opening coordinates. */
export type OpenContextMenu =
  | ({ kind: 'card'; x: number; y: number } & CardMenuData)
  | ({ kind: 'phase'; x: number; y: number } & PhaseMenuData)
  | ({ kind: 'player'; x: number; y: number } & PlayerMenuData)
  | ({ kind: 'token-art'; x: number; y: number } & TokenArtMenuData)
  | { kind: 'trigger-order' | 'deck-card' | 'chat' | 'llm'; x: number; y: number }

export interface ContextMenuState {
  /** The one open menu, if any. Null = no context menu anywhere. */
  openContextMenu: OpenContextMenu | null
  /** Phase-stop config as the client believes the server has it (see PhaseBar). */
  phaseStopConfig: import('@/types').PhaseStopConfig
  setPhaseStopConfig: (config: import('@/types').PhaseStopConfig) => void
  setOpenContextMenu: (menu: OpenContextMenu | null) => void
}

/**
 * The phase-stop config is write-only on the wire (update_phase_stops has no
 * read-back), so the client tracks the last config it sent. PhaseStopSettings
 * used to initialize from a hardcoded DEFAULT_STOPS constant, which silently
 * reverted every other toggle each time the dialog opened — this tracked copy
 * is the fix's data source.
 */
export const INITIAL_PHASE_STOP_CONFIG: import('@/types').PhaseStopConfig = {
  yourTurn: { upkeep: true, draw: true, main1: true, beforeCombat: true, endOfCombat: true, main2: true, endOfTurn: true },
  opponentTurn: { upkeep: true, draw: true, main1: true, beforeCombat: true, endOfCombat: true, main2: true, endOfTurn: true },
  stopOnDeclareBlockersWithZeroPermanents: true,
  stopOnDeclareBlockersWithAnyPermanents: true,
  stopOnAllMainPhases: true,
  stopOnAllEndPhases: true,
  stopOnStackNewObjects: true,
}

export const createContextMenuSlice: StateCreator<GameStore, [], [], ContextMenuState> = (set) => ({
  openContextMenu: null,
  phaseStopConfig: INITIAL_PHASE_STOP_CONFIG,

  setPhaseStopConfig: (config) => set({ phaseStopConfig: config }),

  setOpenContextMenu: (menu) => set({ openContextMenu: menu }),
})
