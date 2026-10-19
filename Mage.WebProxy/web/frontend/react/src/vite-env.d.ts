/// <reference types="vite/client" />

// Build-time LLMage toggle, injected by vite.config.ts
// `define` (VITE_LLMAGE=false → false). Compile-time constant: in a stock
// bundle every `!__LLMAGE__` branch is dead code the minifier removes.
declare const __LLMAGE__: boolean

// Expose the Zustand store on window in dev mode for testing/debugging
interface Window {
  __gameStore?: import('@/store/gameStore').useGameStore
}