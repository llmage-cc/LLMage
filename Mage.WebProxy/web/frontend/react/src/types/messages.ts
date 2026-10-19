import type { GameState, GameStateDelta } from './gameState'
import type { GameEvent } from './events'
import type { ActionType } from './enums'

/**
 * WebSocket message types — derived from:
 * WebProxyClient.java: server→client messages (put("type"...))
 * - WebProxySession.java: client→server message routing
 * - app.js handleMessage: switch cases for all server message types
 * - app.js ws.send: all client→server message types
 */

// ── Server → Client messages ──

/**
 * What the CONNECTED server can do: probed by the proxy
 * from the server's own ServerState right after connect. `llm=false` (official
 * XMage servers) hides every LLM control and deactivates the llm_prompt bridge;
 * `replayScrubber`/`replaySidecar` ride the same LLMage signal for 1.4.61
 * (upstream has no seekTo — see ServerCapabilities.java).
 */
export interface ServerCapabilities {
  llm: boolean
  p1: boolean
  replayScrubber: boolean
  replaySidecar: boolean
  serverVersion: string
  playerTypes: string[]
}

/**
 * One deck in the Argentum-P1 training pool — the ONLY decks a COMPUTER_P1 seat
 * may be given (mage.cards.decks.P1DeckPool). `deck` is paste-ready decklist text.
 */
export interface P1Deck {
  name: string
  deck: string
}

/**
 * The 55 training decks + the deterministic default, sent in response to
 * `get_p1_decks` (WebProxySession / P1DeckOffering). Only ever sent by an
 * LLMage/proxy server that advertises capabilities.p1.
 */
export interface P1DecksMessage {
  type: 'p1_decks'
  decks: P1Deck[]
  default: string
}

export interface ConnectedMessage {
  type: 'connected'
  username: string
  capabilities?: ServerCapabilities
}

export interface ConnectErrorMessage {
  type: 'connect_error'
  message: string
}

export interface TableListMessage {
  type: 'table_list'
  tables: TableInfo[]
}

export interface TableCreatedMessage {
  type: 'table_created'
  tableId: string
  tableName: string
}

export interface TableJoinedMessage {
  type: 'table_joined'
  tableId: string
  tableName: string
}

/**
 * The proxy's answer to `leave_table` — the user's seat was dropped. The
 * handler clears the same lobby seat state game_over clears (joinWaiting /
 * pendingJoinTableId / myTableId / isTableOwner / mySeatNames), which is what
 * removes the waiting panel and re-shows the tables grid.
 */
export interface TableLeftMessage {
  type: 'table_left'
  tableId: string
}

export interface StartGameMessage {
  type: 'start_game'
  gameId: string
}

export interface GameInitMessage {
  type: 'game_init'
  state: GameState
}

export interface GameUpdateMessage {
  type: 'game_update'
  state: GameState
}

export interface GameDeltaMessage {
  type: 'game_delta'
  delta: GameStateDelta
  stateSeq: number
}

export interface GameOverMessage {
  type: 'game_over'
  result: string
  hasWon?: boolean
  matchInfo?: string
  additionalInfo?: string
  matchScore?: string
  players?: Array<{ name: string; life: number }>
  /**
   * Duration windows (epoch ms) for the game-end dialog's stats section — the
   * desktop GameEndDialog Statistics tab shows both durations. Game window:
   * GameEndView.startTime/endTime. Match window: the embedded MatchView's
   * start/end (same source as the lobby's finishedMatches). Absent = unknown
   * (Gson drops nulls; partial GAME_OVER payloads never carry them).
   */
  gameStartMs?: number
  gameEndMs?: number
  matchStartMs?: number
  matchEndMs?: number
  /**
   * false = PARTIAL payload (the server's GAME_OVER callback carries only a message string,
   * no hasWon/score). The frontend must not let a partial clobber a rich END_GAME_INFO
   * summary that arrived first — that inversion showed "💀 Defeat" to the winner.
   * true/absent = authoritative summary.
   */
  complete?: boolean
}

export interface ActionPromptMessage {
  type: 'action_prompt'
  action_type: ActionType
  message: string
  context?: string
  // Select fields
  choices?: Array<{ index: number; id: string; name: string; action?: string; manaCost?: string }>
  special_button?: string
  // Target fields
  targets?: Array<{ id: string; name: string; type: string; abilityText?: string }>
  target_count?: number
  min_targets?: number
  max_targets?: number
  valid_target_types?: string[]
  required?: boolean
  min?: number
  max?: number
  /** True for the trigger-order prompt family (GAME_TARGET with queryType=PICK_ABILITY):
   *  `targets` lists the ORDERABLE ABILITIES (id = ability UUID), and one click picks
   *  one — it is not an N-target selection. See WebProxyClient.handleGameTarget. */
  trigger_order?: boolean
  // Boolean fields
  yes_label?: string
  no_label?: string
  /**
   * Auto-answer key material for THIS boolean ask. Written by the
   * proxy's WebProxyClient.handleGameAsk from the engine's own options map:
   *  - `original_id`        — the ask's source ability originalId (absent for
   *                           source-less asks like chooseMulligan), required by the
   *                           id-variant key `originalId + '#' + auto_answer_message`;
   *  - `auto_answer_message` — the engine's filtered AUTO_ANSWER_MESSAGE (source names
   *                           replaced by {this}), the key for both variants. Falls
   *                           back to the displayed question, matching the desktop
   *                           client (FeedbackPanel.java:106-111).
   */
  original_id?: string
  auto_answer_message?: string
  // Amount fields
  default_value?: number
  // Play mana fields
  mana_options?: Array<{ landId: string; landName: string; colors: string[]; auto: boolean }>
  /**
   * Per-source "the ENGINE says this source can pay the CURRENT unpaid cost"
   * flags, from the server's play_mana prompt option `manaPayableForThisCost`
   * (HumanPlayer.addManaPayableForThisCost), forwarded by
   * WebProxyClient.handleGamePlayMana. Keyed by permanent id; `true` means the
   * engine verified that source's mana passes its own conditions for the
   * object/cost being paid right now.
   *
   * ABSENT means the server did not evaluate (older proxy/server): the client
   * then keeps its previous behaviour — only sources whose production it can
   * read off the rule text may be auto-tapped. A value of `false` is NOT
   * "payable": never auto-tap on it.
   */
  mana_payable_for_this_cost?: Record<string, boolean>
  remaining_cost?: string
  auto_pay_available?: boolean
  special_available?: boolean
  // Choose pile fields
  pile1?: { index: number; cards: import('./gameState').Card[] }
  pile2?: { index: number; cards: import('./gameState').Card[] }
  // Multi-amount fields
  entries?: Array<{ id: string; name: string; min: number; max: number; value: number }>
  total?: number
  // Choose ability fields
  abilities?: Array<{ index: number; name: string; abilityText: string }>
  // Choose choice fields
  options?: Array<{ index: number; name: string }>
  // Look-at cards (Surveil/scry/fetch — cards being looked at during target selection)
  lookAtCards?: Array<{ name: string; typeLine: string; manaCost: string; rules: string[]; id: string | null; imageUrl: string; isValidTarget: boolean; sourceName?: string; sourceImageUrl?: string; abilityText?: string }>
  lookAtCardsValidIds?: string[]
  // Any-number target fields (GAME_TARGET options "UI.right.btn.text" + "chosenTargets")
  done_button_text?: string
  chosen_targets?: string[]
  chosen_count?: number
  /** True when this GAME_TARGET is a NOT_TARGET card list (Target.isNotTarget — e.g.
   *  Chrome Mox imprint "exile a card from your hand"): nothing is being targeted, so
   *  no targeting arrows may be drawn. Forwarded from the engine's prompt options. */
  not_target?: boolean
}

export interface EventMessage {
  type: 'event'
  event: GameEvent
}

/**
 * `history_backfill` — the proxy's replay of the game-log and chat events it
 * already sent for a game, sent immediately BEFORE the `game_init` state push on
 * a rejoin/reload (WebProxyClient.sendGameStateToBrowser). GAME_INIT carries a
 * GameView snapshot only, so without this the panels came back empty while the
 * board restored. `events` are log entries (type 'action'), `chat` are chat lines (type
 * 'talk'); both carry their ORIGINAL `seq`, which is how the merge de-duplicates.
 */
export interface HistoryBackfillMessage {
  type: 'history_backfill'
  gameId: string
  events: GameEvent[]
  chat: GameEvent[]
}

export interface ServerErrorMessage {
  type: 'error'
  message: string
  /** Optional server stack / cause chain, rendered in a collapsible section. */
  details?: string | undefined
}

export interface ServerMessageMessage {
  type: 'server_message'
  message: string
}

export interface DisconnectedMessage {
  type: 'disconnected'
  message: string
}

export interface ServerErrorNotificationMessage {
  type: 'server_error'
  message: string
}

export interface CallbackErrorMessage {
  type: 'callback_error'
  message: string
}

export interface ShowUserMessage {
  type: 'show_usermessage'
  message: string
}

/**
 * Answer to a `register` request. Exactly one of the two arrives
 * per request. `message` is the XMage server's own text when it produced one
 * ("You are successfully registered as x.  Your initial, generated password is:
 * …" in the server's test mode, "Registration is disabled on this server." when
 * the mail transport is missing) — the proxy never invents a reason.
 */
export interface RegisterSuccessMessage {
  type: 'register_success'
  message: string
}

export interface RegisterErrorMessage {
  type: 'register_error'
  message: string
}

export interface ModelsListMessage {
  type: 'models_list'
  models: string[]
  /** The endpoint this list was fetched from. */
  endpoint?: string
  /** The proxy's thinking-shape verdict for that endpoint (ollama-classified = thinking{} object expected). */
  ollamaClassified?: boolean
}

export interface ModelsErrorMessage {
  type: 'models_error'
  message: string
}

/**
 * The typed refusal for a `fetch_personalities` the proxy could not serve
 * (today: the unauthenticated-reject path). Symmetric with
 * ModelsErrorMessage; there is no personalities spinner, so the consumer only
 * clears the fetch marker and surfaces the message.
 */
export interface PersonalitiesErrorMessage {
  type: 'personalities_error'
  message: string
}

/**
 * Setup-time thinking-shape probe: the proxy classified a
 * saved seat's thinking shape as strict (non-Ollama endpoint + effort dialect
 * → bare reasoning_effort) while thinking is enabled. Non-fatal — the create
 * proceeds; this is the warning the user should act on before the game starts.
 */
export interface LlmSetupWarningMessage {
  type: 'llm_setup_warning'
  seat: string
  message: string
}

/**
 * Client-key bridge: the server built the exact
 * OpenAI-compatible request body and asks THIS browser to POST it to the
 * provider with the user's locally-stored key. No key ever crosses this
 * message — the browser attaches its own.
 */
export interface LlmPromptMessage {
  type: 'llm_prompt'
  requestId: string
  seatId: string
  gameId: string
  playerName: string
  requestBody: string
  apiEndpoint: string
  stream: boolean
  timeoutSeconds: number
  clientKeyTag: string
}

/** Browser → proxy: the provider's response for a pending exchange. */
export interface LlmDecisionRequest {
  type: 'llm_decision'
  requestId: string
  rawResponse: string
  thinking?: string
  httpStatus?: number
}

/** Browser → proxy: the browser's provider call failed. */
export interface LlmErrorRequest {
  type: 'llm_error'
  requestId: string
  message: string
  retryable: boolean
  httpStatus?: number
}

export interface PersonalitiesListMessage {
  type: 'personalities_list'
  personalities: string[]
}

export interface ResyncCompleteMessage {
  type: 'resync_complete'
}

export interface TournamentTypesMessage {
  type: 'tournament_types'
  tournamentTypes: TournamentTypeInfo[]
  gameTypes: string[]
  deckTypes: string[]
}

export interface TournamentCreatedMessage {
  type: 'tournament_created'
  tableId: string
  tableName: string
}

export interface TournamentJoinedMessage {
  type: 'tournament_joined'
  tableId: string
}

export interface ShowTournamentMessage {
  type: 'show_tournament'
  tournamentId: string | null
  tableId: string | null
  tournament?: TournamentData
}

export interface TournamentUpdateMessage {
  type: 'tournament_update'
  tournamentId: string | null
  tournament?: TournamentData
}

export interface TournamentOverMessage {
  type: 'tournament_over'
  /** Name of the winning player, if resolvable from the last standing entry. */
  winnerName?: string
}

export interface TournamentData {
  name: string
  type: string
  state: string
  watchingAllowed: boolean
  players: Array<{
    name: string
    state: string
    points: number
    results: string
    quit: boolean
  }>
  rounds: Array<{
    games: Array<{
      roundNum: number
      gameId: string | null
      tableId: string | null
      state: string
      result: string
      players: string
      canWatch: boolean
    }>
  }>
}

export interface RejoinGameMessage {
  type: 'rejoin_game'
  gameId: string
  tableId: string
}

export interface ViewSideboardMessage {
  type: 'view_sideboard'
  message: string
}

export interface SideboardPromptMessage {
  type: 'sideboard_prompt'
  tableId: string | null
  parentTableId: string | null
  /** Seconds remaining on the sideboard/construct timer (from TableController timeout). */
  time: number
  /** true = limited deck construction (CONSTRUCT callback / SIDEBOARD flag=true); false = normal sideboarding. */
  construct: boolean
  deckName: string
  cards: DeckEntryLite[]
  sideboard: DeckEntryLite[]
}

/** Collapsed card entry from the proxy — name+setCode+cardNumber identity, count copies.
 *  Field names match the deck-editor wire convention (LegalityService / deck.ts DeckEntry). */
export interface DeckEntryLite {
  name: string
  setCode: string
  cardNumber: string
  count: number
}

/**
 * View-deck answer. Proxy serialization of the XMage
 * VIEW_LIMITED_DECK callback: the server resolves the target seat's deck and the
 * proxy ships its two card lists with the same routine the sideboard prompt uses
 * (WebProxyClient.sendLimitedDeck → serializeSimpleCardList), so entries carry the
 * deck-editor wire shape (name/setCode/cardNumber/count).
 *
 * deckName is DeckView.getName(), which for this path is the SEAT name (the server
 * ships MatchPlayer.getDeckForViewer, which replaces the real deck name) — the
 * modal titles the seat itself and shows deckName as the label the server sent.
 */
export interface ViewLimitedDeckMessage {
  type: 'view_limited_deck'
  tableId: string | null
  parentTableId: string | null
  /** Server-side timeout for the view, seconds (TableController; 0 = none). */
  time: number
  deckName: string
  cards: DeckEntryLite[]
  sideboard: DeckEntryLite[]
}

export interface GameErrorMessage {
  type: 'game_error'
  message: string
}

// ── Replay messages (REPLAY_* callbacks + replay request responses) ──

/**
 * A finished match record from the proxy's `finished_matches` response
 * (WebProxySession.serializeFinishedMatches → MatchView). Mirrors the desktop
 * client's tableCompleted row: the per-row action is "Replay" when
 * replayAvailable, otherwise there is nothing to replay.
 */
export interface FinishedMatch {
  matchId: string | null
  tableId: string | null
  name: string
  gameType: string
  deckType: string
  players: string
  result: string
  tournament: boolean
  replayAvailable: boolean
  /** Match start/end as epoch ms (proxy serializes MatchView's Dates). */
  startTime: number | null
  endTime: number | null
  rated: boolean
  /** Structured per-player rows (deck names included) for search/sort. */
  playerList: { name: string; wins: number; quit: boolean; deckName: string | null }[]
  /** Game UUIDs in the match — a match can have several; the user picks one. */
  games: string[]
}

export interface FinishedMatchesMessage {
  type: 'finished_matches'
  matches: FinishedMatch[]
}

/**
 * One online user from the proxy's `users_list` response. Fields mirror
 * mage.view.UsersView exactly (WebProxySession.serializeRoomUsers — nothing
 * invented): flag/user name, match + tournament history with quit ratios,
 * activity info, ping, and the three ratings the desktop client's users
 * window shows.
 */
export interface LobbyUser {
  flagName: string | null
  userName: string
  matchHistory: string | null
  matchQuitRatio: number
  tourneyHistory: string | null
  tourneyQuitRatio: number
  infoGames: string | null
  infoPing: string | null
  generalRating: number
  constructedRating: number
  limitedRating: number
}

/**
 * `users_list` response to `get_room_users` — the lobby's online-users panel
 * data plus the server's Message-of-the-Day lines (the same pull the desktop
 * client does in TablesPanel.reloadServerMessages).
 */
export interface UsersListMessage {
  type: 'users_list'
  users: LobbyUser[]
  /** MOTD lines (server.msg.txt content); empty when the server has none. */
  motd: string[]
}

/** Searchable metadata for one saved replay file (saved/<uuid>.game). */
export interface ReplayFileMeta {
  /** Save size in bytes (from the directory listing itself). */
  bytes?: number
  /** Save file mtime as epoch ms (from the directory listing itself). */
  savedAtMs?: number
  /** Fields below come from the server-side sidecar JSON (ReplayMetadata). */
  gameId?: string
  gameType?: string | null
  deckType?: string | null
  tableName?: string | null
  tableId?: string | null
  startTime?: string | null
  endTime?: string | null
  /** e.g. "Player X is the winner" / "Game is a draw". */
  winner?: string | null
  turns?: number | null
  savedAt?: string | null
  players?: { name: string; wins: number; quit: boolean; deck: string | null }[]
}

export interface ReplayFilesMessage {
  type: 'replay_files'
  /** Directory that was listed, relative to the server's working directory. */
  directory: string
  gameIds: string[]
  /** Per-file metadata keyed by game id (sizes, dates, sidecar data). */
  fileMeta?: Record<string, ReplayFileMeta>
}

/**
 * Interim acknowledgement of `replay_load`, sent BEFORE the server's blocking
 * deserialization. `bytes` is the save's size when the proxy can read it, null
 * otherwise (the load proceeds either way — see the proxy's replayFileSize).
 * The completion signals are still replay_game / replay_init / replay_done.
 */
export interface ReplayLoadingMessage {
  type: 'replay_loading'
  gameId: string
  bytes: number | null
}

/**
 * REPLAY_GAME callback — the server has created the replay session; the next
 * step is `replay_start`.
 */
export interface ReplayGameMessage {
  type: 'replay_game'
  gameId: string | null
}

/** REPLAY_INIT — first replay frame (state index 0). */
export interface ReplayInitMessage {
  type: 'replay_init'
  gameId: string | null
  /** Position in the saved game's state list (proxy forwards GameView.replayStateIndex). */
  replayStateIndex?: number
  /**
   * Total states in the saved game (GameView.replayStateCount) — the length of
   * the viewer's position control. Absent on proxies older than 2026-09-21, in
   * which case the control degrades to a single-frame track.
   */
  replayStateCount?: number
  state: GameState
}

/** REPLAY_UPDATE — a later frame (next / previous / skip / seek). */
export interface ReplayUpdateMessage {
  type: 'replay_update'
  gameId: string | null
  /** Position in the saved game's state list — moves BACKWARD on previous(). */
  replayStateIndex?: number
  /** Total states in the saved game — see ReplayInitMessage.replayStateCount. */
  replayStateCount?: number
  state: GameState
}

/**
 * REPLAY_DONE. `reason` is classified proxy-side from the server's free-form
 * result string:
 *  - 'stopped'  — the user stopped it (or CLIENT_REPLAY_ACTION); session ended.
 *  - 'boundary' — "end of replay": stepping ran off either end; the session
 *                 stays alive server-side, so going back still works.
 *  - 'error'    — e.g. "Replay file not found or corrupt"; session ended.
 */
export interface ReplayDoneMessage {
  type: 'replay_done'
  gameId: string | null
  reason: 'stopped' | 'boundary' | 'error'
  result: string
}

export interface UserRequestMessage {
  type: 'user_request'
  title: string
  message: string
  relatedUserName: string
  relatedUserId: string | null
  gameId: string | null
  button1Text: string | null
  button1Action: string | null
  button2Text: string | null
  button2Action: string | null
}

/** Discriminated union of all server→client messages. */
export type ServerMessage =
  | ConnectedMessage
  | ConnectErrorMessage
  | TableListMessage
  | TableCreatedMessage
  | TableJoinedMessage
  | TableLeftMessage
  | StartGameMessage
  | GameInitMessage
  | GameUpdateMessage
  | GameDeltaMessage
  | GameOverMessage
  | ActionPromptMessage
  | EventMessage
  | HistoryBackfillMessage
  | ServerErrorMessage
  | ServerMessageMessage
  | DisconnectedMessage
  | ServerErrorNotificationMessage
  | CallbackErrorMessage
  | ShowUserMessage
  | RegisterSuccessMessage
  | RegisterErrorMessage
  | ModelsListMessage
  | ModelsErrorMessage
  | PersonalitiesErrorMessage
  | LlmSetupWarningMessage
  | LlmPromptMessage
  | PersonalitiesListMessage
  | ResyncCompleteMessage
  | TournamentTypesMessage
  | TournamentCreatedMessage
  | TournamentJoinedMessage
  | ShowTournamentMessage
  | TournamentUpdateMessage
  | TournamentOverMessage
  | RejoinGameMessage
  | ViewSideboardMessage
  | SideboardPromptMessage
  | ViewLimitedDeckMessage
  | GameErrorMessage
  | UserRequestMessage
  | FinishedMatchesMessage
  | UsersListMessage
  | ReplayFilesMessage
  | ReplayLoadingMessage
  | ReplayGameMessage
  | ReplayInitMessage
  | ReplayUpdateMessage
  | ReplayDoneMessage
  | import('./deck').CardSearchResultMessage
  | import('./deck').CardVersionsResultMessage
  | import('./deck').CardLookupResultMessage
  | import('./deck').LegalityResultMessage
  | TokenVersionsResultMessage
  | TokenArtPreferencesMessage
  | AvatarSetMessage
  | P1DecksMessage

// ── Client → Server messages ──

export interface ConnectRequest {
  type: 'connect'
  /**
   * XMage target host/port. OPTIONAL when the proxy pins the target itself
   * (-Dwebproxy.pinnedXmageTarget, used with -Dwebproxy.hideServerTarget=true so
   * the connect form hides these fields): the proxy ignores client-supplied
   * values and refuses a connect whose target is neither pinned nor supplied.
   */
  server?: string
  port?: number
  username: string
  password: string
  /** Optional shared-secret for proxy-level auth (-Dwebproxy.authToken). */
  authToken?: string
  /**
   * Persistent browser identifier for the shared-IP problem

   * Format: `web:<username>:<uuid>` — generated once, stored in localStorage,
   * forwarded by the proxy as Connection.userIdStr.
   */
  browserId?: string
  /**
   * My avatar id — the desktop's PreferencesDialog "My Avatar" choice,
   * selectable range 10…32 (`utils/avatars.ts`). The proxy forwards it as
   * UserData.avatarId so every PlayerView carries it; a value the proxy cannot
   * clamp to a shipped image is repaired to the default and logged. Absent =
   * default (old bundles keep working unchanged).
   */
  avatarId?: number
}

export interface GetTablesRequest { type: 'get_tables' }

/** Ask the proxy for the Argentum-P1 training deck pool (responds `p1_decks`). */
export interface GetP1DecksRequest { type: 'get_p1_decks' }
export interface PingRequest { type: 'ping' }
export interface RequestResyncRequest { type: 'request_resync' }

/**
 * Create an XMage account on the target server.
 *
 * Mirrors the desktop RegisterUserDialog: username + email matter (the server
 * generates the initial password and mails it), password/confirmation exist only
 * as the form's own check. server/port follow the connect message's rules exactly
 * — present on an unpinned proxy, omitted when the proxy pins its target.
 */
export interface RegisterRequest {
  type: 'register'
  username: string
  password: string
  email: string
  server?: string
  port?: number
  /** Optional shared-secret for proxy-level auth (-Dwebproxy.authToken). */
  authToken?: string
}

export interface CreateTableRequest {
  type: 'create_table'
  name: string
  gameType: string
  deckType: string
  winsNeeded: number
  playerConfigs: Array<{
    type: string
    deck: string
    isYou: boolean
    name?: string
    llmConfig?: Record<string, unknown>
  }>
  deck: string
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
  /** LLMage-only MatchOptions field: stock builds compile
   * the key out of the create_table frame, hence optional. */
  llmChatPublic?: boolean
  rated: boolean
  password?: string
  quitRatio?: number
  minimumRating?: number
  edhPowerLevel?: number
}

export interface JoinTableRequest {
  type: 'join_table'
  tableId: string
  deck: string
}

/**
 * Start a non-tournament match. Valid ONLY for the table's owner (the server checks
 * `isOwner(userId)` in TableController.startMatch and silently ignores anyone else).
 *
 * Before this existed the React client had no way to start a human-vs-human table at all:
 * the proxy supported `start_match` (WebProxySession.handleStartMatch) but the bundle never
 * sent it, so a table sat at READY_TO_START forever. See
 * the start_match handler).
 */
export interface StartMatchRequest {
  type: 'start_match'
  tableId: string
}

/**
 * Leave a waiting table (the desktop TableWaitingDialog's Cancel →
 * SessionHandler.leaveTable → the engine's roomLeaveTableOrTournament). The
 * proxy's `leave_table` verb is PLAYER-ONLY and Fail-Fast on a non-seated user.
 * `tableId` is OPTIONAL: omitted, the proxy leaves the table it is currently
 * watching.
 */
export interface LeaveTableRequest {
  type: 'leave_table'
  tableId?: string | undefined
}

export interface FetchModelsRequest {
  type: 'fetch_models'
  endpoint: string
  apiKey?: string
}

export interface FetchPersonalitiesRequest {
  type: 'fetch_personalities'
}

export interface SendChatRequest {
  type: 'send_chat'
  message: string
}

/** Ask for the room's online users + MOTD (responds with `users_list`). */
export interface GetRoomUsersRequest { type: 'get_room_users' }

export interface SelectChoiceRequest { type: 'select_choice'; index: number }
export interface PlayCardRequest { type: 'play_card'; cardId: string }
export interface SelectSpecialRequest { type: 'select_special'; value: string }
export interface SelectTargetRequest { type: 'select_target'; targetId: string }
export interface BooleanChoiceRequest { type: 'boolean_choice'; value: boolean }
export interface PassPriorityRequest { type: 'pass_priority' }
export interface SkipPriorityRequest { type: 'skip_priority'; skip: string }
export interface CancelSkipRequest { type: 'cancel_skip' }
export interface UndoRequest { type: 'undo' }
export interface ConcedeRequest { type: 'concede' }
export interface TapManaRequest { type: 'tap_mana'; landId: string }
export interface UseManaRequest { type: 'use_mana'; color: string; playerId: string }
export interface AmountChoiceRequest { type: 'amount_choice'; value: number }
export interface MultiAmountChoiceRequest { type: 'multi_amount_choice'; amounts: number[] }
export interface ChoosePileRequest { type: 'choose_pile'; pile: number }

export interface GetTournamentTypesRequest { type: 'get_tournament_types' }

export interface CreateTournamentRequest {
  type: 'create_tournament'
  name: string
  tournamentType: string
  gameType: string
  deckType: string
  winsNeeded: number
  numRounds: number
  singleMultiplayerGame: boolean
  allowSpectators: boolean
  rollbackTurnsAllowed: boolean
  rated: boolean
  timeLimit: string
  bufferTime: string
  skillLevel: string
  quitRatio: number
  minimumRating: number
  playerConfigs: Array<{
    type: string
    deck: string
    isYou: boolean
    name?: string
    llmConfig?: Record<string, unknown>
  }>
}

export interface JoinTournamentRequest {
  type: 'join_tournament'
  tableId: string
  deck: string
}

export interface StartTournamentRequest {
  type: 'start_tournament'
  tableId: string
}

export interface RejoinGameRequest {
  type: 'rejoin_game'
  gameId: string
}

export interface SpecialManaRequest {
  type: 'special_mana'
}

export interface ConcedeMatchRequest {
  type: 'concede_match'
}

export interface HoldPriorityRequest {
  type: 'hold_priority'
  hold: boolean
}

export interface RollbackTurnsRequest {
  type: 'rollback_turns'
}

export interface StopWatchingRequest {
  type: 'stop_watching'
}

export interface TriggerOrderRequest {
  type: 'trigger_order'
  action: 'first' | 'last' | 'name_first' | 'name_last' | 'reset_all'
  abilityId: string | null
  ruleText: string | null
}

/**
 * Remembered auto-answers — sets/clears THIS player's recurring
 * yes/no memory on the engine (HumanPlayer.requestAutoAnswerId / requestAutoAnswerText),
 * or resets the replacement-effect auto-selection list.
 *
 * Wire contract (proxy handler WebProxySession.handleAutoAnswer — keep in sync):
 *   id_yes / id_no       → REQUEST_AUTO_ANSWER_ID_YES/NO,   data = originalId + '#' + message
 *   text_yes / text_no   → REQUEST_AUTO_ANSWER_TEXT_YES/NO, data = message
 *   reset_all            → REQUEST_AUTO_ANSWER_RESET_ALL,   data = null
 *   reset_replacement    → RESET_AUTO_SELECT_REPLACEMENT_EFFECTS, data = null
 *
 * `message` MUST be the ask's autoAnswerMessage (the prompt payload's
 * `auto_answer_message`), NOT the displayed question — the engine's lookup key is
 * built from its own filtered message (source names replaced by {this}).
 * Trigger-order reset rides the existing TriggerOrderRequest 'reset_all' instead.
 */
export interface AutoAnswerRequest {
  type: 'auto_answer'
  action: 'id_yes' | 'id_no' | 'text_yes' | 'text_no' | 'reset_all' | 'reset_replacement'
  /** The ask's source ability id — required by id_yes/id_no, null for the rest. */
  originalId: string | null
  /** The ask's autoAnswerMessage — required by the four yes/no actions, null for resets. */
  message: string | null
}

export interface ViewSideboardRequest {
  type: 'view_sideboard'
}

/**
 * Hand request / permission management — the INITIATING half of
 * the desktop client's "Cards on hand (name)" submenu (PlayAreaPanel.java:403-434
 * for a seat:479-481 for a watcher). The RESPONSE half already exists: the
 * granter's UserRequestDialog sends `user_request_response` with
 * ADD_PERMISSION_TO_SEE_HAND_CARDS.
 *
 * Wire contract (proxy handlers WebProxySession.handleRequestHandPermission /
 * handleRevokeHandPermission / handleAllowHandRequests — keep in sync):
 *   request_hand_permission → REQUEST_PERMISSION_TO_SEE_HAND_CARDS, data = playerId
 *                             (the GRANTER's Player.playerId; watcher-allowed)
 *   revoke_hand_permission  → REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS, data = null
 *                             (own hand; PLAYER_ONLY)
 *   allow_hand_requests     → PERMISSION_REQUESTS_ALLOWED_ON|_OFF, data = null
 *                             (own hand; PLAYER_ONLY; ON also clears the
 *                             one-request-per-user memory)
 *
 * The granted hand comes back on every state push as `gameState.watchedHands`
 * (GameView.getWatchedHands → GameStateSerializer) — no dedicated response frame.
 */
export interface RequestHandPermissionRequest {
  type: 'request_hand_permission'
  /** UUID of the seat whose hand to ask for (the granter's `playerId`). */
  playerId: string
}

export interface RevokeHandPermissionRequest {
  type: 'revoke_hand_permission'
}

export interface AllowHandRequestsRequest {
  type: 'allow_hand_requests'
  /** true = accept hand requests (also resets the one-request-per-user memory). */
  enabled: boolean
}

/**
 * Ask the server for one seat's deck. Proxy:
 * WebProxySession.handleViewLimitedDeck → PlayerAction.VIEW_LIMITED_DECK with the
 * target player UUID; the server allows self / computer seats only and answers with
 * the `view_limited_deck` message (VIEW_LIMITED_DECK callback).
 */
export interface ViewLimitedDeckRequest {
  type: 'view_limited_deck'
  /** UUID of the seat whose deck to show (Player.playerId from game state). */
  playerId: string
}

export interface SubmitDeckRequest {
  type: 'submit_deck'
  tableId: string
  deck: { cards: DeckEntryLite[]; sideboard: DeckEntryLite[] }
}

export interface ToggleManaAutoPayRequest {
  type: 'toggle_mana_auto_pay'
  enabled: boolean
}

export interface ToggleUseFirstManaRequest {
  type: 'toggle_use_first_mana'
  enabled: boolean
}

export interface UpdatePhaseStopsRequest {
  type: 'update_phase_stops'
  stops: PhaseStopConfig
}

/**
 * Change MY avatar mid-session.
 *
 * Desktop parity: PreferencesDialog pushes a newly picked avatar to the running
 * session immediately (`SessionHandler.updatePreferencesForServer`), instead of
 * deferring it to the next connect. The proxy answers `avatar_set` with the
 * EFFECTIVE id — the picker then reflects a value the server actually stored if
 * the id had to be clamped.
 */
export interface SetAvatarRequest {
  type: 'set_avatar'
  avatarId: number
}

/** Phase stop configuration — mirrors UserSkipPrioritySteps in Java. */
export interface PhaseStopConfig {
  yourTurn: { upkeep: boolean; draw: boolean; main1: boolean; beforeCombat: boolean; endOfCombat: boolean; main2: boolean; endOfTurn: boolean }
  opponentTurn: { upkeep: boolean; draw: boolean; main1: boolean; beforeCombat: boolean; endOfCombat: boolean; main2: boolean; endOfTurn: boolean }
  stopOnDeclareBlockersWithZeroPermanents: boolean
  stopOnDeclareBlockersWithAnyPermanents: boolean
  stopOnAllMainPhases: boolean
  stopOnAllEndPhases: boolean
  stopOnStackNewObjects: boolean
}

export interface WatchGameRequest {
  type: 'watch_game'
  tableId: string
}

export interface WatchTournamentTableRequest {
  type: 'watch_tournament_table'
  tableId: string
}

export interface RefreshTournamentRequest {
  type: 'refresh_tournament'
  tournamentId: string
}

export interface UserRequestResponseRequest {
  type: 'user_request_response'
  action: string
  gameId: string | null
  relatedUserId: string | null
}

// ── Replay requests ──

/** List the main room's finished matches (desktop: the completed-matches table). */
export interface GetFinishedMatchesRequest { type: 'get_finished_matches' }

/** List `<uuid>.game` files in the server's `saved/` directory. */
export interface GetReplayFilesRequest { type: 'get_replay_files' }

/** Create the server-side replay session for a game (SessionImpl.replayGame). */
export interface ReplayLoadRequest { type: 'replay_load'; gameId: string }

/** Begin playback — the server answers with replay_init (or replay_done on a bad file). */
export interface ReplayStartRequest { type: 'replay_start' }

/** End the replay — the server answers with replay_done reason 'stopped'. */
export interface ReplayStopRequest { type: 'replay_stop' }

export interface ReplayNextRequest { type: 'replay_next' }
export interface ReplayPreviousRequest { type: 'replay_previous' }
/** Advance N frames. Server clamps to 1..100. */
export interface ReplaySkipRequest { type: 'replay_skip'; moves: number }
/**
 * Jump straight to a saved-state index (0-based) — the viewer's position
 * control. One round-trip regardless of distance, and frame 0 is reachable
 * (unlike replay_previous, which cannot step back onto the first frame).
 */
export interface ReplaySeekRequest { type: 'replay_seek'; stateIndex: number }

/** Union of all client→server messages. */
export type ClientMessage =
  | ConnectRequest
  | RegisterRequest
  | GetTablesRequest
  | GetP1DecksRequest
  | PingRequest
  | RequestResyncRequest
  | CreateTableRequest
  | JoinTableRequest
  | StartMatchRequest
  | LeaveTableRequest
  | FetchModelsRequest
  | FetchPersonalitiesRequest
  | LlmDecisionRequest
  | LlmErrorRequest
  | SendChatRequest
  | GetRoomUsersRequest
  | SelectChoiceRequest
  | PlayCardRequest
  | SelectSpecialRequest
  | SelectTargetRequest
  | BooleanChoiceRequest
  | PassPriorityRequest
  | SkipPriorityRequest
  | CancelSkipRequest
  | UndoRequest
  | ConcedeRequest
  | TapManaRequest
  | UseManaRequest
  | AmountChoiceRequest
  | MultiAmountChoiceRequest
  | ChoosePileRequest
  | GetTournamentTypesRequest
  | CreateTournamentRequest
  | JoinTournamentRequest
  | StartTournamentRequest
  | RejoinGameRequest
  | SpecialManaRequest
  | ConcedeMatchRequest
  | HoldPriorityRequest
  | RollbackTurnsRequest
  | StopWatchingRequest
  | TriggerOrderRequest
  | AutoAnswerRequest
  | ViewSideboardRequest
  | ViewLimitedDeckRequest
  | RequestHandPermissionRequest
  | RevokeHandPermissionRequest
  | AllowHandRequestsRequest
  | SubmitDeckRequest
  | ToggleManaAutoPayRequest
  | ToggleUseFirstManaRequest
  | UpdatePhaseStopsRequest
  | SetAvatarRequest
  | WatchGameRequest
  | WatchTournamentTableRequest
  | RefreshTournamentRequest
  | UserRequestResponseRequest
  | GetFinishedMatchesRequest
  | GetReplayFilesRequest
  | ReplayLoadRequest
  | ReplayStartRequest
  | ReplayStopRequest
  | ReplayNextRequest
  | ReplayPreviousRequest
  | ReplaySkipRequest
  | ReplaySeekRequest
  | import('./deck').CardSearchRequest
  | import('./deck').CardLookupRequest
  | import('./deck').CardVersionsRequest
  | import('./deck').LegalityCheckRequest
  | TokenVersionsRequest
  | TokenArtPreferenceRequest

// ── Token art (print-version selection + per-player preferences) ──

/**
 * Ask the proxy for a token's candidate art printings.
 *
 * A token name is not addressable as a card image (`named?exact=Soldier` returns an
 * unrelated card; `named?exact=Soldier Token` 404s), so the proxy resolves an ordered
 * list of `api.scryfall.com/cards/t<set>/<n>` candidates from XMage's own
 * tokens-database.
 *
 * `expand: false` is answered from engine data with NO network access. Set
 * `expand: true` only after every engine candidate has failed to load — that lets the
 * proxy consult Scryfall (for sets whose t-mirror is missing, and for tokens that copy
 * a real card), which costs rate-limit budget.
 */
export interface TokenVersionsRequest {
  type: 'token_versions'
  tokenName: string
  /** false = engine printings only (no network). Default false. */
  expand?: boolean
  requestId?: string
}

/** One candidate printing of a token. */
export interface TokenArtVersionMessage {
  url: string
  setCode?: string
  collectorNumber?: string
  /** 'engine' = XMage tokens-database; 'scryfall-token'/'scryfall-card' = searched. */
  source?: string
}

export interface TokenVersionsResultMessage {
  type: 'token_versions_result'
  requestId?: string
  result?: {
    tokenName: string
    versions: TokenArtVersionMessage[]
    /** The engine's preferred art (first candidate), when there is one. */
    defaultUrl?: string
    /** True when Scryfall could not be reached — "unknown", not "no art exists". */
    fetchFailed?: boolean
  }
  error?: string
}

/**
 * Share (or clear) the art I want for a token type, relayed to the other players at
 * this table.
 *
 * A null/empty `url` clears the choice so the token falls back to the engine's default.
 * The proxy validates the URL against the Scryfall image-host allowlist before
 * broadcasting it, because this string reaches every opponent's board.
 */
export interface TokenArtPreferenceRequest {
  type: 'token_art_preference'
  tokenName: string
  /** Chosen art URL, or null/absent to clear the choice. */
  url?: string | null
  setCode?: string
  collectorNumber?: string
}

/** One player's shared choice, as relayed by the proxy. */
export interface SharedTokenArtPreference {
  playerId: string
  tokenName: string
  url: string
  setCode?: string
  collectorNumber?: string
  playerName?: string
}

/**
 * A token-art choice made by someone at this table (or the full snapshot on join),
 * so every client renders the owner's chosen art.
 */
export interface TokenArtPreferencesMessage {
  type: 'token_art_preferences'
  gameId: string
  preferences: SharedTokenArtPreference[]
}

/**
 * The proxy's answer to `set_avatar`: the EFFECTIVE avatar id after the
 * proxy's clamp. A client that sent an id with no shipped image (hand-crafted
 * frame, stale localStorage) learns which value the server actually got instead
 * of assuming its own.
 */
export interface AvatarSetMessage {
  type: 'avatar_set'
  avatarId: number
}

// ── Shared data types ──

export interface TableInfo {
  id: string
  name: string
  gameType: string
  playerCount: number
  maxPlayers: number
  state: string
  /** Player names seated at the table (empty seats omitted). */
  seatNames?: string[]
  /** Active game ids — present only for tables that have started games (rejoin). */
  gameIds?: string[]
  /** Deck type label, e.g. "Commander". Absent when the server sent none. */
  deckType?: string
  /** Table creation/start time as epoch millis. Absent when unknown. */
  createTimeMs?: number
  rated?: boolean
  passworded?: boolean
  /** Spectators allowed — absent means allowed (backend work pending; default on). */
  spectatorsAllowed?: boolean
  isTournament?: boolean
  /** Skill level text, e.g. "Casual". Absent when unknown. */
  skillLevel?: string
  /** Table owner line — controller name (+ opponents for started tables), stock "Owner" column. */
  controllerName?: string
  /**
   * Candidate 2:
   * server-authoritative "this table is yours" flag. Set by the proxy
   * (WebProxyClient.serializeTableList) from TableController.isOwner — the same
   * check startMatch enforces — for non-tournament WAITING/READY_TO_START tables
   * only. ABSENT means "not yours or unknown": the proxy never emits a false
   * claim, and a proxy that cannot ask emits nothing. The grid uses it to offer
   * the Start Match control to a non-seated owner, and because it rides the
   * table list it survives a page reload (no browser-side memory involved).
   */
  owner?: boolean
}

/**
 * Player slot config for table creation — from app.js GameSetupDialog.
 *
 * `name` (seat-name editing): the seat's chosen display name, as typed in
 * the seat row. ABSENT means "no preference" — the proxy then derives the
 * default (`WebProxySession.handleCreateTable`/`handleCreateTournament`:
 * `pc.has("name") ? … : (pt.isAI() ? "AI-" + i : username)`), i.e. the
 * connected username for a human seat and `AI-<seatIndex>` for an AI seat.
 * Never set it to an empty string: omit the field instead (an empty value is
 * treated the same way, but omitting keeps the wire frame identical to the
 * previous one — see `utils/seatNames.seatNameForWire`).
 *
 * The field is ONLY populated for the "You" seat and for AI/LLM seats: a second
 * HUMAN seat is filled by a later joiner whose own login name arrives with their
 * join (`handleJoinTable` joins as `proxyClient.getUsername()`), so naming it
 * here would be overwritten and is deliberately not offered.
 */
export interface PlayerSlot {
  type: string  // 'HUMAN' | 'COMPUTER_MAD' | 'COMPUTER_MONTE_CARLO' | 'COMPUTER_LLM'
  isYou: boolean
  deck: string
  showLLMConfig: boolean
  llmConfig: Record<string, unknown>
  /** Typed seat name; absent (or whitespace-only) = proxy-derived default. */
  name?: string
}

/** LLM config field definition — from app.js llmConfigSections. */
export interface LLMConfigField {
  key: string
  label: string
  type: 'text' | 'password' | 'number' | 'checkbox' | 'select' | 'model_select' | 'personality_select' | 'endpoint_select'
  value: unknown
  placeholder?: string
  options?: string[]
  min?: number
  max?: number
  step?: number
}

/** LLM config section — groups of fields for the setup UI. */
export interface LLMConfigSection {
  name: string
  fields: LLMConfigField[]
}

/** LLM config fields — from app.js LLM_CONFIG_FIELDS. */
export interface LLMConfig {
  model?: string
  apiEndpoint?: string
  apiKey?: string
  useClientKey?: boolean
  clientKeyTag?: string
  reasoningEffort?: string
  thinkingEnabled?: boolean
  temperatureEnabled?: boolean
  temperature?: number
  maxTokens?: number
  timeoutSeconds?: number
  streamingEnabled?: boolean
  verboseLogging?: boolean
  deckAssessmentEnabled?: boolean
  concedeEnabled?: boolean
  askReason?: boolean
  transcriptDir?: string
  personalityEnabled?: boolean
  personalityName?: string
  botName?: string
  conversationMaxExchanges?: number
  conversationMaxHistoryChars?: number
  conversationThinkCharsPerMsg?: number
  summarizeTriggerChars?: number
  summarizeContextRatio?: number
  modelContextWindow?: number
  summarizeTargetChars?: number
  summarizeTargetRatio?: number
  summarizeKeepExchanges?: number
}

/** Tournament type info — from WebProxySession.handleGetTournamentTypes(). */
export interface TournamentTypeInfo {
  name: string
  minPlayers: number
  maxPlayers: number
  numBoosters: number
  draft: boolean
  limited: boolean
  cubeBooster: boolean
  elimination: boolean
  random: boolean
  reshuffled: boolean
  richMan: boolean
  jumpstart: boolean
}

/** Tournament setup config — from TournamentSetupDialog state. */
export interface TournamentSetupConfig {
  name: string
  tournamentType: string
  gameType: string
  deckType: string
  winsNeeded: number
  numRounds: number
  numPlayers: number
  singleMultiplayerGame: boolean
  allowSpectators: boolean
  rollbackTurnsAllowed: boolean
  rated: boolean
  timeLimit: string
  bufferTime: string
  skillLevel: string
  quitRatio: number
  minimumRating: number
  playerSlots: PlayerSlot[]
}