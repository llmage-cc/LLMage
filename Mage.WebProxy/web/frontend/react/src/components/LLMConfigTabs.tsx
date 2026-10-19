import { useId, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { LLM_CONFIG_SECTIONS, DEFAULT_LLM_ENDPOINT, NO_SEAT_ENDPOINT_MESSAGE } from '@/utils/constants'
import type { LLMConfigField, LLMConfigSection } from '@/types'
import { LLMConfigFieldInput } from './LLMConfigFieldInput'
import { strictThinkingWarning, thinkingShapeFor } from '@/utils/thinkingShape'
import { relayKeyTransitWarning } from '@/utils/llmBridgeClient'

/**
 * LLM seat config, split into tabs exactly like the desktop client's
 * `LLMConfigDialog` JTabbedPane (Mage.Client `LLMConfigDialog.java`, Core /
 * Behavior / Personality / Conversation / Summarization).
 *
 * Replaces the flat one-column list the seat row used to render: the same fields,
 * in the same order, in the same section groups — one group visible at a time.
 * All five panels stay mounted (hidden, like JTabbedPane) so component-local
 * state survives a tab switch: the Provider dropdown's "Other…" mode and the
 * typed custom-URL draft live in `EndpointSelectInput` and would be lost if a
 * switch unmounted them.
 *
 * The tab bar is a WAI-ARIA tablist: `role=tablist` / `role=tab` with
 * `aria-selected` + `aria-controls`, `role=tabpanel` with `aria-labelledby`,
 * roving `tabIndex`, and Arrow/Home/End navigation (selection follows focus, the
 * same way the desktop JTabbedPane moves between tabs).
 *
 * Fail Fast (AGENTS.md): the tab names are canonical here, not derived from the
 * incoming sections. A section whose name this component does not know — or a
 * canonical tab with no section behind it — throws instead of dropping fields or
 * rendering an empty tab.
 */

/** Tab titles in desktop-client order. Canonical: the render validates against this. */
export const LLM_CONFIG_TABS = ['Core', 'Behavior', 'Personality', 'Conversation', 'Summarization'] as const
export type LLMConfigTabName = (typeof LLM_CONFIG_TABS)[number]

/** The desktop dialog opens on Core. */
export const DEFAULT_LLM_CONFIG_TAB: LLMConfigTabName = 'Core'

/**
 * Index of a tab name. Fail Fast: an unknown name is a programming error (a
 * renamed/typo'd section), never a silent fallback to the first tab.
 */
export function llmConfigTabIndex(name: string): number {
  const index = (LLM_CONFIG_TABS as readonly string[]).indexOf(name)
  if (index < 0) {
    throw new Error(`LLM config tabs: unknown section '${name}' — known tabs are ${LLM_CONFIG_TABS.join(', ')}`)
  }
  return index
}

/** Tab name at an index. Fail Fast: out-of-range is a programming error, never undefined. */
export function llmConfigTabAt(index: number): LLMConfigTabName {
  const name = LLM_CONFIG_TABS[index]
  if (name === undefined) throw new Error(`LLM config tabs: no tab at index ${index}`)
  return name
}

/**
 * The tab a key press moves to (automatic activation, as JTabbedPane does it).
 * Only the keys the tablist pattern owns move the selection; anything else
 * (Tab, Enter, Space, letters) returns the current tab so the click/keydown
 * handler can leave the event alone. Throws for an unknown current tab.
 */
export function tabAfterKey(current: string, key: string): LLMConfigTabName {
  const index = llmConfigTabIndex(current)
  const last = LLM_CONFIG_TABS.length - 1
  switch (key) {
    case 'ArrowRight':
    case 'ArrowDown':
      return llmConfigTabAt((index + 1) % LLM_CONFIG_TABS.length)
    case 'ArrowLeft':
    case 'ArrowUp':
      return llmConfigTabAt((index + last) % LLM_CONFIG_TABS.length)
    case 'Home':
      return llmConfigTabAt(0)
    case 'End':
      return llmConfigTabAt(last)
    default:
      return llmConfigTabAt(index)
  }
}

export interface LlmConfigTab {
  name: LLMConfigTabName
  section: LLMConfigSection
}

/**
 * Pair the canonical tab list with the field sections.
 *
 * Fail Fast in both directions: an unknown/duplicate section name throws, and a
 * canonical tab with no section behind it throws (a dropped section must not
 * degrade into an empty tab).
 */
export function buildLlmConfigTabs(sections: LLMConfigSection[]): LlmConfigTab[] {
  const byName = new Map<string, LLMConfigSection>()
  for (const section of sections) {
    llmConfigTabIndex(section.name) // unknown name -> throw
    if (byName.has(section.name)) {
      throw new Error(`LLM config tabs: duplicate section '${section.name}'`)
    }
    byName.set(section.name, section)
  }
  return LLM_CONFIG_TABS.map((name) => {
    const section = byName.get(name)
    if (!section) {
      throw new Error(`LLM config tabs: section '${name}' is missing from LLM_CONFIG_SECTIONS — every tab must have fields`)
    }
    return { name, section }
  })
}

export interface LlmConfigFieldChangeDeps {
  /** Per-model context windows recorded by the browser-direct model fetch. */
  modelContextWindows: Record<string, number>
  /** Proxy-relayed model fetch (server-key seats). */
  refreshModels: (endpoint: string, apiKey: string) => void
  /** Browser-direct model fetch — the key never enters the proxy. */
  refreshModelsBrowserDirect: (endpoint: string, apiKey: string) => void
  /**
   * Surface a user-facing message in the setup dialog (lobbySlice.setModelsError).
   * Used by {@link refreshModelsForSeat} for the one case it must refuse to dial:
   * a seat with no endpoint of its own.
   */
  reportModelsError: (message: string) => void
}

/**
 * Apply one field edit to the seat's llmConfig — the seat row's behaviour,
 * unchanged, factored out so the tab harness can drive it directly (same pattern
 * as `endpointSelectTransition` for the Provider dropdown).
 *
 * - model change: auto-fill `modelContextWindow` from the browser-direct fetch
 *   when that model's window is known (an explicit window is mandatory for
 *   client-key bridge seats, which the server cannot auto-detect);
 * - apiEndpoint change: refresh the model list — browser-direct when a key is
 *   present, proxy-relayed otherwise;
 * - anything else: plain set.
 */
export function applyLlmConfigFieldChange(
  config: Record<string, unknown>,
  field: LLMConfigField,
  value: unknown,
  deps: LlmConfigFieldChangeDeps
): Record<string, unknown> {
  let newConfig: Record<string, unknown>
  if (field.key === 'model' && typeof value === 'string' && value) {
    const window = deps.modelContextWindows[value]
    if (window !== undefined) {
      newConfig = { ...config, model: value, modelContextWindow: window }
    } else {
      newConfig = { ...config, model: value }
    }
  } else {
    newConfig = { ...config, [field.key]: value }
  }
  if (field.key === 'apiEndpoint' && typeof value === 'string' && value.trim()) {
    const key = (config.apiKey as string) || ''
    if (key) {
      deps.refreshModelsBrowserDirect(value.trim(), key)
    } else {
      deps.refreshModels(value.trim(), '')
    }
  }
  return newConfig
}

/**
 * Route a model-list refresh for the seat: browser-direct when the seat carries
 * a key (the key must never enter the proxy), proxy-relayed otherwise.
 *
 * Refuses to dial when the seat has NO endpoint of its own (the "(server default)"
 * placeholder): the seat then uses the SERVER's configured endpoint, which this
 * browser does not know. The old code substituted a fabricated localhost default
 * here, which the proxy's host allowlist rejected while naming the WRONG host
 * ('localhost' for an opencode.ai seat). Instead the seat row
 * says plainly that there is no endpoint to fetch from.
 */
export function refreshModelsForSeat(
  config: Record<string, unknown>,
  deps: Pick<LlmConfigFieldChangeDeps, 'refreshModels' | 'refreshModelsBrowserDirect' | 'reportModelsError'>
): void {
  const ep = ((config.apiEndpoint as string | undefined) ?? '').trim()
  if (!ep) {
    deps.reportModelsError(NO_SEAT_ENDPOINT_MESSAGE)
    return
  }
  const key = (config.apiKey as string) || ''
  if (key) {
    deps.refreshModelsBrowserDirect(ep, key)
  } else {
    deps.refreshModels(ep, '')
  }
}

/** DOM-id slug for a tab name — names are validated against LLM_CONFIG_TABS. */
function tabSlug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-')
}

/**
 * Pure helper: the setup-time thinking-shape warning for one field of one seat.
 * The warning shows NEXT TO the Thinking Enabled checkbox
 * for endpoints classified strict (non-Ollama, effort dialect → bare
 * reasoning_effort) while thinking is enabled. The PROXY's classification of a
 * fetched endpoint (thinkingEndpointClass, from the models_list payload) takes
 * precedence when present — it is the same rule the engine runs, evaluated
 * where fetch_models actually authenticated. No proxy verdict (endpoint never
 * fetched) falls back to the seat's own deterministic classification.
 *
 * Returns '' for every non-strict/non-thinking field — nothing renders.
 */
export function thinkingShapeWarningFor(
  config: Record<string, unknown>,
  fieldKey: string,
  proxyVerdict: boolean | undefined
): string {
  if (fieldKey !== 'thinkingEnabled') return ''
  const thinkingEnabled = Boolean(config.thinkingEnabled)
  if (!thinkingEnabled) return ''
  const endpoint = (config.apiEndpoint as string | undefined)?.trim() || DEFAULT_LLM_ENDPOINT
  // The proxy verdict is about the FETCHED endpoint; a verdict is only usable
  // when it was recorded for the same endpoint the seat points at.
  if (proxyVerdict !== undefined) {
    return proxyVerdict ? '' : strictThinkingWarning(endpoint, undefined, thinkingEnabled)
  }
  return strictThinkingWarning(endpoint, undefined, thinkingEnabled)
}

/** The seat's thinking shape for the CURRENT config (used by the seat row and harnesses). */
export function seatThinkingShape(config: Record<string, unknown>): 'object' | 'effort' | 'off' {
  const endpoint = (config.apiEndpoint as string | undefined)?.trim() || DEFAULT_LLM_ENDPOINT
  return thinkingShapeFor(endpoint, undefined, Boolean(config.thinkingEnabled))
}

/**
 * Pure helper: the relay-transit warning for the seat's selected endpoint.
 * Shown UNDER the Provider dropdown when the endpoint is relay-routed: the
 * provider has no CORS support, so its calls — Authorization header included —
 * transit this deployment's relay instead of going browser→provider directly.
 * Derived from `isRelayHost`, the SAME rule the routing uses, so the note can
 * never disagree with where the key actually goes. Empty = browser-direct
 * (or no endpoint selected), nothing renders.
 */
export function relayWarningFor(config: Record<string, unknown>): string {
  return relayKeyTransitWarning((config.apiEndpoint as string | undefined)?.trim() || '')
}

/**
 * The setup-time warning for one field of one seat, or undefined. Two sources:
 * the relay-transit note under the Provider dropdown, and the thinking-shape
 * probe next to Thinking Enabled. Distinct data-testids so both can coexist.
 */
export function setupWarningFor(
  config: Record<string, unknown>,
  fieldKey: string,
  proxyVerdict: boolean | undefined
): { message: string; testId: string } | undefined {
  if (fieldKey === 'apiEndpoint') {
    const message = relayWarningFor(config)
    return message ? { message, testId: 'relay-key-transit-warning' } : undefined
  }
  const message = thinkingShapeWarningFor(config, fieldKey, proxyVerdict)
  return message ? { message, testId: 'thinking-shape-warning' } : undefined
}

/**
 * The seat's LLM config block: tab bar + the five field panels.
 *
 * `config` is the seat's `llmConfig` record; `onConfigChange` receives the whole
 * record after each edit (the caller stores it on the slot), exactly as the flat
 * list did.
 */
export function LLMConfigTabs({
  config,
  onConfigChange,
  defaultTab = DEFAULT_LLM_CONFIG_TAB,
}: {
  config: Record<string, unknown>
  onConfigChange: (config: Record<string, unknown>) => void
  /**
   * Tab shown first. Validated (unknown name throws) — the tab harness renders
   * each tab through it to prove every panel is reachable.
   */
  defaultTab?: string
}) {
  const availableModels = useGameStore((s) => s.availableModels)
  const modelsLoading = useGameStore((s) => s.modelsLoading)
  const refreshModels = useGameStore((s) => s.refreshModels)
  const refreshModelsBrowserDirect = useGameStore((s) => s.refreshModelsBrowserDirect)
  const setModelsError = useGameStore((s) => s.setModelsError)
  const modelContextWindows = useGameStore((s) => s.modelContextWindows)
  const availablePersonalities = useGameStore((s) => s.availablePersonalities)
  const thinkingEndpointClass = useGameStore((s) => s.thinkingEndpointClass)

  const tabs = buildLlmConfigTabs(LLM_CONFIG_SECTIONS)
  const idPrefix = useId()
  const [activeTab, setActiveTab] = useState<string>(() => llmConfigTabAt(llmConfigTabIndex(defaultTab)))
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  const onRefreshModels = () => refreshModelsForSeat(config, {
    refreshModels,
    refreshModelsBrowserDirect,
    reportModelsError: setModelsError,
  })

  const activate = (name: LLMConfigTabName) => {
    setActiveTab(name)
    tabRefs.current[llmConfigTabIndex(name)]?.focus()
  }

  return (
    <div className="llm-config-section">
      <div className="llm-config-tabs" role="tablist" aria-label="LLM configuration sections">
        {tabs.map(({ name }, idx) => (
          <button
            key={name}
            type="button"
            role="tab"
            id={`${idPrefix}llm-tab-${tabSlug(name)}`}
            aria-selected={name === activeTab}
            aria-controls={`${idPrefix}llm-panel-${tabSlug(name)}`}
            tabIndex={name === activeTab ? 0 : -1}
            ref={(el) => {
              tabRefs.current[idx] = el
            }}
            className="llm-config-tab"
            data-explain="llm-config-tab"
            onClick={() => setActiveTab(name)}
            onKeyDown={(e) => {
              const next = tabAfterKey(activeTab, e.key)
              if (next === activeTab) return
              e.preventDefault()
              activate(next)
            }}
          >
            {name}
          </button>
        ))}
      </div>

      {tabs.map(({ name, section }) => (
        <div
          key={name}
          role="tabpanel"
          id={`${idPrefix}llm-panel-${tabSlug(name)}`}
          aria-labelledby={`${idPrefix}llm-tab-${tabSlug(name)}`}
          hidden={name !== activeTab}
          className="llm-config-tabpanel"
        >
          {section.fields.map((field) => {
            const warn = setupWarningFor(
              config,
              field.key,
              thinkingEndpointClass[(config.apiEndpoint as string | undefined)?.trim() || DEFAULT_LLM_ENDPOINT]
            )
            return (
              <LLMConfigFieldInput
                key={field.key}
                field={field}
                value={config[field.key]}
                availableModels={availableModels}
                modelsLoading={modelsLoading}
                availablePersonalities={availablePersonalities}
                warning={warn?.message}
                warningTestId={warn?.testId}
                onChange={(v) =>
                  onConfigChange(
                    applyLlmConfigFieldChange(config, field, v, {
                      modelContextWindows,
                      refreshModels,
                      refreshModelsBrowserDirect,
                      reportModelsError: setModelsError,
                    })
                  )
                }
                onRefreshModels={onRefreshModels}
              />
            )
          })}
        </div>
      ))}
    </div>
  )
}
