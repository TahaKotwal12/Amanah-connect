import { useState, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { formatDate, formatDateTime } from '@/lib/dates'
import { formatMoney, sumMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import type { UseQueryResult } from '@tanstack/react-query'
import { MemberSheet } from '../components/MemberSheet'
import {
  useActivateMember,
  useDeactivateMember,
  useMember,
  useMemberCommunications,
  useMemberComplaints,
  useMemberPayments,
  useMemberStatement,
} from '../hooks/useMembers'
import { useWriteAccess } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'

const TABS = [
  { id: 'profile', label: 'Profile' },
  { id: 'dues', label: 'Dues statement' },
  { id: 'payments', label: 'Payments' },
  { id: 'complaints', label: 'Complaints' },
  { id: 'communication', label: 'Communication' },
] as const
type TabId = (typeof TABS)[number]['id']
const isTab = (value: string | null): value is TabId => TABS.some((tab) => tab.id === value)

/** The loading, error and empty states every tab shares. */
function TabBody<T>({
  query,
  label,
  isEmpty,
  empty,
  children,
}: {
  query: UseQueryResult<T>
  label: string
  isEmpty: (data: T) => boolean
  empty: ReactNode
  children: (data: T) => ReactNode
}) {
  if (query.isError) {
    return (
      <ErrorState
        headingLevel={3}
        error={query.error}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    )
  }
  if (query.data === undefined) {
    return <Skeleton className="h-40" role="status" aria-label={`Loading ${label}`} />
  }
  return isEmpty(query.data) ? empty : children(query.data)
}

const TEMPLATE_WORDS: Record<string, string> = {
  'member-welcome': 'Welcome email',
  'member-invite': 'Invitation',
  'invoice-issued': 'Bill',
  'invoice-reminder': 'Payment reminder',
  'invoice-overdue': 'Overdue reminder',
  receipt: 'Receipt',
  'payment-receipt': 'Receipt',
  announcement: 'Announcement',
}

function templateLabel(template: string): string {
  const known = TEMPLATE_WORDS[template]
  if (known) return known
  const words = template.replace(/[-_]/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="mt-0.5 font-medium text-brand-900">{children}</dd>
    </div>
  )
}

export default function MemberDetailPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const member = useMember(id)
  const statement = useMemberStatement(id)
  const payments = useMemberPayments(id)
  const complaints = useMemberComplaints(id)
  const communications = useMemberCommunications(id)
  const write = useWriteAccess()
  const settings = useSettings()
  const currency = settings.data?.currency ?? 'INR'
  const groupLabel = settings.data?.memberGroupLabel ?? 'Group'
  const deactivate = useDeactivateMember()
  const activate = useActivateMember()
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)
  const [activating, setActivating] = useState(false)
  const requested = params.get('tab')
  const tab: TabId = isTab(requested) ? requested : 'profile'
  const money = (amount: string) => formatMoney(amount, { currency })

  if (member.isError) {
    return (
      <>
        <PageHeader
          title="Member"
          breadcrumbs={[{ label: 'Members', to: paths.admin.members }, { label: 'Member' }]}
        />
        <ErrorState
          headingLevel={2}
          error={member.error}
          onRetry={() => void member.refetch()}
          retrying={member.isFetching}
        />
      </>
    )
  }
  if (!member.data) {
    return (
      <div role="status" aria-label="Loading member" className="grid gap-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-64" />
      </div>
    )
  }
  const data = member.data
  const active = data.status === 'ACTIVE'

  return (
    <>
      <PageHeader
        title={data.fullName}
        description={`Member ${data.memberNo}`}
        breadcrumbs={[{ label: 'Members', to: paths.admin.members }, { label: data.fullName }]}
        actions={
          <>
            <StatusBadge status={data.status} />
            <Button
              variant="secondary"
              onClick={() => setEditing(true)}
              disabled={!write.allowed}
              {...(write.reason ? { title: write.reason } : {})}
            >
              Edit
            </Button>
            {active ? (
              <Button
                variant="danger"
                onClick={() => setDeactivating(true)}
                disabled={!write.allowed}
                {...(write.reason ? { title: write.reason } : {})}
              >
                Deactivate
              </Button>
            ) : (
              <Button
                variant="secondary"
                onClick={() => setActivating(true)}
                disabled={!write.allowed}
                {...(write.reason ? { title: write.reason } : {})}
              >
                Activate
              </Button>
            )}
          </>
        }
      />

      <Tabs
        value={tab}
        onValueChange={(next) =>
          setParams(
            (previous) => {
              const updated = new URLSearchParams(previous)
              if (next === 'profile') updated.delete('tab')
              else updated.set('tab', next)
              return updated
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Member sections">
          {TABS.map(({ id: value, label }) => (
            <TabsTrigger key={value} value={value}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="profile">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Profile
              </CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <Fact label="Email">{data.email ?? 'Not given'}</Fact>
                <Fact label="Phone">{data.phone ?? 'Not given'}</Fact>
                <Fact label={groupLabel}>{data.group ?? 'None'}</Fact>
                <Fact label="Joined">
                  {data.joinedOn ? formatDate(data.joinedOn) : 'Not given'}
                </Fact>
                <Fact label="Emails">
                  {data.consentEmail ? 'Agrees to receive emails' : 'Has not agreed to emails'}
                </Fact>
                <Fact label="Added">{formatDate(data.createdAt)}</Fact>
              </dl>
              {data.statusReason ? (
                <p className="mt-4 rounded border border-brand-100 bg-brand-50 p-3 text-sm">
                  <span className="font-semibold">Why they are {data.status.toLowerCase()}: </span>
                  {data.statusReason}
                  {data.statusChangedAt ? ` (${formatDate(data.statusChangedAt)})` : ''}
                </p>
              ) : null}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="dues">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Dues statement
              </CardTitle>
            </CardHeader>
            <CardContent>
              <TabBody
                query={statement}
                label="the dues statement"
                isEmpty={(page) => page.items.length === 0}
                empty={
                  <EmptyState
                    headingLevel={3}
                    title="No bills yet"
                    description="Invoices for this member appear here."
                  />
                }
              >
                {(page) => {
                  const live = page.items.filter((invoice) => invoice.status !== 'CANCELLED')
                  return (
                    <>
                      <p className="mb-3 text-sm">
                        Outstanding:{' '}
                        <span className="font-semibold tabular-nums">
                          {money(sumMoney(live.map((invoice) => invoice.balance)))}
                        </span>
                      </p>
                      <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                          <caption className="sr-only">Invoices</caption>
                          <thead>
                            <tr className="border-b border-brand-100 text-muted">
                              <th scope="col" className="py-2 pr-4 font-medium">
                                Invoice
                              </th>
                              <th scope="col" className="py-2 pr-4 font-medium">
                                For
                              </th>
                              <th scope="col" className="py-2 pr-4 font-medium">
                                Due
                              </th>
                              <th scope="col" className="py-2 pr-4 text-right font-medium">
                                Amount
                              </th>
                              <th scope="col" className="py-2 pr-4 text-right font-medium">
                                Paid
                              </th>
                              <th scope="col" className="py-2 pr-4 text-right font-medium">
                                Balance
                              </th>
                              <th scope="col" className="py-2 font-medium">
                                Status
                              </th>
                            </tr>
                          </thead>
                          <tbody>
                            {page.items.map((invoice) => (
                              <tr key={invoice.id} className="border-b border-brand-50">
                                <td className="py-2 pr-4">{invoice.invoiceNo}</td>
                                <td className="py-2 pr-4">{invoice.description}</td>
                                <td className="py-2 pr-4">{formatDate(invoice.dueDate)}</td>
                                <td className="py-2 pr-4 text-right tabular-nums">
                                  {money(invoice.amount)}
                                </td>
                                <td className="py-2 pr-4 text-right tabular-nums">
                                  {money(invoice.amountPaid)}
                                </td>
                                <td className="py-2 pr-4 text-right tabular-nums">
                                  {money(invoice.balance)}
                                </td>
                                <td className="py-2">
                                  <StatusBadge status={invoice.status} />
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </>
                  )
                }}
              </TabBody>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="payments">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Payment history
              </CardTitle>
            </CardHeader>
            <CardContent>
              <TabBody
                query={payments}
                label="payments"
                isEmpty={(page) => page.items.length === 0}
                empty={
                  <EmptyState
                    headingLevel={3}
                    title="No payments yet"
                    description="Payments recorded for this member appear here."
                  />
                }
              >
                {(page) => (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <caption className="sr-only">Payments</caption>
                      <thead>
                        <tr className="border-b border-brand-100 text-muted">
                          <th scope="col" className="py-2 pr-4 font-medium">
                            Received
                          </th>
                          <th scope="col" className="py-2 pr-4 font-medium">
                            For
                          </th>
                          <th scope="col" className="py-2 pr-4 font-medium">
                            Method
                          </th>
                          <th scope="col" className="py-2 pr-4 text-right font-medium">
                            Amount
                          </th>
                          <th scope="col" className="py-2 font-medium">
                            Receipt
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {page.items.map((payment) => (
                          <tr key={payment.id} className="border-b border-brand-50">
                            <td className="py-2 pr-4">{formatDate(payment.receivedOn)}</td>
                            <td className="py-2 pr-4">
                              {payment.invoiceNo ?? payment.kind.toLowerCase()}
                              {payment.reversed ? (
                                <span className="ml-2">
                                  <StatusBadge status="reversed" />
                                </span>
                              ) : null}
                            </td>
                            <td className="py-2 pr-4">{payment.method}</td>
                            <td className="py-2 pr-4 text-right tabular-nums">
                              {money(payment.amount)}
                            </td>
                            <td className="py-2">{payment.receiptNo ?? ''}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </TabBody>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="complaints">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Complaints
              </CardTitle>
            </CardHeader>
            <CardContent>
              <TabBody
                query={complaints}
                label="complaints"
                isEmpty={(page) => page.items.length === 0}
                empty={
                  <EmptyState
                    headingLevel={3}
                    title="No complaints"
                    description="Complaints raised by or about this member appear here."
                  />
                }
              >
                {(page) => (
                  <ul className="divide-y divide-brand-100">
                    {page.items.map((complaint) => (
                      <li
                        key={complaint.id}
                        className="flex flex-wrap items-center justify-between gap-2 py-3"
                      >
                        <div className="min-w-0">
                          <Link
                            to={`${paths.admin.complaints}?open=${complaint.id}`}
                            className="font-medium text-brand-700 underline-offset-2 hover:underline"
                          >
                            {complaint.subject}
                          </Link>
                          <p className="text-sm text-muted">
                            Raised {formatDate(complaint.createdAt)}
                          </p>
                        </div>
                        <StatusBadge status={complaint.status} />
                      </li>
                    ))}
                  </ul>
                )}
              </TabBody>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="communication">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Emails sent to this member
              </CardTitle>
            </CardHeader>
            <CardContent>
              <TabBody
                query={communications}
                label="the email history"
                isEmpty={(items) => items.length === 0}
                empty={
                  <EmptyState
                    headingLevel={3}
                    title="No emails yet"
                    description={
                      data.email
                        ? 'Bills, receipts, reminders and announcements sent to this member appear here.'
                        : 'This member has no email address, so nothing is sent.'
                    }
                  />
                }
              >
                {(items) => (
                  <ol className="divide-y divide-brand-100">
                    {items.map((item) => (
                      <li
                        key={item.id}
                        className="flex flex-wrap items-center justify-between gap-2 py-3"
                      >
                        <div>
                          <p className="font-medium text-brand-900">
                            {templateLabel(item.template)}
                          </p>
                          <p className="text-sm text-muted">
                            {item.sentAt
                              ? `Sent ${formatDateTime(item.sentAt)}`
                              : `Queued ${formatDateTime(item.createdAt)}`}
                          </p>
                        </div>
                        <StatusBadge status={item.status} />
                      </li>
                    ))}
                  </ol>
                )}
              </TabBody>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <MemberSheet open={editing} onOpenChange={setEditing} member={data} />
      <ReasonDialog
        open={deactivating}
        onOpenChange={setDeactivating}
        title={`Deactivate ${data.fullName}?`}
        description="They stop receiving bills and reminders. Their history stays, and you can activate them again."
        confirmLabel="Deactivate"
        onSubmit={async (reason) => {
          await deactivate.mutateAsync({ id: data.id, reason })
          notify.success(`${data.fullName} is inactive`)
        }}
      />
      <ReasonDialog
        open={activating}
        onOpenChange={setActivating}
        title={`Activate ${data.fullName}?`}
        description="They get bills and reminders again."
        confirmLabel="Activate"
        variant="default"
        required={false}
        onSubmit={async (reason) => {
          await activate.mutateAsync({ id: data.id, body: reason ? { reason } : {} })
          notify.success(`${data.fullName} is active`)
        }}
      />
    </>
  )
}
