import { Handshake, Scale, Shield, Star } from 'lucide-react'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

// The four values of the Amanah Connect logo, each tied to something the product actually does.
const VALUES = [
  {
    icon: Shield,
    name: 'Trust',
    text: 'Every payment, change and message is recorded in a trail that cannot be edited.',
  },
  {
    icon: Scale,
    name: 'Integrity',
    text: 'Amounts are exact to the paisa, and a mistake is corrected with a visible reversal, never quietly overwritten.',
  },
  {
    icon: Handshake,
    name: 'Collaboration',
    text: 'Members stay informed by email, and admins can message our team from inside the app.',
  },
  {
    icon: Star,
    name: 'Excellence',
    text: 'Clear screens and checks that catch mistakes, such as refusing a payment larger than the amount due.',
  },
] as const

export default function Values() {
  return (
    <section aria-labelledby="values-title" className="bg-brand-900 py-16 text-white sm:py-24">
      <div className="container grid gap-12">
        <SectionHeading
          id="values-title"
          onDark
          eyebrow="Why Amanah"
          title="An amanah is something held in trust"
          intro="The name is the promise. Four values guide how Amanah Connect is built."
        />
        <ul className="grid gap-px overflow-hidden rounded-card bg-gold-500/40 sm:grid-cols-2 lg:grid-cols-4">
          {VALUES.map((value, index) => (
            <li key={value.name} className="bg-brand-900">
              <Reveal delay={index * 0.06} className="h-full">
                <div className="h-full p-6 sm:p-8">
                  <span
                    aria-hidden="true"
                    className="flex size-12 items-center justify-center rounded-full border border-gold-500 text-gold-500"
                  >
                    <value.icon className="size-6" />
                  </span>
                  <div aria-hidden="true" className="my-4 h-px w-10 bg-gold-500" />
                  <h3 className="font-heading text-xl font-semibold text-white">{value.name}</h3>
                  <p className="mt-2 text-sm text-brand-100">{value.text}</p>
                </div>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
