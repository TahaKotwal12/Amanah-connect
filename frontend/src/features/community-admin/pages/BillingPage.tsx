import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { formatMoney } from '@/lib/money'
import { Combobox } from '@/shared/components/ui/combobox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import type { InvoiceView } from '@/types'
// Phase 1: bills come from dividing an expense (Budget), so fee plans, generating invoices, new invoices and
// the defaulters list are switched off here. Their components and API stay in the code.
// import { DefaultersTab, FeePlansTab } from '../components/BillingTabs'
// import { GenerateInvoicesWizard } from '../components/GenerateInvoicesWizard'
// import { NewInvoiceSheet } from '../components/NewInvoiceSheet'
import { ReceiptsTab } from '../components/BillingTabs'
import { InvoiceActionDialogs, type InvoiceActionTarget } from '../components/InvoiceActionDialogs'
import { InvoicesTab } from '../components/InvoicesTab'
import { useInvoices } from '../hooks/useBilling'
import { useWriteAccess } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'

const TABS = [
  { id: 'invoices', label: 'Invoices' },
  // { id: 'fee-plans', label: 'Fee plans' },
  { id: 'receipts', label: 'Receipts' },
  // { id: 'defaulters', label: 'Defaulters' },
] as const
type TabId = (typeof TABS)[number]['id']
const isTab = (value: string | null): value is TabId => TABS.some((tab) => tab.id === value)

/** "Record payment" from the dashboard: choose which unpaid invoice, then record against it. */
function PickInvoiceDialog({
  open,
  onOpenChange,
  onPick,
  currency,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (invoice: InvoiceView) => void
  currency: string
}) {
  const invoices = useInvoices({ outstandingOnly: true, size: 100, sort: ['dueDate,asc'] })
  const items = invoices.data?.items ?? []
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Which invoice was paid?</DialogTitle>
          <DialogDescription>
            Choose one that is still owed. Search by member or number.
          </DialogDescription>
        </DialogHeader>
        <Combobox
          aria-label="Unpaid invoice"
          options={items.map((invoice) => ({
            value: invoice.id,
            label: `${invoice.invoiceNo}, ${invoice.memberName}, owes ${formatMoney(invoice.balance, { currency })}`,
          }))}
          onValueChange={(id) => {
            const picked = items.find((invoice) => invoice.id === id)
            if (picked) onPick(picked)
          }}
          placeholder={
            invoices.isLoading
              ? 'Loading invoices'
              : items.length === 0
                ? 'Nothing is owed'
                : 'Choose an invoice'
          }
          searchPlaceholder="Search invoices"
        />
      </DialogContent>
    </Dialog>
  )
}

export default function BillingPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const settings = useSettings()
  const currency = settings.data?.currency ?? 'INR'
  const requested = params.get('tab')
  const tab: TabId = isTab(requested) ? requested : 'invoices'
  const [action, setAction] = useState<InvoiceActionTarget | null>(null)

  // ?new=payment opens the invoice picker (the dashboard's "Record payment" quick action).
  const wanted = params.get('new')
  function clearNew() {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        next.delete('new')
        return next
      },
      { replace: true },
    )
  }

  return (
    <>
      <PageHeader
        title="Billing"
        description="Bills for members' shares of an expense, the cash received, and receipts. To bill members, add an expense in Budget and choose Collect from members."
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <Tabs
        value={tab}
        onValueChange={(next) =>
          setParams(
            () => {
              // Filters belong to a tab: a new tab starts clean.
              const updated = new URLSearchParams()
              if (next !== 'invoices') updated.set('tab', next)
              return updated
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Billing sections">
          {TABS.map(({ id, label }) => (
            <TabsTrigger key={id} value={id}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="invoices">
          <InvoicesTab currency={currency} />
        </TabsContent>
        {/* <TabsContent value="fee-plans"><FeePlansTab currency={currency} onGenerate={...} /></TabsContent> */}
        <TabsContent value="receipts">
          <ReceiptsTab currency={currency} />
        </TabsContent>
        {/* <TabsContent value="defaulters"><DefaultersTab currency={currency} /></TabsContent> */}
      </Tabs>

      <PickInvoiceDialog
        open={wanted === 'payment' && action === null}
        onOpenChange={(open) => !open && clearNew()}
        currency={currency}
        onPick={(invoice) => {
          clearNew()
          setAction({ kind: 'pay', invoice })
        }}
      />
      <InvoiceActionDialogs target={action} onClose={() => setAction(null)} currency={currency} />
    </>
  )
}
