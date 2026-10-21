import {
  ChartColumn,
  HandCoins,
  Mail,
  Megaphone,
  MessageSquareWarning,
  QrCode,
  ShieldCheck,
  Users,
} from 'lucide-react'
import { cn } from '@/shared/utils/cn'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

const FEATURES = [
  {
    icon: Users,
    title: 'Member management',
    text: 'One directory with groups, status and contact details. Import from a spreadsheet, add people one at a time, or share an invite link or QR code and approve who registers.',
    wide: true,
  },
  {
    icon: HandCoins,
    title: 'Dues and donations',
    text: 'Fee plans, invoices, partial payments and donations, with every correction shown as a reversal rather than a silent edit.',
  },
  {
    icon: Mail,
    title: 'Instant receipts by email',
    text: 'A receipt is created when a payment is recorded and can be emailed to the member straight away.',
  },
  {
    icon: QrCode,
    title: 'UPI QR payments',
    text: 'Each invoice has a payment page with a UPI QR code. Money goes straight to your own UPI account, and an admin confirms it.',
  },
  {
    icon: ChartColumn,
    title: 'Budget and financial reports',
    text: 'Income and expense statements, category breakdowns, defaulters and receipt registers, as CSV and, on plans that include it, PDF.',
    wide: true,
  },
  {
    icon: MessageSquareWarning,
    title: 'Complaint tracking',
    text: 'Status, priority and a target date for every complaint, with comments members can see and notes they cannot.',
  },
  {
    icon: Megaphone,
    title: 'Announcements',
    text: 'Write once and send to everyone, a group or selected members, now or on a schedule.',
  },
  {
    icon: ShieldCheck,
    title: 'Audit trail and 2FA',
    text: 'A log of who did what that nobody can edit, and two-factor sign-in for admins.',
  },
] as const

export default function Features() {
  return (
    <section aria-labelledby="features-title" className="bg-white py-16 sm:py-24">
      <div className="container grid gap-12">
        <SectionHeading
          id="features-title"
          eyebrow="Features"
          title="Built for the people who keep a community running"
          intro="Plain screens and the checks that matter, without a training course."
        />
        <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {FEATURES.map((feature, index) => (
            <li
              key={feature.title}
              className={cn('wide' in feature && feature.wide && 'lg:col-span-2')}
            >
              <Reveal delay={(index % 4) * 0.05} className="h-full">
                <div
                  className={cn(
                    'h-full rounded-card border border-brand-100 p-6',
                    'wide' in feature && feature.wide ? 'bg-brand-50' : 'bg-ivory',
                  )}
                >
                  <span
                    aria-hidden="true"
                    className="flex size-11 items-center justify-center rounded bg-white text-brand-700 shadow-soft"
                  >
                    <feature.icon className="size-6" />
                  </span>
                  <h3 className="mt-4 font-heading text-lg font-semibold text-brand-900">
                    {feature.title}
                  </h3>
                  <p className="mt-2 text-sm text-ink">{feature.text}</p>
                </div>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
