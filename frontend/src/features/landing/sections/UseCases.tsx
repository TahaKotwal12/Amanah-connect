import { CalendarCheck, HandHeart, Megaphone, MessageSquareWarning, PiggyBank } from 'lucide-react'
import { Card } from '@/shared/components/ui/card'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

const USE_CASES = [
  {
    icon: CalendarCheck,
    title: 'Collect monthly dues',
    pitch: 'Set a fee once and invoices are created for every active member, every period.',
    example: 'A monthly maintenance fee, billed on a due date you choose.',
  },
  {
    icon: HandHeart,
    title: 'Record donations',
    pitch: 'Note every gift from a member or a visitor and give each one a receipt.',
    example: 'A one-off festival donation, recorded in seconds.',
  },
  {
    icon: PiggyBank,
    title: 'Track spending against budget',
    pitch: 'Log income and expenses by category and see where the money goes.',
    example: 'Repairs and utilities set against what came in this quarter.',
  },
  {
    icon: MessageSquareWarning,
    title: 'Handle member complaints',
    pitch: 'Capture each complaint, assign it, reply, and close it with a record of what was done.',
    example: 'A leaking tap reported by a member, assigned and resolved with notes.',
  },
  {
    icon: Megaphone,
    title: 'Keep everyone informed',
    pitch: 'Send announcements by email to everyone, to a group, or to chosen members.',
    example: 'A notice about a planned water shutdown, sent to one block.',
  },
] as const

export default function UseCases() {
  return (
    <section aria-labelledby="solutions-title" className="py-16 sm:py-24">
      <div className="container grid gap-12">
        <SectionHeading
          id="solutions-title"
          eyebrow="Solutions"
          title="Everything your community runs on"
          intro="The same few jobs come up in every community. Amanah Connect handles each of them in one place."
        />
        <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-6">
          {USE_CASES.map((item, index) => (
            <li key={item.title} className={index < 3 ? 'lg:col-span-2' : 'lg:col-span-3'}>
              <Reveal delay={(index % 3) * 0.06} className="h-full">
                <Card className="h-full p-6">
                  <span
                    aria-hidden="true"
                    className="flex size-11 items-center justify-center rounded bg-brand-50 text-brand-700"
                  >
                    <item.icon className="size-6" />
                  </span>
                  <h3 className="mt-4 font-heading text-xl font-semibold text-brand-900">
                    {item.title}
                  </h3>
                  <p className="mt-2 text-ink">{item.pitch}</p>
                  <p className="mt-3 border-l-2 border-gold-500 pl-3 text-sm text-muted">
                    <span className="font-semibold text-gold-600">For example: </span>
                    {item.example}
                  </p>
                </Card>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
