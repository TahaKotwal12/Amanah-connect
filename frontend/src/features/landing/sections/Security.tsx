import {
  DatabaseBackup,
  FileDown,
  KeyRound,
  Lock,
  ScrollText,
  ShieldCheck,
  UsersRound,
} from 'lucide-react'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

/*
 * Every line below is something the system does today (see docs/security.md and docs/runbook.md).
 * No certifications, audits or uptime figures are claimed, because none are published.
 * CONFIRM BEFORE LAUNCH: "daily backups" describes the nightly dump in the runbook; check the
 * schedule is actually running in production.
 */
const POINTS = [
  {
    icon: Lock,
    title: 'Encrypted connections',
    text: 'Everything travels over HTTPS (TLS 1.2 or newer). Passwords are never stored, only salted one-way hashes, and two-factor secrets are stored encrypted.',
  },
  {
    icon: KeyRound,
    title: 'Two-factor authentication',
    text: 'Sign-in with an authenticator app and single-use recovery codes. It is required for our own staff and available to every community admin.',
  },
  {
    icon: UsersRound,
    title: 'Role-based access',
    text: 'Each community sees only its own data. Platform staff and community admins have separate permissions, and access to your data by our staff is logged.',
  },
  {
    icon: ScrollText,
    title: 'Audit logs',
    text: 'Sign-ins, payments, exports and changes are written to a log that cannot be edited. Each community can read its own.',
  },
  {
    icon: DatabaseBackup,
    title: 'Daily backups',
    text: 'The database is backed up every night, and restores are tested on a regular schedule.',
  },
  {
    icon: FileDown,
    title: 'Your data, on request',
    text: 'Export your community’s data whenever you want it, and have a member’s personal details erased when asked.',
  },
] as const

export default function Security() {
  return (
    <section aria-labelledby="security-title" className="bg-white py-16 sm:py-24">
      <div className="container grid gap-12 lg:grid-cols-[0.8fr_1.2fr] lg:gap-16">
        <div className="grid content-start gap-6">
          <SectionHeading
            id="security-title"
            align="left"
            eyebrow="Transparency and security"
            title="Handling your members’ details with care"
            intro="Community records include names, phone numbers and payments. Here is what protects them, stated plainly."
          />
          <div className="flex items-start gap-3 rounded-card border border-gold-200 bg-gold-50 p-4">
            <ShieldCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-gold-600" />
            <p className="text-sm text-ink">
              We describe only what the system does today. We make no claims about certifications we
              do not hold.
            </p>
          </div>
        </div>
        <ul className="grid gap-5 sm:grid-cols-2">
          {POINTS.map((point, index) => (
            <li key={point.title}>
              <Reveal delay={(index % 2) * 0.06} className="h-full">
                <div className="h-full rounded-card border border-brand-100 bg-ivory p-5">
                  <span
                    aria-hidden="true"
                    className="flex size-10 items-center justify-center rounded bg-white text-brand-700 shadow-soft"
                  >
                    <point.icon className="size-5" />
                  </span>
                  <h3 className="mt-3 font-heading text-lg font-semibold text-brand-900">
                    {point.title}
                  </h3>
                  <p className="mt-1.5 text-sm text-ink">{point.text}</p>
                </div>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
