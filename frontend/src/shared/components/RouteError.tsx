import { isRouteErrorResponse, useRouteError } from 'react-router'
import { ErrorFallback } from './ErrorFallback'
import NotFoundPage from './NotFoundPage'

/** A lazy route chunk that 404s after a deploy replaced the hashed files: the fix is a reload. */
const CHUNK_LOAD_FAILURE =
  /dynamically imported module|importing a module script failed|loading chunk|loading css chunk/i

/** The router's errorElement: loader, action, lazy-chunk and render errors inside any route. */
export function RouteError() {
  const error = useRouteError()

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
