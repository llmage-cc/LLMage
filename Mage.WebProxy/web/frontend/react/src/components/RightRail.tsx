import { useState, useRef, useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectBoardState } from '@/store/selectors'
import { chatFromLabel } from '@/utils/chatLabels'
import { replayHasGameLog, replayLLMHistoryFor, type ReplayLLMFrame } from '@/store/slices/replaySlice'
import { filterPostLossNoise, logLineClass } from '@/utils/endGame'
import type { LLMDebugEntry } from '@/types'

/** The rail's tab identifiers. */
export type RightTab = 'log' | 'chat' | 'llm'

/**
 * Tabs the rail OFFERS for a given board.
 *
 * `log` is offered for live games and — since the flat game-log capture
 * — for replays that actually
 * carry log data. A REPLAY frame reports its log via `gameLog`/`gameLogCount`;
 * a save written before the capture work carries neither, and offering the tab
 * there would be a panel that can never fill

 *
 * EXACT LLM RULE:
 *  - LIVE board: `llm` is offered only when `serverSupportsLlm` AND
 *    `hasLlmPlayers` — the root-level, UNFILTERED flag the serializer emits
 *    (true iff the game has at least one LLM player seat). Never gate on
 *    `llmDebug.length`: that list is per-viewer FILTERED (an opponent in a live
 *    game with private LLM chat sees it empty while LLM players ARE in the
 *    game), so gating on it would hide the tab for exactly the games that have
 *    an LLM.
 *  - REPLAY board: unchanged from before this flag existed — `llm` keeps its
 *    pre-existing unconditional offer (subject to `serverSupportsLlm`). The
 *    frame's own `llmDebug` drives the PANEL content, not the tab offer: a
 *    replay's LLM data is attached per state index (ReplaySession), so early
 *    frames of an LLM game carry none, and gating the tab on it would flicker
 *    the tab bar while scrubbing. `hasLlmPlayers` is deliberately NOT consulted
 *    here for the same reason (it is per-frame on replay states).
 *
 * `hasReplayLog` defaults to false and `hasLlmPlayers` to false so the
 * conservative answer (hide) is what a caller with no frame data gets.
 */
export function visibleRightTabs(
  inReplay: boolean,
  hasReplayLog = false,
  serverSupportsLlm = true,
  hasLlmPlayers = false,
): RightTab[] {
  // this fork: the LLM tab exists only when the connected server runs
  // LLM players (capabilities.llm). Stock server → log+chat only.
  if (!serverSupportsLlm) return ['log', 'chat']
  if (!inReplay) return hasLlmPlayers ? ['log', 'chat', 'llm'] : ['log', 'chat']
  return hasReplayLog ? ['log', 'chat', 'llm'] : ['chat', 'llm']
}

/**
 * The tab a rail actually shows, given the user's stored choice.
 *
 * If the stored choice names a tab this board does not offer, the rail falls back
 * to the first OFFERED tab — never to an empty panel area. This is what keeps a
 * stored `llm` choice valid when the game has no LLM player (it falls back to
 * `log`/`chat`) and what brings it back verbatim once LLM players appear.
 *
 * Exported and pure so the behavioural harness can assert the mapping without a
 * DOM (same pattern as selectCurrentView / isEmptySelectWindow).
 */
export function effectiveRightTab(
  stored: RightTab,
  inReplay: boolean,
  hasReplayLog = false,
  serverSupportsLlm = true,
  hasLlmPlayers = false,
): RightTab {
  const offered = visibleRightTabs(inReplay, hasReplayLog, serverSupportsLlm, hasLlmPlayers)
  return offered.includes(stored) ? stored : offered[0]!
}

/**
 * The LLM tab's empty-state copy, accurate per board and mode

 *
 * The pre-fix wording was a single literal — "No LLM players in this game." —
 * which is only ever true for a LIVE board with no LLM seat. In a replay of an
 * LLM game a frame can carry no exchange while the game DOES have LLM players,
 * and claiming otherwise is actively misleading ("when there is no text
 * shown... it doesn't show anything"). `replayHasLlmPlayers` is the frame-
 * INDEPENDENT flag ReplaySession sends on every replay frame, so:
 *  - live board, or a replay whose game genuinely has no LLM seat (flag false)
 *    → the original no-LLM wording;
 *  - replay with LLM seats (flag true), full history ON → "up to this frame"
 *    (the cursor may simply be before the first exchange);
 *  - replay with LLM seats, single-frame mode → "at this frame".
 *  - flag ABSENT (proxy older than the field) → the exchange wording, never
 *    the live-only claim.
 *
 * LIVE WITH SEATS (bug-hunt #6): on a LIVE board the LLM tab is only ever
 * offered when the root `hasLlmPlayers` flag is true, yet the panel's
 * `llmDebug` list is filtered PER VIEWER — a spectator (or an opponent when the
 * table isn't llmChatPublic) sees an EMPTY list under a tab that exists
 * precisely because LLM seats DO. Claiming "No LLM players in this game." there
 * is false. `hasLlmPlayersLive` carries that root flag: when it is true the
 * empty copy says the exchanges are not visible to this viewer instead.
 * Defaults false so existing callers (and the pinned f1 assertion) keep the
 * original wording.
 *
 * Exported and pure so the harness asserts the same strings the component
 * renders (same pattern as splitAssistantMessage / visibleRightTabs).
 */
export function emptyLlmCopyFor(
  inReplay: boolean,
  replayHasLlmPlayers: boolean | undefined,
  fullHistory: boolean,
  hasLlmPlayersLive = false,
): string {
  if (!inReplay) {
    return hasLlmPlayersLive
      ? 'No LLM exchanges are visible to you in this game yet.'
      : 'No LLM players in this game.'
  }
  if (replayHasLlmPlayers === false) {
    return 'No LLM players in this game.'
  }
  return fullHistory
    ? 'No LLM exchanges recorded up to this frame.'
    : 'No LLM exchanges recorded at this frame.'
}

/**
 * Right rail — ported from index.html game-right panel.
 * Contains three tabs: Game Log, Chat, and LLM Debug.
 *
 * Reads the board state through selectBoardState so the LLM tab shows the data of
 * whatever board is on screen: the live game's llmDebug normally, the current
 * REPLAY frame's llmDebug while the replay viewer is open (ReplaySession attaches
 * the LLM conversation for each replay state index, and the serializer forwards it).
 */
export function RightRail() {
  const storedTab = useGameStore((s) => s.activeRightTab)
  const setActiveTab = useGameStore((s) => s.setActiveRightTab)
  const inReplay = useGameStore((s) => s.inReplay)
  const replayLogSlice = useGameStore((s) => s.replayLogSlice)
  // The Game Log tab renders the LIVE channel's eventLog during a game and the
  // current replay frame's slice while a replay is open. The replay slice is
  // empty for saves written before the flat log capture, so the tab is offered
  // there only when it has something to show — a hidden tab must never stay the
  // active one, and `activeTab` is DERIVED (not rewritten in the store) so the
  // user's own tab choice survives the replay.
  //
  // The gate is "does this replay CARRY a log", not "does THIS frame have
  // entries": every frame carries the same full log once (flat capture), and
  // frame 0's slice is legitimately 0 entries — gating on the slice would hide
  // the tab at frame 0 and pop it in at frame 1, flickering the tab bar.
  const replayState = useGameStore((s) => s.replayState)
  const hasReplayLog = inReplay && replayHasGameLog(replayState)
  const serverSupportsLlm = useGameStore((s) => s.capabilities.llm)
  const gameState = useGameStore(selectBoardState)
  // The UNFILTERED root-level flag the serializer emits (true iff the game has
  // at least one LLM seat) — the LIVE LLM-tab gate. NOT `llmDebug.length`:
  // that list is per-viewer FILTERED, so an opponent in a live game with
  // private LLM chat sees it empty even though LLM players ARE in the game
  // Absent on older
  // proxies → undefined → conservative hide. Ignored for replay boards, whose
  // llm offer is unchanged (`visibleRightTabs` documents the exact rule).
  const hasLlmPlayers = gameState?.hasLlmPlayers === true
  const offeredTabs = visibleRightTabs(inReplay, hasReplayLog, serverSupportsLlm, hasLlmPlayers)
  const activeTab = effectiveRightTab(storedTab, inReplay, hasReplayLog, serverSupportsLlm, hasLlmPlayers)
  const eventLog = useGameStore((s) => s.eventLog)
  const chatMessages = useGameStore((s) => s.chatMessages)
  const sendChat = useGameStore((s) => s.sendChat)
  const activeLLMPlayerIdx = useGameStore((s) => s.activeLLMPlayerIdx)
  const setActiveLLMPlayerIdx = useGameStore((s) => s.setActiveLLMPlayerIdx)
  const autoScrollLog = useGameStore((s) => s.autoScrollLog)
  const setAutoScrollLog = useGameStore((s) => s.setAutoScrollLog)
  const autoScrollChat = useGameStore((s) => s.autoScrollChat)
  const setAutoScrollChat = useGameStore((s) => s.setAutoScrollChat)
  const autoScrollLLM = useGameStore((s) => s.autoScrollLLM)
  const setAutoScrollLLM = useGameStore((s) => s.setAutoScrollLLM)

  const [chatInput, setChatInput] = useState('')
  const logEndRef = useRef<HTMLDivElement>(null)
  const chatEndRef = useRef<HTMLDivElement>(null)
  const llmEndRef = useRef<HTMLDivElement>(null)

  const llmPlayers = gameState?.llmDebug ?? []
  // REPLAY CUMULATIVE HISTORY: a
  // replay frame carries only ITS OWN state-index bucket — typically 1–2
  // exchanges, one per LLM seat that acted — and frames between decisions carry
  // none, so the raw per-frame panel is blank on empty phases and misleadingly
  // reports "No LLM players in this game". With `replayLLMFullHistory` ON
  // the panel renders the conversation accumulated up to the
  // CURRENT frame: all retained buckets with stateIndex <= replayStateIndex.
  // Scrubbing backward drops the later exchanges; forward re-adds them; an
  // empty frame keeps showing the last known history. The raw per-frame list is
  // only rendered for LIVE boards (untouched) and for replays when the toggle
  // is OFF (single-frame behavior preserved).
  const replayStateIndex = useGameStore((s) => s.replayStateIndex)
  const replayLLMFrames = useGameStore((s) => s.replayLLMFrames)
  const replayLLMFullHistory = useGameStore((s) => s.replayLLMFullHistory)
  const setReplayLLMFullHistory = useGameStore((s) => s.setReplayLLMFullHistory)
  const llmView: LLMDebugEntry[] =
    inReplay && replayLLMFullHistory
      ? replayLLMHistoryFor(replayLLMFrames as ReplayLLMFrame[], replayStateIndex)
      : llmPlayers

  // Total message count across all LLM players — used as a scroll trigger.
  const llmMessageCount = (gameState?.llmDebug ?? []).reduce(
    (sum, l) => sum + (l.messages?.length ?? 0),
    0,
  )
  // Cumulative view's message count — the auto-scroll trigger while a replay's
  // history grows (llmView can be much longer than the frame's own bucket; the
  // live path's llmMessageCount is unchanged).
  const llmViewMessageCount = llmView.reduce((sum, l) => sum + (l.messages?.length ?? 0), 0)
  // Total streaming content length — triggers scroll while streaming.
  const llmStreamingChars = (gameState?.llmDebug ?? []).reduce(
    (sum, l) =>
      sum +
      (l.streaming ? ((l.streamingContent ?? '') + (l.streamingReasoning ?? '')).length : 0),
    0,
  )

  // Auto-scroll game log to bottom when new events arrive — or when a replay
  // frame changes the log length (stepping forward grows the slice, stepping
  // back shrinks it).
  useEffect(() => {
    if (autoScrollLog && logEndRef.current) {
      logEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [eventLog.length, replayLogSlice.length, autoScrollLog])

  // Auto-scroll chat to bottom when new messages arrive
  useEffect(() => {
    if (autoScrollChat && chatEndRef.current) {
      chatEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [chatMessages.length, autoScrollChat])

  // Auto-scroll LLM debug content when messages or streaming content changes
  useEffect(() => {
    if (autoScrollLLM && llmEndRef.current) {
      llmEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [llmMessageCount, llmViewMessageCount, llmStreamingChars, autoScrollLLM])

  // The history toggle. Rendered in EVERY replay panel state — empty AND
  // non-empty: on a frame with no bucket it is the only control that can bring
  // the history back, and hiding it inside the non-empty branch made the mode
  // unreachable exactly when it was needed. The toggle's home is the Settings
  // dialog; this in-panel checkbox mirrors it so the choice is also one click
  // away exactly where the effect is visible (same store field).
  const replayHistoryToggle = inReplay ? (
    <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '0.15rem 0.4rem', borderBottom: '1px solid var(--border-subtle)' }}>
      <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', cursor: 'pointer', fontSize: '0.7rem', color: 'var(--text-muted)' }}>
        <input
          type="checkbox"
          checked={replayLLMFullHistory}
          onChange={(e) => setReplayLLMFullHistory(e.target.checked)}
          style={{ cursor: 'pointer' }}
          data-explain="replay-llm-history-toggle"
        />
        Show entire history
      </label>
    </div>
  ) : null

  // Empty-state copy, accurate per mode — pure rule in emptyLlmCopyFor,
  // harness-tested.
  const emptyLlmCopy = emptyLlmCopyFor(inReplay, replayState?.replayHasLlmPlayers, replayLLMFullHistory, hasLlmPlayers)

  return (
    <>
      {/* Tab bar */}
      <div className="right-tabs">
        {/* The Game Log tab is offered for live games and for replays that
            carry log data — see visibleRightTabs. */}
        {offeredTabs.includes('log') && (
          <button
            className={`right-tab${activeTab === 'log' ? ' active' : ''}`}
            onClick={() => setActiveTab('log')}
            data-explain="rail-tab-log"
          >
            Game Log
          </button>
        )}
        <button
          className={`right-tab${activeTab === 'chat' ? ' active' : ''}`}
          onClick={() => setActiveTab('chat')}
          data-explain="rail-tab-chat"
        >
          Chat
        </button>
        {/* The LLM tab is offered only when the connected server runs LLM
            players AND the game actually has one — see visibleRightTabs for the
            exact live/replay rule. `__LLMAGE__` is the build-time stock toggle
            (a stock bundle must not carry the control at all). */}
        {__LLMAGE__ && offeredTabs.includes('llm') && (
          <button
            className={`right-tab${activeTab === 'llm' ? ' active' : ''}`}
            onClick={() => setActiveTab('llm')}
            data-explain="rail-tab-llm"
          >
            LLM
          </button>
        )}
      </div>

      {/* Game Log tab */}
      {activeTab === 'log' && (
        <div className="tab-panel active">
          <div className="game-log-header" style={{ display: 'flex', justifyContent: 'flex-end', padding: '0.2rem 0.4rem', borderBottom: '1px solid var(--border-subtle)' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', cursor: 'pointer', fontSize: '0.7rem', color: 'var(--text-muted)' }}>
              <input
                type="checkbox"
                checked={autoScrollLog}
                onChange={(e) => setAutoScrollLog(e.target.checked)}
                style={{ cursor: 'pointer' }}
              />
              Auto-scroll
            </label>
          </div>
          <div className="game-log-list">
            {/* During a replay the panel renders the CURRENT FRAME's slice of the
                saved game's log (flat capture; entries 0..count-1). A line is a
                plain string — the saved log carries the server's own text, with
                HTML stripped on the way out (stripServerHtml), same as events. */}
            {inReplay
              ? replayLogSlice.map((message, i) => (
                  <div key={i} className={logLineClass('action', message)}>
                    {message}
                  </div>
                ))
              : filterPostLossNoise(eventLog).map((event) => (
                  <div key={event.seq} className={logLineClass(event.type, event.message)}>
                    {event.message}
                  </div>
                ))}
            <div ref={logEndRef} />
          </div>
        </div>
      )}

      {/* Chat tab */}
      {activeTab === 'chat' && (
        <div className="tab-panel active">
          <div className="chat-panel">
            <div className="chat-header" style={{ display: 'flex', justifyContent: 'flex-end', padding: '0.2rem 0.4rem', borderBottom: '1px solid var(--border-subtle)' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', cursor: 'pointer', fontSize: '0.7rem', color: 'var(--text-muted)' }}>
                <input
                  type="checkbox"
                  checked={autoScrollChat}
                  onChange={(e) => setAutoScrollChat(e.target.checked)}
                  style={{ cursor: 'pointer' }}
                />
                Auto-scroll
              </label>
            </div>
            <div className="game-log-list">
              {chatMessages.map((event, i) => (
                <div key={i} className={`chat-entry chat-${event.chatType ?? event.type}`}>
                  {/* The desktop's whisper direction labels ("Whisper from
                      <name>" / "Whisper to <name>" — ChatPanelBasic.java:245-259).
                      Shared helper with the lobby's Talk tab (utils/chatLabels). */}
                  <span style={{ fontWeight: 600, color: 'var(--accent-primary)' }}>
                    {chatFromLabel(event)}:
                  </span>{' '}
                  {event.message}
                </div>
              ))}
              <div ref={chatEndRef} />
            </div>
            <div className="chat-input-row">
              <input
                type="text"
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && chatInput.trim()) {
                    sendChat(chatInput.trim())
                    setChatInput('')
                  }
                }}
                placeholder="Send a message..."
                style={{ flex: 1 }}
              />
              <button
                className="btn btn-primary btn-sm"
                onClick={() => {
                  if (chatInput.trim()) {
                    sendChat(chatInput.trim())
                    setChatInput('')
                  }
                }}
                data-explain="rail-send-chat"
              >
                Send
              </button>
            </div>
          </div>
        </div>
      )}

      {/* LLM Debug tab */}
      {activeTab === 'llm' && (
        <div className="tab-panel active">
          {/* The history toggle renders in EVERY replay state (bug 1): on a frame
              with no bucket the panel below is empty, and this row is the only
              way back to the accumulated history. */}
          {replayHistoryToggle}
          {llmView.length === 0 ? (
            <div
              style={{
                padding: '2rem 1rem',
                color: 'var(--text-muted)',
                textAlign: 'center',
                fontSize: '0.85rem',
              }}
            >
              {emptyLlmCopy}
            </div>
          ) : (
            <>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.2rem 0.4rem', borderBottom: '1px solid var(--border-subtle)' }}>
                {/* Sub-tabs list the players actually RENDERED (llmView), not the
                    frame's own bucket: with cumulative history the two differ on
                    every frame that carries no exchange of its own, and the old
                    `llmPlayers` source left the row blank while messages were
                    on screen. Indexing matches the LLMDebugPanel loop below. */}
                <div className="llm-sub-tabs" style={{ flex: 1 }}>
                  {llmView.map((llm, idx) => (
                    <button
                      key={idx}
                      className={`llm-sub-tab${activeLLMPlayerIdx === idx ? ' active' : ''}`}
                      onClick={() => setActiveLLMPlayerIdx(idx)}
                    >
                      {llm.playerName || `LLM ${idx + 1}`}
                    </button>
                  ))}
                </div>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', cursor: 'pointer', fontSize: '0.7rem', color: 'var(--text-muted)', marginLeft: '0.4rem', flexShrink: 0 }}>
                  <input
                    type="checkbox"
                    checked={autoScrollLLM}
                    onChange={(e) => setAutoScrollLLM(e.target.checked)}
                    style={{ cursor: 'pointer' }}
                  />
                  Auto-scroll
                </label>
              </div>
              <div className="llm-content">
                {llmView.map((llm, idx) => (
                  <LLMDebugPanel
                    key={idx}
                    llm={llm}
                    visible={activeLLMPlayerIdx === idx}
                  />
                ))}
                <div ref={llmEndRef} />
              </div>
              {/* Live 'LLM Thinking' docked at the bottom of the chat column —
                  outside the scrollable list so it stays visible with auto-scroll on. */}
              {llmPlayers.map((llm, idx) => (
                <LLMStreamingBox key={`stream-${idx}`} llm={llm} visible={activeLLMPlayerIdx === idx} />
              ))}
            </>
          )}
        </div>
      )}
    </>
  )
}

/** Collapsible section — a <details> wrapper with consistent styling. */
function CollapsibleSection({ title, children, defaultOpen = false, storageKey }: {
  title: string
  children: React.ReactNode
  defaultOpen?: boolean
  /**
   * When set, the open/closed state persists across re-renders AND replay
   * frame changes (the <details> is then controlled). Reset only when the
   * viewer leaves the game/replay (the store field is per session).
   */
  storageKey?: string
}) {
  const collapsedMap = useGameStore((s) => s.llmCollapsedBlocks)
  const toggle = useGameStore((s) => s.toggleLlmBlockCollapsed)
  // storageKey present => controlled by the store (persist across frames);
  // absent => uncontrolled <details> with defaultOpen (legacy callers).
  const open = storageKey != null ? !collapsedMap[storageKey] : undefined
  return (
    <details
      open={storageKey != null ? open : defaultOpen}
      onToggle={storageKey != null ? (e) => {
        // Keep the store in sync with the user's disclosure toggles.
        const isOpen = (e.target as HTMLDetailsElement).open
        if (isOpen === collapsedMap[storageKey]) toggle(storageKey)
      } : undefined}
      style={{ marginBottom: '0.4rem' }}
    >
      <summary style={{
        fontSize: '0.7rem',
        fontWeight: 700,
        cursor: 'pointer',
        color: 'var(--accent-primary)',
        padding: '0.2rem 0',
        userSelect: 'none',
      }}>
        {title}
      </summary>
      <div style={{ padding: '0.3rem 0', whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.4 }}>
        {children}
      </div>
    </details>
  )
}

/** Opening and closing literal tokens of the assistant reasoning fence. */
const REASONING_FENCE_OPEN = '[model_reasoning]'
const REASONING_FENCE_CLOSE = '[/model_reasoning]'

/**
 * Split an assistant message into its visible answer and its reasoning text.
 *
 * The stored assistant text has TWO shapes in the wild and both must render:
 * NEW: a bare answer, then
 *    `[model_reasoning]`, the reasoning, then `[/model_reasoning]` — the
 *    reasoning block is the content BETWEEN the tokens. A missing closing fence
 *    (truncated write, or a message caught mid-stream) keeps the rest of the
 *    text as reasoning rather than dropping it.
 *  - LEGACY (old saves / old replays): `Decision: N` + a line starting with
 *    `Reasoning:` — behavior unchanged from before this helper existed.
 *
 * Non-assistant messages (user prompts, summaries) are never split.
 * Exported and pure so the behavioural harness asserts the same split the
 * component renders (same pattern as visibleRightTabs / effectiveRightTab).
 */
export function splitAssistantMessage(
  content: string,
  role: string,
): { decision: string; reasoning: string } {
  if (role !== 'assistant') return { decision: content, reasoning: '' }

  // New fence format takes precedence: only messages produced from the new
  // prompts carry the literal token, so its presence identifies the shape.
  const openIdx = content.indexOf(REASONING_FENCE_OPEN)
  if (openIdx >= 0) {
    const decision = content.slice(0, openIdx).trimEnd()
    const afterOpen = content.slice(openIdx + REASONING_FENCE_OPEN.length)
    const closeIdx = afterOpen.indexOf(REASONING_FENCE_CLOSE)
    // Missing closing fence => the rest of the text is reasoning (graceful).
    const reasoning = (closeIdx >= 0 ? afterOpen.slice(0, closeIdx) : afterOpen).trim()
    return { decision, reasoning }
  }

  // Legacy format (unchanged): case-insensitive match on a line starting with
  // "Reasoning:"; everything before is the visible decision/answer.
  const reasoningIdx = content.search(/^Reasoning:/im)
  if (reasoningIdx >= 0) {
    return {
      decision: content.slice(0, reasoningIdx).trimEnd(),
      reasoning: content.slice(reasoningIdx),
    }
  }
  return { decision: content, reasoning: '' }
}

/** LLM debug panel — shows model info, streaming status, conversation messages with
 *  per-exchange metadata, deck assessment, and post-game reflection.
 *
 * Every text block is collapsible: the system prompt, each
 *  conversation message (user / assistant / assistant_thinking), and the
 *  pre-game / post-game sections. Collapse state keys off
 *  `<playerName>:<kind>:<idx>` in the store's llmCollapsedBlocks so it
 *  survives replay-frame re-renders and per-player sub-tab switches, and is
 *  session-scoped (a collapsed block must not stay collapsed into the next
 *  game by default). */
function LLMDebugPanel({ llm, visible }: { llm: LLMDebugEntry; visible: boolean }) {
  if (!visible) return null

  const contextPercent = llm.modelContextWindow > 0
    ? Math.round((llm.totalContextChars / (llm.modelContextWindow * 2)) * 100)
    : 0

  const pfx = llm.playerName || 'llm'

  return (
    <div>
      <div style={{ marginBottom: '0.4rem', color: 'var(--text-muted)', fontSize: '0.65rem' }}>
        {llm.modelName} | Messages: {llm.messages.length}
        {llm.totalContextChars > 0 && (
          <> | Context: {llm.totalContextChars} chars
          {llm.modelContextWindow > 0 && (
            <> / {llm.modelContextWindow} tokens ({contextPercent}%)</>
          )}</>
        )}
      </div>

      {/* Pre-game deck assessment / gameplan */}
      {llm.gamePlan && (
        <CollapsibleSection title="📋 Deck Assessment" storageKey={`${pfx}:section:gameplan`}>
          {llm.gamePlan}
        </CollapsibleSection>
      )}

      {/* Match review for sideboarding */}
      {llm.matchReview && (
        <CollapsibleSection title="🔧 Match Review (for sideboarding)" storageKey={`${pfx}:section:matchreview`}>
          {llm.matchReview}
        </CollapsibleSection>
      )}

      {/* Conversation messages with per-exchange metadata headers. Every block
          is collapsible — the system prompt and long user prompts are exactly
          the blocks a replay viewer wants out of the way. */}
      {(llm.messages || []).map((msg, mIdx) => {
        // Assistant messages carry their reasoning in EITHER a [model_reasoning]
        // fence (new saves) or the legacy "Reasoning:" shape
        // (older saves/replays) — both split into the collapsible block.
        const { decision, reasoning } = splitAssistantMessage(msg.content || '', msg.role)
        const roleLabel = msg.role || '?'
        const blockKey = `${pfx}:msg:${mIdx}`
        // One-line preview for the collapsed summary: first non-empty line,
        // capped — long enough to orient, short enough to stay a summary.
        const preview = (msg.content || '').split('\n').find((l) => l.trim().length > 0)?.slice(0, 80) ?? ''
        const sectionTitle = `${roleLabel}${preview ? ` — ${preview}${(msg.content || '').length > 80 ? '…' : ''}` : ''}`

        return (
          <CollapsibleSection key={mIdx} title={sectionTitle} storageKey={blockKey}>
            <div className={`llm-message llm-${msg.role || 'unknown'}`}>
              {/* Metadata header for user messages that have prompt context */}
              {msg.role === 'user' && (msg.promptType || msg.turn != null || msg.phaseStep) && (
                <div style={{ fontSize: '0.6rem', color: 'var(--text-muted)', marginBottom: '0.15rem', fontStyle: 'italic' }}>
                  {[
                    msg.promptType,
                    msg.turn != null ? `T${msg.turn}` : null,
                    msg.phaseStep,
                  ].filter(Boolean).join(' · ')}
                </div>
              )}
              <div className="llm-content">
                {decision}
                {reasoning && (
                  <CollapsibleSection title="💭 Reasoning" storageKey={`${blockKey}:reasoning`}>
                    {reasoning}
                  </CollapsibleSection>
                )}
              </div>
            </div>
          </CollapsibleSection>
        )
      })}

      {/* Post-game reflection */}
      {llm.postGameReflection && (
        <CollapsibleSection title="🪞 Post-Game Reflection" storageKey={`${pfx}:section:reflection`}>
          {llm.postGameReflection}
        </CollapsibleSection>
      )}
    </div>
  )
}

/** Live 'LLM Thinking' stream box — rendered OUTSIDE the scrollable message
 *  list so it stays docked at the bottom of the chat column and remains
 * visible while auto-scroll keeps the log pinned to the bottom. */
function LLMStreamingBox({ llm, visible }: { llm: LLMDebugEntry; visible: boolean }) {
  if (!visible) return null
  // Compute streaming elapsed time from streamingStartedAt (epoch millis)
  const streamingElapsed = llm.streaming && llm.streamingStartedAt
    ? ((Date.now() - llm.streamingStartedAt) / 1000).toFixed(1)
    : null

  if (!llm.streaming) return null

  return (
    <div className="llm-streaming">
      <span className="llm-pulse">●</span> LLM thinking...
      {streamingElapsed && (
        <span style={{ marginLeft: '0.4rem', color: 'var(--text-muted)' }}>
          {streamingElapsed}s
        </span>
      )}
      {llm.streamingReasoning && (
        <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', marginTop: '0.2rem', whiteSpace: 'pre-wrap', maxHeight: '80px', overflowY: 'auto' }}>
          {llm.streamingReasoning}
        </div>
      )}
      {llm.streamingContent && (
        <div style={{ fontSize: '0.75rem', color: 'var(--accent-primary)', marginTop: '0.2rem', whiteSpace: 'pre-wrap' }}>
          {llm.streamingContent}
        </div>
      )}
    </div>
  )
}