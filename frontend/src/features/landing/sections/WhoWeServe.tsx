import { Building2, GraduationCap, HandHeart, HeartHandshake, Landmark, Users } from 'lucide-react'
import { Card } from '@/shared/components/ui/card'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

const AUDIENCES = [
  {
    icon: Landmark,
    title: 'Mosques & Islamic Centers',
    text: 'Manage memberships, donations, subscriptions, events, and community communications.',
  },
  {
    icon: HandHeart,
    title: 'Charitable Trusts',
    text: 'Track donations, beneficiaries, projects, and financial reports.',
  },
  {
    icon: Users,
    title: 'Welfare Associations',
    text: 'Simplify member management, collections, and community engagement.',
  },
  {
    icon: Building2,
    title: 'Residential Communities',
    text: 'Manage residents, maintenance fees, notices, and community records.',
  },
  {
    icon: GraduationCap,
    title: 'Educational Institutions',
    text: 'Manage student associations, memberships, fees, and events.',
  },
  {
    icon: HeartHandshake,
    title: 'Non-Profit Organizations',
    text: 'Improve operational efficiency and maintain transparent financial management.',
  },
] as const

export default function WhoWeServe() {
  return (
    <section aria-labelledby="who-we-serve-title" className="bg-brand-50/60 py-16 sm:py-24">
      <div className="container grid gap-12">
        <SectionHeading
          id="who-we-serve-title"
          eyebrow="Who we serve"
          title="Designed for Every Community"
        />
        <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {AUDIENCES.map((item, index) => (
            <li key={item.title}>
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
                  <p className="mt-2 text-ink">{item.text}</p>
                </Card>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
