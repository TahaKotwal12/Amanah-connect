import { Handshake, Scale, Shield, Star } from 'lucide-react'
import { Link, Outlet } from 'react-router'
import { paths } from '@/routes/paths'
import { BrandMark } from '@/shared/components/BrandMark'

const VALUES = [
  { icon: Shield, name: 'Trust', text: 'Every payment and change is recorded.' },
  { icon: Scale, name: 'Integrity', text: 'Exact amounts, honest corrections.' },
  { icon: Handshake, name: 'Collaboration', text: 'Members informed, admins supported.' },
  { icon: Star, name: 'Excellence', text: 'Clear screens that catch mistakes.' },
] as const

/**
 * The public layout for sign-in pages, split in two: a deep-teal panel for the brand on the left
 * (it collapses to a slim header on phones) and the form on ivory on the right.
 */
export default function AuthLayout() {
  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <meta name="robots" content="noindex" />
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-gradient-to-br from-brand-950 via-brand-900 to-brand-800 p-12 text-white lg:flex">
        <div
          aria-hidden="true"
          className="lattice pointer-events-none absolute inset-0 opacity-[0.05]"
        />
        <Link to={paths.home} className="relative flex items-center gap-3 rounded">
          <span className="flex size-14 items-center justify-center rounded bg-ivory">
            <BrandMark width={44} />
          </span>
          <span className="font-display text-xl font-semibold tracking-[0.12em]">
            AMANAH CONNECT
          </span>
        </Link>

        <div className="relative grid gap-8">
          <div className="grid gap-3">
            <p className="eyebrow text-gold-500">Connecting communities with trust</p>
            <p className="max-w-md font-heading text-4xl font-semibold leading-tight">
              Your community’s records, in safe hands.
            </p>
          </div>
          <ul className="grid gap-5">
            {VALUES.map((value) => (
              <li
                key={value.name}
                className="flex items-center gap-4 border-t border-gold-500/40 pt-4"
              >
                <span
                  aria-hidden="true"
                  className="flex size-11 shrink-0 items-center justify-center rounded-full border border-gold-500 text-gold-500"
                >
                  <value.icon className="size-5" />
                </span>
                <span>
                  <span className="block font-heading text-lg font-semibold">{value.name}</span>
                  <span className="text-sm text-brand-100">{value.text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-sm text-brand-100">
          &copy; {new Date().getFullYear()} Amanah Connect
        </p>
      </aside>

      <div className="flex flex-col bg-ivory">
        <header className="border-b border-brand-100 bg-white px-4 py-3 lg:hidden">
          <Link to={paths.home} className="flex items-center gap-3 rounded">
            <BrandMark />
            <span className="font-display text-base font-semibold tracking-[0.12em] text-brand-900">
              AMANAH CONNECT
            </span>
          </Link>
        </header>
        <main
          id="main"
          tabIndex={-1}
          className="flex flex-1 items-center justify-center px-4 py-10 outline-none"
        >
          <Outlet />
        </main>
      </div>
    </div>
  )
}
