import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// Dev proxy: Vite serves at :5173, proxies /ws to the XMage WebProxy at :8090.
// The XMage proxy serves the React frontend and handles WebSocket routing.
export default defineConfig(({ mode }) => {
  // Build-time LLMage toggle.
  //   default build:            npm run build              → llmage=true
  //   official-server artifact: VITE_LLMAGE=false npm run build → stock
  // loadEnv merges .env files and the process env; `define` bakes the flag as
  // the __LLMAGE__ compile-time constant (see src/vite-env.d.ts), so in a
  // stock bundle every `!__LLMAGE__` branch is dead code the minifier removes.
  // Runtime behavior is ALSO gated per-server by the capability probe — this
  // flag only controls what ships in the bundle.
  const env = loadEnv(mode, process.cwd(), '')
  const llmage = env.VITE_LLMAGE !== 'false'
  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    define: {
      __LLMAGE__: JSON.stringify(llmage),
    },
    // Serve the parent web/frontend/ directory as public files (CSS, images).
    // This lets the dev server serve base.css, game-renderer.css, etc. at /.
    publicDir: path.resolve(__dirname, '..'),
    server: {
      port: 5173,
      // No HMR: plugin-react 6.0.3 on vite 8.1.5 never injects the react-refresh
      // preamble into the served HTML, while .tsx modules still get the
      // $RefreshSig$ wrapper -> blank page on every load. hmr:false makes the
      // plugin skip the wrapper entirely (skipFastRefresh); dev reloads become
      // full page reloads. See
      hmr: false,
      proxy: {
        '/ws': {
          target: 'ws://localhost:8090',
          ws: true,
        },
      },
    },
    build: {
      outDir: '../',          // output to web/frontend/ (replaces Alpine files at cutover)
      emptyOutDir: false,     // CRITICAL: don't delete CSS files
    },
  }
})