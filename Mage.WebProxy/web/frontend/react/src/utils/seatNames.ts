/**
 * Seat names — the one definition of what a seat-name field accepts, on
 * the client side of the wire.
 *
 * WHAT THE NAME IS FOR
 * --------------------
 * Both seat-dialog paths (create_table / create_tournament) send
 * `playerConfigs[{type, deck, isYou, name?}]`; the proxy's seat loop reads it as
 * `pc.has("name") ? pc.get("name").getAsString() : (pt.isAI() ? "AI-" + i : username)`
 * (WebProxySession.handleCreateTable / handleCreateTournament). The derived name
 * becomes the seat's player name for the whole match (the board header, the
 * lobby seat list, the log). A typed name therefore has to be a name the XMage
 * server itself would accept for a table seat, or the table comes up with a
 * seat the user cannot recognize — so it is validated here, before the wire,
 * with an inline error instead of silent truncation (AGENTS.md Fail Fast).
 *
 * LIMITS (traced, 2026-09-27)
 * ---------------------------
 * The server's own account-name rule is `Session.validateUserName`: length within
 * config.xml's `minUserNameLength`/`maxUserNameLength` (this repo: 3/14 — the
 * same number the proxy's connect-rejection comment names as "15-char username"
 * off-by-one aside), then an allowlist check against
 * `invalidUserNamePattern` (this repo: `[^a-z0-9_]`, case-insensitive, i.e.
 * NOTHING beyond a-z, 0-9 and underscore survives). That rule governs ACCOUNTS,
 * not table seats: no server-side seat-name validation exists (TableController
 * only checks the seat TYPE and login identity), so a seat name is free-form up
 * to what the UI can carry. We still cap it, because an uncapped client string
 * rides into logs, the seat list and the game header; and we REJECT — never
 * truncate — anything over the cap.
 *
 * CAP CHOICE: 20 characters, letters/digits/space/_/-/./'. That is above the
 * server's own 14-char account cap (a seat name is a display label, not a
 * login) and comfortably below anything that would distort those surfaces.
 * The desktop client's own NewPlayerPanel name field is a free-text JTextField
 * with no validation, so there is no stricter precedent to match.
 */

/** Max accepted seat-name length (chars). Over-length input is REJECTED, never truncated. */
export const SEAT_NAME_MAX_LENGTH = 20

/**
 * Accepted seat-name characters: Unicode letters and digits plus space, `_`,
 * `-`, `.` and `'`. Deliberately permissive enough for "Sir Bob's Bot" while
 * excluding control characters, quotes, angle brackets and every other
 * markup/injection-shaped character a name field never legitimately needs.
 * (Unicode letters via `\p{L}` so non-ASCII names are not silently mangled.)
 */
export const SEAT_NAME_PATTERN = /^[\p{L}\p{N} _.'-]+$/u

/**
 * Validate a raw seat-name field value.
 *
 * Returns `null` when the value is acceptable (including EMPTY — empty means
 * "use the server's derived default", which is the documented no-name
 * behavior), else a fail-fast message for the seat's inline error surface.
 * The message names the offending rule so the user can fix it in place.
 */
export function validateSeatName(raw: string): string | null {
  const name = raw.trim()
  if (name.length === 0) return null // empty = proxy's derived default, always legal
  if (name.length > SEAT_NAME_MAX_LENGTH) {
    return (
      `Seat name is too long (${name.length} characters) — the limit is ${SEAT_NAME_MAX_LENGTH}.` +
      ' Shorten it; the name is not truncated.'
    )
  }
  if (!SEAT_NAME_PATTERN.test(name)) {
    return (
      `Seat name may only use letters, digits, spaces and _ - . '` +
      ` (invalid in "${name}").`
    )
  }
  return null
}

/** True when the raw field holds a usable name (non-empty after trim + validation). */
export function seatNameIsSet(raw: string | undefined): boolean {
  return typeof raw === 'string' && raw.trim().length > 0
}

/**
 * The `name` value to ride the wire for a seat, or `undefined` when the user
 * typed nothing (the field must then be OMITTED entirely — an absent `name` is
 * what makes the proxy derive its default).
 *
 * Throws on an invalid value: callers must have validated already (fail-fast —
 * a bad name must never reach the wire and be silently accepted by the proxy's
 * `getAsString()`).
 */
export function seatNameForWire(raw: string | undefined): string | undefined {
  if (!seatNameIsSet(raw)) return undefined
  const trimmed = raw!.trim()
  const problem = validateSeatName(trimmed)
  if (problem) {
    throw new Error(`create_table seat name rejected before send: ${problem}`)
  }
  return trimmed
}
