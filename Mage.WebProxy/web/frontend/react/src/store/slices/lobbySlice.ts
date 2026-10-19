import type { StateCreator } from 'zustand'
import type { LobbyState, GameStore } from '../types'
import type { PlayerSlot } from '@/types'
import { LLM_CONFIG_SECTIONS } from '@/utils/constants'
import { routeChat } from '@/utils/lobbyChat'
import { setSeatKey, newClientKeyTag, fetchProviderModelsBrowserDirect } from '@/utils/llmBridgeClient'
import { isSocketDown, SOCKET_DOWN_SETUP_ERROR, SOCKET_DOWN_ACTION_ERROR } from '@/utils/socketDown'
import { validateSeatName, seatNameForWire } from '@/utils/seatNames'
import { deckUploadNoticeMessage } from '@/utils/formatBytes'
import type { GameEvent } from '@/types'
// Audio: seat-churn cues on the table list (the desktop's
// TableWaitingDialog seat-count poll). Additive — the list replacement below is
// untouched and the hook reads the previous list before it is replaced.
import { onTableListUpdated } from '@/audio/audioTriggers'

/** Create a new player slot with default LLM config values. */
function makeSlot(defaultType: string, isYou: boolean): PlayerSlot {
  const slot: PlayerSlot = {
    type: defaultType,
    isYou,
    deck: '',
    showLLMConfig: defaultType === 'COMPUTER_LLM',
    llmConfig: {},
  }
  // Initialize llmConfig with defaults from LLM_CONFIG_SECTIONS
  for (const section of LLM_CONFIG_SECTIONS) {
    for (const field of section.fields) {
      slot.llmConfig[field.key] = field.value
    }
  }
  return slot
}

/**
 * Seat names — resolve the `name` every seat should ride the wire with.
 *
 * The row (PlayerSlotRow) offers a Name field for the "You" seat and for
 * AI/LLM seats only; an open HUMAN opponent seat is DELIBERATELY not nameable
 * there (its player name arrives with the joiner's own login —
 * WebProxySession.handleJoinTable joins as the joiner's username), so this
 * resolver returns `undefined` for it and never sends one.
 *
 * `undefined` in `names[i]` means "omit the field": the proxy then derives the
 * seat default (`pc.has("name") ? … : pt.isAI() ? "AI-" + i : username`), which
 * is what keeps an empty field byte-identical to the previous wire frame.
 *
 * Fail Fast: the first invalid name (see utils/seatNames — length cap + allowed
 * characters) returns an error message instead of a payload; the caller shows
 * it in the dialog's existing error surface and sends nothing. Names are
 * trimmed, never truncated.
 */
function resolveSeatNames(slots: PlayerSlot[]): { names: Array<string | undefined>; error: string } {
  const names: Array<string | undefined> = []
  for (let i = 0; i < slots.length; i++) {
    const slot = slots[i]!
    if (!slot.isYou && slot.type === 'HUMAN') {
      names.push(undefined) // open human seat — named by whoever joins it
      continue
    }
    const problem = validateSeatName(slot.name ?? '')
    if (problem) {
      return { names: [], error: `${slot.isYou ? 'Your seat' : `Seat ${i}`}: ${problem}` }
    }
    names.push(seatNameForWire(slot.name))
  }
  return { names, error: '' }
}

/**
 * Apply a resolved seat name to a playerConfig, adding the `name` key ONLY when
 * one was typed. An absent key is the contract: it makes the proxy derive the
 * default (username for a human seat, "AI-<index>" for an AI seat) — an empty
 * string would be indistinguishable to the proxy's `pc.has("name")` check only
 * by accident of `getAsString()` returning "", so the field is omitted instead.
 */
function applySeatName(config: { name?: string }, name: string | undefined): void {
  if (name !== undefined) {
    config.name = name
  }
}

export const createLobbySlice: StateCreator<
  GameStore,
  [],
  [],
  LobbyState
> = (set, get) => ({
  // State
  tables: [],
  showSetup: false,
  joinMode: false,
  setupError: '',
  setupConfig: {
    gameName: 'Web Game',
    gameType: 'Two Player Duel',
    deckType: 'Constructed - Freeform',
    winsNeeded: 1,
    playerSlots: [],
    attackOption: 'LEFT',
    range: 'ALL',
    skillLevel: 'CASUAL',
    timeLimit: 'NONE',
    bufferTime: 'NONE',
    mulliganType: 'GAME_DEFAULT',
    freeMulligans: 0,
    customStartLifeEnabled: false,
    customStartLife: 20,
    customStartHandSizeEnabled: false,
    customStartHandSize: 7,
    planeChase: false,
    rollbackTurnsAllowed: false,
    spectatorsAllowed: false,
    llmChatPublic: false,
    rated: false,
    password: '',
    quitRatio: 100,
    minimumRating: 0,
    edhPowerLevel: 0,
  },
  pendingJoinTableId: null,
  joinWaiting: false,
  myTableId: null,
  isTableOwner: false,
  /** Own-seat names this session created tables under (see LobbyState). */
  mySeatNames: [],
  /** Deck-upload confirmation (see LobbyState.deckUploadNotice). */
  deckUploadNotice: null,

  /** Argentum-P1 training deck pool (filled by `p1_decks`; see LobbyState). */
  p1Decks: [] as import('@/types').P1Deck[],
  p1DefaultDeck: '',

  // Lobby chat + online users (see utils/lobbyChat.ts for the routing rules)
  lobbyChatTab: 'talk' as import('@/utils/lobbyChat').ChatTab,
  lobbyUsers: [] as import('@/types').LobbyUser[],
  lobbyMotd: null as string | null,
  /** Talk tab lines (talk + whisper), capped like gameplaySlice chatMessages. */
  lobbyTalkMessages: [] as GameEvent[],
  /** System tab lines (user_info/status/game + any unrecognized chatType). */
  lobbySystemMessages: [] as GameEvent[],
  availableModels: [],
  modelsLoading: false,
  /**
   * Which fetch verb is still awaiting its typed reply. Set by the
   * PROXY-fetch senders (fetchModels / refreshModels — and NOT by a
   * browser-direct fetch, which never touches the proxy and resolves through
   * its own promise); cleared by the typed replies. Why it exists: a generic
   * `error` frame carries no correlation id, so the ONLY thing the frontend can
   * know about a stray error is whether a proxy fetch is in flight — and the
   * models half is the one that owns a spinner. `pendingFetch === 'models'` is
   * therefore the exact, testable precondition for resolving `modelsLoading` on
   * a generic error (see resolveModelsFetchError); a browser-direct fetch in
   * flight (modelsLoading true, no proxy fetch pending) must NEVER be touched by
   * an unrelated proxy error. The dialog primes BOTH verbs in one tick, so
   * fetchPersonalities must not clobber a pending 'models' marker.
   */
  pendingFetch: null as 'models' | 'personalities' | null,
  modelContextWindows: {} as Record<string, number>,
  availablePersonalities: [],
  /**
   * Thinking-shape classification per fetched endpoint.
   * Key = the chat-completions endpoint as sent to fetch_models; value = the
   * proxy's `ollamaClassified` verdict for it. An endpoint absent from the map
   * has no classification yet (never fetched) — the seat form then warns only
   * from the seat's OWN deterministic classification.
   */
  thinkingEndpointClass: {} as Record<string, boolean>,
  /** Proxy-side llm_setup_warning (setup-time thinking-shape probe), null = none. */
  llmSetupWarning: null as { seat: string; message: string } | null,

  // Tournament state
  showTournamentSetup: false,
  tournamentSetupConfig: {
    name: 'Web Tournament',
    tournamentType: '',
    gameType: 'Two Player Duel',
    deckType: 'Constructed - Freeform',
    winsNeeded: 2,
    numRounds: 3,
    numPlayers: 4,
    singleMultiplayerGame: false,
    allowSpectators: true,
    rollbackTurnsAllowed: false,
    rated: false,
    timeLimit: 'NONE',
    bufferTime: 'NONE',
    skillLevel: 'CASUAL',
    quitRatio: 100,
    minimumRating: 0,
    playerSlots: [],
  },
  tournamentTypes: [],
  tournamentGameTypes: [],
  tournamentDeckTypes: [],

  // Actions
  setTables: (tables) => {
    // Audio: seat churn at THIS session's table (joined/left/can-start).
    // Reads the previous list before it is replaced; the hook itself applies
    // the baseline/full-table guards (see audioTriggers.onTableListUpdated).
    onTableListUpdated(get(), get().tables, tables)
    // Candidate 2: the
    // proxy now puts a server-authoritative `owner: true` on rows this account
    // owns, so a page reload keeps the flag (it rides the wire, not browser
    // memory). Merge it back into the browser-side ownership state WITHOUT
    // clobbering the existing semantics:
    //   - never clear `isTableOwner`/`myTableId` — a table_created frame may have
    //     recorded them for a table that is not in this particular list yet
    //     (create → list race), and setTableJoined's idempotent guard reads them;
    //   - only ADOPT ownership for the table this session created (myTableId) or
    //     when nothing is recorded yet — the flag is scoped to this account
    //     server-side, so adoption is safe, but the store still needs myTableId
    //     for the start_match/sideboard verbs.
    const prev = get()
    const ownedRow = tables.find((t) => t.owner === true)
    if (ownedRow != null && !prev.isTableOwner) {
      set({
        isTableOwner: true,
        // Keep an already-known table id (setTableCreated wins); otherwise the
        // owned row identifies it.
        myTableId: prev.myTableId ?? ownedRow.id,
      })
    }
    set({ tables })
  },

  setShowSetup: (show) => set({ showSetup: show }),

  showGameSetup: () => {
    // Rapid clicks would stack multiple dialogs. Early-return if already open —
    // avoids both the visual stacking and the redundant fetchModels/personalities
    // bursts that compound the proxy rate-limiter.
    if (get().showSetup) return
    set({
      pendingJoinTableId: null,
      joinMode: false,
      showSetup: true,
      setupError: '',
      // a fresh dialog starts without a stale thinking-shape warning or deck-upload notice
      llmSetupWarning: null,
      deckUploadNotice: null,
      setupConfig: {
        ...get().setupConfig,
        playerSlots: [
          makeSlot('HUMAN', true),
          // Default seat 2 to HUMAN: a creator who leaves the dialog as-is
          // previously got a silent AI opponent (COMPUTER_MAD auto-generated
          // a 40-card deck and auto-started the match — live finding
          // 2026-09-12, ).
          // Humans joining via "Join" is the normal path; AI seats are an
          // explicit choice.
          makeSlot('HUMAN', false),
        ],
      },
    })
    // Model/personality lists feed the LLM seat
    // config UI, which stock builds don't render — don't dial the LLM paths.
    if (__LLMAGE__) {
      get().fetchModels()
      get().fetchPersonalities()
    }
  },

  showJoinOverlay: (tableId) => {
    set({
      pendingJoinTableId: tableId,
      joinMode: true,
      showSetup: true,
      setupError: '',
      deckUploadNotice: null,
      setupConfig: {
        ...get().setupConfig,
        playerSlots: [makeSlot('HUMAN', true)],
      },
    })
  },

  hideGameSetup: () =>
    set({ showSetup: false, pendingJoinTableId: null, setupError: '', llmSetupWarning: null, deckUploadNotice: null }),

  updateSetupConfig: (patch) =>
    set((state) => ({
      setupConfig: { ...state.setupConfig, ...patch },
    })),

  updatePlayerSlot: (index, patch) =>
    set((state) => ({
      setupConfig: {
        ...state.setupConfig,
        playerSlots: state.setupConfig.playerSlots.map((slot, i) =>
          i === index ? { ...slot, ...patch } : slot
        ),
      },
    })),

  addPlayerSlot: (defaultType, isYou) =>
    set((state) => ({
      setupConfig: {
        ...state.setupConfig,
        playerSlots: [...state.setupConfig.playerSlots, makeSlot(defaultType, isYou)],
      },
    })),

  removePlayerSlot: (index) =>
    set((state) => ({
      setupConfig: {
        ...state.setupConfig,
        playerSlots: state.setupConfig.playerSlots.filter((_, i) => i !== index),
      },
    })),

  setPendingJoinTableId: (id) => set({ pendingJoinTableId: id }),

  setJoinWaiting: (waiting) => set({ joinWaiting: waiting }),

  /** Record the own-seat names this session created a table under. */
  setMySeatNames: (names) => set({ mySeatNames: names }),

  setTableJoined: (tableId, _tableName) => {
    // Joiner: seated, but NOT the owner — cannot start the match.
    //
    // EXCEPTION: creating a table auto-joins seat 0, and the server fires JOINED_TABLE for
    // it. That callback must not clear the ownership we just recorded in setTableCreated,
    // or the owner would be told "Waiting for the table owner to start the match" about
    // their own table and never get the Start Match button.
    //
    // The proxy may send table_joined WITHOUT a tableId (the callback payload is a
    // TableClientMessage, not a TableView) — fall back to what we already recorded so a
    // creator is never misidentified as a joiner.
    const { myTableId: createdTableId, isTableOwner: wasOwner, pendingJoinTableId } = get()
    const resolved = tableId || pendingJoinTableId || createdTableId || null
    set({
      showSetup: false,
      joinWaiting: true,
      myTableId: resolved,
      // Ownership belongs ONLY to whoever created THIS table. Compare against the id we
      // recorded at create time — NOT against the value we are about to store, which is
      // self-referential and would flip a joiner to owner the second time this fires.
      // (The server sends table_joined more than once; this must be idempotent.)
      isTableOwner: wasOwner && createdTableId === resolved,
    })
  },

  setTableCreated: (tableId, _tableName) => {
    // The proxy emits `llm_setup_warning` BEFORE
    // `table_created` (WebProxySession.sendThinkingShapeWarnings runs before the
    // createTable RPC), and THIS handler is what closes the setup dialog — so the
    // warning was stored but never rendered: PlayerSlotRow only renders it inside
    // the now-closed dialog, and reopening New Game cleared it (the dialog-close defect).
    // Re-surface it at the close point as a toast — the same surface the
    // socket-down action error uses. Warn, never block: the table IS created.
    // The seat-form inline warning stays as-is for the dialog-still-open case
    // (a FAILED create leaves the dialog open, where the inline warning renders).
    const pendingWarning = get().llmSetupWarning
    // Remember that WE created this table — only the creator may start the match.
    set({ showSetup: false, myTableId: tableId, isTableOwner: true })
    if (pendingWarning) {
      get().showError(pendingWarning.message)
    }
    get().requestTables()
  },

  requestTables: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'get_tables' })
  },

  /** Ask the proxy for the room's online users + MOTD (reply: `users_list`). */
  requestUsers: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'get_room_users' })
  },

  /**
   * Argentum-P1 deck pool: request the 55 training decks once the server
   * advertises capability. The reply (`p1_decks`) fills p1Decks/p1DefaultDeck,
   * which the seat row renders as a select-only picker. No P1 capability → no
   * request (the seat type is not offered then, see PlayerSlotRow).
   */
  requestP1Decks: () => {
    const { ws, capabilities, p1Decks } = get()
    if (!ws || !capabilities.p1) return
    if (p1Decks.length > 0) return // already have them
    ws.send({ type: 'get_p1_decks' })
  },

  setP1Decks: (decks: import('@/types').P1Deck[], defaultDeck: string) =>
    set({ p1Decks: decks, p1DefaultDeck: defaultDeck }),

  setUsersList: (users, motd) => {
    const joinedMotd = motd.filter((l) => typeof l === 'string' && l.trim().length > 0).join(' ')
    set({ lobbyUsers: users, lobbyMotd: joinedMotd.length > 0 ? joinedMotd : null })
  },

  setLobbyChatTab: (tab) => set({ lobbyChatTab: tab }),

  /**
   * Route one incoming chat `event` into the lobby Talk/System lists by its
   * raw server messageType (utils/lobbyChat.routeChat). ADDITIVE: the game
   * surfaces keep their exact pre-existing behavior via gameplaySlice
   * appendEvent (RightRail path untouched) — this only adds lobby-tab copies.
   */
  appendLobbyRoutedEvent: (event) => {
    const tab = routeChat(event.chatType ?? (event.type === 'talk' ? 'talk' : 'system'))
    const key = tab === 'talk' ? 'lobbyTalkMessages' : 'lobbySystemMessages'
    set((state) => ({
      [key]: [...state[key], event].slice(-200),
    }))
  },

  startGame: () => {
    const { ws, setupConfig, pendingJoinTableId } = get()
    if (!ws) return
    // With a dead socket the old path fell through to ws.send,
    // which GameWebSocket drops with a console.warn only — the dialog closed
    // (or sat there) and NOTHING happened. Surface instead: the setupError
    // renders inside the open dialog (GameSetupDialog), whether this call
    // came from its Start/Join button or any future caller.
    if (isSocketDown(get())) {
      set({ setupError: SOCKET_DOWN_SETUP_ERROR })
      return
    }

    // Join mode
    if (pendingJoinTableId !== null) {
      const deck = setupConfig.playerSlots.length > 0
        ? setupConfig.playerSlots[0]!.deck.trim()
        : ''
      if (!deck) {
        set({ setupError: 'Deck is empty' })
        return
      }
      ws.send({ type: 'join_table', tableId: pendingJoinTableId, deck })
      set({ showSetup: false, pendingJoinTableId: null })
      return
    }

    // Create mode
    const slots = setupConfig.playerSlots
    if (slots.length < 2) {
      set({ setupError: 'Need at least 2 players' })
      return
    }

    // Resolve + validate the typed seat names BEFORE building the payload.
    // Fail Fast: an invalid name means nothing goes out — the setupError renders
    // in the dialog and names the offending seat (utils/seatNames for the rules).
    const { names: seatNames, error: seatNameError } = resolveSeatNames(slots)
    if (seatNameError) {
      set({ setupError: seatNameError })
      return
    }

    const playerConfigs = slots.map((slot, i) => {
      const config: {
        type: string
        deck: string
        isYou: boolean
        name?: string
        llmConfig?: Record<string, unknown>
      } = {
        type: slot.type,
        deck: (slot.deck || '').trim(),
        isYou: slot.isYou,
      }

      // `name` rides ONLY when the user typed one (trimmed; see applySeatName).
      // Absent = the proxy derives its default (username / "AI-<index>").
      applySeatName(config, seatNames[i])

      if (__LLMAGE__ && slot.type === 'COMPUTER_LLM' && slot.llmConfig) {
        const llmConfig: Record<string, unknown> = {}
        for (const [key, v] of Object.entries(slot.llmConfig)) {
          if (key === 'apiKey') {
            // Client-key bridge: the key stays in
            // the browser. Store it locally (memory-only, keyed by a fresh
            // clientKeyTag) and flag the seat as browser-key; NEVER put the
            // key itself in the create_table message.
            if (v && String(v).trim()) {
              const tag = newClientKeyTag()
              setSeatKey(tag, String(v).trim())
              llmConfig['useClientKey'] = true
              llmConfig['clientKeyTag'] = tag
            } else {
              delete llmConfig['useClientKey']
              delete llmConfig['clientKeyTag']
            }
            continue
          }
          if (typeof v === 'boolean') {
            llmConfig[key] = v
          } else if (typeof v === 'number') {
            // Send numeric values as-is, INCLUDING 0. Several fields use 0 as a
            // meaningful sentinel: modelContextWindow:0 means "auto-detect",
            // summarizeTriggerChars:0 means "disabled", the conversation limits
            // (conversationMaxExchanges/HistoryChars/ThinkCharsPerMsg) use
            // 0 = unlimited/infinity, etc.
            llmConfig[key] = v
          } else if (v && String(v).trim()) {
            // Omit empty strings — let the server use its default
            llmConfig[key] = String(v).trim()
          }
        }
        config.llmConfig = llmConfig
      }

      return config
    })

    const human = playerConfigs.find((p) => p.type === 'HUMAN' && p.isYou)
    if (human && !human.deck) {
      set({ setupError: 'Your deck is empty' })
      return
    }

    set({ setupError: '' })
    // Remember the own-seat names this create is going out under, so the
    // lobby grid's "am I seated here?" check still recognizes a RENAMED own
    // seat (utils/tablesGrid.actionForTable reads mySeatNames). Only our own
    // ("You") seat names are recorded — an open HUMAN opponent seat has no name
    // and is filled by someone else.
    const mySeatNames = seatNames.filter((n, i) => n !== undefined && slots[i]!.isYou) as string[]
    set({ mySeatNames })
    ws.send({
      type: 'create_table',
      name: setupConfig.gameName,
      gameType: setupConfig.gameType,
      deckType: setupConfig.deckType,
      winsNeeded: setupConfig.winsNeeded,
      playerConfigs,
      deck: human ? human.deck : '',
      attackOption: setupConfig.attackOption,
      range: setupConfig.range,
      skillLevel: setupConfig.skillLevel,
      timeLimit: setupConfig.timeLimit,
      bufferTime: setupConfig.bufferTime,
      mulliganType: setupConfig.mulliganType,
      freeMulligans: setupConfig.freeMulligans,
      customStartLifeEnabled: setupConfig.customStartLifeEnabled,
      customStartLife: setupConfig.customStartLife,
      customStartHandSizeEnabled: setupConfig.customStartHandSizeEnabled,
      customStartHandSize: setupConfig.customStartHandSize,
      planeChase: setupConfig.planeChase,
      rollbackTurnsAllowed: setupConfig.rollbackTurnsAllowed,
      spectatorsAllowed: setupConfig.spectatorsAllowed,
      // llmChatPublic is an LLMage-only MatchOptions
      // field (spec inventory) — compile it out of stock builds so the wire
      // frame carries no LLMage key at all. LLMage builds send the checkbox
      // value (its checkbox is already __LLMAGE__-gated in the setup dialog).
      ...(!!__LLMAGE__ ? { llmChatPublic: setupConfig.llmChatPublic } : {}),
      rated: setupConfig.rated,
      password: setupConfig.password,
      quitRatio: setupConfig.quitRatio,
      minimumRating: setupConfig.minimumRating,
      edhPowerLevel: setupConfig.edhPowerLevel,
    })
  },

  joinTable: (tableId, deck) => {
    const { ws } = get()
    if (!ws) return
    // Same surfacing as startGame — a dead socket must not close
    // the join dialog silently (setupError renders in GameSetupDialog).
    if (isSocketDown(get())) {
      set({ setupError: SOCKET_DOWN_SETUP_ERROR })
      return
    }
    set({ pendingJoinTableId: tableId })
    ws.send({ type: 'join_table', tableId, deck })
  },

  watchGame: (tableId) => {
    const { ws } = get()
    if (!ws) return
    ws.send({ type: 'watch_game', tableId })
  },

  /**
   * Owner-only: start a non-tournament match whose seats are all filled.
   *
   * The server rejects this for non-owners (`TableController.startMatch(UUID userId)` only
   * proceeds when `isOwner(userId)`), so there is no point offering it to a joiner.
   *
   * While the socket is down the send used to be dropped silently
   * (GameWebSocket.send console-warn only) and the waiting view sat there
   * forever. The waiting panel disables this button while down
   * (LobbyView, selectSocketDown); this toast guards any other caller.
   */
  startMatch: (tableId) => {
    const { ws } = get()
    if (!ws) return
    if (isSocketDown(get())) {
      get().showError(SOCKET_DOWN_ACTION_ERROR)
      return
    }
    ws.send({ type: 'start_match', tableId })
  },

  /**
   * Leave the waiting table. PLAYER-ONLY proxy verb (WebProxySession
   * handleLeaveTable). The proxy resolves the table from its own watch when no
   * tableId is sent, so the store does not have to track it; on success the
   * proxy answers `table_left`, which messageHandlers turns into the seat-state
   * reset (clearing the waiting panel). No optimistic local reset — the panel
   * stays until the server confirms, so a refused leave (already started) does
   * not strand the user in a lobby with no seat.
   */
  leaveTable: () => {
    const { ws } = get()
    if (!ws) return
    if (isSocketDown(get())) {
      get().showError(SOCKET_DOWN_ACTION_ERROR)
      return
    }
    ws.send({ type: 'leave_table' })
  },

  /**
   * Clear the lobby seat state. The one definition of "this session no
   * longer occupies a table" — logout and the game_over handler already set the
   * same five fields; `table_left` (leave_table's confirmation) reuses this.
   */
  clearSeatState: () => set({
    joinWaiting: false,
    pendingJoinTableId: null,
    myTableId: null,
    isTableOwner: false,
    mySeatNames: [],
  }),

  fetchModels: (endpoint?: string) => {
    const { ws, setupConfig, modelsLoading } = get()
    if (modelsLoading || !ws) return
    // The dialog-open primer dials the first LLM seat's OWN endpoint when it has
    // one. An empty seat endpoint means "(server default)" — the SERVER's
    // configured endpoint, which this browser does NOT know. Never substitute a
    // fabricated localhost default and dial it: the proxy's host allowlist rejects
    // it while naming the WRONG host ('localhost' for an opencode.ai seat), and the
    // New Game dialog renders that rejection. Nothing is dialed
    // here — the primer is opportunistic, so a dialog with no LLM endpoint is
    // silent; the user's own ↻ on a seat reports the reason plainly instead
    // (refreshModelsForSeat → NO_SEAT_ENDPOINT_MESSAGE).
    const llmSlot = setupConfig.playerSlots.find((s) => s.type === 'COMPUTER_LLM')
    const ep = (endpoint ?? (llmSlot?.llmConfig?.apiEndpoint as string | undefined) ?? '').trim()
    if (!ep) return
    // Send the matching slot's API key so authenticated /v1/model endpoints work
    const key = (llmSlot?.llmConfig?.apiKey as string | undefined) || ''
    set({ modelsLoading: true, pendingFetch: 'models' })
    ws.send({ type: 'fetch_models', endpoint: ep, apiKey: key })
  },

  refreshModels: (endpoint, apiKey) => {
    const { ws, modelsLoading } = get()
    if (!endpoint || !ws || modelsLoading) return
    set({ modelsLoading: true, pendingFetch: 'models' })
    ws.send({ type: 'fetch_models', endpoint, apiKey: apiKey || '' })
  },

  /**
   * Client-key bridge: browser-direct model list
   * for seats with a user key. The key NEVER enters the proxy — the fetch
   * happens in this tab. Also records per-model context windows so
   * client-key seats can send an explicit modelContextWindow (the server
   * cannot auto-detect it without a key).
   */
  refreshModelsBrowserDirect: (endpoint, apiKey) => {
    const { modelsLoading } = get()
    if (!endpoint || modelsLoading) return
    set({ modelsLoading: true })
    fetchProviderModelsBrowserDirect(endpoint, apiKey)
      .then((models) => {
        const ids = models.map((m) => m.id)
        const windows: Record<string, number> = {}
        for (const m of models) {
          if (m.contextLength !== undefined) windows[m.id] = m.contextLength
        }
        set({ availableModels: ids, modelContextWindows: windows, modelsLoading: false })
      })
      .catch((e: unknown) => {
        set({
          modelsLoading: false,
          setupError:
            'Browser-direct model fetch failed (provider may lack CORS — try a CORS-enabled ' +
            'provider like OpenRouter/OpenAI, or leave the key blank to use the server key): ' +
            (e instanceof Error ? e.message : String(e)),
        })
      })
  },

  loadDeckFile: (file, slotIndex) => {
    const reader = new FileReader()
    reader.onload = (e) => {
      const text = e.target?.result
      if (typeof text === 'string') {
        get().updatePlayerSlot(slotIndex, { deck: text })
        // Confirmation (bug-hunt: the upload silently replaced the textarea):
        // name + size of what was actually read, surfaced inline by the row.
        set({
          deckUploadNotice: {
            slotIndex,
            message: deckUploadNoticeMessage(file.name, typeof file.size === 'number' ? file.size : null),
          },
        })
      }
    }
    reader.readAsText(file)
  },

  setAvailableModels: (models) =>
    set({ availableModels: models, modelsLoading: false, pendingFetch: null }),

  setModelsError: (message) =>
    set({ modelsLoading: false, setupError: message, pendingFetch: null }),

  resolveFetchError: (message) => {
    const { pendingFetch } = get()
    if (pendingFetch === null) return false
    // A generic `error` (not a typed reply) arrived while a proxy fetch was in
    // flight. This is the fallback that keeps the UI alive against a proxy older
    // than the typed-frame fix, whose unauthenticated fetch_models still
    // answers with the generic error — and the guard is the whole safety
    // argument: the marker is set ONLY by the two proxy senders (never by a
    // browser-direct fetch, which owns its own promise), so an unrelated error
    // landing outside a fetch window (pendingFetch null) leaves models state
    // completely untouched. Any in-flight fetch is over either way, so the
    // marker is consumed; only the models verb owns a spinner.
    if (pendingFetch === 'models') {
      set({ modelsLoading: false, pendingFetch: null, setupError: message })
      return true
    }
    set({ pendingFetch: null })
    return false
  },

  clearPendingPersonalities: () => {
    // The personalities verb has no spinner, but its marker must still clear when
    // its typed reply lands, so a later generic `error` cannot be misattributed
    // to it (the dialog primes both verbs in one tick; the marker is a scalar
    // and 'models' always wins — see fetchPersonalities).
    if (get().pendingFetch === 'personalities') set({ pendingFetch: null })
  },

  /** Record a fetched endpoint's thinking-shape classification (models_list payload). */
  setThinkingEndpointClass: (endpoint, ollamaClassified) =>
    set((state) => ({
      thinkingEndpointClass: { ...state.thinkingEndpointClass, [endpoint]: ollamaClassified },
    })),

  /** Proxy-side llm_setup_warning (setup-time thinking-shape probe). */
  setLlmSetupWarning: (warning) =>
    set({ llmSetupWarning: warning }),

  fetchPersonalities: () => {
    const { ws, pendingFetch } = get()
    if (!ws) return
    // Never clobber a pending models fetch: showGameSetup primes BOTH verbs in
    // one tick, and 'models' is the marker that resolves the spinner.
    if (pendingFetch === null) set({ pendingFetch: 'personalities' })
    ws.send({ type: 'fetch_personalities' })
  },

  setAvailablePersonalities: (personalities) =>
    set({ availablePersonalities: personalities }),

  // ── Tournament actions ──

  showTournamentSetupDialog: () => {
    // Same stacking guard as showGameSetup.
    if (get().showTournamentSetup) return
    set({
      showTournamentSetup: true,
      setupError: '',
      // a fresh dialog starts without a stale thinking-shape warning
      llmSetupWarning: null,
      tournamentSetupConfig: {
        name: 'Web Tournament',
        tournamentType: '',
        gameType: 'Two Player Duel',
        deckType: 'Constructed - Freeform',
        winsNeeded: 2,
        numRounds: 3,
        numPlayers: 4,
        singleMultiplayerGame: false,
        allowSpectators: true,
        rollbackTurnsAllowed: false,
        rated: false,
        timeLimit: 'NONE',
        bufferTime: 'NONE',
        skillLevel: 'CASUAL',
        quitRatio: 100,
        minimumRating: 0,
        playerSlots: [
          makeSlot('HUMAN', true),
          makeSlot('COMPUTER_MAD', false),
          makeSlot('COMPUTER_MAD', false),
          makeSlot('COMPUTER_MAD', false),
        ],
      },
    })
    get().requestTournamentTypes()
    if (__LLMAGE__) {
      get().fetchModels()
      get().fetchPersonalities()
    }
  },

  hideTournamentSetup: () =>
    set({ showTournamentSetup: false, setupError: '', llmSetupWarning: null }),

  requestTournamentTypes: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'get_tournament_types' })
  },

  setTournamentTypes: (data) =>
    set({
      tournamentTypes: data.tournamentTypes,
      tournamentGameTypes: data.gameTypes,
      tournamentDeckTypes: data.deckTypes,
    }),

  updateTournamentSetupConfig: (patch) =>
    set((state) => ({
      tournamentSetupConfig: { ...state.tournamentSetupConfig, ...patch },
    })),

  updateTournamentPlayerSlot: (index, patch) =>
    set((state) => ({
      tournamentSetupConfig: {
        ...state.tournamentSetupConfig,
        playerSlots: state.tournamentSetupConfig.playerSlots.map((slot, i) =>
          i === index ? { ...slot, ...patch } : slot
        ),
      },
    })),

  addTournamentPlayerSlot: (defaultType, isYou) =>
    set((state) => ({
      tournamentSetupConfig: {
        ...state.tournamentSetupConfig,
        playerSlots: [...state.tournamentSetupConfig.playerSlots, makeSlot(defaultType, isYou)],
      },
    })),

  removeTournamentPlayerSlot: (index) =>
    set((state) => ({
      tournamentSetupConfig: {
        ...state.tournamentSetupConfig,
        playerSlots: state.tournamentSetupConfig.playerSlots.filter((_, i) => i !== index),
      },
    })),

  startTournament: () => {
    const { ws, tournamentSetupConfig } = get()
    if (!ws) return

    const slots = tournamentSetupConfig.playerSlots
    if (slots.length < 2) {
      set({ setupError: 'Need at least 2 players' })
      return
    }

    // Identical seat-name resolution as the match path (one definition —
    // resolveSeatNames). Same fail-fast contract: an invalid name blocks the
    // whole create_tournament and surfaces in the dialog's error line.
    const { names: seatNames, error: seatNameError } = resolveSeatNames(slots)
    if (seatNameError) {
      set({ setupError: seatNameError })
      return
    }

    const playerConfigs = slots.map((slot, i) => {
      const config: {
        type: string
        deck: string
        isYou: boolean
        name?: string
        llmConfig?: Record<string, unknown>
      } = {
        type: slot.type,
        deck: (slot.deck || '').trim(),
        isYou: slot.isYou,
      }

      // Same as the match path: the proxy's handleCreateTournament reads
      // `pc.has("name")` and derives the default when it is absent; a tournament
      // seat name becomes the TournamentPlayer's name for every round.
      applySeatName(config, seatNames[i])

      if (__LLMAGE__ && slot.type === 'COMPUTER_LLM' && slot.llmConfig) {
        const llmConfig: Record<string, unknown> = {}
        for (const [key, v] of Object.entries(slot.llmConfig)) {
          if (key === 'apiKey') {
            // Client-key bridge: the key stays in
            // the browser. Store it locally (memory-only, keyed by a fresh
            // clientKeyTag) and flag the seat as browser-key; NEVER put the
            // key itself in the create_table message.
            if (v && String(v).trim()) {
              const tag = newClientKeyTag()
              setSeatKey(tag, String(v).trim())
              llmConfig['useClientKey'] = true
              llmConfig['clientKeyTag'] = tag
            } else {
              delete llmConfig['useClientKey']
              delete llmConfig['clientKeyTag']
            }
            continue
          }
          if (typeof v === 'boolean') {
            llmConfig[key] = v
          } else if (typeof v === 'number') {
            llmConfig[key] = v
          } else if (v && String(v).trim()) {
            llmConfig[key] = String(v).trim()
          }
        }
        config.llmConfig = llmConfig
      }

      return config
    })

    const human = playerConfigs.find((p) => p.type === 'HUMAN' && p.isYou)
    if (human && !human.deck) {
      set({ setupError: 'Your deck is empty' })
      return
    }

    set({ setupError: '' })
    // Same own-seat-name memory as the match path — a tournament creator
    // renamed their seat, and the grid must still recognize them as seated.
    const mySeatNames = seatNames.filter((n, i) => n !== undefined && slots[i]!.isYou) as string[]
    set({ mySeatNames })
    ws.send({
      type: 'create_tournament',
      name: tournamentSetupConfig.name,
      tournamentType: tournamentSetupConfig.tournamentType,
      gameType: tournamentSetupConfig.gameType,
      deckType: tournamentSetupConfig.deckType,
      winsNeeded: tournamentSetupConfig.winsNeeded,
      numRounds: tournamentSetupConfig.numRounds,
      singleMultiplayerGame: tournamentSetupConfig.singleMultiplayerGame,
      allowSpectators: tournamentSetupConfig.allowSpectators,
      rollbackTurnsAllowed: tournamentSetupConfig.rollbackTurnsAllowed,
      rated: tournamentSetupConfig.rated,
      timeLimit: tournamentSetupConfig.timeLimit,
      bufferTime: tournamentSetupConfig.bufferTime,
      skillLevel: tournamentSetupConfig.skillLevel,
      quitRatio: tournamentSetupConfig.quitRatio,
      minimumRating: tournamentSetupConfig.minimumRating,
      playerConfigs,
    })
  },

  setTournamentCreated: (_tableId, _tableName) => {
    // create_tournament also emits llm_setup_warning before tournament_created
    // (WebProxySession.sendThinkingShapeWarnings, second call site) and THIS handler
    // closes the tournament dialog — same never-visible trap as the match path.
    // Re-surface as a toast; the seat-form inline warning stays for the open case.
    const pendingWarning = get().llmSetupWarning
    set({ showTournamentSetup: false })
    if (pendingWarning) {
      get().showError(pendingWarning.message)
    }
    get().requestTables()
  },
})