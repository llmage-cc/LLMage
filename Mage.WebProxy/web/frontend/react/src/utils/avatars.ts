/**
 * Player avatars — the SINGLE resolution point for "which image
 * represents seat X".
 *
 * Desktop reference: `Mage.Client/src/main/java/mage/client/game/PlayerPanelExt.java`
 * (`updateAvatar` :359-377) renders `player.getUserData().getAvatarId()` as
 * `/avatars/<id>.jpg`, with `64` → `/avatars/i64.jpg` (the computer seat) and
 * ids ≥ 1000 → `/avatars/special/<id-1000>.gif`. Ids outside `Constants.MIN…MAX`
 * are clamped to `DEFAULT_AVATAR_ID` BEFORE the lookup (PlayerPanelExt:361-363),
 * which is why an id the client does not know can never produce a broken image.
 * The id constants live in `Mage.Common/src/main/java/mage/constants/Constants.java:42-44`
 * (MIN 10, MAX 32, DEFAULT 10) — mirrored here because the browser cannot read
 * Java constants.
 *
 * The served files are a CURATED SUBSET of the desktop's avatar set
 * (`Mage.WebProxy/web/frontend/avatars/` — 10.jpg…32.jpg + i64.jpg; provenance in
 * THIRD-PARTY-NOTICES.md). The `special/*.gif` set (server-assigned avatars,
 * id ≥ 1000) and `unknown.jpg` are deliberately NOT shipped, so those ids
 * resolve to the default avatar — the same picture the desktop clamps them to
 * for every id it does not recognize.
 *
 * Fail Fast note: every id this module ever RETURNS has a shipped file — the
 * clamp is total (no id escapes it), which is exactly what makes "unknown id →
 * default, never a broken image" a property of the resolver rather than an
 * `onError` patch on the <img>.
 */

/** `Constants.MIN_AVATAR_ID` — first selectable avatar. */
export const AVATAR_MIN_ID = 10
/** `Constants.MAX_AVATAR_ID` — last selectable avatar. */
export const AVATAR_MAX_ID = 32
/** `Constants.DEFAULT_AVATAR_ID` — fallback for every unknown/invalid id. */
export const DEFAULT_AVATAR_ID = 10
/** The desktop's computer-seat avatar (`ComputerPlayer`/`LLMPlayer` set this). */
export const COMPUTER_AVATAR_ID = 64

/** Ids ≥ this are the desktop's server-assigned "special" avatars (not shipped). */
const SPECIAL_AVATAR_MIN_ID = 1000

/** The served location of the copied avatar JPGs (Mage.WebProxy/web/frontend/avatars/). */
export const AVATAR_BASE_URL = '/avatars/'

/**
 * The selectable avatars, in order — exactly the desktop's "My Avatar" grid
 * (PreferencesDialog.addAvatar sites, 10…32 inclusive). The picker renders this
 * list, so adding an id to the shipped directory without adding it here (or
 * vice versa) shows up as a failed `verify-avatars.sh` file check.
 */
export const AVATAR_IDS: readonly number[] = Array.from(
  { length: AVATAR_MAX_ID - AVATAR_MIN_ID + 1 },
  (_, i) => AVATAR_MIN_ID + i,
)

/**
 * Clamp an avatar id to something with a shipped image — the exact decision
 * ladder of PlayerPanelExt:360-372, minus the special-avatar branch (its GIFs
 * are not part of the web client):
 *
 *  - `64` → the computer avatar (`i64.jpg`);
 *  - `10…32` → the id itself;
 *  - anything else — including `null`/`undefined` (a replay or payload from a
 *    proxy build before this feature), `0` (the previous hardcoded wire value),
 *    the special range ≥ 1000, and any out-of-range number — → the default.
 */
export function resolveAvatarId(id: number | null | undefined): number {
  if (id === null || id === undefined || !Number.isFinite(id)) return DEFAULT_AVATAR_ID
  if (id === COMPUTER_AVATAR_ID) return COMPUTER_AVATAR_ID
  if (id === SPECIAL_AVATAR_MIN_ID || id > SPECIAL_AVATAR_MIN_ID) return DEFAULT_AVATAR_ID
  if (id < AVATAR_MIN_ID || id > AVATAR_MAX_ID) return DEFAULT_AVATAR_ID
  return id
}

/** URL of the image for `id`, clamped through {@link resolveAvatarId}. */
export function avatarUrlFor(id: number | null | undefined): string {
  const resolved = resolveAvatarId(id)
  // The computer seat's file is `i64.jpg`, NOT `64.jpg` (PlayerPanelExt maps it
  // the same way) — the only id whose filename is not `<id>.jpg`.
  return resolved === COMPUTER_AVATAR_ID
    ? `${AVATAR_BASE_URL}i64.jpg`
    : `${AVATAR_BASE_URL}${resolved}.jpg`
}

/**
 * The avatar to draw for one game seat.
 *
 * `wireId` is what the seat's own payload carried (`Player.avatarId` from
 * GameStateSerializer, which mirrors PlayerView.getUserData().getAvatarId()).
 * When a payload predates that additive field (a replay saved by an older
 * build, or the moments before the first state push after connect), MY OWN seat
 * falls back to the locally chosen avatar — my seat's picture is mine to know —
 * while every other seat draws the default. Anything the resolver cannot match
 * still ends at the default avatar, never a broken image.
 */
export function avatarIdForSeat(
  wireId: number | null | undefined,
  isMe: boolean,
  myAvatarId: number | null | undefined,
): number {
  if (wireId === null || wireId === undefined) {
    return resolveAvatarId(isMe ? myAvatarId : null)
  }
  return resolveAvatarId(wireId)
}

/**
 * Is `id` a pickable avatar? Fail Fast for the store setter and the picker:
 * only the desktop's own selectable range is legal input (the picker can
 * produce nothing else, so anything else is a programming error, not a value to
 * be silently repaired).
 */
export function isPickableAvatarId(id: number): boolean {
  return Number.isInteger(id) && id >= AVATAR_MIN_ID && id <= AVATAR_MAX_ID
}

/**
 * Read a STORED avatar id (localStorage, a hand-edited blob, an old build's
 * value) into a renderable one. This is the untrusted-input ladder: the id is
 * repaired to the default rather than refused, because a cosmetic preference
 * must never wedge the settings dialog — the desktop repairs its stored value
 * the same way (PreferencesDialog:3633-3639). Non-numbers (a string "12", a
 * null, an object) all repair too.
 */
export function readStoredAvatarId(raw: unknown): number {
  if (typeof raw !== 'number' || !Number.isInteger(raw) || !isPickableAvatarId(raw)) {
    return DEFAULT_AVATAR_ID
  }
  return raw
}
