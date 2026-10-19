import { useState } from 'react'
import type { LLMConfigField } from '@/types'
import { LLM_PROVIDERS, PROVIDER_OTHER, endpointSelectValue } from '@/utils/constants'

/**
 * Pure transition for the Provider dropdown: what the stored endpoint and the
 * "Other…" mode become when the user picks `selected` in the select.
 *
 * - a provider id  -> that provider's endpoint, free-text box hidden
 * - ''             -> server default (empty endpoint), free-text box hidden
 * - PROVIDER_OTHER -> free-text mode; keeps the current URL when it is already
 *                     custom, else restores `customDraft` (the last URL the
 *                     user typed), so provider <-> Other flips don't lose it
 *
 * Fail Fast: an unknown or disabled option id is a caller bug (the DOM cannot
 * produce one — disabled options aren't selectable) and throws.
 */
export function endpointSelectTransition(
  selected: string,
  currentValue: unknown,
  customDraft: string
): { endpoint: string; otherMode: boolean } {
  if (selected === PROVIDER_OTHER) {
    const alreadyCustom = endpointSelectValue(currentValue) === PROVIDER_OTHER
    const kept = alreadyCustom && typeof currentValue === 'string' ? currentValue : customDraft
    return { endpoint: kept, otherMode: true }
  }
  if (selected === '') return { endpoint: '', otherMode: false }
  const provider = LLM_PROVIDERS.find((p) => p.id === selected)
  if (!provider || provider.disabledReason !== undefined) {
    throw new Error(`endpointSelectTransition: unknown or disabled provider option '${selected}'`)
  }
  return { endpoint: provider.endpoint, otherMode: false }
}

/**
 * Provider dropdown for the seat's API endpoint — the LLM seat config's
 * "Provider" field.
 *
 * The stored value stays the raw chat-completions URL ('' = server default), so
 * everything downstream (create_table, model fetch, relay routing) is
 * unchanged. The dropdown is a view over that string: a registered provider is
 * preselected when its endpoint matches, any other URL shows as "Other…" with
 * the free-text box. Picking a provider auto-fills its endpoint; picking
 * "Other…" restores the last custom URL for editing.
 *
 * `otherMode` is component-local: it keeps "Other…" selected while the field is
 * still empty (before the user types). A remount with a stored custom URL needs
 * no flag — the pure endpointSelectValue() classifies it as Other again.
 */
function EndpointSelectInput({
  value,
  placeholder,
  onChange,
}: {
  value: unknown
  placeholder?: string | undefined
  onChange: (value: unknown) => void
}) {
  const [otherMode, setOtherMode] = useState(false)
  // Remember the last custom URL so flipping to a provider and back does not
  // lose a pasted endpoint. Seeded from the stored value on mount so a reopened
  // dialog with a custom URL still round-trips.
  const [customDraft, setCustomDraft] = useState(() =>
    endpointSelectValue(value) === PROVIDER_OTHER ? String(value ?? '') : ''
  )

  const selectValue = otherMode ? PROVIDER_OTHER : endpointSelectValue(value)

  const onSelect = (selected: string) => {
    const next = endpointSelectTransition(selected, value, customDraft)
    setOtherMode(next.otherMode)
    if (next.otherMode) setCustomDraft(next.endpoint)
    onChange(next.endpoint)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem', flex: 1, minWidth: 0 }}>
      <select
        value={selectValue}
        onChange={(e) => onSelect(e.target.value)}
        title={selectValue !== '' && selectValue !== PROVIDER_OTHER ? String(value) : undefined}
      >
        <option value="">(server default)</option>
        {LLM_PROVIDERS.map((p) => (
          <option key={p.id} value={p.id} disabled={p.disabledReason !== undefined}>
            {p.disabledReason !== undefined ? `${p.label} — ${p.disabledReason}` : p.label}
          </option>
        ))}
        <option value={PROVIDER_OTHER}>Other… (custom URL)</option>
      </select>
      {selectValue === PROVIDER_OTHER && (
        <input
          type="text"
          value={String(value ?? '')}
          placeholder={placeholder || ''}
          onChange={(e) => {
            setCustomDraft(e.target.value)
            onChange(e.target.value)
          }}
        />
      )}
    </div>
  )
}

/**
 * LLM config field input — renders a single config field based on its type.
 * Extracted from GameSetupDialog.tsx.
 */
export function LLMConfigFieldInput({
  field,
  value,
  availableModels,
  modelsLoading,
  availablePersonalities,
  onChange,
  onRefreshModels,
  warning,
  warningTestId,
}: {
  field: LLMConfigField
  value: unknown
  availableModels: string[]
  modelsLoading: boolean
  availablePersonalities: string[]
  onChange: (value: unknown) => void
  onRefreshModels: () => void
  /** Setup-time warning rendered with this field (e.g. the thinking-shape probe next to Thinking Enabled, the relay-transit note under the Provider dropdown). Empty = none. */
  warning?: string | undefined
  /** data-testid for the warning block; defaults to the thinking-shape id for backward compat. */
  warningTestId?: string | undefined
}) {
  return (
    <div className="llm-config-field">
      <label>{field.label}</label>

      {field.type === 'checkbox' && (
        <input
          type="checkbox"
          checked={Boolean(value)}
          onChange={(e) => onChange(e.target.checked)}
        />
      )}

      {field.type === 'endpoint_select' && (
        <EndpointSelectInput value={value} placeholder={field.placeholder} onChange={onChange} />
      )}

      {field.type === 'select' && (
        <select
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
        >
          {field.options?.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      )}

      {field.type === 'model_select' && (
        <div style={{ display: 'flex', gap: '0.3rem', alignItems: 'center' }}>
          <select
            value={String(value ?? '')}
            onChange={(e) => onChange(e.target.value)}
            style={{ flex: 1 }}
          >
            <option value="">{field.placeholder || '(server default)'}</option>
            {availableModels.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onRefreshModels}
            data-explain="llm-refresh-models"
            disabled={modelsLoading}
            style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem' }}
            title="Refresh model list from endpoint"
          >
            {modelsLoading ? '...' : '↻'}
          </button>
        </div>
      )}

      {field.type === 'personality_select' && (
        <select
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">{field.placeholder || '(auto)'}</option>
          {availablePersonalities.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      )}

      {(field.type === 'text' || field.type === 'password' || field.type === 'number') && (
        <input
          type={field.type === 'number' ? 'number' : field.type}
          value={field.type === 'number' ? Number(value ?? 0) : String(value ?? '')}
          placeholder={field.placeholder || ''}
          min={field.min}
          max={field.max}
          step={field.step}
          onChange={(e) => {
            if (field.type === 'number') {
              onChange(parseFloat(e.target.value) || 0)
            } else {
              onChange(e.target.value)
            }
          }}
        />
      )}

      {/* Setup-time warning for this field, rendered UNDER its control: the
          thinking-shape probe next to Thinking Enabled, the relay-transit note
          under the Provider dropdown. The caller decides what warns. */}
      {warning && (
        <div
          className="llm-config-warning"
          data-testid={warningTestId ?? 'thinking-shape-warning'}
          role="alert"
          style={{
            color: 'var(--color-danger)',
            fontSize: '0.75rem',
            marginTop: '0.25rem',
            maxWidth: '48ch',
          }}
        >
          {warning}
        </div>
      )}
    </div>
  )
}