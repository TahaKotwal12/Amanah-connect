import { Globe, Mail, Phone } from 'lucide-react'
import { CONTACT } from '../contact'
import { SectionHeading } from '../SectionHeading'
import { ContactDialog } from './ContactDialog'

const telHref = (phone: string) => `tel:${phone.replace(/[^+\d]/g, '')}`

export default function Contact() {
  const rows = [
    CONTACT.email
      ? { icon: Mail, label: 'Email', text: CONTACT.email, href: `mailto:${CONTACT.email}` }
      : null,
    CONTACT.phone
      ? { icon: Phone, label: 'Phone', text: CONTACT.phone, href: telHref(CONTACT.phone) }
      : null,
    CONTACT.website
      ? {
          icon: Globe,
          label: 'Website',
          text: CONTACT.website.replace(/^https?:\/\//, ''),
          href: CONTACT.website,
        }
      : null,
  ].filter((row) => row !== null)

  return (
    <section aria-labelledby="contact-title" className="py-16 sm:py-24">
      <div className="container grid justify-items-center gap-8 text-center">
        <SectionHeading
          id="contact-title"
          eyebrow="Contact us"
          title="Get in Touch"
          intro="We would love to hear from you."
        />
        <p className="max-w-2xl text-lg text-ink">
          Whether you have questions, need a demonstration, or would like to discuss your
          community’s requirements, our team is ready to help.
        </p>
        {rows.length > 0 ? (
          <ul className="grid gap-3 text-start">
            {rows.map((row) => (
              <li key={row.label} className="flex items-center gap-3">
                <span
                  aria-hidden="true"
                  className="flex size-10 shrink-0 items-center justify-center rounded-full bg-brand-100 text-brand-800"
                >
                  <row.icon className="size-5" />
                </span>
                <span>
                  <span className="font-semibold text-brand-900">{row.label}: </span>
                  <a
                    href={row.href}
                    className="text-brand-700 underline underline-offset-2 hover:text-brand-900"
                  >
                    {row.text}
                  </a>
                </span>
              </li>
            ))}
          </ul>
        ) : null}
        <ContactDialog />
      </div>
    </section>
  )
}
