import { Link, Outlet } from 'react-router'
import { paths } from '@/routes/paths'
import { BrandMark } from '../BrandMark'
import { LinkButton } from '../LinkButton'

/** Marketing and auth pages. Prompts F2 and F3 flesh this out (nav links, footer, split screen). */
export function PublicLayout() {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-brand-100 bg-white">
        <div className="container flex items-center justify-between py-3">
          <Link to={paths.home} className="flex items-center gap-3 rounded">
            <BrandMark />
            <span className="font-display text-lg font-semibold tracking-[0.12em] text-brand-900">
              AMANAH CONNECT
            </span>
          </Link>
          <LinkButton to={paths.login} variant="outline">
            Log in
          </LinkButton>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="flex-1 outline-none">
        <Outlet />
      </main>
      <footer className="border-t border-brand-100 bg-white py-6 text-sm text-muted">
        <div className="container flex flex-wrap items-center justify-between gap-3">
          <span>Connecting communities with trust</span>
          <nav aria-label="Legal" className="flex gap-4">
            <Link to={paths.privacy} className="rounded hover:text-brand-700">
              Privacy
            </Link>
            <Link to={paths.terms} className="rounded hover:text-brand-700">
              Terms
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  )
}
