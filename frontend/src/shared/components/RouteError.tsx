import { useEffect } from 'react'
import { isRouteErrorResponse, useRouteError } from 'react-router'
import { reportError } from '@/lib/monitoring'
import { ErrorFallback } from './ErrorFallback'
import NotFoundPage from './NotFoundPage'

/** A lazy route chunk that 404s after a deploy replaced the hashed files: the fix is a reload. */
const CHUNK_LOAD_FAILURE =
  /dynamically imported module|importing a module script failed|loading chunk|loading css chunk/i

/** The router's errorElement: loader, action, lazy-chunk and render errors inside any route. */
export function RouteError() {
  const error = useRouteError()
  const expected =
    isRouteErrorResponse(error) ||
    (error instanceof Error && CHUNK_LOAD_FAILURE.test(error.message))

  // A 404 and a stale chunk after a deploy are not bugs: only the rest is reported.
  useEffect(() => {
    if (!expected) reportError(error)
  }, [error, expected])

  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />

  if (error instanceof Error && CHUNK_LOAD_FAILURE.test(error.message)) {
    return (
      <ErrorFallback
        title="A new version is available"
        message="Amanah Connect was updated while this page was open. Reload to continue."
        actionLabel="Reload"
      />
    )
  }

  console.error('Route error', error)

  return (
    <ErrorFallback message="This page could not be shown. Reloading usually fixes it, and your data is safe." />
  )
}
