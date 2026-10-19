import { useEffect, useState } from 'react'
import type { StackItem } from '@/types'
import { CardThumb } from './CardThumb'
import { ManaText } from './ManaCost'
import { parseStackAbility } from '@/utils/cardImage'
import { useGameStore } from '@/store/gameStore'
import { rootZoom } from '@/utils/viewport'
import { useContextMenu } from '@/hooks/useContextMenu'

/**
 * Stack zone — ported from game-renderer.js renderStack.
 * Shows spells/abilities on the stack with ability overlays and target indicators.
 * Right-clicking an ability shows the trigger order popup (matching Java client's
 * popupMenuTriggerOrder — put first/last, by rule text, reset all).
 */
interface StackZoneProps {
  stack: StackItem[]
  /**
   * Read-only mode (replay viewer): the trigger-order context menu is not
   * offered. Ordering triggers is a game action (the proxy refuses it while a
   * replay is loaded), so showing a menu whose every item errors would be a lie.
   */
  readOnly?: boolean | undefined
}

export function StackZone({ stack, readOnly = false }: StackZoneProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; itemId: string | null; abilityText: string } | null>(null)
  // Shared context-menu registry: opening the trigger-order menu through the
  // hook makes CardPreview hide for it too, and enforces one-menu-at-a-time.
  const triggerMenu = useContextMenu('trigger-order')
  // Mirror the hook's open state into the local shape (the local copy carries
  // the item payload the hook's store entry does not).
  useEffect(() => {
    if (!triggerMenu.isOpen) setContextMenu(null)
  }, [triggerMenu.isOpen])

  const handleContextMenu = (e: React.MouseEvent, item: StackItem, abilityText: string) => {
    // Read-only (replay): no trigger-order menu — see StackZoneProps.readOnly.
    if (readOnly) {
      e.preventDefault()
      return
    }
    setContextMenu({ x: e.clientX, y: e.clientY, itemId: item.id, abilityText })
    triggerMenu.openAt(e)
  }

  if (!stack || stack.length === 0) {
    return (
      <div className="stack-section">
        <div className="zone-title">Stack</div>
        <div id="stack-cards" className="cards-row" />
      </div>
    )
  }

  return (
    <div className="stack-section">
      <div className="zone-title">Stack</div>
      <div id="stack-cards" className="cards-row">
        {stack.map((item) => {
          const name = item.name || ''
          if (!name) return null // skip empty-named stack items

          const abilityInfo = parseStackAbility(name, item)

          if (abilityInfo.isAbility) {
            return (
              <div key={item.id ?? name} className="stack-item">
                <div
                  className="card-thumb ability-thumb"
                  data-card-id={item.id ?? undefined}
                  onContextMenu={(e) => handleContextMenu(e, item, abilityInfo.abilityText || '')}
                >
                  {abilityInfo.sourceCard && (
                    <CardThumb card={item} className="ability-bg-img" />
                  )}
                  <div className="ability-overlay">
                    {abilityInfo.sourceCard && (
                      <div className="ability-source-name">{abilityInfo.sourceCard}</div>
                    )}
                    <div className="ability-text">
                      <ManaText text={abilityInfo.abilityText} cardName={abilityInfo.sourceCard ?? undefined} />
                    </div>
                  </div>
                </div>
                {item.targets && item.targets.length > 0 && (
                  <div className="stack-target">→ {item.targets.join(', ')}</div>
                )}
              </div>
            )
          }

          return (
            <div key={item.id ?? name} className="stack-item" data-card-id={item.id ?? undefined}>
              <CardThumb card={item} />
              {item.targets && item.targets.length > 0 && (
                <div className="stack-target">→ {item.targets.join(', ')}</div>
              )}
            </div>
          )
        })}
      </div>
      {contextMenu && triggerMenu.isOpen && (
        <TriggerOrderMenu
          x={contextMenu.x}
          y={contextMenu.y}
          abilityId={contextMenu.itemId}
          ruleText={contextMenu.abilityText}
          onClose={() => {
            setContextMenu(null)
            triggerMenu.close()
          }}
        />
      )}
    </div>
  )
}

/** Right-click context menu for trigger ordering (matches Java popupMenuTriggerOrder). */
function TriggerOrderMenu({ x, y, abilityId, ruleText, onClose }: {
  x: number; y: number; abilityId: string | null; ruleText: string; onClose: () => void
}) {
  const triggerOrder = useGameStore((s) => s.triggerOrder)

  // x/y arrive in REAL px from the context-menu event; the fixed left/top
  // style renders in ROOT CSS px (base.css zooms <html>), so convert here.
  const z = rootZoom()

  const items = [
    { label: 'Put this ability always first', action: 'first' as const },
    { label: 'Put this ability always last', action: 'last' as const },
    { label: 'Put all with this rule text first', action: 'name_first' as const, disabled: !ruleText },
    { label: 'Put all with this rule text last', action: 'name_last' as const, disabled: !ruleText },
    { label: 'Reset all auto-order settings', action: 'reset_all' as const },
  ]

  return (
    <>
      <div className="modal-overlay" style={{ display: 'block', background: 'transparent' }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose() }} />
      <div
        className="trigger-order-menu"
        style={{ position: 'fixed', left: x / z, top: y / z, zIndex: 3000 }}
      >
        {items.map((item, idx) => (
          <div
            key={idx}
            className={`trigger-order-item${item.disabled ? ' disabled' : ''}`}
            onClick={() => {
              if (!item.disabled) {
                triggerOrder(item.action, abilityId, ruleText)
                onClose()
              }
            }}
          >
            {item.label}
          </div>
        ))}
      </div>
    </>
  )
}