import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  CancelInvoiceRequest,
  CreateFeePlanRequest,
  CreateInvoiceRequest,
  DefaulterView,
  FeePlanView,
  GenerateInvoicesRequest,
  GeneratePreview,
  GenerateResult,
  InvoiceDetail,
  InvoiceTotals,
  InvoiceView,
  PageResponse,
  PayLinkView,
  PaymentResult,
  PaymentView,
  ReceiptView,
  RecordPaymentRequest,
  RemindResult,
  ReversePaymentRequest,
  UpdateFeePlanRequest,
  Uuid,
} from '@/types'
import type { PageParams } from '@/types'
import type { InvoiceFilters, InvoiceListParams, ReceiptListParams } from '../queryKeys'

// ---- invoices ---------------------------------------------------------------------------------

export async function fetchInvoices(params: InvoiceListParams): Promise<PageResponse<InvoiceView>> {
  const { data } = await api.get<PageResponse<InvoiceView>>('/community/invoices', {
    params: toQuery(params),
  })
  return data
}

/** Sums over everything the filters match, not just the page on screen. */
export async function fetchInvoiceTotals(filters: InvoiceFilters): Promise<InvoiceTotals> {
  const { data } = await api.get<InvoiceTotals>('/community/invoices/totals', {
    params: toQuery(filters),
  })
  return data
}

export async function fetchInvoice(id: Uuid): Promise<InvoiceDetail> {
  const { data } = await api.get<InvoiceDetail>(`/community/invoices/${id}`)
  return data
}

export async function createInvoice(body: CreateInvoiceRequest): Promise<InvoiceView> {
  const { data } = await api.post<InvoiceView>('/community/invoices', body)
  return data
}

export async function cancelInvoice(id: Uuid, body: CancelInvoiceRequest): Promise<InvoiceView> {
  const { data } = await api.post<InvoiceView>(`/community/invoices/${id}/cancel`, body)
  return data
}

export async function resendBill(id: Uuid): Promise<void> {
  await api.post(`/community/invoices/${id}/resend`)
}

/** A new expiring link to the public payment page. It is shown once. */
export async function createPayLink(id: Uuid): Promise<PayLinkView> {
  const { data } = await api.post<PayLinkView>(`/community/invoices/${id}/pay-link`)
  return data
}

/** The UPI QR for the balance, as a PNG. 409 UPI_NOT_CONFIGURED until the community sets up UPI. */
export async function fetchInvoiceQr(id: Uuid): Promise<Blob> {
  const { data } = await api.get<Blob>(`/community/invoices/${id}/qr`, { responseType: 'blob' })
  return data
}

// ---- payments ---------------------------------------------------------------------------------

/**
 * Records money that arrived. The Idempotency-Key makes a retry safe: the same key with the same
 * request returns the original result (replayed), the same key with a different one is refused.
 */
export async function recordPayment(
  invoiceId: Uuid,
  body: RecordPaymentRequest,
  idempotencyKey: string,
): Promise<PaymentResult> {
  const { data } = await api.post<PaymentResult>(
    `/community/invoices/${invoiceId}/payments`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return data
}

export async function reversePayment(
  paymentId: Uuid,
  body: ReversePaymentRequest,
  idempotencyKey: string,
): Promise<PaymentResult> {
  const { data } = await api.post<PaymentResult>(`/community/payments/${paymentId}/reverse`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return data
}

export async function fetchPayment(id: Uuid): Promise<PaymentView> {
  const { data } = await api.get<PaymentView>(`/community/payments/${id}`)
  return data
}

// ---- receipts ---------------------------------------------------------------------------------

export async function fetchReceipts(params: ReceiptListParams): Promise<PageResponse<ReceiptView>> {
  const { data } = await api.get<PageResponse<ReceiptView>>('/community/receipts', {
    params: toQuery(params),
  })
  return data
}

export async function fetchReceiptPdf(id: Uuid): Promise<Blob> {
  const { data } = await api.get<Blob>(`/community/receipts/${id}/pdf`, { responseType: 'blob' })
  return data
}

export async function resendReceipt(id: Uuid): Promise<void> {
  await api.post(`/community/receipts/${id}/resend`)
}

// ---- fee plans and billing runs ---------------------------------------------------------------

export async function fetchFeePlans(): Promise<FeePlanView[]> {
  const { data } = await api.get<FeePlanView[]>('/community/fee-plans')
  return data
}

export async function createFeePlan(body: CreateFeePlanRequest): Promise<FeePlanView> {
  const { data } = await api.post<FeePlanView>('/community/fee-plans', body)
  return data
}

export async function updateFeePlan(id: Uuid, body: UpdateFeePlanRequest): Promise<FeePlanView> {
  const { data } = await api.patch<FeePlanView>(`/community/fee-plans/${id}`, body)
  return data
}

/** What billing would do, without doing it: nothing is created or emailed. */
export async function previewGeneration(body: GenerateInvoicesRequest): Promise<GeneratePreview> {
  const { data } = await api.post<GeneratePreview>('/community/invoices/generate/preview', body)
  return data
}

/** Safe to repeat: nobody is billed twice for the same plan and period. */
export async function generateInvoices(body: GenerateInvoicesRequest): Promise<GenerateResult> {
  const { data } = await api.post<GenerateResult>('/community/invoices/generate', body)
  return data
}

// ---- defaulters -------------------------------------------------------------------------------

export async function fetchDefaulters(params: PageParams): Promise<PageResponse<DefaulterView>> {
  const { data } = await api.get<PageResponse<DefaulterView>>('/community/reminders/defaulters', {
    params: toQuery(params),
  })
  return data
}

/** An overdue notice for every overdue invoice; each invoice is reminded once a day at most. */
export async function remindAllOverdue(): Promise<RemindResult> {
  const { data } = await api.post<RemindResult>('/community/reminders/overdue')
  return data
}
