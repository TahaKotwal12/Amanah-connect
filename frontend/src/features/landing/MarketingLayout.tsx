import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router'
import { Footer } from './Footer'
import { Navbar } from './Navbar'

/** Navbar, content and footer for the public marketing and legal pages. */
export default function MarketingLayout() {
  const { hash, pathname } = useLocation()

  // Router links to /#section change the address but do not scroll: do it here, and go to the top
  // on a plain page change.
  useEffect(() => {
    if (!hash) {
      window.scrollTo({ top: 0 })
      return
    }
    const id = decodeURIComponent(hash.slice(1))
    // Deferred sections mount their anchor immediately, so it is there even before the content is.
    const frame = requestAnimationFrame(() => {
      document.getElementById(id)?.scrollIntoView()
    })
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [hash, pathname])

  return (
    <div className="flex min-h-screen flex-col">
      <Navbar />
      <main id="main" tabIndex={-1} className="flex-1 outline-none">
        <Outlet />
      </main>
      <Footer />
    </div>
  )
}
