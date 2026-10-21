import { lazy, Suspense } from 'react'

const Toaster = lazy(() => import('sonner').then((module) => ({ default: module.Toaster })))

/** The toast container, fetched after the first paint instead of with the page. */
export function LazyToaster() {
  return (
    <Suspense fallback={null}>
      <Toaster position="top-right" closeButton />
    </Suspense>
  )
}
