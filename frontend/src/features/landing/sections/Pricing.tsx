import { useQuery } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { formatMoney, isZeroMoney } from '@/lib/money'
import { Button } from '@/shared/components/ui/button'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { cn } from '@/shared/utils/cn'
import { fetchPublicPlans, type PublicPlan } from '../api'
import { landingKeys } from '../queryKeys'
import { Reveal } from '../motion'
import { DEMO_ID } from '../nav'
import { SectionHeading } from '../SectionHeading'

export type Billing = 'monthly' | 'yearly'

const FEATURE_LABELS: Record<string, string> = {
  upi_qr: 'UPI QR payments',
  receipts_email: 'Receipts by email',
  csv_export: 'CSV export',
  pdf_reports: 'PDF reports',
  two_factor: 'Two-factor sign-in',
  priority_support: 'Priority support',
}

function limitLines(limits: PublicPlan['limits']): string[] {
  const lines: string[] = []
  if ('max_members' in limits) {
    const max = limits.max_members
    lines.push(
      max === null || max === undefined
        ? 'Unlimited members'
        : `Up to ${max.toLocaleString('en-IN')} members`,
    )
  }
  const storage = limits.storage_mb
  if (typeof storage === 'number') {
    lines.push(
      storage >= 1024
        ? `${+(storage / 1024).toFixed(1)} GB of file storage`
        : `${storage} MB of file storage`,
    )
  }
  const emails = limits.emails_per_month
  if (typeof emails === 'number') lines.push(`${emails.toLocaleString('en-IN')} emails a month`)
  return lines
}

function featureLines(features: PublicPlan['features']): string[] {
  return Object.entries(features)
    .filter(([, included]) => included)
    .map(([key]) => FEATURE_LABELS[key])
    .filter((label): label is string => label !== undefined)
}

const isEnterprise = (plan: PublicPlan) => plan.code.toUpperCase() === 'ENTERPRISE'

/** Also used by the super admin plan preview, so what is previewed is what the page shows. */
export function PlanCard({
  plan,
  billing,
  highlighted,
}: {
  plan: PublicPlan
  billing: Billing
  highlighted: boolean
}) {
  const enterprise = isEnterprise(plan)
  const price = billing === 'monthly' ? plan.priceMonthly : plan.priceYearly
  const lines = [...limitLines(plan.limits), ...featureLines(plan.features)]
  return (
    <div
      className={cn(
        'flex h-full flex-col rounded-card border bg-white p-6 shadow-soft',
        highlighted && 'border-2 border-gold-500 shadow-lift',
        enterprise && 'border-2 border-premium',
        !highlighted && !enterprise && 'border-brand-100',
      )}
    >
      {highlighted ? (
        <p className="mb-3 w-fit rounded bg-gold-50 px-2 py-0.5 text-xs font-semibold text-gold-600">
          Recommended
        </p>
      ) : null}
      <h3 className="font-heading text-2xl font-semibold text-brand-900">{plan.name}</h3>
      <p className="mt-4 min-h-[3.5rem]">
        {enterprise ? (
          <span className="font-heading text-2xl font-semibold text-premium">Contact us</span>
        ) : isZeroMoney(price) ? (
          <span className="font-heading text-3xl font-semibold text-brand-900">Free</span>
        ) : (
          <>
            <span className="font-heading text-3xl font-semibold tabular-nums text-brand-900">
              {formatMoney(price).replace(/\.00$/, '')}
            </span>
            <span className="text-muted"> / {billing === 'monthly' ? 'month' : 'year'}</span>
          </>
        )}
      </p>
      <ul className="mt-4 grid flex-1 content-start gap-2 text-sm">
        {lines.map((line) => (
          <li key={line} className="flex items-start gap-2">
            <Check
              aria-hidden="true"
              className={cn(
                'mt-0.5 size-4 shrink-0',
                enterprise ? 'text-premium' : 'text-brand-600',
              )}
            />
            {line}
          </li>
        ))}
      </ul>
      <Button
        asChild
        variant={enterprise ? 'secondary' : highlighted ? 'primary' : 'secondary'}
        className={cn('mt-6', enterprise && 'border-premium text-premium hover:bg-premium-50')}
      >
        <Link to={`/#${DEMO_ID}`}>{enterprise ? 'Contact us' : 'Request a demo'}</Link>
      </Button>
    </div>
  )
}

/** Shown while the plans endpoint is missing, failing or empty. No tiers or prices are invented. */
function StaticPricing() {
  return (
    <div className="mx-auto grid max-w-2xl gap-4 rounded-card border border-brand-100 bg-white p-8 text-center shadow-soft">
      <h3 className="font-heading text-2xl font-semibold text-brand-900">
        Plans sized to your community
      </h3>
      <p className="text-ink">
        Pricing depends on how many members you have and which features you need. Tell us about your
        community and we will send a clear quote, with no obligation.
      </p>
      <div>
        <Button asChild>
          <Link to={`/#${DEMO_ID}`}>Request a demo</Link>
        </Button>
      </div>
    </div>
  )
}

export default function Pricing() {
  const [billing, setBilling] = useState<Billing>('monthly')
  const { data, isPending, isError } = useQuery({
    queryKey: landingKeys.plans(),
    queryFn: fetchPublicPlans,
    staleTime: 10 * 60_000,
    meta: { silent: true }, // this section has its own fallback; never toast about it
  })
  const plans = data ?? []
  const showPlans = !isPending && !isError && plans.length > 0
  const highlightedIndex = plans.length >= 3 ? Math.floor(plans.length / 2) : -1

  return (
    <section aria-labelledby="pricing-title" className="py-16 sm:py-24">
      <div className="container grid gap-10">
        <SectionHeading
          id="pricing-title"
          eyebrow="Pricing"
          title="Simple plans, no surprises"
          intro={showPlans ? 'Choose monthly or yearly billing.' : undefined}
        />

        {isPending ? (
          <div role="status" aria-label="Loading plans" className="grid gap-5 md:grid-cols-3">
            {[0, 1, 2].map((n) => (
              <Skeleton key={n} className="h-72 rounded-card" />
            ))}
          </div>
        ) : showPlans ? (
          <>
            <div className="flex justify-center">
              <div
                role="group"
                aria-label="Billing period"
                className="flex gap-1 rounded border border-brand-100 bg-white p-1"
              >
                {(['monthly', 'yearly'] as const).map((option) => (
                  <Button
                    key={option}
                    size="sm"
                    variant={billing === option ? 'primary' : 'ghost'}
                    aria-pressed={billing === option}
                    onClick={() => {
                      setBilling(option)
                    }}
                  >
                    {option === 'monthly' ? 'Monthly' : 'Yearly'}
                  </Button>
                ))}
              </div>
            </div>
            <ul
              className={cn('grid gap-5', plans.length >= 3 ? 'md:grid-cols-3' : 'md:grid-cols-2')}
            >
              {plans.map((plan, index) => (
                <li key={plan.code}>
                  <Reveal delay={index * 0.06} className="h-full">
                    <PlanCard
                      plan={plan}
                      billing={billing}
                      highlighted={index === highlightedIndex}
                    />
                  </Reveal>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <StaticPricing />
        )}
      </div>
    </section>
  )
}
