import { create } from 'zustand'
import { createConnectionSlice } from './slices/connectionSlice'
import { createLobbySlice } from './slices/lobbySlice'
import { createGameplaySlice } from './slices/gameplaySlice'
import { createUISlice } from './slices/uiSlice'
import { createDeckSlice } from './slices/deckSlice'
import { createReplaySlice } from './slices/replaySlice'
import { createContextMenuSlice } from './slices/contextMenuSlice'
import type { GameStore } from './types'

/**
 * Main Zustand store for the XMage web client.
 * Combines all slices into a single store.
 *
 * Slice organization mirrors the Alpine data object in app.js but typed
 * and with proper separation of concerns:
 * - connection: WebSocket lifecycle, reconnection, resync
 * - lobby: table list, game setup, join/create
 * - gameplay: game state, action prompts, events, chat
 * - ui: popups, tabs, error toast, card preview
 * - replay: finished matches, replay files, read-only replay viewer
 */
export const useGameStore = create<GameStore>()((...args) => ({
  ...createConnectionSlice(...args),
  ...createLobbySlice(...args),
  ...createGameplaySlice(...args),
  ...createUISlice(...args),
  ...createDeckSlice(...args),
  ...createReplaySlice(...args),
  ...createContextMenuSlice(...args),
}))

// Expose store on window for dev-mode testing/debugging
if (import.meta.env.DEV) {
  window.__gameStore = useGameStore
}