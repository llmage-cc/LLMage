/**
 * Client-side slash-command parsing for the chat inputs (RightRail's Chat tab
 * and the lobby's LobbyChat panel). PURE — no store, no DOM, no network — so
 * scripts/verify-slash-commands.ts can drive it directly under Node.
 *
 * WHY THIS EXISTS (and its limits): the XMage server ALREADY treats a message
 * starting with `\` or `/` as a user command — ChatManagerImpl.broadcast
 * (Mage.Server/src/main/java/mage/server/ChatManagerImpl.java:130) hands it to
 * performUserCommand (222-365), which implements history/me/list/whisper/w/
 * card/ignore/botgame. `\w <user> <text>` therefore works today if the raw
 * string reaches the server (the proxy only forwards it: WebProxySession
 * handleSendChat:2305). What was missing was CLIENT ergonomics for the three
 * commands a browser should not have to round-trip:
 *   /help  — no server equivalent (the server answers an unknown command with
 *            an "Invalid User Command" dump of its whole command list);
 *   /clear — a local panel action (nothing to send);
 *   /whisper|/w — the same thing the server calls `\w`, spelled the way a user
 *            who has only seen the web UI would guess.
 *
 * AUTHORITY RULE: anything this module does not recognise passes through RAW
 * (byte-identical) so the server's command set stays authoritative — a new or
 * changed server command never needs a client release. Only `/`-prefixed text
 * is examined at all: `\`-prefixed text is the server's own spelling and is
 * never touched.
 *
 * CASE: command words are matched case-insensitively, mirroring the server
 * (performUserCommand uppercases the command before matching).
 */

/**
 * Mirror of the proxy's wire cap (WebProxySession.java:62, MAX_CHAT_LENGTH =
 * 500 — enforced at :2314 with an error the browser shows as a toast). A
 * composed whisper is checked here so the refusal is specific instead of a
 * round-trip proxy error.
 */
export const MAX_CHAT_LENGTH = 500

/**
 * The line `/help` appends to the panel. One line on purpose: chat entries
 * render one line each (`lobby-chat-entry` / `.chat-entry`). Plain text only —
 * the local append path deliberately does NOT run stripServerHtml (a `<user>`
 * placeholder would be eaten by the tag-strip), and React renders it as a text
 * node.
 */
export const SLASH_COMMAND_HELP =
  'Commands: /help | /clear | /whisper <user> <text> (or /w). Anything else starting with / goes to the server as-is (\\list or \\l lists the server\'s own set).'

/**
 * Result of parsing one outgoing chat line.
 *  - help        — append SLASH_COMMAND_HELP locally, send nothing
 *  - clear       — empty the visible chat list locally, send nothing
 *  - whisper     — `wire` is the exact server spelling (`\w <user> <text>`)
 *  - passthrough — send `text` unchanged (plain chat AND unknown commands)
 *  - invalid     — caller surfaces `message` (showError) and sends NOTHING
 */
export type SlashCommand =
  | { kind: 'help' }
  | { kind: 'clear' }
  | { kind: 'whisper'; to: string; body: string; wire: string }
  | { kind: 'passthrough'; text: string }
  | { kind: 'invalid'; message: string; input: string }

/**
 * Which chat LIST a local line belongs in — the list the user is actually
 * looking at. Pure, so the store and the harness agree on one rule:
 *
 *   in a live game OR the replay viewer → 'chatMessages'
 *       (RightRail's Chat tab is the only chat surface mounted; LobbyChat is not)
 *   in the lobby                        → the ACTIVE LobbyChat tab's list
 *       ('talk' → lobbyTalkMessages, 'system' → lobbySystemMessages)
 *
 * Getting this wrong is invisible rather than loud: the wrong list's panel is
 * simply not mounted, so the line would vanish.
 */
export type ActiveChatList = 'chatMessages' | 'lobbyTalkMessages' | 'lobbySystemMessages'

/** The slice of store state activeChatList needs (structural, keeps this pure). */
export interface ChatSurfaceState {
  inGame: boolean
  inReplay: boolean
  /** Lobby chat sub-tab — utils/lobbyChat.ChatTab. */
  lobbyChatTab: 'talk' | 'system'
}

export function activeChatList(s: ChatSurfaceState): ActiveChatList {
  if (s.inGame || s.inReplay) return 'chatMessages'
  return s.lobbyChatTab === 'system' ? 'lobbySystemMessages' : 'lobbyTalkMessages'
}

/**
 * Sequence for a local (never-sent) chat line.
 *
 * The proxy's own seq is a global POSITIVE counter (GameHistory.EVENT_SEQ is a
 * static AtomicLong shared by every session), and mergeBySeq re-sorts
 * chatMessages by seq after a rejoin backfill. Local lines must therefore never
 * collide with a server value (a collision would dedupe away a real line) and
 * must sort LAST (a line the user just typed belongs at the bottom). Counting up
 * from MAX_SAFE_INTEGER − 1e6 satisfies both; the window is far larger than the
 * 200-entry list cap, so reuse is impossible in one page life.
 */
let localChatSeq = Number.MAX_SAFE_INTEGER - 1_000_000

export function nextLocalChatSeq(): number {
  localChatSeq += 1
  return localChatSeq
}

/** Parse one outgoing chat line — see the module header for the authority rule. */
export function parseSlashCommand(text: string): SlashCommand {
  if (typeof text !== 'string') {
    // Fail Fast (AGENTS.md): a non-string is a caller bug, not user input.
    throw new TypeError(`parseSlashCommand expects a string, got ${typeof text}`)
  }
  // Only `/` text is examined; `\...` stays the server's own spelling.
  if (!text.startsWith('/')) return { kind: 'passthrough', text }

  const match = /^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text)
  if (match === null) {
    // `/` alone, `/ x`: not command-shaped — the server answers these itself.
    return { kind: 'passthrough', text }
  }
  const word = match[1]!.toLowerCase()
  const args = (match[2] ?? '').trim()

  switch (word) {
    case 'help':
      return { kind: 'help' }
    case 'h':
      // `\h` is the server's HISTORY command, so bare `/h` is the only form we
      // claim as a help alias: `/h <args>` is left to the server untouched.
      return args === '' ? { kind: 'help' } : { kind: 'passthrough', text }
    case 'clear':
      return { kind: 'clear' }
    case 'whisper':
    case 'w':
      return parseWhisper(args, text)
    default:
      // Unknown client-side command: RAW to the server (authority rule).
      return { kind: 'passthrough', text }
  }
}

/** Shared malformed-whisper handling: never send, always name the usage. */
function parseWhisper(args: string, input: string): SlashCommand {
  const invalid = (message: string): SlashCommand => ({ kind: 'invalid', message, input })
  const sep = args.search(/\s/)
  if (args === '' || sep === -1) {
    return invalid('Usage: /whisper <user> <text> — nothing was sent')
  }
  const to = args.slice(0, sep)
  const body = args.slice(sep + 1).trim()
  if (body === '') {
    return invalid('Usage: /whisper <user> <text> — message text is required, nothing was sent')
  }
  // The server's own spelling (ChatManagerImpl.java:323): `\w <user> <text>`.
  const wire = `\\w ${to} ${body}`
  if (wire.length > MAX_CHAT_LENGTH) {
    return invalid(`Whisper too long (max ${MAX_CHAT_LENGTH} chars) — nothing was sent`)
  }
  return { kind: 'whisper', to, body, wire }
}
