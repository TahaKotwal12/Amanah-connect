import { Suspense, useEffect } from 'react'
import { Outlet, useNavigation } from 'react-router'
import { PageSpinner } from '../PageSpinner'

/** Outermost route element: skip link, a loading bar while a route chunk loads, Suspense. */
export function RootLayout() {
  const navigation = useNavigation()

  // The page is on screen now: put away the prerendered first screen that stood in for it. Two
  // frames, so the real content has been painted before the stand-in goes. It is hidden, not
  // deleted, at first (deleting an element makes the browser forget it was the largest paint), and
  // cleared away later so the DOM does not keep a second copy of the page.
  useEffect(() => {
    const poster = document.getElementById('poster')
    if (!poster) return
    let second = 0
    let cleanup: ReturnType<typeof setTimeout> | undefined
    const first = requestAnimationFrame(() => {
      second = requestAnimationFrame(() => {
        poster.hidden = true
        cleanup = setTimeout(() => {
          poster.remove()
        }, 20_000)
      })
    })
    return () => {
      cancelAnimationFrame(first)
      cancelAnimationFrame(second)
      clearTimeout(cleanup)
    }
  }, [])
  return (
    <>
      <a href="#main" className="skip-link">
        Skip to main content
      </a>
      {navigation.state === 'loading' ? (
        <div
          role="progressbar"
          aria-label="Loading page"
          className="fixed inset-x-0 top-0 z-50 h-1 bg-gold-500"
        />
      ) : null}
      <Suspense fallback={<PageSpinner fullScreen />}>
        <Outlet />
      </Suspense>
    </>
  )
}
