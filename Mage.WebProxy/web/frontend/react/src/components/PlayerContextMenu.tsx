import { useContextMenu, ContextMenuShell } from '@/hooks/useContextMenu'
import { useGameStore } from '@/store/gameStore'
import { requestViewDeck } from '@/store/slices/viewDeck'
import { resolveAllowHandRequests, selectHand, useAllowIntent } from '@/store/slices/handWatch'

/**
 * Right-click a player header (PlayerBoard) → quick navigation for that seat.
 *
 * Items:
 *  - View LLM reasoning — right rail → LLM tab, sub-tab bound to this seat's
 *    debug entry (setActiveRightTab + setActiveLLMPlayerIdx both exist). Only
 *    offered when the seat HAS an llmDebug entry — GameView computes the index
 *    when opening the menu, so a non-LLM seat never sees a dead item.
 *  - View deck — opens the read-only deck modal for that seat. Mirrors the
 *    desktop client's PlayAreaPanel.java:438-447 item: offered as "View deck (you)"
 *    for your own seat and "View deck (AI-N)" for a computer seat, and shown
 *    DISABLED as "View deck — restricted" for another human's seat (the engine
 *    refuses deck views of other humans — GameController.viewDeckOrSideboard logs
 *    "trying to cheat" — so the control says so instead of sending a doomed
 *    request).
 *  - View graveyard / View exile — the same setZonePopup payload the zone
 *    titles produce, from the header for reachability.
 *  - Copy player name.
 *  - Auto-answers — the three resets the desktop client keeps in its
 *    battlefield right-click menu (PlayAreaPanel.java:275-300, the
 *    "Auto-answers" submenu: replacement effects / triggered stack order /
 *    yes-no). PLACEMENT DEVIATION, deliberate: the desktop hangs those resets
 *    off the BATTLEFIELD (every permanent, right-click → Auto-answers), but the
 *    web client has no battlefield-level menu — the player header menu is the
 *    existing per-seat right-click surface, so the resets live here instead of
 *    inventing a fourth menu kind. They act on YOUR OWN memory regardless of
 *    which header was clicked (the engine keys these maps per player, and the
 *    session can only write its own), so the section is labelled "your" and the
 *    menu title stays the clicked seat's name.
 */
export function PlayerContextMenu() {
  const { isOpen, close, position } = useContextMenu('player')
  const openMenu = useGameStore((s) => s.openContextMenu)
  const gameState = useGameStore((s) => s.gameState)
  const setZonePopup = useGameStore((s) => s.setZonePopup)
  const setActiveRightTab = useGameStore((s) => s.setActiveRightTab)
  const setActiveLLMPlayerIdx = useGameStore((s) => s.setActiveLLMPlayerIdx)
  const autoAnswerReset = useGameStore((s) => s.autoAnswerReset)
  const triggerOrder = useGameStore((s) => s.triggerOrder)
  const requestHandPermission = useGameStore((s) => s.requestHandPermission)
  const revokeHandPermission = useGameStore((s) => s.revokeHandPermission)
  const allowHandRequests = useGameStore((s) => s.allowHandRequests)
  // The allow checkbox's value: what THIS browser last sent (the engine fires no
  // state push, so the serialized flag lags — see handWatch.ts's intent block).
  const allowIntent = useAllowIntent()

  const payload = isOpen && openMenu?.kind === 'player' ? openMenu : null
  if (!isOpen || !payload) return null

  const player = gameState?.players.find((p) => p.name === payload.playerName)
  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).catch(() => {
      useGameStore.getState().showError('Clipboard unavailable in this browser')
    })
  }

  const viewZone = (zone: 'graveyard' | 'exile') => {
    if (!player) return
    setZonePopup({
      zone,
      playerName: player.name,
      cards: zone === 'graveyard' ? player.graveyard : player.exile,
    })
    close()
  }

  // Desktop parity (PlayAreaPanel.java:436-448): the item is ALWAYS in the menu —
  // "View deck (me)" for yourself, "View deck (playerName)" for a computer seat, and
  // for another human's seat the text gets ", restricted" appended and the action is
  // never wired (the engine refuses deck views of other humans — GameController
  // .viewDeckOrSideboard logs "trying to cheat"). Same three shapes here, with the
  // restricted variant rendered DISABLED instead of hidden. `isHuman` rides the
  // serialized player (GameStateSerializer); an ABSENT flag is unknown (replay /
  // older proxy payload), so the item renders disabled rather than sending a request
  // the engine would refuse — Fail Fast on the safe side of the gate.
  const canViewDeck = player != null && (player.isMe || player.isHuman === false)
  const viewDeckLabel = !player
    ? 'View deck'
    : player.isMe
      ? 'View deck (you)'
      : player.isHuman === false
        ? `View deck (${player.name})`
        : `View deck (${player.name}), restricted`

  // ── Hand requests — desktop-verbatim wording ───────────────────────────────
  // The desktop client hangs these off a "Cards on hand (name)" SUBMENU of the
  // battlefield right-click (PlayAreaPanel.java:403-434); the web client's only
  // per-seat right-click surface is this player menu, so the same items live here
  // FLAT, with the "Cards on hand (…)" head as a section note instead of a nested
  // submenu (a deliberate placement deviation — same reasoning as the auto-answer block
  // below). Items, exactly as the desktop words them:
  //   * another seat   → "Request permission to see the hand cards"
  //   * your own seat  → checkbox "Allow hand requests from other users" (with the
  //                       desktop tooltip) + "Revoke all permission(s) to see your
  //                       hand cards"
  //   * a WATCHER      → "Request permission to see hand cards (name)"
  //                       (PlayAreaPanel.addPopupMenuWatcher:479-481)
  // The engine refuses a request for your own seat outright
  // (GameController.requestPermissionToSeeHandCards:759), so the item is only
  // rendered on a non-self seat — a spectator's session has no self seat at all,
  // which is why every seat offers the item there.
  const watchedHand = player != null
    ? (gameState?.watchedHands ?? []).find((h) => h.name === player.name)
    : undefined
  const requestHandLabel = gameState?.isSpectator && player
    ? `Request permission to see hand cards (${player.name})`
    : 'Request permission to see the hand cards'

  // The allow checkbox's resolved value: local intent wins until a push
  // arrives — the desktop's JCheckBoxMenuItem reads its stored preference for
  // exactly the same reason (the engine sends no view update for this toggle).
  const allowValue = player ? resolveAllowHandRequests(player.allowHandRequests, allowIntent) : false

  return (
    <ContextMenuShell position={position} onClose={close} width={230} height={320} ariaLabel={`Player menu — ${payload.playerName}`}>
      <div className="context-menu-title">{payload.playerName}</div>
      {payload.llmPlayerIdx != null && (
        <button
          type="button"
          className="context-menu-item"
          data-explain="player-ctx-llm"
          onClick={() => {
            setActiveRightTab('llm')
            setActiveLLMPlayerIdx(payload.llmPlayerIdx!)
            close()
          }}
        >
          🤖 View LLM reasoning
        </button>
      )}
      {/* ── Cards on hand — desktop's "Cards on hand (name)" submenu,
             flattened into this menu; ordering mirrors the desktop (hand items before
             View deck). Section head uses the desktop's own submenu title. ── */}
      {player && !player.isMe && (
        <>
          <div className="context-menu-note">Cards on hand ({gameState?.isSpectator ? player.name : 'them'})</div>
          {watchedHand ? (
            // Already granted: the desktop's "Switch Hand" container has the cards;
            // here the same content lives in the watched-hand viewer, and this item is
            // its always-available opener (the switch rail only appears with 2+ grants).
            <button
              type="button"
              className="context-menu-item"
              data-explain="player-ctx-view-granted-hand"
              onClick={() => { selectHand(player.name); close() }}
            >
              🖐 View {player.name}&apos;s hand (granted)
            </button>
          ) : (
            <button
              type="button"
              className="context-menu-item"
              data-explain="player-ctx-request-hand"
              onClick={() => { requestHandPermission(player.playerId!); close() }}
            >
              ✋ {requestHandLabel}
            </button>
          )}
        </>
      )}
      {player && player.isMe && (
        <>
          <div className="context-menu-note">Cards on hand (me)</div>
          <button
            type="button"
            className="context-menu-item"
            data-explain="player-ctx-allow-hand"
            title="Watchers or other players can request your hand cards once per game. Re-activate it to allow new requests."
            onClick={() => { allowHandRequests(!allowValue); close() }}
          >
            {allowValue ? '☑' : '☐'} Allow hand requests from other users
          </button>
          <button
            type="button"
            className="context-menu-item"
            data-explain="player-ctx-revoke-hand"
            title="Revoke already granted permission for all spectators to see your hand cards."
            onClick={() => { revokeHandPermission(); close() }}
          >
            🚫 Revoke all permission(s) to see your hand cards
          </button>
        </>
      )}
      {player && (
        <button
          type="button"
          className="context-menu-item"
          data-explain="player-ctx-view-deck"
          disabled={!canViewDeck}
          title={canViewDeck
            ? undefined
            : 'Only your own deck and computer opponents’ decks can be viewed in-game'}
          onClick={() => {
            if (!player.playerId || !canViewDeck) return
            requestViewDeck(player.playerId)
            close()
          }}
        >
          🗂 {viewDeckLabel}
        </button>
      )}
      {player && player.graveyard.length > 0 && (
        <button type="button" className="context-menu-item" data-explain="player-ctx-graveyard" onClick={() => viewZone('graveyard')}>
          ⚰ View graveyard ({player.graveyard.length})
        </button>
      )}
      {player && player.exile.length > 0 && (
        <button type="button" className="context-menu-item" data-explain="player-ctx-exile" onClick={() => viewZone('exile')}>
          ✨ View exile ({player.exile.length})
        </button>
      )}
      <hr className="context-menu-sep" />
      <button type="button" className="context-menu-item" data-explain="player-ctx-copy-name" onClick={() => { copy(payload.playerName); close() }}>
        Copy player name
      </button>
      {/* ── Auto-answers — the desktop's battlefield resets, placed here (see head).
             A spectator is rejected by the proxy (auto_answer is in PLAYER_ONLY_ACTIONS)
             and has no memory of its own, so the section is not offered to one. ── */}
      {!gameState?.isSpectator && (
        <>
          <hr className="context-menu-sep" />
          <div className="context-menu-note">Auto-answers (yours, this game)</div>
          <button
            type="button"
            className="context-menu-item"
            data-explain="player-ctx-reset-yesno"
            onClick={() => { autoAnswerReset('reset_all'); close() }}
          >
            ↺ Reset all YES/NO auto-answers
          </button>
          <button
            type="button"
            className="context-menu-item"
            data-explain="player-ctx-reset-trigger-order"
            onClick={() => { triggerOrder('reset_all', null, null); close() }}
          >
            ↺ Reset trigger-order auto-answers
          </button>
          <button
            type="button"
            className="context-menu-item"
            data-explain="player-ctx-reset-replacement"
            onClick={() => { autoAnswerReset('reset_replacement'); close() }}
          >
            ↺ Reset replacement-effect auto-answers
          </button>
        </>
      )}
    </ContextMenuShell>
  )
}
