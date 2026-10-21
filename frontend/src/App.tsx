import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router/dom'
import { setSessionExpiredHandler } from '@/lib/axios'
import { queryClient } from '@/lib/queryClient'
import { createAppRouter } from '@/routes'
import { paths } from '@/routes/paths'
import { ErrorBoundary } from '@/shared/components/ErrorBoundary'
import { LazyToaster } from '@/shared/components/LazyToaster'

// One router for the life of the page. Created at module scope, not in a component, so React's
// StrictMode double render cannot leave the expiry handler pointing at a discarded router.
const router = createAppRouter()

// When a refresh fails the session is already cleared. Move to the sign-in page with a SPA
// navigation (no reload), so the page can say why and in-memory form drafts survive.
setSessionExpiredHandler(() => {
  void router.navigate(paths.login, { replace: true })
})

export default function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <LazyToaster />
      </QueryClientProvider>
    </ErrorBoundary>
  )
}
