import { Plus } from 'lucide-react'
import { useState } from 'react'
import { PlanCard as WebsitePlanCard } from '@/features/landing/sections/Pricing'
import { notify } from '@/lib/notify'
import { formatMoney } from '@/lib/money'
import { Badge } from '@/shared/components/ui/badge'
import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { PlanView } from '@/types'
import { PlanEditorSheet } from '../components/PlanEditorSheet'
import { useSetPlanActive, usePlans } from '../hooks/usePlans'
import { FEATURE_FIELDS, LIMIT_FIELDS, formToPublicPlan, planToForm } from '../planDefinition'

function limitText(plan: PlanView, key: string): string {
  const value = plan.limits[key]
  return typeof value === 'number' ? value.toLocaleString('en-IN') : 'Unlimited'
}

function AdminPlanCard({
  plan,
  onEdit,
  onPreview,
  onToggle,
}: {
  plan: PlanView
  onEdit: () => void
  onPreview: () => void
  onToggle: () => void
}) {
  const features = FEATURE_FIELDS.filter(({ key }) => plan.features[key] === true)
  return (
    <Card className="flex flex-col">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={plan.active ? 'success' : 'neutral'}>
            {plan.active ? 'Available' : 'Not available'}
          </Badge>
          <Badge variant={plan.publicPlan ? 'brand' : 'neutral'}>
            {plan.publicPlan ? 'On website' : 'Hidden from website'}
          </Badge>
        </div>
        <CardTitle as="h2" className="text-xl">
          {plan.name}
        </CardTitle>
        <p className="font-mono text-xs text-muted">{plan.code}</p>
      </CardHeader>
      <CardContent className="flex flex-1 flex-col gap-4">
        <p className="tabular-nums">
          <span className="font-heading text-2xl font-semibold lining-nums text-brand-900">
            {formatMoney(plan.priceMonthly)}
          </span>
          <span className="text-muted"> / month</span>
          <br />
          <span className="text-sm text-muted">{formatMoney(plan.priceYearly)} / year</span>
        </p>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          {LIMIT_FIELDS.map(({ key, label }) => (
            <div key={key} className="contents">
              <dt className="text-muted">{label}</dt>
              <dd className="text-right tabular-nums">{limitText(plan, key)}</dd>
            </div>
          ))}
        </dl>
        <p className="text-sm">
          <span className="text-muted">Features: </span>
          {features.length > 0 ? features.map(({ label }) => label).join(', ') : 'None'}
        </p>
        <p className="text-sm text-muted">
          {plan.communityCount === 0
            ? 'No communities on this plan'
            : `${plan.communityCount.toLocaleString('en-IN')} ${plan.communityCount === 1 ? 'community' : 'communities'} on this plan`}
        </p>
        <div className="mt-auto flex flex-wrap gap-2 pt-2">
          <Button size="sm" onClick={onEdit} aria-label={`Edit ${plan.name}`}>
            Edit
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={onPreview}
            aria-label={`Preview ${plan.name} on the website`}
          >
            Preview
          </Button>
          <Button
            size="sm"
            variant={plan.active ? 'danger' : 'secondary'}
            onClick={onToggle}
            aria-label={`${plan.active ? 'Stop offering' : 'Offer'} ${plan.name}`}
          >
            {plan.active ? 'Stop offering' : 'Offer again'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

export default function PlansPage() {
  const plans = usePlans()
  const setActive = useSetPlanActive()
  const [editing, setEditing] = useState<PlanView | 'new' | null>(null)
  const [previewing, setPreviewing] = useState<PlanView | null>(null)
  const [deactivating, setDeactivating] = useState<PlanView | null>(null)

  async function toggle(plan: PlanView, active: boolean) {
    try {
      await setActive.mutateAsync({ plan, active })
      notify.success(
        active ? `${plan.name} is available again` : `${plan.name} is no longer offered`,
      )
    } catch (error) {
      notify.apiError(error)
      throw error
    }
  }

  return (
    <>
      <PageHeader
        title="Plans"
        description="What each plan costs and includes. Changes apply to new subscriptions and to the website."
        actions={
          <Button onClick={() => setEditing('new')}>
            <Plus aria-hidden="true" className="size-4" />
            New plan
          </Button>
        }
      />

      {plans.isError ? (
        <ErrorState
          headingLevel={2}
          error={plans.error}
          onRetry={() => void plans.refetch()}
          retrying={plans.isFetching}
        />
      ) : plans.isLoading ? (
        <div
          className="grid gap-4 md:grid-cols-2 xl:grid-cols-3"
          role="status"
          aria-label="Loading plans"
        >
          <Skeleton className="h-72" />
          <Skeleton className="h-72" />
          <Skeleton className="h-72" />
        </div>
      ) : plans.data && plans.data.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {plans.data.map((plan) => (
            <AdminPlanCard
              key={plan.id}
              plan={plan}
              onEdit={() => setEditing(plan)}
              onPreview={() => setPreviewing(plan)}
              onToggle={() =>
                plan.active ? setDeactivating(plan) : void toggle(plan, true).catch(() => undefined)
              }
            />
          ))}
        </div>
      ) : (
        <EmptyState
          title="No plans yet"
          description="Create the first plan to start adding communities."
          action={<Button onClick={() => setEditing('new')}>New plan</Button>}
        />
      )}

      <PlanEditorSheet
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        plan={editing === 'new' || editing === null ? undefined : editing}
      />

      <Dialog open={previewing !== null} onOpenChange={(open) => !open && setPreviewing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{previewing?.name} on the website</DialogTitle>
            <DialogDescription>
              {previewing?.publicPlan
                ? 'This is the card visitors see on the pricing section.'
                : 'This plan is hidden from the website. This is how its card would look.'}
            </DialogDescription>
          </DialogHeader>
          {previewing ? (
            <WebsitePlanCard
              plan={formToPublicPlan(planToForm(previewing))}
              billing="monthly"
              highlighted={false}
            />
          ) : null}
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deactivating !== null}
        onOpenChange={(open) => !open && setDeactivating(null)}
        variant="danger"
        title={`Stop offering ${deactivating?.name ?? 'this plan'}?`}
        description="It can no longer be chosen for new communities or payments, and it disappears from the website. Communities already on it keep it."
        confirmLabel="Stop offering"
        onConfirm={() => (deactivating ? toggle(deactivating, false) : undefined)}
      />
    </>
  )
}
