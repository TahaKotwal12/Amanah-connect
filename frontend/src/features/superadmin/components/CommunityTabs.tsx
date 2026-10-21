import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { Control } from 'react-hook-form'
import { Link } from 'react-router'
import { isApiError, toApiError } from '@/lib/apiError'
import { formatDate, formatDateTime, formatRelative } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form } from '@/shared/components/ui/form-field'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { Switch } from '@/shared/components/ui/switch'
import type { CommunityView } from '@/types'
import {
  editCommunitySchema,
  toEditForm,
  toUpdateRequest,
  type EditCommunityValues,
} from '../communitySchema'
import {
  useCommunityActivity,
  useCommunityThreads,
  useSubscriptionHistory,
  useUpdateCommunity,
} from '../hooks/useCommunities'
import { usePlans } from '../hooks/usePlans'
import { superadminKeys } from '../queryKeys'
import { ExpiryBadge } from './ExpiryBadge'
import { ProfileFields, type ProfileInput } from './ProfileFields'
import { RecordPaymentSheet } from './RecordPaymentSheet'

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="mt-0.5 font-medium text-brand-900">{children}</dd>
    </div>
  )
}

// ---- Overview ---------------------------------------------------------------------------------

/**
 * Everything here comes from the community record itself. The richer "counts and finance" view is
 * the support view, which is audited every time it is opened, so it is a deliberate click.
 */
export function OverviewTab({ community }: { community: CommunityView }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Community
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-2">
            <Fact label="Status">
              <StatusBadge status={community.status} />
            </Fact>
            <Fact label="Members">{community.memberCount.toLocaleString('en-IN')}</Fact>
            <Fact label="Web address name">{community.slug}</Fact>
            <Fact label="Created">{formatDate(community.createdAt)}</Fact>
            <Fact label="Financial year starts">Month {community.financialYearStartMonth}</Fact>
            <Fact label="Currency">{community.currency}</Fact>
          </dl>
          {community.statusReason ? (
            <p className="mt-4 rounded border border-brand-100 bg-brand-50 p-3 text-sm">
              <span className="font-semibold">Why it is {community.status.toLowerCase()}: </span>
              {community.statusReason}
              {community.statusChangedAt ? ` (${formatDate(community.statusChangedAt)})` : ''}
            </p>
          ) : null}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Plan
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-2">
            <Fact label="Plan">{community.plan.name}</Fact>
            <Fact label="Subscription">
              {community.subscriptionStatus ? (
                <StatusBadge status={community.subscriptionStatus} />
              ) : (
                'No payment recorded'
              )}
            </Fact>
            <Fact label="Plan ends">
              {community.subscriptionEndsOn ? formatDate(community.subscriptionEndsOn) : 'Not set'}
            </Fact>
            <Fact label="Two-step sign-in required">{community.require2fa ? 'Yes' : 'No'}</Fact>
          </dl>
        </CardContent>
      </Card>
    </div>
  )
}

// ---- Admin and contact ------------------------------------------------------------------------

export function ContactTab({
  community,
  onResetPassword,
}: {
  community: CommunityView
  onResetPassword: () => void
}) {
  const update = useUpdateCommunity()
  const client = useQueryClient()
  const original = toEditForm(community)
  const form = useZodForm(editCommunitySchema, { defaultValues: original })
  const [formError, setFormError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  // A newer version of the record (after a save, or another admin's edit) becomes the form.
  useEffect(() => {
    form.reset(toEditForm(community))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the record changes
  }, [community.version])

  async function submit(values: EditCommunityValues) {
    setFormError(null)
    const body = toUpdateRequest(values, original, community.version)
    if (Object.keys(body).length === 1) {
      notify.info('Nothing to save: no field was changed')
      return
    }
    try {
      await update.mutateAsync({ id: community.id, body })
      setStale(false)
      notify.success('Saved')
    } catch (error) {
      if (isApiError(error) && error.code === 'VERSION_CONFLICT') {
        setStale(true)
        return
      }
      const unmatched = applyApiFieldErrors(form, error, Object.keys(original))
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
      <Card>
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Contact details
          </CardTitle>
        </CardHeader>
        <CardContent>
          <Form {...form}>
            <form
              noValidate
              onSubmit={(event) => {
                void form.handleSubmit(submit)(event)
              }}
              className="grid gap-4"
            >
              {stale ? (
                <p
                  role="alert"
                  className="rounded border border-warning/30 bg-warning-50 p-3 text-sm"
                >
                  Someone else changed this community while you were editing. Your changes were not
                  saved.{' '}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setStale(false)
                      void client.invalidateQueries({
                        queryKey: superadminKeys.communities.detail(community.id),
                      })
                    }}
                  >
                    Load the latest
                  </Button>
                </p>
              ) : null}
              {formError ? (
                <p
                  role="alert"
                  className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
                >
                  {formError}
                </p>
              ) : null}
              <ProfileFields control={form.control as unknown as Control<ProfileInput>} />
              <Switch
                label="Require two-step sign-in for this community's administrators"
                checked={form.watch('require2fa')}
                onCheckedChange={(checked) =>
                  form.setValue('require2fa', checked, { shouldDirty: true })
                }
              />
              <p className="text-sm text-muted">
                A box cannot be emptied from here: leaving it blank keeps what is stored.
              </p>
              <div className="flex justify-end">
                <Button type="submit" loading={update.isPending}>
                  Save changes
                </Button>
              </div>
            </form>
          </Form>
        </CardContent>
      </Card>

      <Card className="self-start">
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Administrator
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3">
          {community.owner ? (
            <>
              <dl className="grid gap-3">
                <Fact label="Name">{community.owner.fullName}</Fact>
                <Fact label="Email">{community.owner.email}</Fact>
                <Fact label="Account">
                  <StatusBadge status={community.owner.status} />
                </Fact>
              </dl>
              <Button variant="secondary" onClick={onResetPassword}>
                {community.owner.status === 'INVITED'
                  ? 'Send the invitation again'
                  : 'Reset password'}
              </Button>
            </>
          ) : (
            <p className="text-sm text-muted">This community has no administrator yet.</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

// ---- Plan and subscription --------------------------------------------------------------------

export function PlanTab({ community }: { community: CommunityView }) {
  const plans = usePlans()
  const history = useSubscriptionHistory(community.id)
  const update = useUpdateCommunity()
  const [planId, setPlanId] = useState(community.plan.id)
  const [paymentOpen, setPaymentOpen] = useState(false)
  const options = (plans.data ?? [])
    .filter((plan) => plan.active || plan.id === community.plan.id)
    .map((plan) => ({ value: plan.id, label: `${plan.name} (${plan.code})` }))

  async function changePlan() {
    try {
      await update.mutateAsync({ id: community.id, body: { planId, version: community.version } })
      notify.success('Plan changed')
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Current plan
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="grid gap-1.5">
              <label htmlFor="community-plan" className="text-sm font-medium">
                Plan
              </label>
              <SimpleSelect
                id="community-plan"
                options={options}
                value={planId}
                onValueChange={setPlanId}
                className="w-64"
              />
            </div>
            <Button
              variant="secondary"
              onClick={() => void changePlan()}
              disabled={planId === community.plan.id}
              loading={update.isPending}
            >
              Change plan
            </Button>
            <Button onClick={() => setPaymentOpen(true)}>Record payment</Button>
          </div>
          <p className="text-sm text-muted">
            Changing the plan takes effect at once. Record a payment to start or extend the paid
            period.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle as="h2" className="text-lg">
            Payment history
          </CardTitle>
        </CardHeader>
        <CardContent>
          {history.isError ? (
            <ErrorState
              headingLevel={3}
              error={history.error}
              onRetry={() => void history.refetch()}
              retrying={history.isFetching}
            />
          ) : history.isLoading ? (
            <Skeleton className="h-32" role="status" aria-label="Loading payment history" />
          ) : history.data && history.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">Payment history</caption>
                <thead>
                  <tr className="border-b border-brand-100 text-muted">
                    <th scope="col" className="py-2 pr-4 font-medium">
                      Plan
                    </th>
                    <th scope="col" className="py-2 pr-4 font-medium">
                      Period
                    </th>
                    <th scope="col" className="py-2 pr-4 font-medium">
                      Amount
                    </th>
                    <th scope="col" className="py-2 pr-4 font-medium">
                      Received
                    </th>
                    <th scope="col" className="py-2 pr-4 font-medium">
                      Status
                    </th>
                    <th scope="col" className="py-2 font-medium">
                      Reference
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {history.data.map((line) => (
                    <tr key={line.id} className="border-b border-brand-50">
                      <td className="py-2 pr-4">{line.planName}</td>
                      <td className="py-2 pr-4">
                        {formatDate(line.periodStart)} to {formatDate(line.periodEnd)}
                      </td>
                      <td className="py-2 pr-4 tabular-nums">{formatMoney(line.amount)}</td>
                      <td className="py-2 pr-4">{line.paidOn ? formatDate(line.paidOn) : ''}</td>
                      <td className="py-2 pr-4">
                        <ExpiryBadge status={line.status} daysLeft={0} />
                      </td>
                      <td className="py-2">{line.reference ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              headingLevel={3}
              title="No payments yet"
              description="Record a payment to start this community's subscription."
              action={<Button onClick={() => setPaymentOpen(true)}>Record payment</Button>}
            />
          )}
        </CardContent>
      </Card>

      <RecordPaymentSheet
        open={paymentOpen}
        onOpenChange={setPaymentOpen}
        communityId={community.id}
      />
    </div>
  )
}

// ---- Activity ---------------------------------------------------------------------------------

export function ActivityTab({ community }: { community: CommunityView }) {
  const activity = useCommunityActivity(community.id)
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle as="h2" className="text-lg">
          Recent activity
        </CardTitle>
        <Button asChild variant="ghost" size="sm">
          <Link to={`${paths.superadmin.audit}?communityId=${community.id}`}>Full audit trail</Link>
        </Button>
      </CardHeader>
      <CardContent>
        {activity.isError ? (
          <ErrorState
            headingLevel={3}
            error={activity.error}
            onRetry={() => void activity.refetch()}
            retrying={activity.isFetching}
          />
        ) : activity.isLoading ? (
          <Skeleton className="h-40" role="status" aria-label="Loading activity" />
        ) : activity.data && activity.data.items.length > 0 ? (
          <ol className="divide-y divide-brand-100">
            {activity.data.items.map((entry) => (
              <li key={entry.id} className="py-3">
                <p className="font-medium text-brand-900">{entry.summary}</p>
                <p className="text-sm text-muted">
                  {entry.actorName ?? 'System'}, {formatDateTime(entry.at)}
                </p>
              </li>
            ))}
          </ol>
        ) : (
          <EmptyState headingLevel={3} title="No activity yet" />
        )}
      </CardContent>
    </Card>
  )
}

// ---- Support ----------------------------------------------------------------------------------

export function SupportTab({ community }: { community: CommunityView }) {
  const threads = useCommunityThreads(community.id)
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle as="h2" className="text-lg">
          Support conversations
        </CardTitle>
        <Button asChild variant="ghost" size="sm">
          <Link to={`${paths.superadmin.support}?communityId=${community.id}`}>Open inbox</Link>
        </Button>
      </CardHeader>
      <CardContent>
        {threads.isError ? (
          <ErrorState
            headingLevel={3}
            error={threads.error}
            onRetry={() => void threads.refetch()}
            retrying={threads.isFetching}
          />
        ) : threads.isLoading ? (
          <Skeleton className="h-40" role="status" aria-label="Loading conversations" />
        ) : threads.data && threads.data.items.length > 0 ? (
          <ul className="divide-y divide-brand-100">
            {threads.data.items.map((thread) => (
              <li
                key={thread.id}
                className="flex flex-wrap items-center justify-between gap-2 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium text-brand-900">{thread.subject}</p>
                  <p className="truncate text-sm text-muted">
                    {thread.lastMessagePreview ?? ''} {formatRelative(thread.lastMessageAt)}
                  </p>
                </div>
                <StatusBadge status={thread.status} />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            headingLevel={3}
            title="No conversations"
            description="This community has not contacted support."
          />
        )}
      </CardContent>
    </Card>
  )
}
