# Finance: invoice → payment → receipt → ledger

Everything under `/api/v1/community/**` acts on the signed-in admin's community only (see [`tenancy.md`](tenancy.md)).
There is **no payment gateway**: members pay however they like (cash, UPI app, bank, cheque) and an admin *confirms*
the payment by recording it. The `PaymentGateway` interface has one implementation, `ManualGateway`; a real gateway
would plug in behind the same interface later.

## The flow

```
fee plan ──generate──▶ invoice (ISSUED) ──record payment──▶ payment ──▶ receipt (PDF, emailed)
 (or manual invoice)        │  bill email + pay link              │
                            │                                     └──▶ ledger entry (INCOME, source PAYMENT)
                            └─ PARTIAL → PAID, or → OVERDUE after the due date
reverse payment ──▶ negative payment + negative ledger entry; invoice reopens; receipt marked REVERSED
```

### 1. Fee plans and invoices

* A **fee plan** has a kind (MAINTENANCE, SUBSCRIPTION, DONATION, EVENT, FINE, OTHER), an amount, a frequency
  (MONTHLY, QUARTERLY, YEARLY, ONE_TIME), a due day (default 10) and an audience: `ALL_ACTIVE`, a `GROUP`, or
  `SELECTED` members.
* `POST /community/invoices/generate {feePlanId, period?}` bills a period. **Idempotent**: the key is
  `(fee plan, member, period)` and is enforced by a unique index, so running it twice (or two admins at once)
  creates each invoice exactly once; the response says how many were created and how many already existed.
  Periods are `2026-05` (monthly), `2026-Q2` (quarterly, calendar), `2026-27` (yearly, the community's financial
  year) or a free label for ONE_TIME (which also needs an explicit due date).
* Invoices are numbered `INV-2026-27/000123` from a per-community, per-financial-year counter taken under a row lock
  inside the same transaction as the insert, so **numbers have no gaps** even under concurrency (a rolled-back
  transaction gives its number back). A bill email with a payment link is queued per member (needs an email address
  and email consent; counted against the plan's email quota).
* One-off invoices: `POST /community/invoices` (optionally `draft: true`; `POST /{id}/issue` numbers and issues it).
  Donations: `POST /community/donations` for a member **or** an anonymous donor name.
* Statuses: `DRAFT`, `ISSUED`, `PARTIAL`, `PAID`, `OVERDUE`, `CANCELLED`. They are derived server-side from the
  amount paid and the due date (a late, part-paid invoice is OVERDUE); clients never set them. Cancelling needs a
  reason, is audited, and is refused once money has been received (reverse the payments first).
* A daily ShedLock job flips ISSUED/PARTIAL invoices past their due date to OVERDUE (00:30 IST) and another bills
  plans with `autoGenerate` on (06:00 IST). Both are safe to re-run. Auto-billing is opt-in per plan and starts
  with the *next* period after the plan is created or switched on.

### 2. Payments

`POST /community/invoices/{id}/payments`

```json
{ "amount": "1000.00", "method": "UPI", "reference": "UTR123456", "receivedOn": "2026-05-12" }
```

* Money is a **string** in JSON, `NUMERIC(14,2)` in the database and `BigDecimal` in Java.
* Partial payments are fine. **Overpayment is rejected** (`422 OVERPAYMENT`).
* The invoice row is locked (`SELECT … FOR UPDATE`) and carries a `@Version`, so two admins paying at once are
  serialised and the balance can never go negative.
* Send an **`Idempotency-Key`** header to make a retry safe: the same key with the same request returns the first
  result (`200`, header `Idempotent-Replayed: true`); the same key with a different request is
  `422 IDEMPOTENCY_KEY_REUSED`. Without a key, nothing is deduplicated.
* In one transaction the payment: updates `amount_paid` and the status, takes the next receipt number
  (`RCP-2026-27/000045`, from the *received-on* date's financial year), and posts an INCOME ledger entry linked to
  the payment. The receipt PDF (logo, community, address, receipt number, member, amount in figures and words,
  method, date, signature line) is rendered **after** commit, stored in S3 and emailed; if storage is down the PDF is
  rendered on demand, so a payment is never lost to a PDF problem.

### 3. Reversal (nothing is ever deleted)

`POST /community/payments/{id}/reverse {"reason": "..."}` writes an offsetting **negative payment** and a **negative
ledger entry**, restores the invoice balance and status, and marks the receipt REVERSED (the receipt row, its number
and its PDF stay; the PDF is re-rendered with a REVERSED banner). A payment can be reversed once. Reversing a
reversal is refused.

### 4. Ledger

Categories are seeded per community from defaults and can be added, renamed and hidden. Payment postings go to
system categories (Membership Fees, Donations, Events, Other income) chosen by the fee kind; these can be renamed but
not hidden. Manual entries (`/community/ledger/entries`) are INCOME or EXPENSE with a category, date, amount, notes
and an optional attachment (`POST /ledger/attachments/upload-url` returns a presigned S3 `PUT`; PNG, JPEG, WebP or
PDF, 5 MB). Entries from payments are read-only; entries are corrected by reversal (a negative twin with a reason).

`GET /community/ledger/summary?from&to`: total income, expense, net, by category, by month, plus the opening
balance (a community setting) and the closing balance. Because every payment posts exactly one ledger entry
(and every reversal its negative), **total payment income always equals the payment-sourced ledger income**; the
tests assert it.

### 5. UPI QR and the payment link

* `GET /community/invoices/{id}/qr` returns a PNG of
  `upi://pay?pa=<upi_id>&pn=<payee>&am=<balance>&cu=INR&tn=<invoice no>` (needs a UPI ID in settings; INR only).
* The bill email contains `/pay/<token>`, served publicly by `GET /api/v1/public/pay/{token}`. The token is random
  (256 bits), only its hash is stored, it expires (default 60 days) and can be revoked; the page shows only the
  community name, invoice number, amount due, the QR and a "pay with UPI app" link, never other member data.
  It says in plain words that **paying does not settle the invoice until the community confirms it**. The endpoint
  is rate-limited per IP and every unknown, expired or revoked token gets the same `404`.

### 6. Reports

`GET /community/reports/{report}?format=csv|pdf&from&to`: `income-expense`, `category-breakdown`, `collection`
(billed vs collected vs outstanding), `member-dues` (needs `memberId`), `defaulters` (optional `asOf`),
`receipts-register`. CSV needs the `csv_export` plan feature, PDF needs `pdf_reports` (`402` otherwise). CSV text
cells are guarded against spreadsheet formula injection. Every export is audited.

## Worked example

Community "Lotus Residents" (financial year April–March), monthly maintenance of ₹1,500, 3 members.

1. `POST /fee-plans {"name":"Maintenance","kind":"MAINTENANCE","amount":"1500.00","frequency":"MONTHLY","dueDay":10}`
2. `POST /invoices/generate {"feePlanId":"…","period":"2026-05"}` → `created: 3`. Invoices
   `INV-2026-27/000001..3`, due 2026-05-10, each ₹1,500.00 ISSUED; three bill emails queued. Repeating the call
   returns `created: 0, alreadyBilled: 3`.
3. Asha pays ₹1,000 by UPI on 12 May: `POST /invoices/{id}/payments {"amount":"1000.00","method":"UPI","reference":"UTR9"}`
   → invoice `OVERDUE` (late) with `amountPaid 1000.00`, balance 500.00; receipt `RCP-2026-27/000001`; ledger INCOME
   ₹1,000.00 under *Membership Fees*.
4. She pays the remaining ₹500.00 → invoice `PAID`, receipt `RCP-2026-27/000002`, ledger INCOME ₹500.00.
5. Trying ₹1 more → `422 OVERPAYMENT`.
6. The second payment was a cash mix-up: `POST /payments/{id}/reverse {"reason":"Cheque bounced"}` → a −₹500.00
   payment and −₹500.00 ledger entry; invoice back to `OVERDUE` with balance 500.00; receipt `…000002` shows REVERSED.
7. `GET /ledger/summary`: income ₹1,000.00 (1,000 + 500 − 500), and `collection` report: billed ₹4,500.00,
   collected ₹1,000.00, outstanding ₹3,500.00.

## Where the guarantees live

| Guarantee | Enforced by |
| --- | --- |
| One invoice per plan, member and period | unique partial index `uq_invoices_fee_plan_member_period` + advisory lock |
| Gap-free invoice and receipt numbers | `document_counters` row lock inside the issuing transaction |
| No overpayment, no lost update | invoice row lock + `@Version` |
| Safe retries | `Idempotency-Key` unique per community + request hash |
| Nothing deleted | reversal rows; repositories expose no delete |
| Cross-tenant = 404 | tenant filter + `TenantGuard`; every endpoint in `FinanceIsolationIT` |
