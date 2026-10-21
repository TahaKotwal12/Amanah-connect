import { ClipboardCheck, Rocket, UserPlus } from 'lucide-react'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

const STEPS = [
  {
    icon: ClipboardCheck,
    title: 'We set up your community',
    text: 'We create your community, agree a plan with you, and invite your first admin.',
  },
  {
    icon: UserPlus,
    title: 'Add or invite members',
    text: 'Import a spreadsheet, add people one by one, or share an invite link or QR code.',
  },
  {
    icon: Rocket,
    title: 'Collect and report with confidence',
    text: 'Send invoices, record payments, and get receipts and reports without extra tools.',
  },
] as const

export default function HowItWorks() {
  return (
    <section aria-labelledby="how-title" className="py-16 sm:py-24">
      <div className="container grid gap-12">
        <SectionHeading
          id="how-title"
          eyebrow="How it works"
          title="Up and running in three steps"
        />
        <ol className="relative grid gap-10 md:grid-cols-3 md:gap-6">
          {/* The dashed connector: horizontal behind the numbers on wide screens. */}
          <div
            aria-hidden="true"
            className="absolute left-[16.7%] right-[16.7%] top-7 hidden border-t-2 border-dashed border-gold-500 md:block"
          />
          {STEPS.map((step, index) => (
            <li key={step.title} className="relative grid justify-items-center gap-3 text-center">
              {/* ...and vertical between the steps on phones. */}
              {index < STEPS.length - 1 ? (
                <div
                  aria-hidden="true"
                  className="absolute left-1/2 top-16 h-[calc(100%-2rem)] -translate-x-1/2 border-l-2 border-dashed border-gold-500 md:hidden"
                />
              ) : null}
              <Reveal delay={index * 0.08} className="relative grid justify-items-center gap-3">
                <span className="relative z-10 flex size-14 items-center justify-center rounded-full border-2 border-gold-500 bg-ivory font-heading text-xl font-semibold text-brand-900">
                  <span className="sr-only">Step </span>
                  {index + 1}
                </span>
                <step.icon aria-hidden="true" className="size-6 text-brand-600" />
                <h3 className="font-heading text-xl font-semibold text-brand-900">{step.title}</h3>
                <p className="max-w-xs text-ink">{step.text}</p>
              </Reveal>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}
