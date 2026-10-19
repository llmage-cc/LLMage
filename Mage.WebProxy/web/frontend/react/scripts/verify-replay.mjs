/**
 * Live end-to-end check for the web client's REPLAY path.
 *
 * Drives a real proxy (scratch port) over a real WebSocket and asserts the
 * request/response contract the frontend depends on:
 *   get_replay_files  -> replay_files        (ids of saved/<uuid>.game on the server)
 *   replay_load       -> replay_game         (server created the ReplaySession)
 *   replay_start      -> replay_init         (GameView frame 0, serialized)
 *   replay_next       -> replay_update       (next frame)
 *   replay_previous   -> replay_update       (previous frame)
 *   replay_skip       -> replay_update       (N frames ahead)
 *   replay_stop       -> replay_done/stopped
 *   replay_load+start on a MISSING file -> replay_done/error "Replay file not found or corrupt"
 *   replay_next without a loaded replay -> error (fail fast, no silent no-op)
 *
 * Uses Node's built-in WebSocket (Node >= 22) — no dependencies.
 *
 * Run (the proxy must already be up on --port, and an XMage server must be
 * reachable at --server):
 *
 *   node scripts/verify-replay.mjs --port 18091 --server 127.0.0.1:17171
 */

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const port = parseInt(arg('port', '18091'), 10)
const server = arg('server', '127.0.0.1:17171')
// XMage usernames are capped at 14 characters server-side.
const username = arg('username', `rp${String(Date.now()).slice(-8)}`)
const [serverHost, serverPort] = server.split(':')
const missingGameId = arg('missingGameId', '00000000-0000-0000-0000-0000000000ff')

const results = []
function record(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, {
  headers: { Origin: `http://127.0.0.1:${port}` },
})

/** Wait for the next message matching `pred`, or reject after `timeoutMs`. */
function next(pred, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.removeEventListener('message', onMessage)
      reject(new Error(`timeout waiting for ${pred.toString()}`))
    }, timeoutMs)
    function onMessage(ev) {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return
      }
      if (pred(msg)) {
        clearTimeout(timer)
        ws.removeEventListener('message', onMessage)
        resolve(msg)
      }
    }
    ws.addEventListener('message', onMessage)
  })
}

function send(obj) {
  ws.send(JSON.stringify(obj))
}

let finished = false
function finish() {
  if (finished) return
  finished = true
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  try {
    ws.close()
  } catch {
    // already closed
  }
  process.exit(failed.length === 0 ? 0 : 1)
}

ws.addEventListener('error', (e) => {
  record('websocket', false, e.message || 'websocket error')
  finish()
})

ws.addEventListener('open', async () => {
  try {
    send({ type: 'connect', server: serverHost, port: serverPort, username, password: '' })
    const connected = await next((m) => m.type === 'connected' || m.type === 'connect_error')
    if (connected.type === 'connect_error') {
      record('connect', false, connected.message)
      return finish()
    }
    record('connect', true, `as ${connected.username}`)

    // ── 1. Replay file list ──
    send({ type: 'get_replay_files' })
    const files = await next((m) => m.type === 'replay_files')
    record(
      'get_replay_files',
      Array.isArray(files.gameIds) && files.gameIds.length > 0,
      `${files.gameIds?.length ?? 0} file(s) in ${files.directory}/`
    )
    const gameId = arg('gameId', files.gameIds?.[0])
    if (!gameId) {
      record('pick a replay', false, 'no replay files on the server — cannot continue')
      return finish()
    }
    console.log(`     using replay ${gameId}`)

    // ── 2. Fail-fast: playback without a loaded replay ──
    send({ type: 'replay_next' })
    const noReplay = await next((m) => m.type === 'error')
    record('replay_next without load is refused', /no replay loaded/i.test(noReplay.message || ''), noReplay.message)

    // ── 3. Fail-fast: a missing file surfaces as replay_done/error ──
    send({ type: 'replay_load', gameId: missingGameId })
    const missingLoading = await next((m) => m.type === 'replay_loading')
    record(
      'replay_load acknowledges IMMEDIATELY (replay_loading)',
      missingLoading.gameId === missingGameId && 'bytes' in missingLoading,
      `bytes=${missingLoading.bytes}`
    )
    // The ack must carry a byte count for a file that exists, and null for one
    // that does not — null is not an error (the proxy may simply not share the
    // server's filesystem), so both are valid here.
    record(
      'replay_loading reports bytes as a number or null',
      missingLoading.bytes === null || typeof missingLoading.bytes === 'number',
      String(missingLoading.bytes)
    )
    const missingAck = await next((m) => m.type === 'replay_game')
    record('replay_load (missing file) creates a session', missingAck.gameId === missingGameId, missingAck.gameId)
    send({ type: 'replay_start' })
    const missingDone = await next((m) => m.type === 'replay_done')
    record(
      'missing file -> replay_done/error',
      missingDone.reason === 'error' && /not found or corrupt/i.test(missingDone.result || ''),
      `${missingDone.reason}: ${missingDone.result}`
    )

    // ── 4. Real replay: load -> start -> frames -> stop ──
    send({ type: 'replay_load', gameId })
    // THE POINT OF THE ACK: it must arrive before the blocking load completes.
    // Measure the gap; if the proxy only sent it after replay_game the whole
    // exercise is pointless (a multi-MB save blocks the server for seconds).
    const loadSentAt = Date.now()
    const ackLoading = await next((m) => m.type === 'replay_loading')
    const ackLoadingMs = Date.now() - loadSentAt
    record(
      'replay_loading arrives promptly after replay_load',
      ackLoading.gameId === gameId && ackLoadingMs < 500,
      `${ackLoadingMs}ms, bytes=${ackLoading.bytes}`
    )
    record(
      'replay_loading carries the save size for an existing file',
      typeof ackLoading.bytes === 'number' && ackLoading.bytes > 0,
      String(ackLoading.bytes)
    )
    const ack = await next((m) => m.type === 'replay_game')
    record('replay_load', ack.gameId === gameId, ack.gameId)

    send({ type: 'replay_start' })
    const init = await next((m) => m.type === 'replay_init')
    const initState = init.state
    record(
      'replay_start -> replay_init with a serialized frame',
      initState != null &&
        Array.isArray(initState.players) &&
        initState.players.length > 0 &&
        typeof initState.turn === 'number' &&
        initState.isSpectator === true,
      `players=${initState?.players?.length} turn=${initState?.turn} spectator=${initState?.isSpectator}`
    )

    const firstTurn = initState?.turn
    send({ type: 'replay_next' })
    const upd = await next((m) => m.type === 'replay_update' || m.type === 'replay_done')
    record('replay_next -> replay_update', upd.type === 'replay_update' && upd.state != null, `turn=${upd.state?.turn}`)

    send({ type: 'replay_previous' })
    const back = await next((m) => m.type === 'replay_update')
    record(
      'replay_previous -> replay_update',
      back.state != null && back.state.turn === firstTurn,
      `turn=${back.state?.turn} (expected ${firstTurn})`
    )

    send({ type: 'replay_skip', moves: 5 })
    const skipped = await next((m) => m.type === 'replay_update' || m.type === 'replay_done')
    record('replay_skip(5)', skipped.type === 'replay_update', `turn=${skipped.state?.turn ?? skipped.result}`)

    send({ type: 'replay_stop' })
    const done = await next((m) => m.type === 'replay_done')
    record('replay_stop -> replay_done/stopped', done.reason === 'stopped', `${done.reason}: ${done.result}`)
  } catch (e) {
    record('scripted flow', false, e.message)
  } finally {
    finish()
  }
})
