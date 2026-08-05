import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import type { ApiError } from './api/client'
import { createHomeOpsQueryClient } from './queryClient'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HomeOpsRoot />
  </StrictMode>,
)

function HomeOpsRoot() {
  const [authorizationError, setAuthorizationError] = useState<ApiError | null>(null)
  const [queryClient] = useState(() => createHomeOpsQueryClient(setAuthorizationError))

  if (authorizationError) {
    return (
      <main className="grid min-h-dvh place-items-center bg-slate-950 px-4 text-slate-100">
        <section role="alert" className="w-full max-w-md rounded-2xl border border-rose-400/30 bg-slate-900 p-5">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-rose-300">Access blocked</p>
          <h1 className="mt-2 text-xl font-semibold">Unable to authorize HomeOps</h1>
          <p className="mt-3 text-sm text-slate-400">{authorizationError.message}</p>
          <button
            type="button"
            className="mt-5 min-h-11 rounded-xl bg-white/10 px-4 text-sm font-semibold"
            onClick={() => {
              queryClient.clear()
              setAuthorizationError(null)
            }}
          >
            Retry access
          </button>
        </section>
      </main>
    )
  }

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
