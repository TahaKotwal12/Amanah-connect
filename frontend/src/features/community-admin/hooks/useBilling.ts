import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type {
  CreateFeePlanRequest,
  CreateInvoiceRequest,
  GenerateInvoicesRequest,
  PageParams,
  RecordPaymentRequest,
  ReversePaymentRequest,
  UpdateFeePlanRequest,
  Uuid,
} from '@/types'
import {
  cancelInvoice,
  createFeePlan,
  createInvoice,
  createPayLink,
  fetchDefaulters,
  fetchFeePlans,
  fetchInvoice,
  fetchInvoiceQr,
  fetchInvoiceTotals,
  fetchInvoices,
  fetchReceipts,
  generateInvoices,
  previewGeneration,
  recordPayment,
  remindAllOverdue,
  resendBill,
  resendReceipt,
  reversePayment,
  updateFeePlan,
} from '../api/billing'
import {
  communityAdminKeys,
  type InvoiceFilters,
  type InvoiceListParams,
  type ReceiptListParams,
} from '../queryKeys'

export function useInvoices(params: InvoiceListParams) {
  return useQuery({
    queryKey: communityAdminKeys.invoices.list(params),
    queryFn: () => fetchInvoices(params),
    placeholderData: keepPreviousData,
  })
}

export function useInvoiceTotals(filters: InvoiceFilters) {
  return useQuery({
    queryKey: communityAdminKeys.invoices.totals(filters),
    queryFn: () => fetchInvoiceTotals(filters),
    placeholderData: keepPreviousData,
  })
}

export function useInvoice(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.invoices.detail(id),
    queryFn: () => fetchInvoice(id),
  })
}

/** The QR is made for the current balance, so it is never reused after a payment. */
export function useInvoiceQr(id: Uuid, enabled: boolean) {
  return useQuery({
    queryKey: [...communityAdminKeys.invoices.detail(id), 'qr'] as const,
    queryFn: () => fetchInvoiceQr(id),
    enabled,
    staleTime: 0,
    gcTime: 0,
    retry: false,
    meta: { silent: true },
  })
}

export function useReceipts(params: ReceiptListParams) {
  return useQuery({
    queryKey: communityAdminKeys.receipts.list(params),
    queryFn: () => fetchReceipts(params),
    placeholderData: keepPreviousData,
  })
}

export function useFeePlans() {
  return useQuery({
    queryKey: communityAdminKeys.feePlans.lists(),
    queryFn: fetchFeePlans,
  })
}

export function useDefaulters(params: PageParams) {
  return useQuery({
    queryKey: communityAdminKeys.defaulters.list(params),
    queryFn: () => fetchDefaulters(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Money moved, so everything that shows money is stale: invoices and their totals, receipts,
 * the ledger, defaulters and the dashboard.
 */
function refreshMoney(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: communityAdminKeys.invoices.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.payments.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.receipts.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.defaulters.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.ledger.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
    client.invalidateQueries({ queryKey: communityAdminKeys.members.all }),
  ])
}

/** The idempotency key is the caller's: it stays the same for a retry of the same payment. */
export function useRecordPayment() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({
      invoiceId,
      body,
      idempotencyKey,
    }: {
      invoiceId: Uuid
      body: RecordPaymentRequest
      idempotencyKey: string
    }) => recordPayment(invoiceId, body, idempotencyKey),
    onSuccess: () => refreshMoney(client),
  })
}

export function useReversePayment() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({
      paymentId,
      body,
      idempotencyKey,
    }: {
      paymentId: Uuid
      body: ReversePaymentRequest
      idempotencyKey: string
    }) => reversePayment(paymentId, body, idempotencyKey),
    onSuccess: () => refreshMoney(client),
  })
}

export function useCancelInvoice() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: Uuid; reason: string }) => cancelInvoice(id, { reason }),
    onSuccess: () => refreshMoney(client),
  })
}

export function useCreateInvoice() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateInvoiceRequest) => createInvoice(body),
    onSuccess: () => refreshMoney(client),
  })
}

export function useResendBill() {
  return useMutation({ mutationFn: (id: Uuid) => resendBill(id) })
}

export function useCreatePayLink() {
  return useMutation({ mutationFn: (id: Uuid) => createPayLink(id) })
}

export function useResendReceipt() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: Uuid) => resendReceipt(id),
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.receipts.all }),
  })
}

export function useCreateFeePlan() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateFeePlanRequest) => createFeePlan(body),
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.feePlans.all }),
  })
}

export function useUpdateFeePlan() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateFeePlanRequest }) => updateFeePlan(id, body),
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.feePlans.all }),
  })
}

export function usePreviewGeneration() {
  return useMutation({ mutationFn: (body: GenerateInvoicesRequest) => previewGeneration(body) })
}

export function useGenerateInvoices() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: GenerateInvoicesRequest) => generateInvoices(body),
    retry: false,
    onSuccess: () =>
      Promise.all([
        refreshMoney(client),
        client.invalidateQueries({ queryKey: communityAdminKeys.feePlans.all }),
      ]),
  })
}

export function useRemindOverdue() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: remindAllOverdue,
    retry: false,
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.defaulters.all }),
  })
}
