import { useEffect, useRef } from 'react'
import { useGameStore } from '@/store/gameStore'

/**
 * Event animation hook — ported from app.js _triggerEventAnimation.
 * Watches the latest event and applies transient CSS animation classes
 * to matching card elements on the board.
 *
 * Usage: call useEventAnimation() from GameView.
 * It reads the eventLog from the store and animates the latest event.
 */

/** Word-boundary containment check — "Island" must not match "Islandwalk". */
function containsCardName(message: string, cardName: string): boolean {
  const lower = message.toLowerCase()
  const name = cardName.toLowerCase()
  const isWordChar = (c: string) => /[a-z0-9]/.test(c)
  let idx = lower.indexOf(name)
  while (idx !== -1) {
    const before = idx === 0 ? ' ' : lower[idx - 1]!
    const after = idx + name.length >= lower.length ? ' ' : lower[idx + name.length]!
    if (!isWordChar(before) && !isWordChar(after)) return true
    idx = lower.indexOf(name, idx + 1)
  }
  return false
}

export function useEventAnimation() {
  const eventLog = useGameStore((s) => s.eventLog)
  const lastAnimatedSeq = useRef<number>(0)

  useEffect(() => {
    if (eventLog.length === 0) return
    const latestEvent = eventLog[eventLog.length - 1]!
    if (latestEvent.seq <= lastAnimatedSeq.current) return
    lastAnimatedSeq.current = latestEvent.seq

    // Only animate events with a category (not generic action events)
    if (!latestEvent.category || latestEvent.category === 'action') return

    const animClass = `anim-${latestEvent.category}`
    const duration = 800 // ms
    const message = (latestEvent.message || '').toLowerCase()

    // Find card elements whose name appears in the message
    const root = document.getElementById('players-grid')
    if (!root) return

    const cardEls = root.querySelectorAll('.card-thumb[data-card-name]')
    let matched = 0
    cardEls.forEach((el) => {
      const cardName = el.getAttribute('data-card-name') || ''
      if (cardName && containsCardName(message, cardName)) {
        el.classList.add(animClass)
        matched++
        setTimeout(() => el.classList.remove(animClass), duration)
      }
    })

    // If no specific cards matched, flash the last log line instead
    if (matched === 0) {
      const logList = document.querySelector('.game-log-list')
      if (logList) {
        const lastLine = logList.lastElementChild
        if (lastLine) {
          lastLine.classList.add(animClass)
          setTimeout(() => lastLine.classList.remove(animClass), duration)
        }
      }
    }
  }, [eventLog])
}
