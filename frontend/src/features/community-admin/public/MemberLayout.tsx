import { Outlet } from 'react-router'
import { BrandMark } from '@/shared/components/BrandMark'

/**
 * The frame for pages a member reaches from a link or a QR code. Members have no login, so there
 * is no "Log in" here: the page is the community's, with a quiet mention of the platform.
 */
export default function MemberLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-ivory">
      <main id="main" tabIndex={-1} className="flex flex-1 justify-center px-4 py-8 outline-none">
        <div className="w-full max-w-lg">
          <Outlet />
        </div>
      </main>
      <footer className="flex items-center justify-center gap-2 border-t border-brand-100 bg-white py-4 text-sm text-muted">
        <BrandMark />
        <span>Amanah Connect</span>
      </footer>
    </div>
  )
}
