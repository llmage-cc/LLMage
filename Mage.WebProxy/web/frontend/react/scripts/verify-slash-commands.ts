/**
 * Harness: client-side slash commands (see utils/slashCommands.ts).
 *
 * Asserts:
 *  1-14   parseSlashCommand: the four kinds (/help, /h, /clear, /whisper|/w),
 *         case-insensitivity, malformed whisper → `invalid` (never sent),
 *         unknown /commands → RAW passthrough (server stays authoritative),
 *         `\`-spelled server commands and plain chat → untouched, non-string → throws
 * 15-16   whisper length cap mirrors the proxy's MAX_CHAT_LENGTH (and the Java
 *         source actually carries that number)
 * 17-20   activeChatList: game/replay → chatMessages; lobby tabs → talk/system list
 * 21-22   nextLocalChatSeq: strictly increasing, far above any server seq
 * 23-30   store interception through the REAL sendChat with a fake ws:
 *         /help appends locally + sends NOTHING, in the list the user is looking
 *         at (lobby talk vs lobby system vs in-game); /clear empties only the
 *         visible chat list (never the game log); /w forwards the server form
 *         `\w <user> <text>`; plain + unknown-slash text pass through unchanged;
 *         malformed whisper raises an error toast and sends nothing
 *
 * Run: bash scripts/verify-slash-commands.sh
 * (rolldown-bundled like verify-lobby-chat-users.sh; scripts/ is outside tsconfig
 * "include", so this is not part of the vite build.)
 */
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import {
  MAX_CHAT_LENGTH,
  SLASH_COMMAND_HELP,
  activeChatList,
  nextLocalChatSeq,
  parseSlashCommand,
  type SlashCommand,
} from '../src/utils/slashCommands'

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

// Cross-check the proxy's cap so the client mirror cannot silently drift. The
// harness is bundled to /tmp, so the repo root is passed by the .sh wrapper
// (fail fast if it is missing — a silently-skipped check is worse than a red one).
const repoRoot = process.env.XMAGE_REPO_ROOT
if (!repoRoot) {
  throw new Error('XMAGE_REPO_ROOT is not set — run via scripts/verify-slash-commands.sh')
}
const proxySessionSrc = readFileSync(
  `${repoRoot}/Mage.WebProxy/src/main/java/mage/webproxy/WebProxySession.java`,
  'utf8',
)

// ── 1. /help family ──
const help = parseSlashCommand('/help')
check('parse(/help) → help', help.kind === 'help', JSON.stringify(help))
check('parse(/HELP) case-insensitive → help', parseSlashCommand('/HELP').kind === 'help')
check('parse(/help with trailing words) → help', parseSlashCommand('/help me now').kind === 'help')
check('parse(/h) → help alias', parseSlashCommand('/h').kind === 'help')
check(
  'parse(/h Ket) → passthrough (\\h is the server\'s history command)',
  parseSlashCommand('/h Ket').kind === 'passthrough',
)

// ── 2. /clear ──
check('parse(/clear) → clear', parseSlashCommand('/clear').kind === 'clear')
check('parse(/CLEAR) → clear', parseSlashCommand('/CLEAR').kind === 'clear')

// ── 3. /whisper and /w → the server's own spelling ──
const w1 = parseSlashCommand('/whisper Ket hello world')
check(
  'parse(/whisper Ket hello world) → whisper',
  w1.kind === 'whisper' &&
    w1.to === 'Ket' &&
    w1.body === 'hello world' &&
    w1.wire === '\\w Ket hello world',
  JSON.stringify(w1),
)
const w2 = parseSlashCommand('/w Ket hi there')
check(
  'parse(/w Ket hi there) → whisper, same wire form',
  w2.kind === 'whisper' && w2.wire === '\\w Ket hi there',
  JSON.stringify(w2),
)
const w3 = parseSlashCommand('/WHISPER ket  spaced   body ')
check(
  'parse(/WHISPER) case-insensitive, body trimmed + inner spacing kept',
  w3.kind === 'whisper' && w3.to === 'ket' && w3.body === 'spaced   body',
  JSON.stringify(w3),
)

// ── 4. malformed whisper → invalid (caller must not send) ──
for (const bad of ['/whisper', '/whisper Ket', '/w', '/w Ket', '/whisper Ket    ']) {
  const r = parseSlashCommand(bad)
  check(
    `parse(${JSON.stringify(bad)}) → invalid`,
    r.kind === 'invalid' && r.message.includes('/whisper'),
    JSON.stringify(r),
  )
}

// ── 5. unknown /commands pass through RAW (the server stays authoritative) ──
for (const raw of ['/list', '/history Ket', '/card Lightning Bolt', '/ignore bob', '/botgame']) {
  const r = parseSlashCommand(raw)
  check(
    `parse(${JSON.stringify(raw)}) → raw passthrough`,
    r.kind === 'passthrough' && r.text === raw,
    JSON.stringify(r),
  )
}

// ── 6. plain chat + server-spelled `\` commands are never touched ──
for (const raw of ['hello there', 'gg', '\\w Ket hi', '\\list', '2/2 lands tapped', '/', '/ x']) {
  const r = parseSlashCommand(raw)
  check(
    `parse(${JSON.stringify(raw)}) → untouched passthrough`,
    r.kind === 'passthrough' && r.text === raw,
    JSON.stringify(r),
  )
}
check('parse(empty) → passthrough', parseSlashCommand('').kind === 'passthrough')
let threw = false
try {
  parseSlashCommand(42 as never)
} catch (e) {
  threw = e instanceof TypeError
}
check('parse(non-string) → TypeError (fail fast)', threw)

// ── 7. whisper length cap mirrors the proxy's MAX_CHAT_LENGTH ──
check('MAX_CHAT_LENGTH === 500', MAX_CHAT_LENGTH === 500, String(MAX_CHAT_LENGTH))
check(
  'the proxy source still declares 500 (the mirror cannot drift)',
  /MAX_CHAT_LENGTH = 500;/.test(proxySessionSrc),
)
const longBody = 'a'.repeat(MAX_CHAT_LENGTH)
const tooLong = parseSlashCommand(`/whisper Ket ${longBody}`)
check(
  'over-long whisper → invalid (refused client-side)',
  tooLong.kind === 'invalid' && tooLong.message.includes('too long'),
  JSON.stringify(tooLong).slice(0, 120),
)
const fits = parseSlashCommand(`/w Ket ${'a'.repeat(MAX_CHAT_LENGTH - 10)}`)
check('whisper within the cap → whisper', fits.kind === 'whisper')

// ── 8. activeChatList: route a local line to the list the user sees ──
check(
  'activeChatList(in game) → chatMessages',
  activeChatList({ inGame: true, inReplay: false, lobbyChatTab: 'talk' }) === 'chatMessages',
)
check(
  'activeChatList(in replay) → chatMessages',
  activeChatList({ inGame: false, inReplay: true, lobbyChatTab: 'talk' }) === 'chatMessages',
)
check(
  'activeChatList(lobby, talk tab) → lobbyTalkMessages',
  activeChatList({ inGame: false, inReplay: false, lobbyChatTab: 'talk' }) === 'lobbyTalkMessages',
)
check(
  'activeChatList(lobby, system tab) → lobbySystemMessages',
  activeChatList({ inGame: false, inReplay: false, lobbyChatTab: 'system' }) === 'lobbySystemMessages',
)

// ── 9. local seq never collides with the proxy's positive counter ──
const s1 = nextLocalChatSeq()
const s2 = nextLocalChatSeq()
check('nextLocalChatSeq strictly increases', s2 > s1, `${s1} → ${s2}`)
check('nextLocalChatSeq is far above any server seq', s1 > 1_000_000_000, String(s1))

// ── 10. store interception through the REAL sendChat ──
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
feed({ type: 'connected', username: 'qa' })
sent.length = 0

const st = () => useGameStore.getState()

// Lobby, Talk tab (the default surface after connect).
st().setLobbyChatTab('talk')
const talkBefore = st().lobbyTalkMessages.length
const sysBefore = st().lobbySystemMessages.length
const chatBefore = st().chatMessages.length
st().sendChat('/help')
check('/help sends NOTHING on the wire', sent.length === 0, JSON.stringify(sent))
check(
  '/help appends one line to lobbyTalkMessages',
  st().lobbyTalkMessages.length === talkBefore + 1,
)
check(
  '/help text names the commands',
  st().lobbyTalkMessages.at(-1)?.message === SLASH_COMMAND_HELP &&
    SLASH_COMMAND_HELP.includes('/clear') &&
    SLASH_COMMAND_HELP.includes('/whisper'),
)
// The rendered help must show the SERVER's own spelling with a single
// backslash — a doubled one is the classic escaping slip (JSON/log output hides
// it, so assert on the evaluated string).
check(
  'help text shows `\\list` / `\\l` with single backslashes',
  SLASH_COMMAND_HELP.includes('(\\list or \\l lists') &&
    !SLASH_COMMAND_HELP.includes('\\\\list'),
  JSON.stringify(SLASH_COMMAND_HELP),
)
check(
  '/help leaves the other lists alone',
  st().lobbySystemMessages.length === sysBefore && st().chatMessages.length === chatBefore,
)

// Same command, System tab → the SYSTEM list.
st().setLobbyChatTab('system')
st().sendChat('/h')
check(
  '/help on the System tab appends to lobbySystemMessages',
  st().lobbySystemMessages.length === sysBefore + 1 && sent.length === 0,
)

// In game → chatMessages (RightRail), never a lobby list.
st().setInGame(true)
const chatBeforeGame = st().chatMessages.length
const talkBeforeGame = st().lobbyTalkMessages.length
st().sendChat('/help')
check(
  '/help in a game appends to chatMessages (RightRail)',
  st().chatMessages.length === chatBeforeGame + 1 && sent.length === 0,
)
check('/help in a game leaves lobby lists alone', st().lobbyTalkMessages.length === talkBeforeGame)
const localSeq = st().chatMessages.at(-1)!.seq

// A local line must survive a rejoin backfill AND sort last in it: mergeHistory
// re-sorts chatMessages by seq (gameplaySlice.mergeBySeq), so a colliding or low
// local seq would either drop a real line or reorder the panel. Drive the REAL
// mergeHistory through the REAL message handler.
feed({ type: 'event', event: { seq: 7, type: 'talk', chatType: 'talk', from: 'Ket', message: 'hi' } })
feed({
  type: 'history_backfill',
  gameId: 'g-backfill',
  events: [{ seq: 1, type: 'action', message: 'Ket casts Bolt' }],
  chat: [{ seq: 9_000_000, type: 'talk', chatType: 'talk', from: 'Ket', message: 'backfilled talk' }],
} as never)
const merged = st().chatMessages
check(
  'local line survives a history_backfill (no seq collision with the server counter)',
  merged.some((e) => e.seq === localSeq),
)
check(
  'local line sorts AFTER every server seq once merged',
  merged.at(-1)!.seq === localSeq && localSeq > 9_000_000,
  `${localSeq} last-of ${merged.length}`,
)

// ── 11. /clear empties only the visible chat list ──
st().appendEvent({ seq: 100, type: 'action', message: 'Ket casts Bolt' } as never)
const logBefore = st().eventLog.length
st().sendChat('/clear')
check('/clear empties chatMessages in a game', st().chatMessages.length === 0)
check('/clear never touches the game log', st().eventLog.length === logBefore)
check('/clear sends NOTHING on the wire', sent.length === 0)

st().setInGame(false)
st().setLobbyChatTab('talk')
st().sendChat('/clear')
check(
  '/clear empties lobbyTalkMessages in the lobby (Talk tab)',
  st().lobbyTalkMessages.length === 0 && st().lobbySystemMessages.length === sysBefore + 1,
)

// ── 12. /whisper and /w forward the server's own spelling ──
st().sendChat('/whisper Ket good luck')
check(
  '/whisper forwards `\\w Ket good luck`',
  sent.length === 1 &&
    sent[0]!.type === 'send_chat' &&
    sent[0]!.message === '\\w Ket good luck',
  JSON.stringify(sent),
)
sent.length = 0
st().sendChat('/w Ket gg')
check(
  '/w forwards the same wire form',
  sent.length === 1 && sent[0]!.message === '\\w Ket gg',
  JSON.stringify(sent),
)

// ── 13. passthrough keeps the pre-existing contract ──
sent.length = 0
st().sendChat('gg')
check(
  'plain chat still sends send_chat with the raw text',
  sent.length === 1 && sent[0]!.type === 'send_chat' && sent[0]!.message === 'gg',
  JSON.stringify(sent),
)
sent.length = 0
st().sendChat('/list')
check(
  'unknown /command passes through RAW (server authoritative)',
  sent.length === 1 && sent[0]!.message === '/list',
  JSON.stringify(sent),
)
sent.length = 0
st().sendChat('\\w Ket hi')
check(
  '`\\`-spelled server command is never rewritten',
  sent.length === 1 && sent[0]!.message === '\\w Ket hi',
  JSON.stringify(sent),
)

// ── 14. malformed whisper: error toast, nothing sent ──
sent.length = 0
const errBefore = st().errorMessage
st().sendChat('/whisper Ket')
check('malformed /whisper sends NOTHING', sent.length === 0, JSON.stringify(sent))
check(
  'malformed /whisper raises a visible error',
  st().errorMessage !== errBefore && /Usage: \/whisper/.test(st().errorMessage),
  st().errorMessage,
)

// ── 15. the help line never leaked onto the wire during this run ──
const wireTexts = sent.map((m) => String(m.message ?? ''))
check(
  'no local help text was ever sent',
  wireTexts.every((t) => t !== SLASH_COMMAND_HELP),
)
check(
  'proxy source cross-check resolved',
  proxySessionSrc.includes('handleSendChat'),
)

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-slash-commands: ALL CHECKS PASS')
// Explicit exit: showError arms a 5 s toast timer (uiSlice) that would otherwise
// hold the event loop open — the results above are complete and deterministic.
process.exit(0)
