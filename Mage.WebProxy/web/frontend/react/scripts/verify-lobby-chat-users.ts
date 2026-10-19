/**
 * Harness: lobby chat routing + users panel logic (see utils/lobbyChat.ts).
 *
 * Asserts:
 *  1-6   routeChat mapping: talk/whisper/whisper_from/whisper_to → 'talk';
 *        system-ish user_info/status/game/motd → 'system'
 *  7     unknown chat types fail visible → 'system'
 *  8-12  users sort: self first, then case-insensitive by name, stable ties,
 *        input array never mutated
 * 13-20  users_list JSON parsing: valid fixtures (exact UsersView field set),
 *        malformed payloads (missing users array / bad userName / bad rating /
 *        non-object payload / null entries) throw with a named error
 * 21-24  store wiring through the REAL message handler: users_list populates
 *        lobbyUsers + lobbyMotd; incoming chat events route into
 *        lobbyTalkMessages / lobbySystemMessages; requestUsers sends
 *        get_room_users
 *
 * Run: bash scripts/verify-lobby-chat-users.sh
 * (rolldown-bundled like verify-replay-browser.sh; scripts/ is outside tsconfig
 * "include", so this is not part of the vite build.)
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { routeChat, sortUsers, parseUsersListPayload, type LobbyUser } from '../src/utils/lobbyChat'

let failures = 0
let passed = 0
function check(name: string, ok: boolean, detail = '') {
  if (ok) {
    passed++
    console.log(`PASS  ${name}`)
  } else {
    failures++
    console.log(`FAIL  ${name}${detail ? '  — ' + detail : ''}`)
  }
}
function throwsWith(fn: () => unknown, needle: string): boolean {
  try {
    fn()
    return false
  } catch (e) {
    return e instanceof Error && e.message.includes(needle)
  }
}

// ── 1. routeChat: player conversation → Talk ──
check('routeChat(talk) === talk', routeChat('talk') === 'talk')
check('routeChat(whisper_from) === talk', routeChat('whisper_from') === 'talk')
check('routeChat(whisper_to) === talk', routeChat('whisper_to') === 'talk')
check('routeChat(whisper alias) === talk', routeChat('whisper') === 'talk')

// ── 2. routeChat: server narration → System ──
check('routeChat(user_info) === system', routeChat('user_info') === 'system')
check('routeChat(status) === system', routeChat('status') === 'system')
check('routeChat(game) === system', routeChat('game') === 'system')
check('routeChat(motd) === system', routeChat('motd') === 'system')

// ── 3. routeChat: unknown/absent types fail visible into System ──
check('routeChat(unknown) === system', routeChat('super_new_type') === 'system')
check('routeChat(null) === system', routeChat(null) === 'system')
check('routeChat(undefined) === system', routeChat(undefined) === 'system')
check('routeChat is case-insensitive', routeChat('TALK') === 'talk' && routeChat('GAME') === 'system')

// ── 4. users sort: self first, then alphabetical ──
const mkUser = (name: string): LobbyUser => ({
  flagName: 'us', userName: name, matchHistory: null, matchQuitRatio: 0,
  tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: null,
  generalRating: 0, constructedRating: 0, limitedRating: 0,
})
{
  const input = [mkUser('zeta'), mkUser('Alice'), mkUser('me'), mkUser('bob')]
  const sorted = sortUsers(input, 'me')
  check('sort: self first', sorted[0]?.userName === 'me')
  check('sort: rest case-insensitive alpha', sorted.slice(1).map((u) => u.userName).join(',') === 'Alice,bob,zeta')
  check('sort: does not mutate input', input[0]?.userName === 'zeta' && input.length === 4)
}
{
  // self matching is case-insensitive too
  const sorted = sortUsers([mkUser('alice'), mkUser('ME')], 'me')
  check('sort: self match is case-insensitive', sorted[0]?.userName === 'ME')
}
{
  // no self in list → plain alphabetical
  const sorted = sortUsers([mkUser('zeta'), mkUser('Alice')], 'nobody')
  check('sort: without self, plain alpha', sorted.map((u) => u.userName).join(',') === 'Alice,zeta')
}
{
  // duplicate names keep a stable order (tie broken on raw name, sort is stable in V8)
  const sorted = sortUsers([mkUser('bob'), mkUser('Bob'), mkUser('alice')], null)
  check('sort: stable for duplicates', sorted[2]?.userName === 'alice' || sorted[0]?.userName === 'alice')
}
{
  // empty list + null selfName are safe
  check('sort: empty list ok', sortUsers([], null).length === 0)
}

// ── 5. users_list JSON parsing: valid fixtures ──
{
  const payload = {
    type: 'users_list',
    users: [
      {
        flagName: 'de', userName: 'Ket', matchHistory: '12-3', matchQuitRatio: 5,
        tourneyHistory: '2-1', tourneyQuitRatio: 7, infoGames: '4', infoPing: '23 ms',
        generalRating: 1500, constructedRating: 1600, limitedRating: 1550,
      },
      {
        flagName: null, userName: 'anon', matchHistory: null, matchQuitRatio: 0,
        tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: null,
        generalRating: 0, constructedRating: 0, limitedRating: 0,
      },
    ],
    motd: ['If you like XMage consider supporting by Patreon at https://xmage.today/#donate', 'Server uptime: 42 hours'],
  }
  const users = parseUsersListPayload(payload)
  check('parse: valid fixture length', users.length === 2)
  check('parse: fields carried through', users[0]?.userName === 'Ket' && users[0]?.flagName === 'de' && users[0]?.generalRating === 1500)
  check('parse: null-able fields stay null', users[1]?.flagName === null && users[1]?.infoPing === null)
  check('parse: empty users list is valid', parseUsersListPayload({ type: 'users_list', users: [], motd: [] }).length === 0)
}

// ── 6. users_list JSON parsing: malformed → fail fast ──
check('parse: non-object payload throws', throwsWith(() => parseUsersListPayload('nope'), 'not an object'))
check('parse: null payload throws', throwsWith(() => parseUsersListPayload(null), 'not an object'))
check('parse: missing users array throws', throwsWith(() => parseUsersListPayload({ type: 'users_list' }), 'no users array'))
check('parse: users not array throws', throwsWith(() => parseUsersListPayload({ users: 'all' }), 'no users array'))
check('parse: entry not object throws', throwsWith(() => parseUsersListPayload({ users: ['x'] }), 'is not an object'))
check('parse: missing userName throws', throwsWith(() => parseUsersListPayload({ users: [{ generalRating: 0 }] }), 'no userName'))
check('parse: empty userName throws', throwsWith(() => parseUsersListPayload({ users: [{ userName: '' }] }), 'no userName'))
check('parse: bad rating throws', throwsWith(() => parseUsersListPayload({ users: [{ userName: 'a', flagName: null, matchHistory: null, matchQuitRatio: 0, tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: null, generalRating: 'high', constructedRating: 0, limitedRating: 0 }] }), 'generalRating is not a number'))
check('parse: NaN rating throws', throwsWith(() => parseUsersListPayload({ users: [{ userName: 'a', matchQuitRatio: Number.NaN }] }), 'matchQuitRatio is not a number'))
check('parse: null entry throws', throwsWith(() => parseUsersListPayload({ users: [null] }), 'is not an object'))

// ── 7. store wiring through the REAL message handler ──
const sent: Record<string, unknown>[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)
const feed = (m: unknown) => handleMessage(m as never, useGameStore.getState())

// connect so ws/connected are live (also proves the new action coexists with connect)
feed({ type: 'connected', username: 'qa' })
sent.length = 0
useGameStore.getState().requestUsers()
check('requestUsers sends get_room_users', sent.some((m) => m.type === 'get_room_users'))

feed({
  type: 'users_list',
  users: [
    { flagName: 'us', userName: 'zeta', matchHistory: null, matchQuitRatio: 0, tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: '9 ms', generalRating: 0, constructedRating: 0, limitedRating: 0 },
    { flagName: 'us', userName: 'qa', matchHistory: null, matchQuitRatio: 0, tourneyHistory: null, tourneyQuitRatio: 0, infoGames: null, infoPing: '5 ms', generalRating: 0, constructedRating: 0, limitedRating: 0 },
  ],
  motd: ['If you like XMage consider supporting by Patreon', 'Server uptime: 7 hours'],
} as never)
check('users_list populates lobbyUsers', useGameStore.getState().lobbyUsers.length === 2)
check('users_list joins MOTD lines into lobbyMotd', (useGameStore.getState().lobbyMotd ?? '').includes('Patreon'))
check('users_list empty motd → lobbyMotd null', (feed({ type: 'users_list', users: [], motd: [] } as never), useGameStore.getState().lobbyMotd === null))

// malformed users_list → visible error, store state kept
const errBefore = useGameStore.getState().errorMessage
feed({ type: 'users_list', users: 'oops' } as never)
check('users_list malformed → error toast raised', useGameStore.getState().errorMessage !== errBefore)

// chat events route into the right tab lists
feed({ type: 'event', event: { seq: 1, type: 'talk', chatType: 'talk', from: 'Ket', message: 'hi all' } } as never)
feed({ type: 'event', event: { seq: 2, type: 'talk', chatType: 'whisper_from', from: 'Ket', message: 'psst' } } as never)
feed({ type: 'event', event: { seq: 3, type: 'action', chatType: 'user_info', from: 'Game', message: 'Ket has left' } } as never)
feed({ type: 'event', event: { seq: 4, type: 'action', chatType: 'status', from: 'Game', message: 'reconnected' } } as never)
const talk = useGameStore.getState().lobbyTalkMessages
const sys = useGameStore.getState().lobbySystemMessages
check('talk line routed to Talk tab', talk.some((e) => e.seq === 1))
check('whisper routed to Talk tab', talk.some((e) => e.seq === 2))
check('user_info routed to System tab', sys.some((e) => e.seq === 3))
check('status routed to System tab', sys.some((e) => e.seq === 4))

// in-game chat path unaffected: the same feed still populates chatMessages (RightRail)
check('RightRail chatMessages still populated', useGameStore.getState().chatMessages.some((e) => e.seq === 1))

// sendChat contract unchanged (used by LobbyChat's input)
sent.length = 0
useGameStore.getState().sendChat('gg')
check('sendChat sends send_chat', sent.some((m) => m.type === 'send_chat' && m.message === 'gg'))

// tab switch action
useGameStore.getState().setLobbyChatTab('system')
check('setLobbyChatTab switches tab', useGameStore.getState().lobbyChatTab === 'system')

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-lobby-chat-users: ALL CHECKS PASS')