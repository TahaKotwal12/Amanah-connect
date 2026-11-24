import { MailCheck, Receipt, ScrollText, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/shared/components/ui/button'
import { DashboardMock } from './DashboardMock'
import { CONTACT_ID, DEMO_ID } from './nav'

const PROOF_POINTS = [
  { icon: MailCheck, text: 'Members never need to log in' },
  { icon: Receipt, text: 'Receipts by email' },
  { icon: ScrollText, text: 'Complete audit trail' },
  { icon: ShieldCheck, text: 'Your data stays yours' },
] as const

/**
 * Above the fold, so it renders in the first chunk with no animation (nothing here may delay the
 * largest paint). The wave nods to the logo and carries the eye into the ivory page below.
 */
export function Hero() {
  return (
    <>
      <section
        aria-labelledby="hero-title"
        className="relative overflow-hidden bg-gradient-to-br from-brand-950 via-brand-900 to-brand-800 text-white"
      >
        <div
          aria-hidden="true"
          className="lattice pointer-events-none absolute inset-0 opacity-[0.05]"
        />

        <div className="container relative grid items-center gap-12 pb-28 pt-14 lg:grid-cols-[1.05fr_0.95fr] lg:gap-10 lg:pb-36 lg:pt-20">
          <div className="grid justify-items-start gap-6">
            <h1
              id="hero-title"
              className="font-heading text-4xl font-semibold leading-tight text-white sm:text-5xl lg:text-[3.25rem]"
            >
              Simplifying Community Management with Trust and Transparency
            </h1>
            <p className="max-w-xl text-lg text-brand-100">
              Amanah Connect is a powerful cloud-based platform designed to help communities manage
              members, subscriptions, donations, finances, events, and communication from a single
              secure system.
            </p>
            <div className="flex flex-wrap gap-3">
              <Button asChild variant="premium" size="lg">
                <Link to={`/#${DEMO_ID}`}>Request a Demo</Link>
              </Button>
              <Button
                asChild
                variant="secondary"
                size="lg"
                className="border-white text-white hover:bg-white/10"
              >
                <Link to={`/#${CONTACT_ID}`}>Get Started</Link>
              </Button>
            </div>
          </div>

          <div className="flex justify-center lg:justify-end">
            <DashboardMock />
          </div>
        </div>

        {/* The logo's wave: a gold line riding on an ivory swell that becomes the page below. */}
        <svg
          aria-hidden="true"
          viewBox="0 0 1440 90"
          preserveAspectRatio="none"
          className="absolute inset-x-0 -bottom-px h-12 w-full sm:h-20"
        >
          <path
            d="M0 52 C 240 8, 480 8, 720 44 S 1200 92, 1440 40"
            fill="none"
            stroke="#C9A227"
            strokeWidth="2.5"
            strokeLinecap="round"
            opacity="0.85"
          />
          <path
            d="M0 60 C 240 20, 480 20, 720 56 S 1200 100, 1440 52 L1440 90 L0 90 Z"
            fill="#FAFAF7"
          />
        </svg>
      </section>

      <section aria-label="What you can count on" className="bg-ivory">
        <ul className="container grid gap-4 py-8 sm:grid-cols-2 lg:grid-cols-4">
          {PROOF_POINTS.map((point) => (
            <li key={point.text} className="flex items-center gap-3">
              <span
                aria-hidden="true"
                className="flex size-10 shrink-0 items-center justify-center rounded-full bg-brand-100 text-brand-800"
              >
                <point.icon className="size-5" />
              </span>
              <span className="text-sm font-semibold text-brand-900">{point.text}</span>
            </li>
          ))}
        </ul>
      </section>
    </>
  )
}

const ABOUT = [
  'Whether you manage a mosque, charitable trust, welfare association, residential community, or non-profit organization, Amanah Connect provides the tools you need to operate efficiently while maintaining transparency and accountability.',
  'We understand the challenges faced by communities in managing memberships, subscriptions, donations, finances, events, and communication. Our mission is to provide reliable and user-friendly technology that helps organizations operate more efficiently while building trust among their members.',
  'With a strong focus on transparency, accountability, and ease of use, Amanah Connect enables communities to spend less time on administration and more time serving their members.',
] as const

/** The rest of the introduction, set in three columns under the first screen. */
export function AboutIntro() {
  return (
    <section aria-label="About Amanah Connect" className="bg-ivory pb-14 sm:pb-20">
      <div className="container grid gap-10">
        <div className="grid gap-6 md:grid-cols-3">
          {ABOUT.map((text) => (
            <p key={text} className="border-t-2 border-gold-500 pt-4 text-ink">
              {text}
            </p>
          ))}
        </div>
        <p className="text-center font-heading text-xl font-semibold text-gold-600">
          Connecting Communities with Trust.
        </p>
      </div>
    </section>
  )
}
