import type { StateCreator } from 'zustand'
import type { GameplayState, GameStore } from '../types'
// Optimistic allow-toggle intent: the engine fires no state push for the
// permission actions, so the checkbox must remember what the user last sent —
// see handWatch.ts's allow-intent block.
import { setAllowIntent } from './handWatch'
import type { ServerMessage, ActionPrompt, ActionPromptMessage, ActionChoice, ActionTarget, LookAtCard, GameState, GameEvent, ClientMessage, HistoryBackfillMessage } from '@/types'
import { stripServerHtml } from '@/utils/textFormat'
import {
  parseSlashCommand,
  SLASH_COMMAND_HELP,
  activeChatList,
  nextLocalChatSeq,
} from '@/utils/slashCommands'
import { onPromptClearedForAutoTapRun, onPromptSetForAutoTapRun } from './autoTapRun'
// Priority ding. Additive —
// fires once per NEW prompt arrival; it sends nothing and reads no run state,
// so the auto-tap machinery below is untouched.
import { onPromptClearedForPriorityDing, onPromptSetForPriorityDing } from './priorityDing'
// Audio: event-sound triggers for stack growth + the skip buttons. The
// prompt-arrival moment stays owned by the priority ding above — no event sound
// fires there (see audio/audioTriggers.ts's non-trigger note).
import { onCancelSkipClicked, onGameStatePushed, onSkipPriorityClicked } from '@/audio/audioTriggers'
// Targeting arrows: track which card the player
// activated so TargetingArrows can draw source→target while a target prompt
// is open. Additive only — the prompt lifecycle semantics below are untouched.
import {
  setPendingActionSource,
  setPendingActionSourceName,
  getPendingActionSourceId,
  clearPendingActionSource,
} from './pendingActionSource'

// ── Prompt lifecycle: repeat-activation guard (2026-09-14 / 2026-09-11) ──
//
// The server consumes exactly ONE answer per prompt, so a second activation of
// the same choice before the server moves on is either a double-click or a click
// on a prompt that has already been resolved. Both used to be forwarded, and a
// late answer lands on a LATER prompt as a misdelivered choice (the 9-duplicate
// `select_target` burst, the double-click `select_choice` toast in the
// 2026-09-11 report, the 12-click mulligan burst).
//
// The notion is an "epoch": the epoch advances whenever the prompt lifecycle
// moves on — a new action_prompt arrives, the prompt is cleared, or server state
// proves the server is no longer waiting for us (setGameState clears the prompt
// when a state push names a DIFFERENT player as the priority holder). An answer
// recorded against an older epoch no longer counts, so genuinely live prompts
// (combat re-prompts, multi-target sequences, mana re-prompts) keep accepting
// the next legitimate answer.
//
// Kept in module scope (same pattern as uiSlice's errorToastTimer) because the
// store's declared shape (src/store/types.ts) is owned by another agent — do not
// widen GameStore here.
let promptEpoch = 0
let answeredEpoch = -1
let answeredKey: string | null = null

/**
 * Lifecycle state of the prompt this client is working on:
 *  - 'none'    — no prompt outstanding (before a prompt, after a local hide, or
 *                after a rejoin/resync). Answers are allowed: the rail still
 *                offers Pass Priority while the server says we hold priority.
 *  - 'open'    — the server is waiting on the prompt we rendered.
 *  - 'resolved'— the prompt was answered AND server state has since proved the
 *                server moved on. It must refuse clicks until a NEW prompt
 *                arrives: stale buttons
 *                kept answering prompts that no longer existed and the clicks
 *                landed on later prompts as misdelivered answers.
 */
let promptState: 'none' | 'open' | 'resolved' = 'none'

/**
 * Prompt context the proxy writes for the mulligan family
 * (WebProxyClient.handleGameAsk: any GAME_ASK whose server text mentions
 * mulligan / keep / hand — the keep/mulligan question itself, incl. the free
 * and "draw another" variants, and the pre-game keep-hand wording).
 *
 * Typed `string` on purpose: the wire value is what gates the local withdraw in
 * `booleanChoice`, while `ActionContext` (src/types/enums.ts) is the narrower
 * client-side union that predates this proxy context.
 */
const MULLIGAN_CONTEXT: string = 'mulligan'

/** Advance the prompt epoch — every answer recorded before this is stale. */
function advancePromptEpoch(): void {
  promptEpoch += 1
}

/**
 * Answer keys — the identity of one activation within one prompt. The click
 * site (GameView) and the store actions must agree on these, so both build them
 * through this one helper.
 */
export const answerKeys = {
  selectChoice: (index: number) => `select_choice:${index}`,
  playCard: (cardId: string) => `play_card:${cardId}`,
  selectTarget: (targetId: string) => `select_target:${targetId}`,
  booleanChoice: (value: boolean) => `boolean_choice:${value}`,
  selectSpecial: (value: string) => `select_special:${value}`,
  amountChoice: (value: number) => `amount_choice:${value}`,
  multiAmountChoice: (amounts: number[]) => `multi_amount_choice:${amounts.join(',')}`,
  choosePile: (pile: number) => `choose_pile:${pile}`,
  tapMana: (landId: string) => `tap_mana:${landId}`,
  specialMana: () => 'special_mana',
  passPriority: () => 'pass_priority',
}

/**
 * True when `key` has already been answered on the CURRENT prompt — a repeat
 * activation (double-click, or a click after the server consumed the answer)
 * that must be dropped instead of forwarded.
 *
 * A RESOLVED prompt refuses EVERY answer, not just the repeat: its buttons are
 * stale UI, and anything sent from it lands on a later prompt.
 */
export function isRepeatAnswer(key: string): boolean {
  if (promptState === 'resolved') return true
  return answeredEpoch === promptEpoch && answeredKey === key
}

/** Record that `key` has been answered on the current prompt. */
function markAnswered(key: string): void {
  answeredEpoch = promptEpoch
  answeredKey = key
}

/**
 * True when server state says the priority window belongs to somebody else.
 * An EMPTY `priorityPlayer` is NOT "somebody else" — that is the pre-game
 * mulligan / cleanup-discard case where priority is deliberately left unset
 * (live transcripts show `Priority: —` with a live mulligan prompt up).
 */
function priorityHeldByAnother(state: GameState): boolean {
  const me = state.players.find((p) => p.isMe)
  if (!me) return false // spectator — never holds priority
  if (state.priorityPlayer === '') return false
  return state.priorityPlayer !== me.name
}

/**
 * True when the outstanding prompt has been RESOLVED per server state: this
 * client already sent its answer AND the state push now names a DIFFERENT
 * player as the priority holder.
 *
 * Both halves are load-bearing.
 *  - "Priority is someone else's" alone is NOT enough: the engine only reassigns
 *    `state.priorityPlayerId` when the next player is ASKED
 *    (`HumanPlayer.prepareForResponse()`), so a state push naming the opponent
 *    can arrive while our own prompt is genuinely live and unanswered (observed
 *    live: an unanswered GAME_SELECT on one client while the opponent's pass
 *    produced state pushes). Clearing there would delete a live prompt — exactly
 *    the regression F1 warns against.
 *  - "We answered" alone is NOT enough either: a state push can land between the
 *    click and the server consuming it, while the prompt is still live.
 * Together they cover every answered-then-abandoned prompt (the mulligan in
 * F-A2, whose next state named the opponent) without touching a live one.
 */
function promptResolvedBy(state: GameState): boolean {
  if (answeredEpoch !== promptEpoch) return false // nothing answered on this prompt yet
  return priorityHeldByAnother(state)
}

/**
 * A game_init rebuilds the client's whole game state (first join, rejoin, full
 * resync). Whatever prompt lifecycle was in flight belongs to the old state, so
 * the guard is disarmed — otherwise a rejoin could leave the F3 "no prompt but
 * it is your priority" Pass Priority button permanently dead because the answer
 * recorded before the rejoin still matched the current epoch (or because the
 * prompt had been marked resolved and no fresh prompt ever arrives).
 */
export function resetPromptLifecycle(): void {
  advancePromptEpoch()
  promptState = 'none'
  answeredKey = null
  // A rejoin/resync rebuilds the whole game state, so any in-flight auto-tap run
  // belongs to a payment this client can no longer see — end it rather than let its
  // already-tapped set leak into whatever prompt arrives next.
  onPromptClearedForAutoTapRun()
}

// ── Game-log reset across games in one session (issues 2026-09-14 F-A14) ──
//
// A new game in the same session must start with an empty game log; without this
// the previous game's result sits above the new game's start and the log
// contradicts itself. Keyed on the gameId announced by `start_game`: a rejoin
// re-announces the SAME id and must keep the log (that log is the only remaining
// history for a live game). Consumed lazily by the first game-log write, so
// nothing of the new game's output is dropped by push ordering.
//
// ── The key must SURVIVE a page reload ──
//
// Module memory alone is wiped by an F5, so the rejoin's `start_game`
// re-announcement of the SAME game looked like a new game on the reloaded page:
// it replayed the game-start cue (audioTriggers.onGameStartAnnounced) and armed
// this log reset. The known id therefore also lives in sessionStorage — the same
// per-tab store the connect credentials use (`xmage-connect-creds`,
// ConnectOverlay) — so a reloaded tab still knows "this gameId is already known
// to me". sessionStorage is per-tab by construction: a second tab has its own
// copy (or none), so nothing leaks across tabs.
//
// A STALE id is harmless by construction: game ids are per-game UUIDs, so a
// genuinely new game can never reuse an old id — `gameId === known` is an exact
// test, and anything else is a new game. The key is cleared on game end, logout
// and disconnect (forgetGameKey, called from messageHandlers / connectionSlice).
let currentGameKey: string | null = null
let pendingLogReset = false

/** sessionStorage key holding the game id this tab already knows (per-tab, like creds). */
const GAME_KEY_STORAGE = 'xmage-active-game'

/** The stored game id, or null when storage is unavailable or has no entry. */
function readStoredGameKey(): string | null {
  // Storage unavailable (harness without the stub / privacy mode) is
  // environmental, not a logic failure — the same try/catch convention as
  // ConnectOverlay and connectionSlice.logout. Module memory above still
  // dedupes re-announcements within the page's own life.
  try {
    return sessionStorage.getItem(GAME_KEY_STORAGE)
  } catch {
    return null
  }
}

/** Write-through the known game id (module memory + sessionStorage). */
function rememberGameKey(gameId: string): void {
  currentGameKey = gameId
  try {
    sessionStorage.setItem(GAME_KEY_STORAGE, gameId)
  } catch { /* ignore — see readStoredGameKey */ }
}

/**
 * Log lines the panels must not render. Same rule as `appendEvent` — and it has
 * to be applied to a backfill too: the proxy's buffer holds everything it sent,
 * including the "Waiting for <player>" per-state-push spam that
 * `appendEvent` drops on the way in. Without the filter a rejoin would restore
 * 100+ spam rows that the live panel had never shown.
 */
const SPAM_RE = / skip attack$|^Attacker: .+ unblocked$| - Waiting for .+$/

function isDisplayable(event: GameEvent): boolean {
  return event.type === 'talk' || !SPAM_RE.test(event.message || '')
}

/** Strip server HTML from an event exactly as the live append path does. */
function normalizeEvent(event: GameEvent): GameEvent {
  if (event.type === 'talk') {
    return { ...event, message: stripServerHtml(event.message || ''), from: stripServerHtml(event.from || '?') }
  }
  return { ...event, message: stripServerHtml(event.message || '') }
}

/**
 * Union `incoming` into `existing` by event `seq` (the proxy's single monotonic
 * counter), then sort by seq so the panel reads chronologically even when the
 * backfill is interleaved with a live event. The tail is capped at the same 200
 * entries `appendEvent` uses.
 *
 * `incoming` wins on a seq collision: for the same seq the two copies are the
 * same event, and the backfill copy is the one that carries the proxy's original
 * text.
 */
function mergeBySeq(existing: GameEvent[], incoming: GameEvent[]): GameEvent[] {
  const bySeq = new Map<number, GameEvent>()
  for (const e of existing) {
    if (isDisplayable(e)) bySeq.set(e.seq, normalizeEvent(e))
  }
  for (const e of incoming) {
    if (isDisplayable(e)) bySeq.set(e.seq, normalizeEvent(e))
  }
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq).slice(-200)
}

/**
 * Note the gameId announced by a `start_game` message.
 * @returns true when this is a NEW game (the caller should drop state tied to
 * the previous one), false when it is a re-announcement of the current game
 * (rejoin) — in the SAME page or after a reload (the stored key, above).
 */
export function markGameStart(gameId: string): boolean {
  const known = gameId !== '' && (gameId === currentGameKey || gameId === readStoredGameKey())
  if (known) {
    // Refresh the memory (a post-reload match learned through sessionStorage):
    // the SECOND re-announcement of the same rejoin (`start_game` is sent once
    // by the rejoin handler and again by the server's START_GAME callback) hits
    // the module path and stays silent too.
    rememberGameKey(gameId)
    return false
  }
  currentGameKey = gameId
  if (gameId !== '') {
    try {
      sessionStorage.setItem(GAME_KEY_STORAGE, gameId)
    } catch { /* ignore — see readStoredGameKey */ }
  }
  pendingLogReset = true
  return true
}

/**
 * The proxy OFFERED a rejoin for `gameId` (`rejoin_game` wire message — sent
 * before the rejoin is performed, so it always precedes the rejoin's
 * `start_game`). Record the id then and there: a page that has no memory of
 * the game yet (a freshly opened second tab; a reloaded tab whose storage was
 * cleared) still treats the `start_game` that follows as a rejoin, not a game
 * start. Same-page/memory cases are unaffected — the id is the one already
 * known.
 */
export function noteRejoinOffered(gameId: string): void {
  if (gameId === '') return
  rememberGameKey(gameId)
}

/**
 * Forget the known game id (module memory + sessionStorage). Called when this
 * page's session with the game is over for good: game end (messageHandlers
 * `game_over`), logout and disconnect (connectionSlice). The next game is a
 * new UUID, so a stale entry could not mis-classify it anyway — this keeps the
 * stored state tidy and bounded to the tab's current session.
 */
export function forgetGameKey(): void {
  currentGameKey = null
  try {
    sessionStorage.removeItem(GAME_KEY_STORAGE)
  } catch { /* ignore — see readStoredGameKey */ }
}

/**
 * Build an ActionPrompt from a raw ActionPromptMessage.
 * Ported from app.js handleActionPrompt + _build*Prompt methods.
 * This will be expanded in Phase 4 with full per-type building.
 * For now, we create a basic prompt that captures the common fields.
 */
function buildActionPrompt(msg: ActionPromptMessage): ActionPrompt {
  const base = {
    message: stripServerHtml(msg.message || 'Select an action'),
    rawMessage: msg.message || '',
    context: (msg.context as ActionPrompt['context']) || 'default',
  }

  switch (msg.action_type) {
    case 'boolean':
      return {
        ...base,
        type: 'boolean',
        yesLabel: msg.yes_label || 'Yes',
        noLabel: msg.no_label || 'No',
        // Auto-answer key material — passed through verbatim from the proxy's
        // boolean payload. Both stay optional: a source-less ask has no original_id,
        // and a payload missing auto_answer_message is malformed rather than guessed
        // at (the BooleanPrompt affordances gate on presence).
        originalId: msg.original_id,
        autoAnswerMessage: msg.auto_answer_message,
      }

    case 'select':
      return {
        ...base,
        type: 'select',
        choices: (msg.choices || []).map((c) => {
          const choice: ActionChoice = {
            index: c.index,
            id: c.id,
            name: stripServerHtml(c.name),
          }
          if (c.action) {
            const actionMap: Record<string, ActionChoice['action']> = {
              land: 'land',
              cast: 'cast',
              activate: 'activate',
              attack: 'attack',
              block: 'block',
            }
            const mapped = actionMap[c.action]
            if (mapped) choice.action = mapped
          }
          if (c.manaCost) choice.manaCost = c.manaCost
          return choice
        }),
        isCombat: (msg.choices || []).some((c) => c.action === 'attack' || c.action === 'block'),
        combatChoiceIds: (msg.choices || [])
          .filter((c) => c.action === 'attack' || c.action === 'block')
          .map((c) => c.id),
        specialButton: msg.special_button || undefined,
      }

    case 'target':
      return {
        ...base,
        type: 'target',
        targets: (msg.targets || []).map((t) => {
          const target: ActionTarget = {
            id: t.id,
            name: stripServerHtml(t.name),
            type: t.type,
          }
          if (t.abilityText) target.abilityText = stripServerHtml(t.abilityText)
          return target
        }),
        // Trigger order lists N orderable abilities but consumes exactly ONE pick
        // and the proxy reports target_count=1 for that family.
        //
        // For every OTHER target prompt the engine transmits no min/max at all,
        // so a count is only claimed when the proxy could derive one — 0 means "the
        // engine did not say, do not announce a number" (the desktop client does not
        // either; it relies on the engine's Done button). `|| 1` must NOT be used here:
        // it converts that honest 0 into a false "Select 1 target" claim.
        targetCount: msg.target_count ?? 0,
        maxTargets: msg.max_targets ?? 0,
        validTargetTypes: msg.valid_target_types || [],
        selectedIds: [],
        required: msg.required ?? false,
        min: msg.min || 1,
        max: msg.max || 1,
        triggerOrder: msg.trigger_order ?? false,
        lookAtCards: (msg.lookAtCards || []).map((c: Record<string, unknown>) => {
          const card: LookAtCard = {
            name: String(c.name || ''),
            typeLine: String(c.typeLine || ''),
            manaCost: String(c.manaCost || ''),
            rules: Array.isArray(c.rules) ? c.rules as string[] : [],
            id: c.id ? String(c.id) : null,
            imageUrl: String(c.imageUrl || ''),
            isValidTarget: !!c.isValidTarget,
          }
          if (c.sourceName) card.sourceName = String(c.sourceName)
          if (c.sourceImageUrl) card.sourceImageUrl = String(c.sourceImageUrl)
          if (c.abilityText) card.abilityText = stripServerHtml(String(c.abilityText))
          return card
        }),
        lookAtCardsValidIds: msg.lookAtCardsValidIds || [],
        doneButtonText: msg.done_button_text || undefined,
        chosenTargetIds: msg.chosen_targets || [],
        chosenCount: msg.chosen_count || 0,
        // NOT_TARGET card lists (imprint/surveil-style "choose a card") are not
        // targeting — TargetingArrows gates on this.
        notTarget: msg.not_target ?? false,
      }

    case 'choose_choice':
      // The proxy writes the option text as `label`
      // (WebProxyClient.handleGameChooseChoice), and NEVER as `name` — reading
      // `o.name` yielded undefined for every option, so ChooseChoicePrompt fell
      // back to its `Choice N` placeholder and the player could not tell what
      // they were choosing (verified live for alternative costs and
      // `choose_color`). Read the field the server actually sends.
      //
      // The cast is the boundary between the (wrong) shared message type and the
      // real wire payload; `options` is declared `{index,name}` in
      // src/types/messages.ts, owned by another agent (see ChooseChoiceOption).
      {
        const options = (msg.options ?? []) as unknown as ChooseChoiceOption[]
        // Fail Fast: an option with no label must not silently render as
        // "Choice N" — that placeholder is what disguised this defect for weeks.
        // setActionPrompt catches and surfaces this as a visible error toast.
        for (const o of options) {
          if (!o.label) {
            throw new Error(
              `choose_choice option ${o.index} has no 'label' — the server sent ${JSON.stringify(o)}`
            )
          }
        }
        return {
          ...base,
          type: 'choose_choice',
          choices: options.map((o) => {
            const choice: { index: number; name: string; order?: number } = {
              index: o.index,
              name: stripServerHtml(o.label),
            }
            // Engine relevance, verbatim: mage.choices.ChoiceCreatureType.onChooseStart
            // ranks each creature type on your own board/hand (me = -100), an
            // opponent's (opponent = -10) and leaves the rest at 0. Absent means the
            // engine did not rank this option — never treat it as suggested.
            if (typeof o.order === 'number') choice.order = o.order
            return choice
          }),
        }
      }

    case 'choose_ability':
      return {
        ...base,
        type: 'choose_ability',
        choices: (msg.abilities || []).map((a) => ({
          index: a.index,
          name: a.name,
          abilityText: stripServerHtml(a.abilityText),
        })),
      }

    case 'amount':
      return {
        ...base,
        type: 'amount',
        min: msg.min || 0,
        max: msg.max || 999,
        defaultValue: msg.default_value || 0,
      }

    case 'play_mana':
      return {
        ...base,
        type: 'play_mana',
        manaOptions: (msg.mana_options || []).map((m) => ({
          landId: m.landId,
          landName: m.landName,
          colors: m.colors,
          auto: m.auto,
        })),
        // Per-source engine verdicts (Courtyard autotap option 2). Spread only
        // when the payload really carries a map: an absent field means the
        // server did not evaluate, and the planner must then fall back to its
        // rule-text characterisation — never to a guess. (A present-but-empty
        // map is kept as-is: it says "the engine evaluated and nothing
        // qualified", which is knowledge, not absence.)
        ...(msg.mana_payable_for_this_cost !== undefined
          ? { manaPayableForThisCost: msg.mana_payable_for_this_cost }
          : {}),
        remainingCost: msg.remaining_cost || '',
        autoPayAvailable: msg.auto_pay_available ?? false,
        specialAvailable: msg.special_available ?? false,
      }

    case 'choose_pile':
      return {
        ...base,
        type: 'choose_pile',
        pile1: msg.pile1 || { index: 0, cards: [] },
        pile2: msg.pile2 || { index: 1, cards: [] },
      }

    case 'multi_amount':
      return {
        ...base,
        type: 'multi_amount',
        entries: (msg.entries || []).map((e) => ({
          id: e.id,
          name: e.name,
          min: e.min,
          max: e.max,
          value: e.value,
        })),
        total: msg.total || 0,
        min: msg.min || 0,
        max: msg.max || 0,
      }

    default: {
          // Fail Fast: unknown action prompt type must NOT silently degrade to a
          // generic boolean/OK-Cancel prompt. Throw so the caller (setActionPrompt)
          // catches and surfaces a visible error to the user.
          const errorMsg = `Unknown action prompt type: '${msg.action_type}'. This client may not support this prompt type.`
          console.error('[buildActionPrompt]', errorMsg, msg)
          throw new Error(errorMsg)
        }
  }
}

/**
 * One entry of the `choose_choice` option list EXACTLY as the proxy writes it
 * (WebProxyClient.handleGameChooseChoice — `c.put("label", entry.getValue())`),
 * e.g. `{"index":0,"key":"1","label":"Cast with alternative cost: An opponent
 * gains 3 life (source: Invigorate)"}`.
 *
 * NOTE: `ActionPromptMessage.options` in src/types/messages.ts is declared as
 * `{index, name}` — that declaration is WRONG (no `name` key is ever sent) and
 * is owned by another agent; it must be corrected there. This boundary reads the
 * real wire field so the prompt is not rendered label-less (2026-09-14).
 */
interface ChooseChoiceOption {
  index: number
  key?: string
  label: string
  /** Engine relevance for creature-type choosers
   *  (ChoiceCreatureType.onChooseStart: me = -100, opponent = -10); absent when the
   *  option was not ranked. Forwarded as-is by WebProxyClient.handleGameChooseChoice. */
  order?: number
}

export const createGameplaySlice: StateCreator<
  GameStore,
  [],
  [],
  GameplayState
> = (set, get) => {
  /**
   * Send one answer to the server, dropping an activation that must not be
   * forwarded (F1/F2 guard). Returns false when dropped.
   *
   * Dropped when:
   *  - the prompt is RESOLVED — its buttons are stale UI and anything sent from
   *    them lands on a later prompt as a misdelivered answer; or
   *  - the same choice has already been answered on this same prompt — the
   *    double-click case (2026-09-11) and the duplicate-send storm.
   *
   * A different choice on the same prompt still goes out, and once the server
   * moves on (new action_prompt, or a state push that clears a resolved prompt)
   * the same choice is accepted again — so genuinely live multi-part prompts are
   * not regressed. The 'none' state stays answerable so the rail's Pass Priority
   * works while priority is held with no prompt rendered.
   */
  const sendAnswer = (key: string, message: ClientMessage): boolean => {
    if (isRepeatAnswer(key)) {
      console.debug('[prompt] ignoring activation:', key, '(state:', promptState + ')')
      return false
    }
    const { ws } = get()
    if (!ws) return false
    markAnswered(key)
    ws.send(message)
    return true
  }

  return {
  // State
  gameState: null,
  lastRawState: null,
  inGame: false,
  actionPrompt: null,
  currentActionPrompt: null,
  passPending: false,
  gameEnd: null,
  eventLog: [],
  chatMessages: [],
  playerColorMap: {},
  // Tournament running view
  tournamentData: null,
  tournamentId: null,
  showTournamentRunning: false,

  // Between-games sideboarding
  sideboardPrompt: null,
  sideboardSubmitRejectedAt: 0,

  // Actions
  setGameState: (state) => {
    // Audio: a stack LENGTH increase is a new object on the stack (the
    // desktop's playStackNew). Computed against the state this push replaces —
    // every live channel (game_init/update/delta) routes through here, and a
    // rejoin/spectate first push (prev === null) stays silent by design.
    onGameStatePushed(get(), get().gameState, state)
    // ── Prompt lifecycle ──
    // A state push means the server has produced new state, so any pass we sent
    // has either been consumed (priority moved / a prompt would follow) or the
    // engine is still thinking with nothing new to show. Either way the rail must
    // stop claiming "pass sent — waiting" and go back to offering the controls:
    // a further click is legitimate again (the epoch guard in setGameState below
    // re-arms the 'none' state while the priority is ours).
    const wasPassPending = get().passPending
    // A state push can prove an outstanding prompt was resolved: if we already
    // answered it and the server has since named someone else as the priority
    // holder, the server is done with us. The prompt must then stop accepting
    // clicks — it used to stay live for the whole of the opponent's turn,
    // re-answering a dead prompt on every click (2026-09-14: a
    // mulligan prompt rendered 12 further answers after it was resolved, and a
    // stale `select_target` burst landed on later prompts as misdelivered
    // answers).
    const prompt = get().actionPrompt
    if (prompt != null && promptResolvedBy(state)) {
      console.debug('[prompt] resolved — server priority moved to', state.priorityPlayer)
      advancePromptEpoch()
      promptState = 'resolved'
      // Targeting arrows: the prompt is over — drop its source so no stale
      // arrow survives into the next prompt (arrows clear with the prompt).
      clearPendingActionSource()
      set({ gameState: state, lastRawState: state, actionPrompt: null, currentActionPrompt: null, passPending: false })
      // The server proved this prompt resolved, so an in-flight auto-tap run is over —
      // it must not survive into a later payment the user did not ask to auto-tap.
      onPromptClearedForAutoTapRun()
      // Priority ding: forget the continuation/epoch memory with the prompt.
      onPromptClearedForPriorityDing()
      return
    }
    // With NO prompt rendered but the priority OURS, the rail's Pass Priority
    // (and the rest of the controls) must always work — that is exactly the
    // rejoin wedge, and it is also the state left behind by a local
    // hide (a pass whose answer the server has not processed yet). Any answer
    // recorded earlier is stale by definition: disarm it so the controls are live.
    if (prompt == null && !priorityHeldByAnother(state) && state.priorityPlayer !== '') {
      advancePromptEpoch()
      promptState = 'none'
      // Targeting arrows: no prompt is rendered, so any pending source belongs
      // to a prompt that is gone (state proves resolution / hide).
      clearPendingActionSource()
    }
    set(wasPassPending
      ? { gameState: state, lastRawState: state, passPending: false }
      : { gameState: state, lastRawState: state })
  },

  setInGame: (inGame) => set({ inGame }),

  setPlayerColorMap: (map) => set({ playerColorMap: map }),

  setActionPrompt: (msg: ServerMessage) => {
    const actionMsg = msg as ActionPromptMessage
    // A new prompt supersedes whatever was outstanding: everything recorded
    // against the previous epoch is stale, and a previously RESOLVED prompt is
    // replaced by this fresh one (F1).
    advancePromptEpoch()
    promptState = 'open'
    // Targeting arrows: a click on play_card/select_choice may have stamped
    // the source of THIS prompt (the bolt the player just cast). A source
    // stamped outside the click→prompt round-trip window reads as null (the
    // TTL lives in pendingActionSource.ts) and is dropped; the name fallback
    // ("... for <source>" from the engine's secondMessage) is adopted only
    // when no click source is live (server-initiated ability prompts).
    if (!getPendingActionSourceId()) {
      const forMatch = / for ([^"]+)$/.exec(stripServerHtml(actionMsg.message || ''))
      if (forMatch) setPendingActionSourceName(forMatch[1]!)
    }
    try {
      const prompt = buildActionPrompt(actionMsg)
      set({ actionPrompt: prompt, currentActionPrompt: msg })
      // A mana re-prompt (the server asks for the next land after each tap_mana)
      // continues an in-flight "Auto-Tap Lands" run. Runs AFTER the state is installed so
      // the step is planned against this prompt and the live board, and never when the build
      // above failed (a run must not drive a prompt that does not exist).
      onPromptSetForAutoTapRun(get())
      // Priority ding: the player's turn arrived (new prompt). Same timing as the
      // auto-tap hook — after the state is installed — and it never sends or sets
      // anything, so the run above is neither delayed nor suppressed.
      onPromptSetForPriorityDing(get(), promptEpoch)
    } catch (e) {
      // Fail Fast: do NOT fall back to a generic boolean/OK-Cancel prompt.
      // Surface the error to the user via the error toast and log it.
      const errorText = e instanceof Error ? e.message : String(e)
      console.error('[action_prompt] handler error:', e, 'msg:', msg)
      get().showError(`Action prompt error: ${errorText}`)
      promptState = 'none'
      set({ actionPrompt: null, currentActionPrompt: null })
      onPromptClearedForAutoTapRun()
      // Priority ding: the prompt is gone with the build error.
      onPromptClearedForPriorityDing()
    }
  },

  hideActionPrompt: () => {
    // A local hide (pass priority, cancel, new game, game over) ends the prompt
    // lifecycle for this client. State goes to 'none' rather than 'resolved' so
    // the F3 rail stays actionable: with no prompt rendered but the priority
    // ours, Pass Priority must work (that is the rejoin wedge).
    promptState = 'none'
    // Targeting arrows: a local hide (pass/cancel) ends the prompt.
    clearPendingActionSource()
    set({ actionPrompt: null, currentActionPrompt: null })
    // A withdrawn prompt ends an auto-tap run — it must never survive into a later
    // payment the user did not ask to auto-tap.
    onPromptClearedForAutoTapRun()
    // Priority ding: the prompt is withdrawn; forget its continuation memory.
    onPromptClearedForPriorityDing()
  },

  setGameOver: (result, data) => set({ gameEnd: { result, ...data } }),

  clearGameEnd: () => set({ gameEnd: null }),

  appendEvent: (event) => {
    // Filter spam messages, plus the "Waiting for <player>" lines the server
    // fires per state push per waiting player (118 entries at turn
    // 8, runs up to 4× the same message). The "someone is thinking" signal is
    // conveyed by the prompt UI itself; the log is unreadable if 15 entries
    // arrive per 8 game_updates. A `talk` line is never spam-filtered.
    if (!isDisplayable(event)) return

    // Cap arrays to prevent unbounded memory growth in long games.
    const MAX_LOG_ENTRIES = 200
    const MAX_CHAT_ENTRIES = 200

    if (event.type === 'talk') {
      // Chat message — strip HTML and add to chat
      set((state) => ({
        chatMessages: [...state.chatMessages, {
          ...event,
          message: stripServerHtml(event.message || ''),
          from: stripServerHtml(event.from || '?'),
        }].slice(-MAX_CHAT_ENTRIES),
      }))
      return
    }

    // F5: a new game starts with a fresh log. The reset is consumed HERE rather
    // than at `start_game` so the log lines the new game emits (shuffle, toss,
    // draws — which can arrive before the browser's start_game) are not lost.
    const resetLog = pendingLogReset
    pendingLogReset = false

    // Game log event — strip HTML
    set((state) => ({
      eventLog: (resetLog
        ? [{ ...event, message: stripServerHtml(event.message || '') }]
        : [...state.eventLog, { ...event, message: stripServerHtml(event.message || '') }]
      ).slice(-MAX_LOG_ENTRIES),
    }))
  },

  appendChatMessage: (event) =>
    set((state) => ({
      chatMessages: [...state.chatMessages, {
        ...event,
        message: stripServerHtml(event.message || ''),
        from: stripServerHtml(event.from || '?'),
      }].slice(-200),
    })),

  /**
   * Local (client-only) chat lines — the /help answer and the /clear wipe.
   *
   * The chat surfaces render DIFFERENT lists: the lobby's LobbyChat panel reads
   * lobbyTalkMessages / lobbySystemMessages (by lobbyChatTab), the game/replay
   * rail reads chatMessages. A local line must land in the list the user is
   * actually looking at, or it renders into a panel that is not mounted
   * (utils/slashCommands.activeChatList owns that rule — pure, harness-tested).
   *
   * The `seq` comes from nextLocalChatSeq(): the proxy's own seq is a global
   * POSITIVE counter (GameHistory.EVENT_SEQ, a static AtomicLong) and
   * mergeHistory re-sorts chatMessages by seq — a colliding local value would
   * either drop a real line (dedupe by seq) or reorder the panel. The local
   * counter runs far ABOVE any server value so a just-typed line still sorts
   * last in that re-sort, and can never equal a server seq.
   */
  appendLocalChatMessage: (text) => {
    // Resolved OUTSIDE the updater so the entry (and its seq) is computed exactly
    // once per call, whatever zustand does with the updater.
    const list = activeChatList(get())
    // chatType mirrors what routeChat() would have chosen for this list, so a
    // local line is indistinguishable from a routed one for the CSS classes
    // (.lobby-chat-user_info .lobby-chat-from) and any future reader.
    const entry: GameEvent = {
      seq: nextLocalChatSeq(),
      type: 'talk',
      chatType: list === 'lobbySystemMessages' ? 'user_info' : 'talk',
      message: text,
      from: 'System',
    }
    if (list === 'chatMessages') {
      set((state) => ({ chatMessages: [...state.chatMessages, entry].slice(-200) }))
      return
    }
    if (list === 'lobbyTalkMessages') {
      set((state) => ({ lobbyTalkMessages: [...state.lobbyTalkMessages, entry].slice(-200) }))
      return
    }
    set((state) => ({ lobbySystemMessages: [...state.lobbySystemMessages, entry].slice(-200) }))
  },

  clearVisibleChat: () =>
    set((state) => {
      const list = activeChatList(state)
      if (list === 'chatMessages') return { chatMessages: [] }
      if (list === 'lobbyTalkMessages') return { lobbyTalkMessages: [] }
      return { lobbySystemMessages: [] }
    }),

  /**
   * Merge a `history_backfill` payload into the log/chat panels.
   *
   * The proxy replays the events it already sent for this game
   * on a
   * reconnect or a page refresh GAME_INIT carries a GameView snapshot only, so
   * without this the panels come back emptied to the join banner.
   *
   * Dedupe is by the event `seq` — the proxy's one monotonic counter — so a
   * client that still holds seq 1..120 sees a backfill of 1..150 as 1..150 rather
   * than 1..120 followed by a second copy of 1..150. Entries are then re-sorted by
   * seq: a backfill necessarily arrives after the store's own live events
   * (start_game → rejoin → history_backfill → game_init) but its seqs are older
   * for the overlap, and the panel must read chronologically.
   */
  mergeHistory: (backfill: HistoryBackfillMessage) => {
    if (backfill === null || typeof backfill !== 'object') {
      // Fail Fast: a malformed backfill (no payload) must be a visible error, not
      // a silent no-op that looks like "the proxy sent nothing".
      throw new Error(`history_backfill payload is not an object: ${JSON.stringify(backfill)}`)
    }
    if (typeof backfill.gameId !== 'string' || backfill.gameId === '') {
      // Fail Fast: without a game id the merge cannot be checked against the
      // session's game, and merging blindly risks mixing two games' logs.
      throw new Error('history_backfill without a gameId')
    }
    if (!Array.isArray(backfill.events) || !Array.isArray(backfill.chat)) {
      // Fail Fast: both lists are always serialized by the proxy
      // (WebProxyClient.sendHistoryBackfill); a missing one is a protocol bug.
      throw new Error(`history_backfill for ${backfill.gameId} has no events/chat array `
        + `(events=${typeof backfill.events}, chat=${typeof backfill.chat})`)
    }
    // Fail Fast: `seq` is the merge key (and the React list key in RightRail). An
    // event without one would silently collapse the panel to a single row, which
    // is the exact failure mode this whole path exists to prevent.
    for (const [list, name] of [[backfill.events, 'events'], [backfill.chat, 'chat']] as const) {
      for (const e of list) {
        if (typeof e?.seq !== 'number' || !Number.isFinite(e.seq)) {
          throw new Error(`history_backfill ${name} entry has no numeric seq: ${JSON.stringify(e)}`)
        }
      }
    }
    // A pending between-games reset means the panels still hold a PREVIOUS game's
    // log (`start_game` arms it lazily, and the backfill can arrive before the new
    // game's first live event). The backfill then REPLACES the log rather than
    // unioning it, so two games' lines can never sit in one panel. With no reset
    // pending — the WS-reconnect case, where the panels are this same game's — the
    // replay is unioned and de-duplicated by seq.
    const replaceLog = pendingLogReset
    pendingLogReset = false
    // The backfill names the game these panels now hold — the same "already known
    // to me" fact markGameStart records, so it persists across a later reload too.
    rememberGameKey(backfill.gameId)
    set((state) => {
      // Chat is never reset between games (existing behaviour, preserved): always a
      // seq union, which can only remove exact duplicates.
      const log = mergeBySeq(replaceLog ? [] : state.eventLog, backfill.events)
      const chat = mergeBySeq(state.chatMessages, backfill.chat)
      return {
        eventLog: log,
        chatMessages: chat,
      }
    })
  },

  /**
   * The single send point for BOTH chat surfaces (RightRail's Chat tab and the
   * lobby's LobbyChat panel) — so slash-command handling lives here and nowhere
   * else. Server-side command support already exists (ChatManagerImpl.broadcast
   * treats a leading \ or / as a user command); this adds only the three
   * browser-local ergonomics from utils/slashCommands.ts:
   *
   *   /help   — append SLASH_COMMAND_HELP locally to the list the user is
   *             looking at; send nothing (the server has no help command, and
   *             an unknown one would dump its whole command list as spam)
   *   /clear  — empty the visible chat list locally; the game log is untouched
   *   /whisper|/w — forward as the server's own spelling `\w <user> <text>`
   *   anything else (unknown /command) — RAW passthrough: the server's command
   *             set stays authoritative
   *   malformed /whisper — showError and send NOTHING (fail fast; a bare
   *             `\w <user>` would reach the server as a chat line to nobody)
   */
  sendChat: (message) => {
    const command = parseSlashCommand(message)
    switch (command.kind) {
      case 'help':
        get().appendLocalChatMessage(SLASH_COMMAND_HELP)
        return
      case 'clear':
        get().clearVisibleChat()
        return
      case 'invalid':
        get().showError(command.message)
        return
      case 'whisper':
      case 'passthrough': {
        // The wire contract is unchanged (pre-existing sendChat): one
        // send_chat message carrying the exact string the server should see —
        // the composed `\w <user> <text>` for a whisper, the raw text for a
        // plain line and for an unknown /command the client does not own.
        const { ws } = get()
        const wire = command.kind === 'whisper' ? command.wire : command.text
        if (ws) ws.send({ type: 'send_chat', message: wire })
        return
      }
    }
  },

  // ── Action responses ──
  // Choice responses (select/special/boolean/amount/multiAmount/choosePile)
  // intentionally do NOT call hideActionPrompt() — the server is the single
  // source of truth for prompt dismissal; its game_delta/game_update clears
  // the UI. passPriority is the deliberate exception (see below), and the
  // mulligan family is a second, NARROWLY GATED one ("Mulligan
  // question doesn't go away until opponents answer"): see booleanChoice.
  //
  // Why hiding here cannot swallow a follow-up question: every GAME_ASK the
  // engine fires reaches this client as its own FRESH action_prompt message
  // (WebProxyClient.handleGameAsk writes one per GameClientMessage), and
  // setActionPrompt supersedes whatever was outstanding (F1). Mulligan.java's
  // executeMulliganPhase() re-fires the keep/mulligan question per player per
  // round with NO further state between, so after our answer the server is
  // silent until every opponent has answered — the prompt would sit on screen
  // the whole time. Hiding it locally cannot eat a later question because each
  // later question re-renders from its own action_prompt.
  //
  // Every answer goes through sendAnswer(), which drops an activation already
  // recorded on the current prompt (F1/F2): the server consumes exactly one
  // answer per prompt, so forwarding the repeat is what produced the duplicate
  // `select_choice` toast (2026-09-11) and the misdelivered `select_target`
  // storm. A genuinely live prompt accepts the next answer because the
  // next action_prompt / state push advances the epoch.
  selectChoice: (index) => {
    // Targeting arrows: an ability activation arrives as select_choice (the
    // prompt's choice carries the source card id) — same tracking as playCard.
    const { actionPrompt } = get()
    if (actionPrompt?.type === 'select') {
      const choice = actionPrompt.choices.find((c) => c.index === index)
      if (choice?.id) setPendingActionSource(choice.id)
    }
    sendAnswer(answerKeys.selectChoice(index), { type: 'select_choice', index })
  },

  playCard: (cardId) => {
    // Targeting arrows: remember which card the player is casting/activating —
    // the target prompt that follows has no source id of its own on the wire.
    setPendingActionSource(cardId)
    sendAnswer(answerKeys.playCard(cardId), { type: 'play_card', cardId })
  },

  selectSpecial: (value) => {
    sendAnswer(answerKeys.selectSpecial(value), { type: 'select_special', value })
  },

  selectTarget: (targetId) => {
    sendAnswer(answerKeys.selectTarget(targetId), { type: 'select_target', targetId })
  },

  booleanChoice: (value) => {
    const prompt = get().actionPrompt
    // The answer always goes out first — the server's guards (one answer per
    // prompt via the epoch/repeat-answer guard) decide whether it is
    // forwarded, exactly as for every other choice response.
    const sent = sendAnswer(answerKeys.booleanChoice(value), { type: 'boolean_choice', value })
    // The mulligan question lingered until every opponent had
    // answered, because the engine sends NO state between the mulligan asks
    // (Mulligan.executeMulliganPhase loops over players before any state push).
    // For that family only, withdraw the prompt locally: the rail falls back to
    // its "Waiting for <player>…"/priority state while the server waits on the
    // others. Gated on the proxy's mulligan tag (WebProxyClient.handleGameAsk
    // sets context 'mulligan' for every keep/hand wording — the keep/mulligan
    // question, its free and "draw another" variants) so ordinary Yes/No
    // prompts keep the old behaviour. The hide only follows an answer that
    // actually went out (never a dropped repeat). A follow-up question cannot
    // be swallowed: each one arrives as a fresh action_prompt and
    // setActionPrompt supersedes whatever was outstanding (F1) — see the
    // comment above.
    //
    // This withdraw records RESOLVED, not 'none' (unlike hideActionPrompt):
    // the answer is on the wire, so the prompt has no live work left and every
    // further click is stale (F1 — a stale click after resolution must send
    // nothing). With the priority OURS and no prompt
    // rendered, the next state push re-arms the controls ('none'), and during
    // the mulligan window itself priority is unset/opponent's, so the pass
    // wedge cannot be hit between the hide and that push.
    if (sent && prompt?.type === 'boolean' && prompt.context === MULLIGAN_CONTEXT) {
      advancePromptEpoch()
      promptState = 'resolved'
      set({ actionPrompt: null, currentActionPrompt: null })
      // The mulligan prompt never carried a mana payment, but clear the run
      // anyway for symmetry with every other prompt teardown.
      onPromptClearedForAutoTapRun()
      // Priority ding: the withdrawn prompt's continuation memory goes with it.
      onPromptClearedForPriorityDing()
    }
  },

  passPriority: () => {
    const { actionPrompt } = get()
    // One pass per prompt: rapid double-clicks emitted duplicate
    // `pass_priority`... the path was de-duped in some versions and not others;
    // the epoch guard makes it unconditional). The epoch advances when the next
    // prompt arrives, so the NEXT pass of the same priority window still goes out.
    if (!sendAnswer(answerKeys.passPriority(), { type: 'pass_priority' })) return
    // Record that a pass is awaiting a server response. Until the next state push
    // or prompt arrives, another click on Pass Priority is DROPPED by the epoch
    // guard above — the rail uses this flag to say so instead of silently
    // swallowing (during the trigger window no state push arrived for
    // 2 min 27 s, so a live-looking Pass Priority button was a dead control for
    // that whole stretch).
    set({ passPending: true })
    // Hide the prompt immediately unless it's a required target — the backend
    // rejects pass during required targets (sends an error toast instead), so
    // we keep the prompt visible so the player can still choose a target.
    // For all other prompt types (GAME_ASK, GAME_SELECT, optional targets),
    // the pass is valid and the server will move on — hiding the prompt now
    // prevents a stale "Mulligan?" prompt from lingering after "No" is sent.
    if (!(actionPrompt?.type === 'target' && actionPrompt.required)) {
      get().hideActionPrompt()
    }
  },

  skipPriority: (skip) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'skip_priority', skip })
    // Audio: audible ack for every arming path (six rail buttons, the
    // F-key shortcuts, the F6 combo) — this action is their single choke point.
    onSkipPriorityClicked(get())
  },

  cancelSkip: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'cancel_skip' })
    // Audio: the cancel ack — armed-skip toggle and F3 Cancel All Skips.
    onCancelSkipClicked(get())
  },

  undo: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'undo' })
  },

  concede: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'concede' })
  },

  tapMana: (landId) => {
    // A mana prompt re-fires per tap, but each tap is a distinct land — the key
    // is per land, so a second tap of the SAME land inside one prompt is
    // dropped (that is the double-click) while the next land still works.
    sendAnswer(answerKeys.tapMana(landId), { type: 'tap_mana', landId })
  },

  specialMana: () => {
    sendAnswer(answerKeys.specialMana(), { type: 'special_mana' })
  },

  useMana: (color, playerId) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'use_mana', color, playerId })
  },

  amountChoice: (value) => {
    sendAnswer(answerKeys.amountChoice(value), { type: 'amount_choice', value })
  },

  multiAmountChoice: (amounts) => {
    sendAnswer(answerKeys.multiAmountChoice(amounts), { type: 'multi_amount_choice', amounts })
  },

  choosePile: (pile) => {
    sendAnswer(answerKeys.choosePile(pile), { type: 'choose_pile', pile })
  },

  // ── New actions for feature parity ──

  concedeMatch: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'concede_match' })
  },

  holdPriority: (hold) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'hold_priority', hold })
  },

  rollbackTurns: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'rollback_turns' })
  },

  stopWatching: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'stop_watching' })
    // Clear game state and return to lobby/tournament view
    set({ inGame: false, gameState: null, actionPrompt: null, playerColorMap: {} })
  },

  triggerOrder: (action, abilityId, ruleText) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'trigger_order', action, abilityId, ruleText })
  },

  /**
   * Remember a recurring yes/no answer and answer the prompt on
   * screen right now — the desktop client's exact semantics
   * (HelperPanel.handleAutoAnswerPopupMenuEvent: each auto-answer item sends the
   * PlayerAction and then `clickButton(btnLeft|btnRight)`, e.g. :495-513).
   *
   * Order matters and mirrors the desktop: the memory is written FIRST, then the
   * current prompt is answered. Both travel over the same socket in that order, so
   * the engine has the key stored before it consumes the boolean.
   *
   * `value` is the answer stored AND given: true = YES, false = NO. The prompt answer
   * goes through booleanChoice(), which keeps the epoch/repeat guards (F1/F2) and the
   * mulligan local-withdraw exactly as a plain Yes/No click does.
   *
   * Fail Fast: a prompt without autoAnswerMessage has no key to store — the caller
   * (BooleanPrompt) does not offer the affordance in that case; reaching here anyway
   * is a wiring bug and sends nothing rather than storing a dead key.
   */
  autoAnswerPrompt: (action, originalId, message, value) => {
    const prompt = get().actionPrompt
    if (!prompt || prompt.type !== 'boolean' || !message) {
      console.error('[auto_answer] refusing to remember an answer without a boolean prompt + key:', {
        action,
        hasPrompt: !!prompt,
        promptType: prompt?.type,
        hasMessage: !!message,
      })
      get().showError('Auto-answer unavailable: this ask carries no answer key')
      return
    }
    const { ws } = get()
    if (!ws) return
    ws.send({ type: 'auto_answer', action, originalId, message })
    // Same action also answers the prompt on screen (desktop clickButton).
    get().booleanChoice(value)
  },

  /** Reset surfaces — no prompt involvement, pure memory clearing. */
  autoAnswerReset: (action) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'auto_answer', action, originalId: null, message: null })
  },

  viewSideboard: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'view_sideboard' })
  },

  /**
   * Hand request / permission management — the initiating half of
   * the desktop's "Cards on hand (name)" submenu (PlayAreaPanel.java:403-434 seat,
   * :479-481 watcher). The response half (granter Accept → ADD_PERMISSION_TO_SEE_
   * HAND_CARDS) already rides user_request_response; the granted hand comes back on
   * gameState.watchedHands.
   *
   * `playerId` is the GRANTER's Player.playerId — the engine refuses a request for
   * your own seat (GameController.requestPermissionToSeeHandCards:759), which is why
   * the menu only offers the item on another seat's header.
   */
  requestHandPermission: (playerId) => {
    const { ws } = get()
    if (!ws) {
      // Fail Fast: a silent no-op on a dead socket looks like "the other player
      // ignored me" — surface it.
      get().showError('Not connected — cannot request that hand')
      return
    }
    ws.send({ type: 'request_hand_permission', playerId })
  },

  revokeHandPermission: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'revoke_hand_permission' })
  },

  allowHandRequests: (enabled) => {
    const { ws } = get()
    if (ws) {
      // Record the intent BEFORE sending: the engine fires no state push for this
      // action, so the checkbox must not depend on a stale server flag to toggle
      // (see handWatch.ts's allow-intent block for the full reasoning — the
      // desktop reads its stored preference for the same reason).
      setAllowIntent(enabled)
      ws.send({ type: 'allow_hand_requests', enabled })
    }
  },

  toggleManaAutoPay: (enabled) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'toggle_mana_auto_pay', enabled })
  },

  toggleUseFirstMana: (enabled) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'toggle_use_first_mana', enabled })
  },

  updatePhaseStops: (stops) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'update_phase_stops', stops })
  },

  watchGame: (tableId) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'watch_game', tableId })
  },

  watchTournamentTable: (tableId) => {
    const { ws } = get()
    if (ws) ws.send({ type: 'watch_tournament_table', tableId })
  },

  setTournamentData: (data, id) => {
    set({ tournamentData: data, tournamentId: id })
  },

  setShowTournamentRunning: (show) => {
    set({ showTournamentRunning: show })
  },

  refreshTournament: () => {
    const { ws, tournamentId } = get()
    if (ws && tournamentId) ws.send({ type: 'refresh_tournament', tournamentId })
  },

  // ── Between-games sideboarding ──

  setSideboardPrompt: (prompt) => set({ sideboardPrompt: prompt }),

  sideboardSubmitRejected: () => {
    // The proxy reported a submit_deck rejection while a sideboard modal may be
    // open (e.g. deck below the format minimum). Bump the rejection stamp —
    // SideboardView watches it and drops its "Submitted — waiting" state so the
    // player can fix the deck and resubmit before the ~180s timer expires.
    set({ sideboardSubmitRejectedAt: Date.now() })
  },

  submitDeck: (deck) => {
    const { ws, sideboardPrompt } = get()
    if (!sideboardPrompt || !sideboardPrompt.tableId) {
      // Fail Fast: no sideboard prompt context — surface instead of silently dropping.
      console.error('[submitDeck] called with no active sideboardPrompt')
      get().showError('Cannot submit deck: no active sideboard session')
      return
    }
    if (!ws) {
      // Fail Fast: a dropped submit would otherwise leave the modal on
      // "Submitted — waiting for other players…" until the ~180s timer expires
      // with no message and no way to retry (same failure shape as the rejected
      // submit this view already recovers from).
      console.error('[submitDeck] called with no WebSocket — deck not submitted')
      get().showError('Cannot submit deck: connection to the server is down')
      return
    }
    ws.send({ type: 'submit_deck', tableId: sideboardPrompt.tableId, deck })
  },
  }
}