import { useEffect, useRef } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectCurrentView, selectBoardState, selectSocketDown } from '@/store/selectors'
import { GameWebSocket } from '@/network/websocket'
import { handleMessage } from '@/network/messageHandlers'
import type { ServerMessage } from '@/types'
import ConnectOverlay from '@/components/ConnectOverlay'
import LobbyView from '@/components/LobbyView'
import GameView from '@/components/GameView'
import ReplayView from '@/components/ReplayView'
import { TournamentRunningView } from '@/components/TournamentRunningView'
import DeckEditorView from '@/components/deckeditor/DeckEditorView'
import ErrorToast from '@/components/ErrorToast'
import ConnStatusBanner from '@/components/ConnStatusBanner'
import CardPreview from '@/components/CardPreview'
import { ScreenFlash } from '@/components/ScreenFlash'
import DecisionPopup from '@/components/DecisionPopup'
import CardSelectPopup from '@/components/CardSelectPopup'
import { TargetingArrows } from '@/components/TargetingArrows'
import SideboardView from '@/components/SideboardView'
import ViewDeckModal from '@/components/ViewDeckModal'
import CardHintsModal from '@/components/CardHintsModal'
import { UserRequestDialog } from '@/components/UserRequestDialog'
import SettingsDialog from '@/components/SettingsDialog'
import { PANEL_W_EVENT } from '@/components/GameLayout'
import { installAudioUnlock } from '@/audio/dingSynth'
import { preloadAudioSounds } from '@/audio/audioManager'
import { HintsRoot } from '@/hints/HintsRoot'
import {
  applyCardSizing,
  applyPaneCardSizing,
  computeAutoFitCardWidth,
  paneCardSizing,
  BATTLEFIELD_SPLIT_EVENT,
  legacyResponsiveSizing,
  type AutoFitArgs,
  type CardSizing,
} from '@/utils/cardFit'
import { rootZoom } from '@/utils/viewport'
import { loadTokenArtPrefs } from '@/utils/tokenArt'

/** Panel width defaults — MUST mirror GameView.tsx's LEFT_DEFAULT / RIGHT_DEFAULT. */
const LEFT_COL_DEFAULT = 140
const RIGHT_COL_DEFAULT = 280
/** Guard against pathological localStorage content (same clamp GameView.loadWidth uses). */
const PANEL_W_MIN = 50
const PANEL_W_MAX = 800

function readPanelWidth(key: string, fallback: number): number {
  try {
    const raw = localStorage.getItem(key)
    if (raw != null) {
      const v = parseInt(raw, 10)
      if (Number.isFinite(v)) return Math.max(PANEL_W_MIN, Math.min(PANEL_W_MAX, v))
    }
  } catch { /* localStorage unavailable */ }
  return fallback
}

/** Viewport in ROOT CSS px — the space the layout math lives in (see utils/viewport.ts). */
function rootViewport(): { viewportW: number; viewportH: number } {
  const zoom = rootZoom()
  return { viewportW: window.innerWidth / zoom, viewportH: window.innerHeight / zoom }
}

/**
 * The complete auto-fit sizing: fit width from the live board, then intersect with
 * the user's cardScale preference (the preference is a CAP, not an override — the
 * board must fit either way), then derive every dependent dimension.
 */
function autoFitSizing(args: AutoFitArgs): CardSizing {
  const fitWidth = computeAutoFitCardWidth(args)
  const cardWidth = Math.max(40, Math.min(200, Math.min(fitWidth, Math.round(200 * (args.cardScale ?? 1)))))
  const cardHeight = Math.round(cardWidth * 1.4)
  const smallWidth = Math.max(30, Math.floor(cardWidth * 0.7))
  return {
    cardWidth,
    cardHeight,
    smallWidth,
    smallHeight: Math.round(smallWidth * 1.4),
    // Overlap keeps the historical +18px slack over one card height (stacked rows
    // must grow taller than their top card, never clip it).
    overlap: cardHeight + 18,
  }
}

/**
 * Top-level App component.
 * Manages WebSocket lifecycle and routes between the three views:
 * connect → lobby → game (based on store state).
 */
export default function App() {
  const view = useGameStore(selectCurrentView)
  const deckEditorOpen = useGameStore((s) => s.editorOpen)
  const setWebSocket = useGameStore((s) => s.setWebSocket)
  const setConnStatus = useGameStore((s) => s.setConnStatus)
  const errorToastVisible = useGameStore((s) => s.errorToastVisible)
  const errorMessage = useGameStore((s) => s.errorMessage)
  const errorDetails = useGameStore((s) => s.errorDetails)
  const connStatus = useGameStore((s) => s.connStatus)
  // The lobby's red socket-down banner owns the down states where it renders —
  // the same predicate LobbyView gates the red banner and its disabled buttons on.
  const socketDown = useGameStore(selectSocketDown)
  const theme = useGameStore((s) => s.theme)
  const fontScale = useGameStore((s) => s.fontScale)
  const cardScale = useGameStore((s) => s.cardScale)
  const uiScale = useGameStore((s) => s.uiScale)
  const highlightPlayable = useGameStore((s) => s.highlightPlayable)
  const highlightCombat = useGameStore((s) => s.highlightCombat)
  const highlightTarget = useGameStore((s) => s.highlightTarget)
  const highlightManaTappable = useGameStore((s) => s.highlightManaTappable)
  const highlightActiveTurn = useGameStore((s) => s.highlightActiveTurn)
  // useRef survives React StrictMode's double-effect invocation in dev — a
  // useState(false) guard does not, because both effect invocations observe
  // state=false before the setter commits, and the double-run constructs two
  // GameWebSocket instances.
  const wsRef = useRef<GameWebSocket | null>(null)

  // Priority ding: unlock the shared AudioContext on the FIRST user interaction
  // (browser autoplay policy — a context created before any gesture stays
  // suspended). Idempotent; one-time listeners, installed once per page load.
  // Event sounds: the WAV subset starts loading in parallel — preload is
  // NOT gated by the gesture (only play() is), so the first cue after the first
  // click is already buffered.
  useEffect(() => {
    installAudioUnlock()
    preloadAudioSounds()
  }, [])

  // Token-art preferences are a module-level store (not Zustand) because CardThumb
  // reads them for every tile; load the persisted values once at startup.
  useEffect(() => {
    loadTokenArtPrefs()
  }, [])

  // Initialize the WebSocket manager once per page load.
  useEffect(() => {
    if (wsRef.current) return

    // Scheme-relative WebSocket URL — works behind both plain HTTP (dev) and an
    // HTTPS reverse proxy (production). Hardcoded ws:// would break mixed-content
    // rules once the site is served over TLS.
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const wsUrl = `${scheme}://${window.location.host}/ws`

    const gameWs = new GameWebSocket({
      url: wsUrl,
      onMessage: (msg: ServerMessage) => {
        handleMessage(msg, useGameStore.getState())
      },
      onStatusChange: (status) => {
        if (status === 'connected') {
          setConnStatus('')
        } else if (status === 'disconnected') {
          const store = useGameStore.getState()
          if (store.connected) {
            setConnStatus('reconnecting')
          }
        }
      },
      onError: () => {},
    })

    wsRef.current = gameWs
    setWebSocket(gameWs)
  }, [setWebSocket, setConnStatus])

  // Apply theme + font scale + whole-UI scale + highlight colors to document root
  useEffect(() => {
    const root = document.documentElement
    root.setAttribute('data-theme', theme)
    root.style.setProperty('--font-scale', String(fontScale))
    // --ui-scale: structural scale (rail widths, header/phase paddings, button
    // paddings) — same pattern as --font-scale. The GameView rail constants
    // mirror this multiplier so dragged widths land inside the same range.
    root.style.setProperty('--ui-scale', String(uiScale))
    // Highlight colors as rgba() CSS variables for use in game-renderer.css
    const rgb = (c: { r: number; g: number; b: number }) => `${c.r}, ${c.g}, ${c.b}`
    root.style.setProperty('--hl-playable', rgb(highlightPlayable))
    root.style.setProperty('--hl-combat', rgb(highlightCombat))
    root.style.setProperty('--hl-target', rgb(highlightTarget))
    root.style.setProperty('--hl-mana', rgb(highlightManaTappable))
    root.style.setProperty('--hl-active-turn', rgb(highlightActiveTurn))
  }, [theme, fontScale, uiScale, highlightPlayable, highlightCombat, highlightTarget, highlightManaTappable, highlightActiveTurn])

  // Responsive sizing — AUTO-FIT by default: compute the effective battlefield/hand
  // card size from the live viewport, the real board contents, and the actual layout
  // chrome (live rail widths, uiScale) so boards + hands always fit without
  // scrolling. With the toggle OFF, the historical viewport heuristic runs instead.
  // Recompute triggers:
  //   - store changes (board contents, scales, toggle) via the deps below
  //   - resize + rail-width drag, via the PANEL_W_EVENT DOM event GameView dispatches
  //     (per-mousemove drag updates must not re-render the tree, hence no store state)
  //
  // The board the visible view shows — a live game OR a replay frame. Reading the
  // raw `gameState` here meant a replay always took the LEGACY sizing branch
  // (autoArgs null every recompute), so replay cards were sized by the pre-2026-09-19
  // viewport heuristic that cannot see rail widths, board contents or the split.
  const boardState = useGameStore(selectBoardState)
  const autoFitBattlefield = useGameStore((s) => s.autoFitBattlefield)
  const autoFitSignature =
    boardState === null
      ? 'no-game'
      : boardState.players
          .map((p) => `${p.handCount ?? p.hand?.length ?? 0}:${(p.battlefield ?? []).length}`)
          .join('|')
  useEffect(() => {
    // Live rail widths for the fit math. Source of truth: the drag event's detail
    // while a drag is running (localStorage is only written on mouseup), else the
    // persisted base widths × uiScale.
    const live = { left: null as number | null, right: null as number | null, split: null as number | null }
    function panelWidths(uiScale: number): { leftColW: number; rightColW: number } {
      return {
        leftColW: live.left ?? readPanelWidth('xmage-left-col-w', LEFT_COL_DEFAULT) * uiScale,
        rightColW: live.right ?? readPanelWidth('xmage-right-col-w', RIGHT_COL_DEFAULT) * uiScale,
      }
    }
    function onPanelW(e: Event) {
      const detail = (e as CustomEvent).detail as { left?: number; right?: number; scale?: number } | undefined
      if (detail && typeof detail === 'object') {
        if (typeof detail.left === 'number') live.left = detail.left
        if (typeof detail.right === 'number') live.right = detail.right
        if (detail.left === undefined && detail.right === undefined) {
          // mouseup: persistence done — clear live overrides, fall back to storage
          live.left = null
          live.right = null
        }
      } else {
        // mouseup (plain Event): persistence done — fall back to storage.
        live.left = null
        live.right = null
      }
      recompute()
    }
    function onSplit(e: Event) {
      const detail = (e as CustomEvent).detail as { opponentPct?: number } | undefined
      if (detail && typeof detail.opponentPct === 'number') live.split = detail.opponentPct
      else live.split = null // mouseup (plain Event): fall back to storage
      recompute()
    }
    function recompute() {
      const state = useGameStore.getState()
      // The board the CURRENT view shows. Re-read from the store (not the render
      // closure) because recompute also runs from window events long after render.
      const board = selectBoardState(state)
      const { viewportW, viewportH } = rootViewport()
      const { leftColW, rightColW } = panelWidths(state.uiScale)
      // Live split: drag detail while dragging, else the persisted percentage
      // (null when never set — computeAutoFitCardWidth then assumes equal halves).
      // Applies to 1v1 (panes) and 4-player games (grid rows); every other
      // player count ignores it (cardFit.battlefieldSplitApplies).
      const opponentSplitPct =
        live.split ??
        (() => {
          try {
            const raw = localStorage.getItem('xmage-battlefield-split')
            if (raw != null) {
              const n = parseFloat(raw)
              if (Number.isFinite(n)) return n
            }
          } catch { /* ignore */ }
          return null
        })()
      // Auto-fit reads whichever board the visible view shows — a live game or a
      // replay frame (selectBoardState). Gating on raw `gameState` meant replays
      // never auto-fit.
      const autoArgs: AutoFitArgs | null =
        state.autoFitBattlefield && board
          ? {
              viewportW,
              viewportH,
              uiScale: state.uiScale,
              fontScale: state.fontScale,
              cardScale: state.cardScale,
              leftColW,
              rightColW,
              players: board.players,
              opponentSplitPct: opponentSplitPct ?? undefined,
            }
          : null
      const sizing =
        autoArgs != null
          ? autoFitSizing(autoArgs)
          : legacyResponsiveSizing(viewportW, viewportH, state.cardScale)
      applyCardSizing(sizing)
      // Independent battlefield scaling: in 1v1 each pane gets its OWN
      // card size from ITS pane height, and in 4-player games each grid ROW
      // gets its own size from the ROW height — dragging the divider grows one
      // half's cards as the other half's shrink. Cleared whenever the split
      // does not apply (other player counts / split never set / auto-fit off).
      applyPaneCardSizing(autoArgs != null ? paneCardSizing(autoArgs) : null)
    }

    recompute()
    window.addEventListener('resize', recompute)
    window.addEventListener(PANEL_W_EVENT, onPanelW)
    window.addEventListener(BATTLEFIELD_SPLIT_EVENT, onSplit)
    return () => {
      window.removeEventListener('resize', recompute)
      window.removeEventListener(PANEL_W_EVENT, onPanelW)
      window.removeEventListener(BATTLEFIELD_SPLIT_EVENT, onSplit)
    }
  }, [autoFitBattlefield, uiScale, fontScale, cardScale, autoFitSignature])


  return (
    <>
      {view === 'connect' && <ConnectOverlay />}
      {view === 'lobby' && (deckEditorOpen ? <DeckEditorView /> : <LobbyView />)}
      {view === 'tournament' && <TournamentRunningView />}
      {view === 'game' && <GameView />}
      {view === 'replay' && <ReplayView />}
      {/* Banner dedupe: while the lobby's red
          socket-down banner is active it already says "reconnecting" (the red
          banner covers !connected, 'reconnecting' AND 'connection_lost' via
          selectSocketDown) — the legacy yellow ConnStatusBanner rendered ABOVE
          it and repeated the same promise (the dual-banner overlap).
          One owner per state: the red banner wins where it renders; the yellow
          banner keeps every state the red banner does not cover (the game and
          replay views, the deck editor, and 'resyncing'). */}
      {connStatus && !(view === 'lobby' && !deckEditorOpen && socketDown) && (
        <ConnStatusBanner status={connStatus} />
      )}
      {errorToastVisible && <ErrorToast message={errorMessage} details={errorDetails} />}
      <CardPreview />
      <ScreenFlash />
      {/* Action-prompt surfaces are suppressed in the replay viewer: they are
          what turn a prompt into a game request, and a replay is read-only.
          Their own prompt state is never populated during a replay (no
          action_prompt messages arrive), so this is belt-and-braces. */}
      {view !== 'replay' && <DecisionPopup />}
      {view !== 'replay' && <CardSelectPopup />}
      {/* Targeting arrows (spell/ability target prompts) — viewport-wide SVG,
          pointer-events: none; renders nothing without an open target prompt. */}
      {view !== 'replay' && <TargetingArrows />}
      <SideboardView />
      {/* Read-only "View deck" modal (deck answered by the proxy's
          view_limited_deck message). Outside the view branches like SideboardView:
          the request and the answer both belong to a live game, and the modal
          renders nothing when no answer is held. */}
      <ViewDeckModal />
      {/* Card hints helper modal. Outside the view branches like the deck
          modal: the open flag is flipped by the game header's "Hints" button
          and the modal reads whatever board is on screen (selectBoardState). */}
      <CardHintsModal />
      <UserRequestDialog />
      <SettingsDialog />
      {/* Onboarding tips for a player's first games + the shared button-explanation
          tooltip. Both are new files (src/hints/) — this is the only line App.tsx
          needs, and it is deliberately outside every view branch so the hints can
          gate themselves on the view (see hints.ts, VIEW SCOPE). */}
      <HintsRoot />
    </>
  )
}