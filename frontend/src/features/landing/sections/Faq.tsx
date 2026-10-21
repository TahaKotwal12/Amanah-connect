import * as Accordion from '@radix-ui/react-accordion'
import { ChevronDown } from 'lucide-react'
import { Link } from 'react-router'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

// Each answer describes what the product does today.
const FAQS = [
  {
    q: 'Do members need to log in?',
    a: 'No. Only admins have accounts. Members receive invoices, receipts and announcements by email, and open a payment page from a link. There is no password for them to forget.',
  },
  {
    q: 'How do payments work?',
    a: 'Every invoice has a payment page with a UPI QR code and link for your community’s own UPI ID. The member pays in their own UPI app and the money goes straight to your account. An admin then records the payment, which creates the receipt. Amanah Connect does not hold or process the money.',
  },
  {
    q: 'Is our data safe?',
    a: 'Connections use HTTPS, passwords are stored only as one-way hashes, admins can turn on two-factor sign-in, each community sees only its own records, and changes are written to a log nobody can edit. The security section above has the details.',
  },
  {
    q: 'Can we move over from Excel?',
    a: 'Yes. You can upload spreadsheets (CSV) of members, opening balances and open invoices. The upload is checked first and shows any row with a problem, and nothing is saved until you confirm.',
  },
  {
    q: 'What if a payment is entered by mistake?',
    a: 'A payment is reversed rather than deleted. You give a reason, a reversing entry is created, and both stay in the record, so your books always show what happened.',
  },
  {
    q: 'Will it suit our kind of community?',
    a: 'It uses plain words such as members, groups and dues, and you can rename the group label (a block, a wing, a chapter). Residents’ associations, clubs, societies and trusts all fit the same way.',
  },
  {
    q: 'Who can see our community’s data?',
    a: 'The admins you invite. Our own staff can open a community’s data only to give support, and each such access is recorded in the log.',
  },
  {
    q: 'Can we take our data with us?',
    a: 'Yes. You can export your community’s data at any time, and ask for a member’s personal details to be erased.',
  },
  {
    q: 'Which language and currency does it use?',
    a: 'The app is in English today, and amounts default to Indian rupees.',
  },
  {
    q: 'What does it cost?',
    a: 'Plans depend on the size of your community. See the pricing section above, or request a demo and we will send a clear quote.',
  },
] as const

export default function Faq() {
  return (
    <section aria-labelledby="faq-title" className="bg-white py-16 sm:py-24">
      <div className="container grid gap-10">
        <SectionHeading
          id="faq-title"
          eyebrow="FAQ"
          title="Questions people ask first"
          intro="Straight answers. If yours is missing, ask us when you request a demo."
        />
        <Reveal className="mx-auto w-full max-w-3xl">
          <Accordion.Root type="single" collapsible className="grid gap-3">
            {FAQS.map((item) => (
              <Accordion.Item
                key={item.q}
                value={item.q}
                className="rounded-card border border-brand-100 bg-ivory data-[state=open]:border-gold-500"
              >
                <Accordion.Header className="m-0">
                  <Accordion.Trigger className="group flex min-h-14 w-full items-center justify-between gap-4 rounded-card px-5 py-3 text-left font-heading text-lg font-semibold text-brand-900">
                    {item.q}
                    <ChevronDown
                      aria-hidden="true"
                      className="size-5 shrink-0 text-brand-600 transition-transform group-data-[state=open]:rotate-180"
                    />
                  </Accordion.Trigger>
                </Accordion.Header>
                <Accordion.Content className="px-5 pb-5 text-ink">{item.a}</Accordion.Content>
              </Accordion.Item>
            ))}
          </Accordion.Root>
          <p className="mt-6 text-center text-sm text-muted">
            Something else?{' '}
            <Link to="/#demo" className="font-semibold text-brand-700 underline">
              Ask us when you request a demo.
            </Link>
          </p>
        </Reveal>
      </div>
    </section>
  )
}
