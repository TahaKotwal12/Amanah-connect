import { Link } from 'react-router'
import { paths } from '@/routes/paths'
import { BrandMark } from '@/shared/components/BrandMark'
import { SECTION_LINKS } from './nav'

const linkClasses = 'inline-flex min-h-11 items-center rounded text-brand-100 hover:text-white'

export function Footer() {
  return (
    <footer className="bg-brand-950 text-brand-100">
      <div className="container grid gap-10 py-12 md:grid-cols-[1.4fr_1fr_1fr]">
        <div className="grid content-start gap-4">
          <div className="flex items-center gap-3">
            {/* The logo files are made for light backgrounds, so the mark sits on an ivory tile. */}
            <span className="flex size-12 items-center justify-center rounded bg-ivory">
              <BrandMark width={36} />
            </span>
            <span className="font-display text-lg font-semibold tracking-[0.12em] text-white">
              AMANAH CONNECT
            </span>
          </div>
          <p className="max-w-sm text-sm">Connecting communities with trust.</p>
        </div>

        <nav aria-label="Footer sections">
          <h2 className="eyebrow text-gold-500">Explore</h2>
          <ul className="mt-2 grid">
            {SECTION_LINKS.map((link) => (
              <li key={link.id}>
                <Link to={`/#${link.id}`} className={linkClasses}>
                  {link.label}
                </Link>
              </li>
            ))}
            <li>
              <Link to={paths.login} className={linkClasses}>
                Login
              </Link>
            </li>
          </ul>
        </nav>

        <nav aria-label="Legal">
          <h2 className="eyebrow text-gold-500">Legal</h2>
          <ul className="mt-2 grid">
            <li>
              <Link to={paths.privacy} className={linkClasses}>
                Privacy policy
              </Link>
            </li>
            <li>
              <Link to={paths.terms} className={linkClasses}>
                Terms of service
              </Link>
            </li>
          </ul>
        </nav>
      </div>
      <div className="border-t border-brand-800">
        <p className="container py-5 text-sm">
          &copy; {new Date().getFullYear()} Amanah Connect. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
