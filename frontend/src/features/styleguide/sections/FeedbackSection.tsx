import { Banknote, Users } from 'lucide-react'
import { useState } from 'react'
import { formatMoney } from '@/lib/money'
import {
  Breadcrumbs,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
  ConfirmDialog,
  CopyField,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  QrCard,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
  Skeleton,
  StatCard,
  Stepper,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  notify,
} from '@/shared/components/ui'
import { Demo, Section } from '../Section'

const STEPS = [
  { id: 'details', label: 'Details', description: 'Name and contact' },
  { id: 'plan', label: 'Plan', description: 'Choose a plan' },
  { id: 'owner', label: 'Owner', description: 'First admin' },
  { id: 'review', label: 'Review' },
]

export function FeedbackSection() {
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [dangerOpen, setDangerOpen] = useState(false)
  const [page, setPage] = useState(2)
  const [pageSize, setPageSize] = useState(20)
  const [step, setStep] = useState(1)

  return (
    <Section id="surfaces" title="Surfaces, overlays and feedback">
      <Demo title="PageHeader and Breadcrumbs" column>
        <div className="rounded-card border border-brand-100 bg-white p-5">
          <PageHeader
            title="Members"
            description="Everyone in your community, with their dues and contact details."
            breadcrumbs={[{ label: 'Dashboard', to: '/admin/dashboard' }, { label: 'Members' }]}
            actions={<Button>Add member</Button>}
            className="pb-0"
            headingLevel={4}
          />
        </div>
        <Breadcrumbs
          label="Breadcrumb example"
          items={[
            { label: 'Communities', to: '/superadmin/communities' },
            { label: 'Lotus Residents', to: '/superadmin/communities/1' },
            { label: 'Billing' },
          ]}
        />
      </Demo>

      <Demo title="Card" column>
        <Card>
          <CardHeader>
            <CardTitle as="h4">Collection this month</CardTitle>
            <CardDescription>April 2026</CardDescription>
          </CardHeader>
          <CardContent>
            <p className="font-heading text-3xl text-brand-900">{formatMoney('1250000.5')}</p>
          </CardContent>
          <CardFooter>
            <Button variant="secondary" size="sm">
              View invoices
            </Button>
          </CardFooter>
        </Card>
      </Demo>

      <Demo title="StatCard: trends (up good, down bad, flat), no trend, loading" column>
        <div className="grid gap-4 sm:grid-cols-2">
          <StatCard
            label="Collected this month"
            value={formatMoney('1250000.5')}
            icon={<Banknote className="size-5" />}
            trend={{ direction: 'up', value: '12%', comparison: 'vs last month', good: true }}
          />
          <StatCard
            label="Overdue"
            value={formatMoney('84500')}
            trend={{ direction: 'up', value: '8%', comparison: 'vs last month', good: false }}
          />
          <StatCard
            label="Active members"
            value="248"
            icon={<Users className="size-5" />}
            trend={{ direction: 'flat', value: '0', comparison: 'vs last month' }}
          />
          <StatCard label="Open complaints" value="3" hint="1 past its target date" />
          <StatCard label="Loading" value="" loading />
        </div>
      </Demo>

      <Demo title="Skeleton" column>
        <div role="status" aria-label="Loading example" className="grid gap-2">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </div>
      </Demo>

      <Demo title="EmptyState (default art, custom slot, with action)" column>
        <EmptyState
          headingLevel={4}
          title="No members yet"
          description="Add your first member, or invite them to register with a link."
          action={<Button>Add member</Button>}
        />
        <EmptyState
          headingLevel={4}
          title="All caught up"
          description="There are no pending registrations."
          illustration={<Users className="size-10" strokeWidth={1.5} />}
        />
      </Demo>

      <Demo title="ErrorState (message, from an error, with retry)" column>
        <ErrorState
          message="The member list could not be loaded."
          onRetry={() => notify.info('Retrying')}
        />
        <ErrorState headingLevel={4} title="Not allowed" error={new Error('x')} />
      </Demo>

      <Demo title="Tabs">
        <Tabs defaultValue="overview" className="w-full max-w-xl">
          <TabsList aria-label="Community sections">
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="plan">Plan</TabsTrigger>
            <TabsTrigger value="activity">Activity</TabsTrigger>
            <TabsTrigger value="locked" disabled>
              Locked
            </TabsTrigger>
          </TabsList>
          <TabsContent value="overview">
            Overview content. Use the arrow keys to move between tabs.
          </TabsContent>
          <TabsContent value="plan">Plan content.</TabsContent>
          <TabsContent value="activity">Activity content.</TabsContent>
        </Tabs>
      </Demo>

      <Demo title="Dialog, ConfirmDialog (default, danger with typed confirmation), Sheet">
        <Dialog>
          <DialogTrigger asChild>
            <Button variant="secondary">Open dialog</Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Record a payment</DialogTitle>
              <DialogDescription>
                Focus stays inside until you close it. Escape closes it too.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button>Save</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        <Button
          variant="secondary"
          onClick={() => {
            setConfirmOpen(true)
          }}
        >
          Confirm dialog
        </Button>
        <ConfirmDialog
          open={confirmOpen}
          onOpenChange={setConfirmOpen}
          title="Send reminders to 12 members?"
          description="Each member with an overdue invoice gets one email."
          confirmLabel="Send reminders"
          onConfirm={async () => {
            await new Promise((resolve) => setTimeout(resolve, 800))
            notify.success('Reminders queued')
          }}
        />

        <Button
          variant="danger"
          onClick={() => {
            setDangerOpen(true)
          }}
        >
          Danger confirm
        </Button>
        <ConfirmDialog
          open={dangerOpen}
          onOpenChange={setDangerOpen}
          variant="danger"
          confirmText="Lotus Residents"
          title="Suspend this community?"
          description="Its admins can sign in but cannot change anything until it is activated again."
          confirmLabel="Suspend community"
          onConfirm={() => {
            notify.success('Community suspended')
          }}
        />

        <Sheet>
          <SheetTrigger asChild>
            <Button variant="secondary">Open sheet</Button>
          </SheetTrigger>
          <SheetContent>
            <SheetHeader>
              <SheetTitle>Add member</SheetTitle>
              <SheetDescription>A slide-over for forms beside a list.</SheetDescription>
            </SheetHeader>
          </SheetContent>
        </Sheet>
      </Demo>

      <Demo title="Pagination (server style, zero-based page)" column>
        <Pagination
          label="Pagination example"
          page={page}
          pageSize={pageSize}
          total={347}
          onPageChange={setPage}
          onPageSizeChange={(size) => {
            setPageSize(size)
            setPage(0)
          }}
        />
        <Pagination
          label="Empty pagination example"
          page={0}
          pageSize={20}
          total={0}
          onPageChange={() => undefined}
        />
      </Demo>

      <Demo title="Stepper" column>
        <Stepper steps={STEPS} current={step} label="Create community" />
        <div className="flex gap-2">
          <Button
            variant="secondary"
            size="sm"
            disabled={step === 0}
            onClick={() => {
              setStep(step - 1)
            }}
          >
            Back
          </Button>
          <Button
            size="sm"
            disabled={step === STEPS.length - 1}
            onClick={() => {
              setStep(step + 1)
            }}
          >
            Next
          </Button>
        </div>
      </Demo>

      <Demo title="CopyField" column>
        <CopyField label="Invite link" value="https://app.example.test/join/Zk3x9QmN2vB7" />
      </Demo>

      <Demo title="QrCard (drawn in the browser)">
        <QrCard
          title="Scan to join"
          caption="Members scan this to open the registration page."
          value="https://app.example.test/join/Zk3x9QmN2vB7"
          alt="QR code that opens the registration page"
          downloadName="join-qr"
        />
      </Demo>
    </Section>
  )
}
