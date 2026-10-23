import type { ServerMessage } from '@/types'
import type { GameStore } from '@/store/types'
import { applyDelta } from './deltaApplicator'
import { stripServerHtml } from '@/utils/textFormat'
import { parseUsersListPayload } from '@/utils/lobbyChat'
import { handleDeckEditorResponse } from '@/store/slices/deckSlice'
import { forgetGameKey, markGameStart, noteRejoinOffered, resetPromptLifecycle } from '@/store/slices/gameplaySlice'
import { handleTokenVersionsResult, replaceSharedTokenArts } from '@/utils/tokenArt'
import { receiveViewDeck } from '@/store/slices/viewDeck'
import { handleLlmPrompt } from '@/utils/llmBridgeClient'
import { onChatEvent, onGameOverAnnounced, onGameStartAnnounced } from '@/audio/audioTriggers'
import { WINNER_GLYPH } from '@/utils/endGame'

/**
 * Message handler — dispatches incoming server messages to the Zustand store.
 *
 * Ported from app.js handleMessage() switch statement.
 * Called by GameWebSocket.onMessage.
 */

// Re-export applyDelta for store use
export { applyDelta }

/**
 * Process a server message and apply side effects to the store.
 * This is a pure function of (message, store) — no React, no DOM.
 */
export function handleMessage(
  msg: ServerMessage,
  store: GameStore
): void {
  switch (msg.type) {
    case 'connected':
      store.setConnected(msg.username || 'webplayer')
      // this fork: gate the UI on what THIS server supports. Only
      // downgrade from the pre-capability default when the message carries
      // capabilities (older proxies send none).
      if (msg.capabilities) {
        store.setCapabilities(msg.capabilities)
        // Argentum-P1: once we know the server supports P1 seats, fetch the 55
        // training decks so the seat picker can offer them (select-only).
        store.requestP1Decks()
      }
      // Fix 1 (auth-reconnect loop): the connect actually succeeded — this is
      // the one place the WS manager's backoff counter may reset (websocket.ts
      // no longer resets on socket-open, which made auth-reject closes loop at
      // ~1.2s forever).
      store.noteConnectAccepted()
      store.requestTables()
      break

    case 'connect_error':
      store.setConnectError(msg.message || 'Failed to connect to XMage server')
      // Re-enable the form — nothing else resets `connecting`, so a rejected
      // connect (bad token on auto-reconnect, wrong password, server down)
      // left the overlay stuck on "Connecting..." forever.
      store.setConnecting(false)
      break

    case 'table_list':
      store.setTables(msg.tables || [])
      break

    case 'game_init': {
      const state = msg.state
      if (state && state.stateSeq !== undefined) {
        store.onStateVersionReceived(state.stateSeq)
      }
      // A game_init rebuilds the whole game state (first join, rejoin, resync):
      // any answer recorded against the pre-rebuild prompt must no longer block
      // the controls the F3 rail now offers while priority is ours.
      resetPromptLifecycle()
      store.setGameState(state)
      // Set inGame=true — this covers both player (start_game already set it)
      // and spectator (game_init arrives directly after watchGame/watchTable).
      if (!store.inGame) {
        store.setInGame(true)
      }
      // Initialize player color map from player names (once per game).
      // Always rebuild on game_init — a new game may have different players,
      // and stale colors from the previous game would mislabel them.
      if (state && state.players) {
        store.setPlayerColorMap(
          state.players.reduce((acc: Record<string, number>, p, i) => {
            acc[p.name] = i % 4
            return acc
          }, {})
        )
      }
      break
    }

    case 'game_update': {
      const state = msg.state
      if (state && state.stateSeq !== undefined) {
        store.onStateVersionReceived(state.stateSeq)
      }
      store.setGameState(state)
      break
    }

    case 'game_delta': {
      if (msg.stateSeq !== undefined && msg.stateSeq !== null) {
        store.onStateVersionReceived(msg.stateSeq)
      }
      const current = store.gameState
      if (current && msg.delta) {
        const merged = applyDelta(current, msg.delta)
        store.setGameState(merged)
      } else {
        // No cached state — request a full resync
        console.warn('[WS] game_delta received but no cached state — requesting resync')
        store.requestResync()
      }
      break
    }

    case 'resync_complete':
      store.clearResyncFlag()
      break

    case 'action_prompt':
      store.setActionPrompt(msg)
      break

    case 'table_joined':
      store.setTableJoined(msg.tableId, msg.tableName)
      break

    case 'table_left':
      // The proxy confirmed our leave_table — drop the seat state so the
      // waiting panel goes away and the tables grid returns. Same reset as
      // game_over / logout (joinWaiting/pendingJoinTableId/myTableId/
      // isTableOwner/mySeatNames), centralized in clearSeatState. Nothing is
      // cleared optimistically on click — this frame is the only confirmation,
      // so a refused leave (match already started) leaves the panel intact.
      store.clearSeatState()
      store.requestTables()
      break

    case 'table_created':
      store.setTableCreated(msg.tableId, msg.tableName)
      break

    case 'start_game': {
      // F5 (2026-09-14 F-A14): a NEW game in one session must start with an
      // empty game log. The previous game's result otherwise sits above the new
      // game's start, so the log contradicts itself in one scroll. A rejoin
      // re-announces the SAME gameId and keeps the log (markGameStart returns
      // false) — that log is the only history the client has for a live game.
      const isNewGame = markGameStart(msg.gameId)
      // Audio: the desktop's playYourGameStarted fires when OUR game
      // actually starts. Only a NEW game sounds — a rejoin re-announcement of
      // the same gameId is not a start (see audioTriggers.onGameStartAnnounced).
      onGameStartAnnounced(store, isNewGame)
      if (isNewGame) {
        // A new game must not inherit the previous game's prompt: without this a
        // prompt left over from the old game stays clickable into the new one.
        // (A rejoin of the same game keeps its prompt — the server may still be
        // waiting on it.)
        store.hideActionPrompt()
        store.clearGameEnd()
        // A new game also invalidates a between-games sideboard modal: the server
        // only starts a game after EVERY player submitted (or the ~180s sideboard
        // timer auto-submitted), so a modal still on screen here is stale (late
        // manual submit dropped server-side: TableController.submitDeck returns
        // false with only a debug log when the table left SIDEBOARDING — found
        // live 2026-09-18 during sideboarding).
        store.setSideboardPrompt(null)
      }
      store.setInGame(true)
      break
    }

    case 'game_over': {
      const goMsg = msg as import('@/types').GameOverMessage
      store.hideActionPrompt()
      // The game this tab was following is over — forget its stored id
      // (gameplaySlice). A rejoin re-announcement can no longer arrive for it,
      // and the next game is a new UUID either way; forgetting keeps the
      // sessionStorage entry bounded to the live game (the test change,
      // 2026-09-27: the stored key must not outlive the game it names).
      forgetGameKey()
      // A match/round advancing also retires a between-games sideboard modal.
      // Normal flow is safe: game_over arrives BEFORE the next sideboard_prompt
      // (game end → GAME_OVER → sideboarding → SIDEBOARD callback). Without this,
      // a modal left open when the MATCH ends (opponent quits during sideboarding
      // — no next game, so start_game never fires) lingers over the game-end
      // dialog while the next match advances.
      store.setSideboardPrompt(null)
      // A `complete:false` game_over is a PARTIAL payload (the server's GAME_OVER callback
      // carries only a message string — no hasWon/score). Applying it unconditionally used
      // to overwrite the rich END_GAME_INFO summary that arrives moments earlier, showing
      // "💀 Defeat" to the player who WON, with a Java toString as the body text.
      // Fill gaps only; never clobber a result we already have.
      const isPartial = goMsg.complete === false
      const existing = store.gameEnd
      // Duration windows ride only the rich payload (the proxy puts them there);
      // pass them through so the game-end dialog's stats section survives a later
      // partial game_over (gameEnd is REPLACED by setGameOver, not merged).
      const durations = {
        gameStartMs: goMsg.gameStartMs,
        gameEndMs: goMsg.gameEndMs,
        matchStartMs: goMsg.matchStartMs,
        matchEndMs: goMsg.matchEndMs,
      }
      if (isPartial) {
        if (existing) {
          // Keep the authoritative summary; only take the text if we have none.
          if (!existing.result && goMsg.result) {
            // Preserve existing's duration windows too (same source as the other
            // preserved fields) — a partial never carries them, and setGameOver
            // REPLACES gameEnd rather than merging.
            store.setGameOver(stripServerHtml(goMsg.result), {
              hasWon: existing.hasWon,
              matchInfo: existing.matchInfo,
              additionalInfo: existing.additionalInfo,
              matchScore: existing.matchScore,
              players: existing.players,
              gameStartMs: existing.gameStartMs,
              gameEndMs: existing.gameEndMs,
              matchStartMs: existing.matchStartMs,
              matchEndMs: existing.matchEndMs,
              // A partial never carries the reason; keep the rich one (a partial
              // REPLACES gameEnd rather than merging).
              endReason: existing.endReason,
            })
          }
          break
        }
        // No summary yet — record the partial so the modal at least names the outcome;
        // a later rich payload will supersede it.
        store.setGameOver(stripServerHtml(goMsg.result), {
          hasWon: goMsg.hasWon,
          matchInfo: goMsg.matchInfo ? stripServerHtml(goMsg.matchInfo) : undefined,
          additionalInfo: goMsg.additionalInfo ? stripServerHtml(goMsg.additionalInfo) : undefined,
          matchScore: goMsg.matchScore ? stripServerHtml(goMsg.matchScore) : undefined,
          players: goMsg.players,
          endReason: goMsg.endReason,
          ...durations,
        })
        break
      }
      store.setGameOver(stripServerHtml(goMsg.result), {
        hasWon: goMsg.hasWon,
        matchInfo: goMsg.matchInfo ? stripServerHtml(goMsg.matchInfo) : undefined,
        additionalInfo: goMsg.additionalInfo ? stripServerHtml(goMsg.additionalInfo) : undefined,
        matchScore: goMsg.matchScore ? stripServerHtml(goMsg.matchScore) : undefined,
        players: goMsg.players,
        // Authoritative end reason for the viewer's own seat (rich payload only;
        // absent for a watcher / older server, in which case the dialog falls back
        // to the 2-seat fatal-log-line inference). Read by GameEndDialog.
        endReason: goMsg.endReason,
        ...durations,
      })
      // Audio: the authoritative end-of-game payload decides the win/loss
      // cue (desktop GameEndDialog.java:82-84). A payload with no hasWon has no
      // verdict to sound — stay silent rather than guess.
      if (goMsg.hasWon !== undefined) {
        onGameOverAnnounced(store, goMsg.hasWon)
      }
      // Clear lobby join state — without this, after concede the lobby shows a
      // phantom "Joined table — waiting for match to start..." banner even
      // though the match is over.
      store.setJoinWaiting(false)
      store.setPendingJoinTableId(null)
      // The own-seat names belonged to that table; drop them with the rest
      // of the seat state so a later table cannot match a stale name.
      store.setMySeatNames([])
      break
    }

    case 'event':
      // Preserve the EXISTING game-surface behavior exactly (RightRail game log +
      // chat tab), then ADDITIVELY copy the line into the lobby Talk/System tabs
      // by its raw server messageType. Two independent sinks, no double-render.
      store.appendEvent(msg.event)
      store.appendLobbyRoutedEvent(msg.event)
      // Audio: an INCOMING whisper rings (chatType 'whisper_from' only —
      // the server sets SoundToPlay.PlayerWhispered on the receiving side; see
      // audioTriggers.onChatEvent). Live channel only: history_backfill does
      // NOT route here, so a rejoin replay of old chat never rings.
      onChatEvent(store, msg.event)
      break

    case 'users_list': {
      // `get_room_users` reply — online users + MOTD (see UsersListMessage).
      // Fail fast, visibly: the payload goes through the strict parser BEFORE it
      // can touch the store, so a malformed reply surfaces as a toast instead of
      // a silently broken users panel (same pattern as history_backfill).
      try {
        const users = parseUsersListPayload(msg)
        store.setUsersList(users, msg.motd ?? [])
      } catch (e) {
        const errorText = e instanceof Error ? e.message : String(e)
        console.error('[users_list] invalid payload:', e, 'msg:', msg)
        store.showError(`Users list error: ${errorText}`)
      }
      break
    }

    case 'history_backfill':
      // Proxy replay of the game-log/chat events already sent for this game,
      // delivered right before the rejoin's `game_init` (see
      // gameplaySlice.mergeHistory). This is what keeps the panels populated
      // across a reconnect or page refresh instead of falling back to the join
      // banner.
      try {
        store.mergeHistory(msg)
      } catch (e) {
        // Fail Fast, surfaced (same pattern as setActionPrompt): a malformed
        // backfill must not be silently skipped — the user sees the reason and the
        // panels keep whatever they had.
        const errorText = e instanceof Error ? e.message : String(e)
        console.error('[history_backfill] merge error:', e, 'msg:', msg)
        store.showError(`Game history replay error: ${errorText}`)
      }
      break

    case 'error': {
      console.error('Proxy error:', msg.message)
      // A proxy may attach a `details` stack to any error frame; render it
      // in the toast's collapsible section (message-only when absent).
      const details = msg.details ? stripServerHtml(msg.details) : ''
      if (details) store.showErrorWithDetails(stripServerHtml(msg.message || 'Unknown error'), details)
      else store.showError(stripServerHtml(msg.message || 'Unknown error'))
      // The fallback: a proxy OLDER
      // than the typed-auth-reject fix answers an unauthenticated `fetch_models`
      // with THIS generic error, and `modelsLoading` used to stick forever —
      // the spinner is only cleared by models_list/models_error. resolveFetchError
      // clears it (and surfaces the text in the setup dialog) ONLY when a proxy
      // fetch was actually pending; an unrelated error is left untouched, and a
      // browser-direct fetch in flight is never disturbed.
      store.resolveFetchError(stripServerHtml(msg.message || 'Unknown error'))
      // A submit_deck the server REJECTED arrives as a proxy `error` from
      // WebProxySession.handleSubmitDeck: it sends `error` when
      // session.submitDeck returns false (too late — table left SIDEBOARDING)
      // or on malformed/foreign-table deck payloads. The server's own
      // SHOW_USERMESSAGE for an invalid deck is forwarded as `show_usermessage`
      // (also handled below), so both rejection flavors re-enable the modal.
      // The SideboardView must drop its "Submitted — waiting for other
      // players…" state, or the player is locked out of retrying while the
      // timer runs (found live 2026-09-18 during sideboarding).
      store.sideboardSubmitRejected()
      // A REFUSED replay_load (e.g. "Cannot load a replay while in a game — stop
      // watching/playing first") lands here too. The request never reaches the
      // file, so no replay_game/replay_init follows and the optimistic load
      // notice would sit on the clicked row forever. Clear it so the refusal
      // reads as an error, not as an eternal "⏳ loading…"

      store.replayLoadFailed()
      break
    }

    case 'show_usermessage':
      // User-facing message from the server (e.g. deck validation feedback —
      // TableController.submitDeck sends SHOW_USERMESSAGE + returns false for a
      // deck below the format minimum). Treat it as a submit rejection too:
      // SideboardView must drop its "Submitted" state so the player can fix the
      // deck and resubmit before the timer expires.
      store.showError(stripServerHtml(msg.message || ''))
      store.sideboardSubmitRejected()
      break

    // ── Account registration ──
    // The proxy answers exactly one of these per `register` request; both carry
    // the XMage server's own text when it produced one. The register dialog is on
    // the connect overlay (no game, no lobby), so the answer is held in the store
    // and rendered inline there — NOT pushed through showError, whose toast is
    // owned by the connected/lobby surface.
    case 'register_success':
      store.setRegisterResult({ ok: true, message: stripServerHtml(msg.message || 'Registration succeeded') })
      break

    case 'register_error':
      store.setRegisterResult({ ok: false, message: stripServerHtml(msg.message || 'Registration failed') })
      break

    case 'server_message':
      store.showError(stripServerHtml(msg.message || ''))
      break

    case 'models_list':
      store.setAvailableModels(msg.models || [])
      // Record the endpoint's thinking-shape verdict so
      // the seat form can warn next to the Thinking Enabled checkbox. Older
      // proxies send no classification — absent fields must not wipe the map.
      if (msg.endpoint !== undefined && msg.ollamaClassified !== undefined) {
        store.setThinkingEndpointClass(msg.endpoint, msg.ollamaClassified)
      }
      break

    case 'p1_decks':
      // Argentum-P1 training deck pool (offering surface). Only an LLMage proxy
      // sends this, and only after a capabilities.p1 connect — a crafted frame on
      // a stock server is ignored because the P1 seat type is not offered without
      // the capability (PlayerSlotRow), and the server is authoritative regardless.
      store.setP1Decks(msg.decks || [], msg.default || '')
      break

    case 'llm_prompt':
      // Client-key bridge: the server asks THIS
      // browser to run the provider call with the user's locally-stored key.
      // Fire-and-forget: the bridge client sends llm_decision/llm_error back
      // on its own; a lost socket means the server times out the exchange.
      //
      // this fork: an LLMage server only sends llm_prompt for LLM
      // seats, which cannot exist when capabilities.llm is false — but a
      // desynced/craft message must not reach the provider bridge. Dropping
      // here (with a log) is safe: the exchange times out server-side.
      // STOCK builds (__LLMAGE__ === false) have no provider bridge at all —
      // the whole case compiles out (verified: no llm_prompt string in a
      // stock bundle, package-stock-desktop.sh grep gate).
      {
        if (__LLMAGE__ === false) {
          break
        }
        if (!store.capabilities.llm) {
          console.error('llm_prompt received but the connected server has no LLM capability — dropped')
          break
        }
        const { ws } = store
        if (ws) {
          handleLlmPrompt(msg, (m) => ws.send(m))
        } else {
          console.error('llm_prompt received with no live WebSocket — exchange will time out server-side')
        }
      }
      break

    case 'models_error':
      store.setModelsError(msg.message)
      break

    case 'personalities_error':
      // The proxy's unauthenticated-reject path for the personalities verb now sends
      // THIS typed frame instead of the generic `error` (symmetric with
      // models_error). There is no personalities spinner, so the consumer only
      // clears the pending marker and surfaces the text — an older frontend
      // without this case logs it as unknown and ignores it, exactly as it
      // ignored the generic error before.
      console.error('Personalities fetch error:', msg.message)
      store.showError(stripServerHtml(msg.message || 'Failed to load personalities'))
      store.clearPendingPersonalities()
      break

    case 'llm_setup_warning':
      // Setup-time thinking-shape warning from the proxy
      // (seat config saved with thinking enabled on a strict-classified
      // endpoint). Non-fatal — displayed in the seat form until cleared.
      store.setLlmSetupWarning({ seat: msg.seat, message: msg.message })
      break

    case 'personalities_list':
      store.setAvailablePersonalities(msg.personalities || [])
      // The typed reply for the fetch_personalities verb — consume its
      // pending marker (see lobbySlice.clearPendingPersonalities). It never
      // touches a pending 'models' marker, so the dialog-open pair (both verbs in
      // one tick) keeps its spinner gate intact.
      store.clearPendingPersonalities()
      break

    case 'tournament_types':
      store.setTournamentTypes({
        tournamentTypes: msg.tournamentTypes || [],
        gameTypes: msg.gameTypes || [],
        deckTypes: msg.deckTypes || [],
      })
      break

    case 'tournament_created':
      store.setTournamentCreated(msg.tableId, msg.tableName)
      break

    case 'tournament_joined':
      store.setJoinWaiting(true)
      store.setShowSetup(false)
      break

    case 'show_tournament':
      // Tournament has started — show the running view with standings + matches
      store.setShowSetup(false)
      store.hideTournamentSetup()
      store.setJoinWaiting(false)
      console.log('Tournament started:', msg.tournamentId, msg.tableId)
      if (msg.tournament) {
        store.setTournamentData(msg.tournament, msg.tournamentId)
        store.setShowTournamentRunning(true)
      } else {
        store.setJoinWaiting(true)
      }
      break

    case 'tournament_update':
      // Server pushed updated tournament data (round changes, game results, etc.)
      if (msg.tournament) {
        store.setTournamentData(msg.tournament, msg.tournamentId)
      }
      break

    case 'tournament_over': {
      // Tournament finished — surface the winner via the error toast + clear tournament view.
      const winnerText = msg.winnerName
        ? `${WINNER_GLYPH} ${msg.winnerName} wins the tournament!`
        : 'Tournament finished.'
      store.showError(winnerText)
      store.setShowTournamentRunning(false)
      break
    }

    case 'rejoin_game': {
      // Server detected an in-progress game where the user is already seated.
      // Send the rejoin_game command back to trigger joinGame(gameId) on the
      // server, which re-attaches us to the game and starts receiving state.
      //
      // Record the id BEFORE the rejoin's `start_game` arrives: the rejoin
      // re-announces the game with `start_game`, and a page with no memory of
      // the id (a reloaded tab whose storage is empty, a freshly opened second
      // tab) would otherwise treat it as a NEW game — replaying the game-start
      // cue and resetting the game log.
      noteRejoinOffered(msg.gameId || '')
      const { ws } = store
      if (ws) {
        console.log('Rejoining game:', msg.gameId)
        ws.send({ type: 'rejoin_game', gameId: msg.gameId })
      }
      break
    }

    case 'view_sideboard':
      // Server says sideboard is available — data already in player state.
      // If we have sideboard cards, auto-open the zone popup for my player.
      {
        const myPlayer = store.gameState?.players.find(p => p.isMe)
        if (myPlayer?.sideboard && myPlayer.sideboard.length > 0) {
          store.setZonePopup({ zone: 'sideboard', playerName: myPlayer.name, cards: myPlayer.sideboard })
        }
      }
      break

    case 'sideboard_prompt':
      // Between-games deck editing (SIDEBOARD/CONSTRUCT callback from the server).
      // The web player moves cards between maindeck/sideboard and submits via
      // submit_deck; the server unblocks match.sideboard() and auto-starts the
      // next game when all players have submitted (TableController.endGameAndStartNextGame).
      store.setSideboardPrompt(msg)
      // Clear any stale game-end dialog from the previous game — sideboarding
      // supersedes it (the match is not over).
      store.clearGameEnd()
      break

    case 'view_limited_deck':
      // The server answered a "View deck" request (VIEW_LIMITED_DECK callback,
      // serialized by WebProxyClient.sendLimitedDeck). The deck arrives complete with
      // this message — the modal renders it directly (desktop parity: the Java client
      // opens a read-only deck editor on the same callback).
      receiveViewDeck({
        seatName: msg.deckName || '',
        cards: msg.cards || [],
        sideboard: msg.sideboard || [],
      })
      break

    case 'disconnected':
      // XMage server connection lost — show error and update connection status.
      // Routed through proxyLinkLost() so the lobby
      // also schedules ONE client-side WebSocket cycle (no game live) — the proxy
      // never re-dials XMage outside handleConnect, so without the cycle this
      // failure class never recovered in place. callback_error below keeps the
      // plain setConnStatus: a single failed callback does not mean the link died.
      console.error('XMage server disconnected:', msg.message)
      store.showError(stripServerHtml(msg.message || 'Connection to XMage server lost'))
      store.proxyLinkLost()
      break

    case 'server_error':
      // Server-side error (e.g. deck validation failure, game error)
      console.error('Server error:', msg.message)
      store.showError(stripServerHtml(msg.message || 'Server error'))
      break

    case 'callback_error':
      // Error processing an XMage callback (decompression failure, handler exception)
      console.error('Callback error:', msg.message)
      store.showError(stripServerHtml(msg.message || 'Callback error'))
      store.setConnStatus('connection_lost')
      break

    case 'game_error':
      // Game-level error from the XMage engine (GAME_ERROR callback)
      console.error('Game error:', msg.message)
      store.showError(stripServerHtml(msg.message || 'Game error'))
      break

    case 'user_request':
      // USER_REQUEST_DIALOG (e.g. rollback-permission request) — show modal
      store.setUserRequest(msg)
      break

    case 'card_search_result':
    case 'legality_result':
    case 'card_versions_result':
    case 'card_lookup_result':
      // Deck editor — correlated by requestId
      handleDeckEditorResponse(msg)
      break

    case 'token_versions_result':
      // Token art candidates (engine printings / Scryfall-expanded). Routed to the
      // module that owns the request bookkeeping; CardThumb reads results from it.
      handleTokenVersionsResult(msg)
      break

    case 'token_art_preferences':
      // A player at this table chose a token art — or the initial snapshot on join.
      replaceSharedTokenArts(msg.preferences || [])
      break

    case 'avatar_set':
      // The proxy's answer to our `set_avatar`: the EFFECTIVE id after its
      // clamp. Write it back so a repaired value (a stale/hand-crafted id the
      // proxy had to replace) cannot leave the picker and the board claiming an
      // avatar the server never stored. `adoptAvatarId` — not `setAvatarId` —
      // because this is the server's value, not a fresh user pick: re-persisting
      // is harmless but re-SENDING would echo a set_avatar back at the proxy.
      store.adoptAvatarId(msg.avatarId)
      break

    // ── Replay ──
    case 'finished_matches':
      store.setFinishedMatches(msg.matches || [])
      break

    case 'replay_files':
      store.setReplayFiles(msg.gameIds || [], msg.directory || 'saved', msg.fileMeta || {})
      break

    case 'replay_loading':
      // Interim ack: the proxy is about to block deserializing the save. Not a
      // frame — it only tells the UI to show progress (see ReplayView / the
      // browser's per-row notice) instead of an apparently hung socket.
      store.onReplayLoading(msg.gameId, msg.bytes ?? null)
      break

    case 'replay_game':
      // The server created the replay session (REPLAY_GAME ack of replay_load).
      // The slice opens the viewer and auto-sends replay_start.
      store.onReplayGame(msg.gameId)
      break

    case 'replay_init':
    case 'replay_update': {
      // A replay frame — full state, serialized by the same GameStateSerializer
      // the live channel uses. DELIBERATELY not routed into gameState: the replay
      // board reads replayState, so a live game's state can never be overwritten
      // by a replay frame (and vice versa).
      const state = msg.state
      if (!state) {
        // Fail Fast: a frame without a state cannot be rendered.
        console.error('[replay] ' + msg.type + ' without state payload', msg)
        store.showError(`Replay frame (${msg.type}) had no state — see console`)
        break
      }
      store.setReplayState(state, msg.gameId, msg.replayStateIndex, msg.replayStateCount)
      break
    }

    case 'replay_done':
      store.onReplayDone(msg.reason, msg.result, msg.gameId)
      break

    default:
      console.warn('Unknown message type:', (msg as { type: string }).type, msg)
  }
}