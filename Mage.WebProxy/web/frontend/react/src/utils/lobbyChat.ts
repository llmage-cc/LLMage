/**
 * Pure lobby-chat + users-list logic. NO React imports — this module is
 * exercised directly by scripts/verify-lobby-chat-users.ts under Node.
 *
 * Chat tab routing mirrors the stock Swing client's room chat panel
 * (TablesPanel + ChatPanelBasic): player conversation (TALK/WHISPER) renders in
 * the "Talk" tab; everything the server narrates (system notices, status
 * lines, game logs, MOTD) renders in the "System" tab.
 *
 * The `messageType` values are the proxy's lower-cased ChatMessage.MessageType
 * enum names (WebProxyClient.handleChatMessage puts `chatType` on every chat
 * event): talk, whisper_from, whisper_to, user_info, status, game. 'whisper'
 * and 'motd' are accepted as defensive aliases — the desktop client renders
 * both whisper directions in one chat stream, and 'motd' keeps the contract
 * testable even though the proxy delivers MOTD via the
 * users_list pull (see lobbySlice.setMotd), never as a chat line.
 */

/** The two chat tabs the lobby panel renders (stock parity: Talk | System). */
export type ChatTab = 'talk' | 'system'

/**
 * Route a chat line to its tab.
 *  - talk, whisper (from/to/alias)  → 'talk'
 *  - user_info/status/game/motd and ANY unrecognized type → 'system'
 *
 * Unknown types fall to 'system' deliberately: server narration must never be
 * rendered as if a player had said it (fail-visible, not fail-silent).
 */
export function routeChat(messageType: string | null | undefined): ChatTab {
  switch ((messageType ?? '').toLowerCase()) {
    case 'talk':
    case 'whisper':
    case 'whisper_from':
    case 'whisper_to':
      return 'talk'
    default:
      // user_info, status, game, motd, unknown → System tab
      return 'system'
  }
}

/**
 * One entry of the proxy's `users_list` response — exactly the fields
 * mage.view.UsersView exposes (Mage.Common/src/main/java/mage/view/UsersView.java),
 * serialized by WebProxySession.serializeRoomUsers. Nothing invented.
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
 * Sort users for the panel: the session's own name first (so "you" is always
 * visible without scrolling), then case-insensitive by name. Ties break on the
 * raw name so the order is stable across refreshes.
 *
 * Returns a NEW array — never mutates the store's list (Zustand contract).
 */
export function sortUsers(users: LobbyUser[], selfName: string | null | undefined): LobbyUser[] {
  const self = (selfName ?? '').toLowerCase()
  return [...users].sort((a, b) => {
    const aSelf = self !== '' && a.userName.toLowerCase() === self ? 0 : 1
    const bSelf = self !== '' && b.userName.toLowerCase() === self ? 0 : 1
    if (aSelf !== bSelf) return aSelf - bSelf
    const cmp = a.userName.toLowerCase().localeCompare(b.userName.toLowerCase())
    if (cmp !== 0) return cmp
    return a.userName < b.userName ? -1 : a.userName > b.userName ? 1 : 0
  })
}

/**
 * Parse the proxy's `users_list` payload into LobbyUser rows. FAIL FAST on
 * malformed payloads (AGENTS.md): wrong shape, a non-string name, or a
 * non-number rating throws — the caller surfaces the message instead of
 * rendering a silently broken user list.
 *
 * Accepts `users` as the top-level key per the wire contract
 * {"type":"users_list","users":[...]} (WebProxySession.handleGetRoomUsers).
 */
export function parseUsersListPayload(payload: unknown): LobbyUser[] {
  if (payload == null || typeof payload !== 'object') {
    throw new Error('users_list payload is not an object')
  }
  const obj = payload as { users?: unknown }
  if (!Array.isArray(obj.users)) {
    throw new Error('users_list payload has no users array')
  }
  return obj.users.map((raw, i): LobbyUser => {
    if (raw == null || typeof raw !== 'object') {
      throw new Error(`users_list[${i}] is not an object`)
    }
    const u = raw as Record<string, unknown>
    if (typeof u.userName !== 'string' || u.userName.length === 0) {
      throw new Error(`users_list[${i}] has no userName`)
    }
    const num = (v: unknown, field: string): number => {
      if (typeof v !== 'number' || !Number.isFinite(v)) {
        throw new Error(`users_list[${i}].${field} is not a number`)
      }
      return v
    }
    const str = (v: unknown): string | null => (typeof v === 'string' ? v : null)
    return {
      flagName: str(u.flagName),
      userName: u.userName,
      matchHistory: str(u.matchHistory),
      matchQuitRatio: num(u.matchQuitRatio, 'matchQuitRatio'),
      tourneyHistory: str(u.tourneyHistory),
      tourneyQuitRatio: num(u.tourneyQuitRatio, 'tourneyQuitRatio'),
      infoGames: str(u.infoGames),
      infoPing: str(u.infoPing),
      generalRating: num(u.generalRating, 'generalRating'),
      constructedRating: num(u.constructedRating, 'constructedRating'),
      limitedRating: num(u.limitedRating, 'limitedRating'),
    }
  })
}