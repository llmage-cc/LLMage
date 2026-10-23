/**
 * End-of-game presentation rules shared by the game-end dialog, the game-log
 * renderer, and the lobby/replay winner rows (
 * presentation.md).
 *
 * WHY THIS MODULE EXISTS
 * ----------------------
 * A terminal LLM-provider failure ends the game by CONCEDE, not by a played-out
 * win: LLMPlayer.concedeOnTerminalFailure
 * (Mage.Server.Plugins/Mage.Player.LLM/src/main/java/mage/player/llm/LLMPlayer.java:2193)
 * does `game.informPlayers(getLogName() + " loses the game (LLM provider failure).")`
 * then `concede(game)`; the ENGINE's `leave()` → `lostForced()` (PlayerImpl.java:2828)
 * then emits the ordinary "<seat> has lost the game", and the opponent's `won()`
 * (PlayerImpl.java:2883) emits "<winner> has won the game" through the ordinary
 * all-opponents-lost path.
 *
 * OUR server now ships the reason itself: GameEndView carries `endReason`
 * (mage.game.GameEndReason — "llm_failure" | "concede" | "timeout" | "idle" |
 * "quit" | "unknown"), and the proxy puts it on the rich `game_over` frame as
 * `endReason` for the viewer's OWN seat (null for a watcher, who has no seat).
 * That field is authoritative and is what endDialogKind uses first.
 *
 * The log-line inference remains ONLY as the fallback for an older server (or
 * an older proxy) whose payload omits the field: GameEndView used to expose
 * gameInfo/matchInfo/additionalInfo/wins/loses/winsNeeded and no reason at all,
 * and MatchView/MatchPlayerView carry only a `quit` flag (which a
 * provider-failure concede shares with a click-concede and an idle timeout),
 * so for a stock/older server the fatal log line is still the only signal the
 * browser has. These helpers turn that line — plus the seat count, which makes
 * the inference exact for the 1v1 LLM-vs-human product — into presentation
 * decisions, and are PURE so the harness exercises the exact strings the UI
 * renders (same pattern as visibleRightTabs / resultDisplay).
 */

/** The literal the engine stamps on a terminal-seat-failure line (LLMPlayer.java:2200). */
const TERMINAL_FAILURE_MARKER = 'LLM provider failure'

/**
 * True for the engine's terminal-seat-failure line, e.g.
 * "<seat> loses the game (LLM provider failure)." — the one reason signal that
 * survives to the browser. HTML-stripped text still contains the marker.
 */
export function isTerminalFailureLine(message: string | undefined): boolean {
  return (message ?? '').includes(TERMINAL_FAILURE_MARKER)
}

/**
 * The seat name carried by a terminal-failure line, e.g.
 * "glm-5.3-flash-dana loses the game (LLM provider failure)." ->
 * "glm-5.3-flash-dana". Null when the line is absent or not that shape.
 */
export function terminalFailureSeat(message: string | undefined): string | null {
  const msg = message ?? ''
  if (!msg.includes(TERMINAL_FAILURE_MARKER)) return null
  const idx = msg.indexOf(' loses the game')
  if (idx <= 0) return null
  const seat = msg.slice(0, idx).trim()
  return seat.length > 0 ? seat : null
}

/**
 * Mulligan/shuffle narration a seat keeps emitting AFTER its terminal failure.
 *
 * The failure lands mid-mulligan, so the engine's already-in-flight mulligan
 * loop goes on narrating the dead seat ("decides to take mulligan" /
 * "mulligans down to N cards" / "keeps hand" / "'s library is shuffled") after
 * it already lost — the exact contradictory post-mortem tail.
 * These strings are the ONLY lines that tail uses; they are matched ONLY once
 * a fatal line has already been seen in this game's log (see filterPostLossNoise),
 * so ordinary, pre-game-over mulligan lines are untouched.
 */
const POST_LOSS_SEAT_NOISE_RE = /decides to take mulligan|mulligans down to|keeps hand|'s library is shuffled/i

export function isPostLossSeatNoise(message: string | undefined): boolean {
  return POST_LOSS_SEAT_NOISE_RE.test(message ?? '')
}

/**
 * The CSS class for one game-log line. Every line gets the base
 * `log-entry log-<type>` classes it always had; the fatal line ADDITIONALLY
 * gets `log-fatal`, which the stylesheet tints red/bold so the reason for the
 * loss stops looking like ordinary grey noise.
 */
export function logLineClass(type: string, message: string | undefined): string {
  const base = `log-entry log-${type}`
  return isTerminalFailureLine(message) ? `${base} log-fatal` : base
}

/**
 * Drop the dead seat's post-mortem narration from a game log.
 *
 * Walks the log once; the first terminal-failure line arms the filter and
 * yields the dead seat's name. After that the filter suppresses:
 *  - the redundant "<same seat> has lost the game" line — the SEAT's loss was
 *    already announced by the fatal line itself (the "duplicate framing
 *    line"); scoped to the SAME seat so a different player's genuine
 *    loss in a 3+-seat game is never eaten;
 *  - the dead seat's mulligan/shuffle narration (the "decides to take mulligan"
 *    / "mulligans down to N cards" / "keeps hand" / "library is shuffled"
 *    contradiction — actions that post-date the loss).
 * The fatal line ITSELF, everything before it, and the winner's "has won the
 * game" announcement are always kept. `talk` lines are never touched — a
 * human's chat must not be silently deleted by a presentation rule. Pure;
 * returns a new array.
 */
export function filterPostLossNoise<T extends { type: string; message?: string }>(
  entries: T[],
): T[] {
  let fatalSeat: string | null = null
  let fatalSeen = false
  const kept: T[] = []
  for (const e of entries) {
    if (isTerminalFailureLine(e.message)) {
      fatalSeen = true
      fatalSeat = terminalFailureSeat(e.message)
      kept.push(e)
      continue
    }
    if (fatalSeen && e.type !== 'talk') {
      // Redundant per-seat loss framing after the fatal line already said so.
      if (fatalSeat !== null && e.message === `${fatalSeat} has lost the game`) continue
      // The dead seat's post-loss mulligan/shuffle narration.
      if (isPostLossSeatNoise(e.message)) continue
    }
    kept.push(e)
  }
  return kept
}

/** True when any event in this game's log is the terminal-seat-failure line. */
export function logHasTerminalFailure(events: Array<{ message?: string }>): boolean {
  return events.some((e) => isTerminalFailureLine(e.message))
}

/**
 * Which end-of-game dialog to render.
 *  - 'defeat'             — the viewer lost (unchanged).
 *  - 'technical-victory'  — a WIN that was awarded because the opponent
 *                           forfeited on a terminal provider failure.
 *  - 'victory'            — a played-out win (unchanged).
 *
 * PRECEDENCE:
 *  1. !hasWon -> 'defeat'. A lost seat is a lost seat whatever the reason says
 *     (a timeout/idle/quit reason on the viewer's own seat is still a loss).
 *  2. `endReason === 'llm_failure'` -> 'technical-victory', for ANY seat count.
 *     The engine records that reason exactly where LLMPlayer.concedeOnTerminalFailure
 *     concedes, so it is authoritative — and it is the reason >2-seat games were
 *     previously mislabelled as played-out wins.
 *  3. any other PRESENT reason ('concede' | 'timeout' | 'idle' | 'quit' |
 *     'unknown') -> 'victory'. A click-concede, a clock flag or a quit is not a
 *     provider failure; the field is trusted over a stale fatal log line that a
 *     previous game may have left in the rail.
 *  4. absent/null -> the old inference, kept ONLY for a stock/older server whose
 *     payload carries no field (an older proxy, or a watcher's null). It is EXACT
 *     for two seats: a terminal failure makes that seat CONCEDE, which ends a 1v1
 *     game (MatchImpl.checkIfMatchEnds → activePlayers < 2), so fatal-line + a win
 *     can only be the human winning by forfeit there. Above two seats one seat's
 *     failure need not end the game, so a later played-out win could be
 *     mislabelled — the fallback declines rather than guess.
 *
 * Why keep the fallback at all: the browser talks to stock XMage servers too
 * (see AGENTS.md stock-LLMage pairing), and those omit `endReason` entirely.
 */
export type EndDialogKind = 'victory' | 'technical-victory' | 'defeat'

export const END_DIALOG_TITLES: Record<EndDialogKind, string> = {
  victory: 'Victory!',
  'technical-victory': 'Victory (by forfeit)',
  defeat: 'Defeat',
}

/** The explanatory line the technical-victory dialog adds under its title. */
export const END_DIALOG_TECHNICAL_NOTE =
  'Opponent forfeited — LLM provider failure. The game did not play out.'

export function endDialogKind(
  hasWon: boolean,
  playerCount: number,
  logHasTerminalFailureFlag: boolean,
  endReason?: string | null,
): EndDialogKind {
  if (!hasWon) return 'defeat'
  // The wire's own reason, when the server sent one, beats every inference.
  // ('unknown' is the engine's "nothing recorded" default — a played-out win —
  // so it takes the same branch as the other non-failure reasons.)
  if (typeof endReason === 'string' && endReason.length > 0) {
    return endReason === 'llm_failure' ? 'technical-victory' : 'victory'
  }
  // Fallback: a stock/older server, or a watcher's null. Exact for 1v1 only.
  if (playerCount === 2 && logHasTerminalFailureFlag) return 'technical-victory'
  return 'victory'
}

/**
 * The winner-row glyph. A trophy EMOJI has no guaranteed font on Linux hosts
 * and rendered as the tofu box in the Victory dialog and the winner rows.
 * A plain ASCII, monochrome-safe tag cannot tofu.
 */
export const WINNER_GLYPH = '[win]'
