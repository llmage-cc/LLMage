/**
 * Harness: the models spinner can no longer stick

 *
 * Run: bash scripts/verify-models-error.sh   (self-locking — do not wrap it)
 *
 * The defect: on a token-gated proxy a `fetch_models` that raced the connect
 * frame was rejected by the proxy's auth gate with the GENERIC `error` frame.
 * `modelsLoading` (set by fetchModels/refreshModels) is only cleared by
 * models_list / models_error, so the model dropdown spun forever.
 *
 * Covers, through the REAL modules (store + messageHandlers, no browser):
 *  1  proxy half — a `models_error` frame clears the spinner, surfaces the
 *     message in the setup dialog, and consumes the pending marker
 *  2  proxy half — a `personalities_error` frame (new typed verb) surfaces
 *     + clears its marker; it never touches models state
 *  3  the pending marker is set by the proxy senders and NOT by a
 *     browser-direct fetch (the marker is the whole safety guard)
 *  4  fallback — a GENERIC `error` while a proxy models fetch is pending
 *     resolves the spinner + surfaces the text (older-proxy path)
 *  5  the guard — a generic error with NO pending fetch disturbs NOTHING
 *     (models state, spinner, or a BROWSER-DIRECT fetch that is in flight)
 *  6  the dialog-open pair (fetchModels + fetchPersonalities in one tick) leaves
 *     'models' as the pending marker — the personalities verb never clobbers it
 *  7  the Java source cross-check: the proxy's auth-reject path really sends the
 *     typed frame (the harness pins the fix it is testing)
 *
 * scripts/ is outside tsconfig "include", so this is not part of the vite build.
 */
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage } from '../src/types'

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
function section(t: string) {
  console.log(`\n## ${t}`)
}

// A live ws stand-in so the store's senders work; records what they send.
const sent: Array<Record<string, unknown>> = []
useGameStore.setState({
  ws: {
    send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
    onStateVersionReceived: () => {},
    requestResync: () => {},
    clearResyncFlag: () => {},
    noteConnectAccepted: () => {},
    disconnect: () => {},
  } as never,
})
const feed = (m: unknown) => handleMessage(m as ServerMessage, useGameStore.getState())
const st = () => useGameStore.getState()

// ─────────────────────────────────────────────────────────────────────────────
section('The proxy source really sends the typed frame (fix cross-check)')

{
  const repoRoot = process.env.XMAGE_REPO_ROOT
  if (!repoRoot) {
    throw new Error('XMAGE_REPO_ROOT is not set — run via scripts/verify-models-error.sh')
  }
  const session = readFileSync(
    `${repoRoot}/Mage.WebProxy/src/main/java/mage/webproxy/WebProxySession.java`,
    'utf8',
  )
  // The exact wire frames the harness below feeds — pinned against the Java source
  // so a regression there fails HERE, not only in the Java unit test.
  check(
    'requireAuthFor sends models_error for the fetch_models reject',
    /"fetch_models"\.equals\(type\)\)\s*\{\s*sendTypedMessage\("models_error"/.test(session),
  )
  check(
    'requireAuthFor sends personalities_error for the fetch_personalities reject',
    /"fetch_personalities"\.equals\(type\)\)\s*\{[\s\S]{0,1500}?sendTypedMessage\("personalities_error"/.test(session),
  )
  check(
    'the auth message text is the one shared constant',
    session.includes('AUTH_REQUIRED_MESSAGE = "Not authenticated — connect with an auth token first"'),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
section('Senders — the pending marker is set by proxy fetches only')

{
  // fetchModels (dialog open path): the primer dials the first LLM seat's endpoint.
  // It must have one — an EMPTY seat endpoint means "(server default)" and the
  // primer must NOT substitute a fabricated localhost default. Give the seat an
  // endpoint so the sender is exercised on its real path.
  const withSeatEndpoint = () => {
    useGameStore.setState({
      setupConfig: {
        ...st().setupConfig,
        playerSlots: [
          { ...st().setupConfig.playerSlots[0]!, type: 'COMPUTER_LLM', llmConfig: { apiEndpoint: 'https://opencode.ai/zen/go/v1/chat/completions' } },
        ],
      },
    } as never)
  }
  withSeatEndpoint()
  sent.length = 0
  st().fetchModels()
  check('fetchModels sends fetch_models', sent.some((m) => m.type === 'fetch_models'))
  check('fetchModels sets modelsLoading', st().modelsLoading === true)
  check('fetchModels marks pendingFetch=models', st().pendingFetch === 'models')

  // The dialog-open primer with NO seat endpoint must send NOTHING: dialing a
  // fabricated localhost default is what produced the wrong-host allowlist banner.
  st().setAvailableModels([])
  useGameStore.setState({
    setupConfig: {
      ...st().setupConfig,
      playerSlots: [{ ...st().setupConfig.playerSlots[0]!, type: 'COMPUTER_LLM', llmConfig: {} }],
    },
  } as never)
  sent.length = 0
  st().fetchModels()
  check('fetchModels with no seat endpoint dials nothing (no fabricated localhost default)',
    !sent.some((m) => m.type === 'fetch_models'), JSON.stringify(sent))
  check('…and raises no spinner', st().modelsLoading === false)

  // refreshModels (seat endpoint-change / refresh button)
  st().setAvailableModels(['m1']) // clears the previous fetch cleanly
  sent.length = 0
  st().refreshModels('http://localhost:11434/v1/chat/completions', '')
  check('refreshModels sends fetch_models', sent.some((m) => m.type === 'fetch_models'))
  check('refreshModels marks pendingFetch=models', st().pendingFetch === 'models')

  // browser-direct (client-key seat): NO proxy fetch, so no marker — this is what
  // keeps an unrelated proxy error from killing a browser-direct fetch.
  st().setAvailableModels([])
  st().refreshModelsBrowserDirect('http://localhost:11434/v1/chat/completions', 'k')
  check('a browser-direct fetch raises the spinner', st().modelsLoading === true)
  check('a browser-direct fetch does NOT mark pendingFetch (it never touches the proxy)',
    st().pendingFetch === null, `pendingFetch=${String(st().pendingFetch)}`)
  // It will reject shortly (no real fetch here) — let the microtask queue drain
  // so the promise's own catch cannot bleed into the next scenario.
}

// ─────────────────────────────────────────────────────────────────────────────
section('Typed replies — models_error / personalities_error resolve their half')

{
  // ── (a) the exact frame: models_error → spinner clears + text surfaces
  st().setModelsError('') // reset the surface
  st().setAvailableModels([])
  sent.length = 0
  st().fetchModels('http://localhost:11434/v1/chat/completions')
  check('fetch pending before the reply', st().modelsLoading === true && st().pendingFetch === 'models')

  feed({ type: 'models_error', message: 'Not authenticated — connect with an auth token first' } as ServerMessage)

  check('models_error clears modelsLoading', st().modelsLoading === false)
  check('models_error surfaces the message in the setup dialog',
    st().setupError === 'Not authenticated — connect with an auth token first', st().setupError)
  check('models_error consumes the pending marker', st().pendingFetch === null)

  // ── models_list success path clears the marker too (no stale marker can leak)
  st().setModelsError('')
  st().fetchModels('http://localhost:11434/v1/chat/completions')
  feed({ type: 'models_list', models: ['glm-5.3-flash'] } as ServerMessage)
  check('models_list clears modelsLoading + marker',
    st().modelsLoading === false && st().pendingFetch === null)
  check('models_list stored the models', st().availableModels.includes('glm-5.3-flash'))

  // ── (b) personalities_error: new typed verb (proxy half, symmetric frame)
  st().setModelsError('')
  sent.length = 0
  st().fetchPersonalities()
  check('fetchPersonalities marks pendingFetch=personalities',
    sent.some((m) => m.type === 'fetch_personalities') && st().pendingFetch === 'personalities')

  feed({ type: 'personalities_error', message: 'Not authenticated — connect with an auth token first' } as ServerMessage)
  check('personalities_error clears its marker', st().pendingFetch === null)
  check('personalities_error raises a visible error (no silent drop)',
    st().errorToastVisible === true || st().setupError !== '')
  check('personalities_error does NOT touch models state',
    st().modelsLoading === false && st().availableModels.length >= 1)

  // personalities_list clears the marker as well
  st().fetchPersonalities()
  feed({ type: 'personalities_list', personalities: ['alex'] } as ServerMessage)
  check('personalities_list clears the marker + stores the list',
    st().pendingFetch === null && st().availablePersonalities.includes('alex'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Fallback — a GENERIC error while a proxy fetch is pending (old-proxy path)')

{
  // ── (a) the fallback resolves the spinner and surfaces the text
  st().setModelsError('')
  st().setAvailableModels([])
  st().fetchModels('http://localhost:11434/v1/chat/completions')
  check('proxy fetch pending', st().modelsLoading === true && st().pendingFetch === 'models')

  feed({ type: 'error', message: 'Not authenticated — connect with an auth token first' } as ServerMessage)

  check('a generic error while a models fetch was pending clears modelsLoading',
    st().modelsLoading === false)
  check('…and surfaces the proxy text in the setup dialog (not just a toast)',
    st().setupError === 'Not authenticated — connect with an auth token first', st().setupError)
  check('…and consumes the pending marker', st().pendingFetch === null)

  // ── (b) a pending PERSONALITIES fetch is cleared by the error too, but the
  //       models spinner is untouched (there is none) and models data survives.
  st().setModelsError('')
  st().setAvailableModels(['keep-me'])
  st().fetchPersonalities()
  feed({ type: 'error', message: 'Not authenticated — connect with an auth token first' } as ServerMessage)
  check('a generic error during a personalities fetch clears its marker', st().pendingFetch === null)
  check('…and leaves models data intact', st().availableModels.includes('keep-me'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('The guard — no pending fetch means an error disturbs nothing')

{
  // ── (c) the required negative: NO pending fetch, models state idle
  st().setAvailableModels(['stable-model'])
  st().setModelsError('')
  const before = {
    modelsLoading: st().modelsLoading,
    availableModels: [...st().availableModels],
    pendingFetch: st().pendingFetch,
    setupError: st().setupError,
  }

  feed({ type: 'error', message: 'Rate limit — wait before fetching again (fetch_models)' } as ServerMessage)

  check('spinner untouched by an unrelated error', st().modelsLoading === before.modelsLoading)
  check('model list untouched by an unrelated error',
    JSON.stringify(st().availableModels) === JSON.stringify(before.availableModels))
  check('pending marker untouched (still null)', st().pendingFetch === before.pendingFetch)
  check('the error still raises its toast (existing behaviour kept)', st().errorToastVisible === true)

  // ── the sharpest case: a BROWSER-DIRECT fetch is in flight (spinner up, NO
  //    proxy marker) and an unrelated proxy error lands. It must survive — the
  //     direct fetch owns its own promise resolution.
  st().setModelsError('')
  st().setAvailableModels([])
  st().refreshModelsBrowserDirect('http://localhost:11434/v1/chat/completions', 'k')
  check('browser-direct fetch in flight', st().modelsLoading === true && st().pendingFetch === null)

  feed({ type: 'error', message: 'Some unrelated proxy error' } as ServerMessage)

  check('an unrelated error does NOT clear a browser-direct fetch spinner',
    st().modelsLoading === true, `modelsLoading=${String(st().modelsLoading)}`)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Dialog-open pair — both verbs primed in one tick, models marker wins')

{
  st().setModelsError('')
  st().setAvailableModels([])
  // The exact order showGameSetup / showTournamentSetupDialog use.
  st().fetchModels('http://localhost:11434/v1/chat/completions')
  st().fetchPersonalities()

  check('the pair leaves pendingFetch=models (personalities never clobbers it)',
    st().pendingFetch === 'models', `pendingFetch=${String(st().pendingFetch)}`)

  // The personalities_list arrives FIRST (it is a local registry read) — it must
  // not clear the models marker.
  feed({ type: 'personalities_list', personalities: ['alex'] } as ServerMessage)
  check('personalities_list does not clear the models marker', st().pendingFetch === 'models')

  // …and then a generic error (old-proxy auth reject for the models half) still
  // resolves the spinner.
  feed({ type: 'error', message: 'Not authenticated — connect with an auth token first' } as ServerMessage)
  check('the pair + generic error still resolves the spinner', st().modelsLoading === false)
  check('…and clears the marker', st().pendingFetch === null)
}

// The browser-direct fetch from the guard section rejects on its own (no fetch
// implementation under Node) after the harness's checks are done — its rejection
// is expected and must not fail the run.
process.on('unhandledRejection', () => {})

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-models-error: ALL CHECKS PASS')
