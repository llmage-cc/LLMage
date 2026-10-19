import type { GameState, TableInfo, GameEvent, ActionPrompt, ConnectionStatus } from '@/types'
import type { ServerMessage, PlayerSlot, TournamentSetupConfig, TournamentTypeInfo, HistoryBackfillMessage, ServerCapabilities, RegisterRequest } from '@/types'
import type { GameWebSocket } from '@/network/websocket'
import type { DeckState } from './slices/deckSlice'
import type { ReplaySlice } from './slices/replaySlice'
import type { ContextMenuState } from './slices/contextMenuSlice'

/**
 * The full store shape — combination of all slices.
 * Each slice contributes state fields and actions.
 */

export interface ConnectionState {
  // State
  ws: GameWebSocket | null
  connected: boolean
  connecting: boolean
  connectError: string
  connStatus: ConnectionStatus
  proxyUsername: string
  /**
   * What the connected server supports. Null until the
   * first `connected` message; llm=false on official servers hides every LLM
   * control. Defaults keep pre-capability builds behaving as LLMage.
   */
  capabilities: ServerCapabilities
  /**
   * Account registration: the register dialog's request state.
   * `registering` is true between the send and the proxy's single
   * register_success/register_error answer; `registerResult` holds that answer
   * (null until one arrives, cleared when the dialog opens or closes).
   */
  registering: boolean
  registerResult: { ok: boolean; message: string } | null

  // Actions
  setWebSocket: (ws: GameWebSocket) => void
  setConnected: (username: string) => void
  setCapabilities: (caps: ServerCapabilities) => void
  setConnecting: (value: boolean) => void
  setConnectError: (message: string) => void
  setConnStatus: (status: ConnectionStatus) => void
  /**
   * Send a `register` request. Fail Fast with an inline
   * registerResult when no socket exists — never a silent no-op.
   */
  registerAccount: (payload: RegisterRequest) => void
  /** Clear the register dialog's request state (dialog opened/closed). */
  clearRegisterResult: () => void
  /**
   * The proxy's answer to a `register` request (register_success /
   * register_error). Held for the connect overlay's register dialog.
   */
  setRegisterResult: (result: { ok: boolean; message: string }) => void
  /**
   * The proxy's XMage-server link died (`disconnected` frame). Sets connStatus
   * 'connection_lost' and — lobby context only, transition-only — schedules ONE
   * deliberate WebSocket cycle so the existing connect machinery re-creates the
   * proxy session (which re-dials XMage).
   */
  proxyLinkLost: () => void
  disconnect: () => void
  logout: () => void
  noteConnectAccepted: () => void
  onStateVersionReceived: (version: number) => void
  requestResync: () => void
  clearResyncFlag: () => void
}

export interface LobbyState {
  // State
  tables: TableInfo[]
  showSetup: boolean
  joinMode: boolean
  setupError: string
  /** Form data for table creation — mirrors app.js setup object. */
  setupConfig: {
    gameName: string
    gameType: string
    deckType: string
    winsNeeded: number
    playerSlots: PlayerSlot[]
    // Extended match options
    attackOption: string
    range: string
    skillLevel: string
    timeLimit: string
    bufferTime: string
    mulliganType: string
    freeMulligans: number
    customStartLifeEnabled: boolean
    customStartLife: number
    customStartHandSizeEnabled: boolean
    customStartHandSize: number
    planeChase: boolean
    rollbackTurnsAllowed: boolean
    spectatorsAllowed: boolean
    llmChatPublic: boolean
    rated: boolean
    /** Table password (empty = open table). */
    password: string
    /** Minimum win-quit ratio required to join (0–100). */
    quitRatio: number
    /** Minimum player rating required to join (0 = no constraint). */
    minimumRating: number
    /** EDH power level (deck-strength hint shown on the table). */
    edhPowerLevel: number
  }
  pendingJoinTableId: string | null
  joinWaiting: boolean
  /**
   * The table this session is currently seated at (creator or joiner), and whether this
   * session CREATED it. Only the creator may start a non-tournament match — the server
   * checks `isOwner(userId)` in TableController.startMatch and silently ignores anyone else.
   */
  myTableId: string | null
  isTableOwner: boolean
  /**
   * Seat names: the seat names THIS session seated itself under when it
   * created a table — the typed `playerConfigs[].name` values for its own
   * ("You") seats, empty strings/trailing entries omitted.
   *
   * Why: once a creator can rename their own seat, the server's SeatView
   * reports that typed name, so the lobby grid's "am I already seated here?"
   * check can no longer be answered by the login username alone
   * (utils/tablesGrid.actionForTable) — without this, the grid would offer
   * "Join" on the creator's own table and a second join attempt is rejected
   * server-side. Cleared on logout/game-over with the rest of the seat state.
   */
  mySeatNames: string[]

  /**
   * Deck-upload confirmation (bug-hunt: "file upload silently replaces the
   * textarea; no confirmation"). Set by lobbySlice.loadDeckFile when a file is
   * read into a seat's deck, carrying the byte size the browser actually read —
   * the ONE fact the client has that the textarea alone does not show. Rendered
   * inline by that seat's row (and the join-mode textarea) as an additive
   * `role="status"` line. `null` = nothing uploaded yet / cleared on dialog
   * open and close.
   */
  deckUploadNotice: { slotIndex: number; message: string } | null

  /**
   * Argentum-P1 training deck pool (the ONLY decks a COMPUTER_P1 seat may use),
   * fetched once per session via `get_p1_decks` when the connected server
   * advertises capabilities.p1. `p1DefaultDeck` is the deterministic default the
   * server assigns when a P1 seat has no explicit choice (P1DeckPool). Empty when
   * the server has no P1 capability — the P1 seat type is not offered then.
   */
  p1Decks: import('@/types').P1Deck[]
  p1DefaultDeck: string

  // ── Lobby chat + online users (stock parity: right-side users column, bottom chat) ──
  /**
   * Which lobby chat tab is active. Kept here (not uiSlice) because it belongs to the
   * lobby surface the same way joinWaiting does.
   */
  lobbyChatTab: import('@/utils/lobbyChat').ChatTab
  /** Online users of the main room (last `users_list` response, unsorted as received). */
  lobbyUsers: import('@/types').LobbyUser[]
  /**
   * Latest Message-of-the-Day text (joined `users_list.motd` lines). null = the server
   * has no MOTD or none received yet — the strip renders nothing.
   */
  lobbyMotd: string | null
  /** Talk tab lines (talk + whisper), capped like gameplaySlice chatMessages. */
  lobbyTalkMessages: GameEvent[]
  /** System tab lines (user_info/status/game + any unrecognized chatType). */
  lobbySystemMessages: GameEvent[]

  // Actions
  setTables: (tables: TableInfo[]) => void
  setShowSetup: (show: boolean) => void
  showGameSetup: () => void
  showJoinOverlay: (tableId: string) => void
  hideGameSetup: () => void
  updateSetupConfig: (patch: Partial<LobbyState['setupConfig']>) => void
  updatePlayerSlot: (index: number, patch: Partial<PlayerSlot>) => void
  addPlayerSlot: (defaultType: string, isYou: boolean) => void
  removePlayerSlot: (index: number) => void
  setPendingJoinTableId: (id: string | null) => void
  setJoinWaiting: (waiting: boolean) => void
  /** Record/clear the own-seat names this session created tables under. */
  setMySeatNames: (names: string[]) => void
  setTableJoined: (tableId: string, tableName: string) => void
  setTableCreated: (tableId: string, tableName: string) => void
  requestTables: () => void
  /** Send `get_room_users`; the `users_list` reply feeds lobbyUsers + lobbyMotd. */
  requestUsers: () => void
  /**
   * Argentum-P1: request the 55 training decks (`get_p1_decks`) once the
   * connected server advertises capabilities.p1. Idempotent — re-sending is
   * harmless; the `p1_decks` reply fills p1Decks/p1DefaultDeck.
   */
  requestP1Decks: () => void
  /** Fill the P1 training deck pool from the proxy's `p1_decks` reply. */
  setP1Decks: (decks: import('@/types').P1Deck[], defaultDeck: string) => void
  /** Store a parsed `users_list` payload (users + MOTD). */
  setUsersList: (users: import('@/types').LobbyUser[], motd: string[]) => void
  setLobbyChatTab: (tab: import('@/utils/lobbyChat').ChatTab) => void
  /**
   * Route one incoming chat event into the lobby Talk/System lists by its raw
   * server messageType (utils/lobbyChat.routeChat). ADDITIVE to the game
   * surfaces: gameplaySlice appendEvent still handles the game log / RightRail.
   */
  appendLobbyRoutedEvent: (event: GameEvent) => void
  startGame: () => void
  joinTable: (tableId: string, deck: string) => void
  /** Owner-only: start a non-tournament match whose seats are all filled. */
  startMatch: (tableId: string) => void
  /**
   * Leave the waiting table (the desktop TableWaitingDialog's Cancel →
   * SessionHandler.leaveTable). PLAYER-ONLY proxy verb. `tableId` optional —
   * omitted, the proxy uses the table it is currently watching.
   */
  leaveTable: () => void
  /**
   * Clear the lobby seat state (joinWaiting / pendingJoinTableId /
   * myTableId / isTableOwner / mySeatNames). The same reset logout and the
   * game_over handler perform; extracted so `table_left` (the proxy's
   * confirmation of a leave_table) can reuse exactly one definition.
   */
  clearSeatState: () => void
  watchGame: (tableId: string) => void
  fetchModels: (endpoint?: string) => void
  refreshModels: (endpoint: string, apiKey?: string) => void
  /** Client-key seats: browser-direct model list (key never enters the proxy). */
  refreshModelsBrowserDirect: (endpoint: string, apiKey: string) => void
  loadDeckFile: (file: File, slotIndex: number) => void
  availableModels: string[]
  modelsLoading: boolean
  /**
   * The fetch verb awaiting its typed reply, null when none (see the lobbySlice
   * field doc). Gates the generic-`error` spinner resolution: only a PENDING
   * proxy fetch may be resolved by an uncorrelated error frame.
   */
  pendingFetch: 'models' | 'personalities' | null
  /** Per-model context windows from the last browser-direct fetch (client-key seats need an explicit window). */
  modelContextWindows: Record<string, number>
  setAvailableModels: (models: string[]) => void
  setModelsError: (message: string) => void
  /**
   * Resolve a fetch that a GENERIC `error` frame answered (no typed reply
   * arrived — the proxy's auth-reject path before the typed-frame fix, or
   * any other uncorrelated error). Clears the spinner ONLY when a PROXY models
   * fetch was actually pending, so an unrelated error never disturbs models
   * state and a browser-direct fetch (modelsLoading true, pendingFetch null) is
   * never cleared by a proxy error that is not about it. Returns whether it
   * resolved a models fetch.
   */
  resolveFetchError: (message: string) => boolean
  /** Clear the pending marker for the personalities verb (its typed reply arrived). */
  clearPendingPersonalities: () => void
  /** Thinking-shape classification per fetched endpoint (endpoint → ollamaClassified). */
  thinkingEndpointClass: Record<string, boolean>
  setThinkingEndpointClass: (endpoint: string, ollamaClassified: boolean) => void
  /** Proxy-side llm_setup_warning (setup-time thinking-shape probe); null = none. */
  llmSetupWarning: { seat: string; message: string } | null
  setLlmSetupWarning: (warning: { seat: string; message: string } | null) => void
  fetchPersonalities: () => void
  availablePersonalities: string[]
  setAvailablePersonalities: (personalities: string[]) => void

  // Tournament state
  showTournamentSetup: boolean
  tournamentSetupConfig: TournamentSetupConfig
  tournamentTypes: TournamentTypeInfo[]
  tournamentGameTypes: string[]
  tournamentDeckTypes: string[]
  showTournamentSetupDialog: () => void
  hideTournamentSetup: () => void
  requestTournamentTypes: () => void
  setTournamentTypes: (data: { tournamentTypes: TournamentTypeInfo[]; gameTypes: string[]; deckTypes: string[] }) => void
  updateTournamentSetupConfig: (patch: Partial<TournamentSetupConfig>) => void
  updateTournamentPlayerSlot: (index: number, patch: Partial<PlayerSlot>) => void
  addTournamentPlayerSlot: (defaultType: string, isYou: boolean) => void
  removeTournamentPlayerSlot: (index: number) => void
  startTournament: () => void
  setTournamentCreated: (tableId: string, tableName: string) => void
}

export interface GameplayState {
  // State
  gameState: GameState | null
  lastRawState: GameState | null
  inGame: boolean
  actionPrompt: ActionPrompt | null
  currentActionPrompt: ServerMessage | null
  /**
   * A Pass Priority click that has been sent but not yet acknowledged by server
   * state (no prompt / no push since). While true the epoch guard would drop a
   * repeat click, so the rail must say "pass sent — waiting" instead of offering
   * a button that silently does nothing. Cleared by setGameState and by
   * any new prompt / local prompt hide.
   */
  passPending: boolean
  gameEnd: {
    result: string
    hasWon?: boolean | undefined
    matchInfo?: string | undefined
    additionalInfo?: string | undefined
    matchScore?: string | undefined
    players?: Array<{ name: string; life: number }> | undefined
    /** Duration windows (epoch ms, absent = unknown) — see GameOverMessage. */
    gameStartMs?: number | undefined
    gameEndMs?: number | undefined
    matchStartMs?: number | undefined
    matchEndMs?: number | undefined
  } | null
  eventLog: GameEvent[]
  chatMessages: GameEvent[]

  // Actions
  setGameState: (state: GameState) => void
  setInGame: (inGame: boolean) => void
  setActionPrompt: (msg: ServerMessage) => void
  hideActionPrompt: () => void
  setGameOver: (result: string, data?: {
    hasWon?: boolean | undefined
    matchInfo?: string | undefined
    additionalInfo?: string | undefined
    matchScore?: string | undefined
    players?: Array<{ name: string; life: number }> | undefined
    gameStartMs?: number | undefined
    gameEndMs?: number | undefined
    matchStartMs?: number | undefined
    matchEndMs?: number | undefined
  }) => void
  clearGameEnd: () => void
  appendEvent: (event: GameEvent) => void
  appendChatMessage: (event: GameEvent) => void
  /**
   * Append a LOCAL chat line (never sent anywhere) to the chat list the user is
   * currently looking at: lobbyTalkMessages while the lobby chat surface is
   * active, chatMessages while a game/replay rail is up. Used by /help, whose
   * text is client-side only (see utils/slashCommands.ts).
   */
  appendLocalChatMessage: (text: string) => void
  /** Empty the visible chat list (/clear). Does NOT touch the game log. */
  clearVisibleChat: () => void
  /**
   * Merge a proxy `history_backfill` (rejoin/reload replay of the game-log and
   * chat events already sent for this game) into the panels. Deduped by event
   * `seq`; see gameplaySlice.mergeHistory.
   */
  mergeHistory: (backfill: HistoryBackfillMessage) => void
  sendChat: (message: string) => void
  playerColorMap: Record<string, number>
  setPlayerColorMap: (map: Record<string, number>) => void

  // Action responses
  selectChoice: (index: number) => void
  /** Attempt to play/cast a card by UUID (server-side cast flow for a card in hand). */
  playCard: (cardId: string) => void
  selectSpecial: (value: string) => void
  selectTarget: (targetId: string) => void
  booleanChoice: (value: boolean) => void
  passPriority: () => void
  skipPriority: (skip: string) => void
  cancelSkip: () => void
  undo: () => void
  concede: () => void
  tapMana: (landId: string) => void
  specialMana: () => void
  useMana: (color: string, playerId: string) => void
  amountChoice: (value: number) => void
  multiAmountChoice: (amounts: number[]) => void
  choosePile: (pile: number) => void

  // New actions for feature parity
  concedeMatch: () => void
  holdPriority: (hold: boolean) => void
  rollbackTurns: () => void
  stopWatching: () => void
  triggerOrder: (action: 'first' | 'last' | 'name_first' | 'name_last' | 'reset_all', abilityId: string | null, ruleText: string | null) => void
  /**
   * Remember a recurring yes/no answer AND answer the current prompt with it
   * (desktop HelperPanel.handleAutoAnswerPopupMenuEvent semantics).
   * `action` is the id_/text_ variant, `value` the stored+sent answer (true = YES).
   */
  autoAnswerPrompt: (
    action: 'id_yes' | 'id_no' | 'text_yes' | 'text_no',
    originalId: string | null,
    message: string,
    value: boolean,
  ) => void
  /** Reset one of the auto-answer memories: yes/no or replacement effects. */
  autoAnswerReset: (action: 'reset_all' | 'reset_replacement') => void
  viewSideboard: () => void
  /**
   * Ask a seat's owner for permission to see their hand.
   * `playerId` is the GRANTER's Player.playerId. Works for a seated player and
   * for a spectator (the proxy deliberately leaves this verb out of
   * PLAYER_ONLY_ACTIONS — the desktop offers the same item in its watcher menu).
   */
  requestHandPermission: (playerId: string) => void
  /**
   * Revoke every permission granted on YOUR OWN hand this game — the
   * desktop's "Revoke all permission(s) to see your hand cards".
   */
  revokeHandPermission: () => void
  /**
   * Allow/deny hand requests on YOUR OWN hand — the desktop's "Allow hand
   * requests from other users" checkbox. ON also clears the engine's
   * one-request-per-user memory, so requests can arrive again.
   */
  allowHandRequests: (enabled: boolean) => void
  toggleManaAutoPay: (enabled: boolean) => void
  toggleUseFirstMana: (enabled: boolean) => void
  updatePhaseStops: (stops: import('@/types').PhaseStopConfig) => void
  watchGame: (tableId: string) => void
  watchTournamentTable: (tableId: string) => void
  // Tournament running view
  tournamentData: import('@/types').TournamentData | null
  tournamentId: string | null
  showTournamentRunning: boolean
  setTournamentData: (data: import('@/types').TournamentData | null, id: string | null) => void
  setShowTournamentRunning: (show: boolean) => void
  refreshTournament: () => void

  // Between-games sideboarding (SIDEBOARD/CONSTRUCT callbacks)
  sideboardPrompt: import('@/types').SideboardPromptMessage | null
  /** Bumped when the proxy reports a submit_deck REJECTION (proxy `error` while a
   *  sideboard modal is open). SideboardView watches this to drop its local
   *  "Submitted — waiting" state so the player can fix + resubmit. */
  sideboardSubmitRejectedAt: number
  setSideboardPrompt: (prompt: import('@/types').SideboardPromptMessage | null) => void
  sideboardSubmitRejected: () => void
  submitDeck: (deck: { cards: import('@/types').DeckEntryLite[]; sideboard: import('@/types').DeckEntryLite[] }) => void
}

export interface UIState {
  // State
  zonePopup: { zone: string; playerName: string; cards: import('@/types').Card[] } | null
  revealedPopup: { name: string; cards: import('@/types').Card[] } | null
  activeRightTab: 'log' | 'chat' | 'llm'
  activeLLMPlayerIdx: number
  errorMessage: string
  /**
   * An optional DETAILS/stack string supplied by the proxy
   * (the `error` / `server_error` / `callback_error` / `game_error` frames may
   * now carry `details`). The ErrorToast renders it in a collapsible section;
   * '' = no details (the section is not rendered at all). Kept separate from
   * errorMessage so the toast's own text is never polluted by a stack.
   */
  errorDetails: string
  errorToastVisible: boolean
  /**
   * While true the 5 s auto-hide timer is suspended — set by the toast
   * while the user is hovering it / has the details expanded, so the Copy
   * button and the stack stay reachable. Cleared on leave/collapse, which
   * re-arms a fresh timer.
   */
  errorToastHeld: boolean
  cardPreview: {
    card: import('@/types').Card | import('@/types').Permanent | import('@/types').Commander | null
    face: 'front' | 'back'
    x: number
    y: number
    /**
     * Token-art owner context (optional — payloads from grids/popups have no
     * board owner). CardThumb supplies it so the hover preview resolves the
     * same preference layer the tile does (utils/tokenArt.ts): the owner's
     * shared choice vs. my own art. Absent = owner-agnostic resolution.
     */
    ownerPlayerId?: string | null | undefined
    isMine?: boolean | undefined
  } | null
  userRequest: import('@/types').UserRequestMessage | null

  // Settings (persisted to localStorage)
  autoScrollLog: boolean
  /**
   * Multiplayer board modes — the
   * "Multiplayer layout" section in Settings → Display, consumed by the shared
   * GameLayout shell for 3+ player games (live + replay):
   *  - `boardModeMaximizeOpponents`: opponents render as a compact chip strip
   *    along the top of the center column; my board takes the remaining height.
   *  - `boardModeFocusEnabled`: pseudo-1v1 vs ONE opponent (the existing 1v1
   *    split markup), other opponents as chips with avatar + name + life and
   *    on-board controls (click chip to focus, ◀/▶ cycle, ✕ exit).
   *  - `boardModeFollowActive`: sub-toggle — the focused opponent follows the
   *    ACTIVE player (enabled only when focus is on).
   * All three are inert in a 2-player game (the exact existing 1v1 path).
   * Decision math lives in utils/boardModes.ts (pure, shared shell).
   */
  boardModeMaximizeOpponents: boolean
  boardModeFocusEnabled: boolean
  boardModeFollowActive: boolean
  /**
   * The user's pinned focus target (chip click / arrows) — SESSION-scoped
   * (never persisted; a focused opponent must not survive into the next
   * game/view). Store state so the resolver's stale-pin fallback is reachable
   * and GameLayout stays render-pure. Name of the seat (the wire-stable
   * identity, like activePlayer).
   */
  boardModeFocusTarget: string | null
  autoScrollChat: boolean
  autoScrollLLM: boolean
  /**
   * Replay LLM tab: render the conversation accumulated UP TO the current frame
   * instead of only the current frame's bucket.
   * The frame's own bucket still arrives; this only changes what the rail
   * renders (replayLLMHistoryFor vs the raw frame). Persisted in xmage-settings
   * like its siblings; the checkbox lives in the Settings dialog (pinned).
   */
  replayLLMFullHistory: boolean
  /**
   * Collapse state for the LLM tab's text blocks ("any text
   * block should be collapsible"). Keyed by a stable block id
   * (`<playerName>:msg:<idx>` for conversation messages,
   * `<playerName>:section:<kind>` for the pre-game sections). NOT persisted
   * to xmage-settings — collapse is a per-viewing aid; a collapsed system
   * prompt must not stay collapsed into the next game/replay by default.
   */
  llmCollapsedBlocks: Record<string, boolean>
  fontScale: number
  cardScale: number
  /**
   * Auto-fit battlefield: when true (default) the effective card
   * size is computed from the live layout + real board contents so boards and hands
   * always fit the screen without scrolling; cardScale acts as a cap. When false,
   * the historical viewport heuristic runs unchanged. Default lives in
   * uiSlice.ts DEFAULTS (exported as UI_DEFAULTS) so a future "Reset to default"
   * feature includes it automatically.
   */
  autoFitBattlefield: boolean
  /** Whole-UI structural scale — see base.css --ui-scale. 1 = unscaled. */
  uiScale: number
  theme: 'dark' | 'light'
  showSettings: boolean
  /** Hover card-preview panel scale (1 = the CSS-default 540px panel width). */
  cardPreviewScale: number
  /**
   * Bumped by the settings "Reset to Default" action so views that hold
   * localStorage-backed state OUTSIDE this store (GameView panel widths,
   * battlefield split) re-read their keys without a page reload.
   */
  settingsResetAt: number
  /** Custom highlight colors as {r,g,b} (0-255 each). */
  highlightPlayable: { r: number; g: number; b: number }
  highlightCombat: { r: number; g: number; b: number }
  highlightTarget: { r: number; g: number; b: number }
  highlightManaTappable: { r: number; g: number; b: number }
  highlightActiveTurn: { r: number; g: number; b: number }
  /**
   * Event sounds: master enable + volume of the WAV
   * event-sound layer (audio/audioManager.ts). Persisted inside xmage-settings
   * like every other store-backed setting; the manager's live snapshot is
   * mirrored by the setters below (see uiSlice).
   */
  audioEnabled: boolean
  audioVolume: number
  /**
   * My avatar id — the desktop's PreferencesDialog "My Avatar" choice
   * (KEY_AVATAR). Selectable range 10…32 (`utils/avatars.ts`); persisted in
   * xmage-settings, sent to the proxy on connect and pushed mid-session when
   * changed. Read by the picker (Settings → Appearance) and by the board
   * header when a seat payload predates the additive `Player.avatarId` field.
   */
  avatarId: number

  // Actions
  setZonePopup: (popup: UIState['zonePopup']) => void
  setRevealedPopup: (popup: UIState['revealedPopup']) => void
  setActiveRightTab: (tab: 'log' | 'chat' | 'llm') => void
  setActiveLLMPlayerIdx: (idx: number) => void
  showError: (message: string) => void
  /**
   * Show an error WITH a collapsible details/stack section. The proxy's
   * error frames may carry a `details` string (a server stack / cause chain);
   * `showError` remains the message-only path for every existing caller.
   */
  showErrorWithDetails: (message: string, details: string) => void
  /** Suspend/resume the auto-hide timer (hover / expanded details). */
  setErrorToastHeld: (held: boolean) => void
  setCardPreview: (preview: UIState['cardPreview']) => void
  setUserRequest: (request: UIState['userRequest']) => void
  respondToUserRequest: (action: string) => void
  setAutoScrollLog: (v: boolean) => void
  setAutoScrollChat: (v: boolean) => void
  setAutoScrollLLM: (v: boolean) => void
  setReplayLLMFullHistory: (v: boolean) => void
  /** Toggle one LLM text block's collapse state (see llmCollapsedBlocks). */
  toggleLlmBlockCollapsed: (key: string) => void
  setFontScale: (v: number) => void
  setCardScale: (v: number) => void
  setAutoFitBattlefield: (v: boolean) => void
  setUiScale: (v: number) => void
  setTheme: (t: 'dark' | 'light') => void
  setShowSettings: (show: boolean) => void
  setCardPreviewScale: (v: number) => void
  markSettingsReset: () => void
  /** Multiplayer board modes — the three Display-tab toggles. */
  setBoardModeMaximizeOpponents: (v: boolean) => void
  setBoardModeFocusEnabled: (v: boolean) => void
  setBoardModeFollowActive: (v: boolean) => void
  /** Pin/unpin the focus target (null = unpinned). Session-scoped, not persisted. */
  setBoardModeFocusTarget: (name: string | null) => void
  setHighlightPlayable: (c: { r: number; g: number; b: number }) => void
  setHighlightCombat: (c: { r: number; g: number; b: number }) => void
  setHighlightTarget: (c: { r: number; g: number; b: number }) => void
  setHighlightManaTappable: (c: { r: number; g: number; b: number }) => void
  setHighlightActiveTurn: (c: { r: number; g: number; b: number }) => void
  /** Event-sound settings — mirrors the values into the audio module. */
  setAudioEnabled: (v: boolean) => void
  setAudioVolume: (v: number) => void
  /**
   * My avatar. The setter persists the choice AND, when a session is live,
   * pushes it to the proxy so the running game reflects it immediately (the
   * desktop's PreferencesDialog behavior). Values outside the selectable range
   * are a programming error — the picker cannot produce them.
   */
  setAvatarId: (id: number) => void
  /**
   * Take the proxy's EFFECTIVE avatar id as read (the `avatar_set` answer to
   * `set_avatar`). Persists the value but never re-sends it — this is the
   * server's answer, not a user pick, so echoing it back would loop. Also used
   * when the value needs repair on the wire (a value the picker cannot produce).
   */
  adoptAvatarId: (id: number) => void

  // Priority-ding settings.
  // Values live in store/slices/priorityDing.ts behind one DEFAULTS object;
  // these actions are thin wrappers so the settings dialog reaches them the
  // same way as every other setting.
  priorityDing: import('@/store/slices/priorityDing').PriorityDingSettings
  setPriorityDingEnabled: (v: boolean) => void
  setPriorityDingVolume: (v: number) => void
  setPriorityDingVariant: (v: import('@/store/slices/priorityDing').SoundVariant) => void
  setPriorityDingFocusMode: (v: import('@/store/slices/priorityDing').DingFocusMode) => void
  setPriorityDingFlashEnabled: (v: boolean) => void
  setPriorityDingFlashIntensity: (v: number) => void
  setPriorityDingFlashSustain: (v: boolean) => void
  setPriorityDingRememberPosition: (v: boolean) => void
}

/** The combined store type — all slices merged. */
export type GameStore = ConnectionState & LobbyState & GameplayState & UIState & DeckState & ReplaySlice & ContextMenuState
