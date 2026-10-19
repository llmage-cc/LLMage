import type { ConnectRequest } from '@/types'
import { resolveAvatarId } from '@/utils/avatars'

/**
 * The shape of the connect form's fields. `hideServerTarget` comes from the
 * proxy's GET /config (see utils/proxyConfig.ts).
 */
export interface ConnectFormState {
  hideServerTarget: boolean
  server: string
  port: string
  username: string
  password: string
  authToken: string
  /**
   * My avatar id — persisted in xmage-settings like the other display
   * preferences (the desktop keeps its choice in PreferencesDialog.KEY_AVATAR).
   * Always sent: an avatar is a pure preference, so there is no deployment
   * condition under which the proxy should have to guess it.
   */
  avatarId: number
}

/**
 * Persistent browser ID for the shared-IP problem (
 * official-shared-ip.md Mitigation 2). Stored in localStorage so it survives
 * refreshes; sent to the proxy as `browserId` so the XMage server sees a
 * stable per-user string instead of an empty `userIdStr`.
 *
 * Honest format: `web:<username>:<uuid>` — tells the server this is a web
 * proxy user, not a desktop client, and gives admins a per-browser handle.
 */
export function getBrowserId(username: string): string {
  const KEY = 'xmage-browser-id'
  try {
    let id = localStorage.getItem(KEY)
    if (!id) {
      id = crypto.randomUUID()
      localStorage.setItem(KEY, id)
    }
    return `web:${username}:${id}`
  } catch {
    // localStorage unavailable (private mode): fall back to session-scoped
    return `web:${username}:no-localstorage-${Math.random().toString(36).slice(2, 10)}`
  }
}

/**
 * Build the `connect` message for the proxy.
 *
 * With hideServerTarget (a deployment that pins the XMage target itself) the
 * message omits server/port entirely: the proxy ignores client-supplied values
 * anyway, and sending them would misrepresent a target the form never showed.
 * Unpinned (the local/LAN default) both fields are sent, exactly as before.
 *
 * Exported separately from the component so the behaviour is provable under
 * Node without a browser (scripts/verify-connect-target-toggle.sh).
 */
export function buildConnectRequest(form: ConnectFormState): ConnectRequest {
  const base = {
    type: 'connect' as const,
    username: form.username,
    password: form.password,
    browserId: getBrowserId(form.username),
    // Avatar: always sent — the proxy clamps it to a drawable id and logs
    // any repair. `resolveAvatarId` here means a stale/broken stored value can
    // never reach the wire in the first place.
    avatarId: resolveAvatarId(form.avatarId),
    ...(form.authToken ? { authToken: form.authToken } : {}),
  }
  if (form.hideServerTarget) {
    return base
  }
  return { ...base, server: form.server, port: parseInt(form.port) }
}

/**
 * The credentials safe to persist for refresh convenience (never
 * the password, never the auth token). server/port are persisted only when the
 * form owns them — a hidden-target deployment reconnects from the username
 * alone, because its proxy pins the target.
 */
export function persistedConnectCreds(form: ConnectFormState): {
  username: string
  server?: string
  port?: string
} {
  return {
    username: form.username,
    ...(form.hideServerTarget ? {} : { server: form.server, port: form.port }),
  }
}
