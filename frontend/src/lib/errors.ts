import { type ApiError, CLIENT_ERROR_CODES, toApiError } from './apiError'

/**
 * Friendly, final-copy messages for the stable `code` values in
 * backend/.../common/error/ErrorCode.java. The server's own `detail` is written for developers
 * and sometimes includes specifics, so it is only used for codes marked "specific" below, where
 * the detail is the useful part. Never rename a code here without the backend renaming it.
 */
export const ERROR_MESSAGES: Record<string, string> = {
  // generic
  UNAUTHENTICATED: 'Please sign in to continue.',
  FORBIDDEN: 'You do not have permission to do that.',
  NOT_FOUND: 'We could not find what you were looking for.',
  VALIDATION_FAILED: 'Some of the details need fixing. Check the highlighted fields.',
  BAD_REQUEST: 'That request was not valid. Please check it and try again.',
  METHOD_NOT_ALLOWED: 'That action is not available.',
  UNSUPPORTED_MEDIA_TYPE: 'That file type is not supported.',
  PAYLOAD_TOO_LARGE: 'That is too large to send. Try a smaller file or less text.',
  RATE_LIMITED: 'Too many attempts. Please wait a little and try again.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again in a moment.',

  // sign-in and two-factor
  INVALID_CREDENTIALS: 'The email or password is not correct.',
  ACCOUNT_LOCKED: 'This account is locked for a short while after too many attempts.',
  INVALID_MFA_CODE: 'That code is not correct or has expired. Try the latest code from your app.',
  MFA_SETUP_REQUIRED: 'Set up two-factor authentication to continue.',
  MFA_ALREADY_ENABLED: 'Two-factor authentication is already turned on.',
  MFA_NOT_ENABLED: 'Two-factor authentication is not turned on.',
  MFA_SETUP_NOT_STARTED: 'Start the two-factor setup again to get a new code.',
  MFA_REQUIRED_BY_POLICY:
    'Two-factor authentication is required for this account and cannot be turned off.',
  INVALID_TOKEN: 'This link is not valid or has expired. Ask for a new one.',
  INVALID_REFRESH_TOKEN: 'Your session has ended. Please sign in again.',
  INVALID_CURRENT_PASSWORD: 'The current password is not correct.',
  PASSWORD_POLICY_VIOLATION: 'That password is not strong enough. Use a longer, less common one.',
  CSRF_HEADER_REQUIRED: 'The request could not be verified. Reload the page and try again.',
  ORIGIN_NOT_ALLOWED: 'The request could not be verified. Reload the page and try again.',

  // conflicts and state
  EMAIL_ALREADY_REGISTERED: 'That email address is already in use.',
  SLUG_TAKEN: 'That web address name is already taken. Choose another.',
  CODE_TAKEN: 'That code is already in use. Choose another.',
  DUPLICATE_REFERENCE: 'A payment with that reference has already been recorded.',
  DUPLICATE_EMAIL: 'Another member already uses that email address.',
  INVALID_STATE_TRANSITION: 'That is not possible in the current state. Refresh and try again.',
  VERSION_CONFLICT: 'Someone else changed this record. Reload to see the latest and try again.',
  CATEGORY_EXISTS: 'That category already exists.',
  SETTING_LOCKED: 'This setting can no longer be changed.',
  IDEMPOTENCY_KEY_REUSED:
    'That request was already sent with different details. Reload and try again.',

  // members, invites, imports
  INVITE_UNAVAILABLE: 'This invitation link is not valid. It may have expired or been used up.',
  MEMBER_HAS_UNPAID_INVOICES: 'This member has unpaid invoices. Settle or cancel them first.',
  MEMBER_NOT_EMAILABLE: 'This member has no email address or has not agreed to emails.',
  MEMBER_ALREADY_ANONYMISED: "This member's personal data has already been erased.",
  IMPORT_BATCH_CONFLICT: 'That import batch was already used with different files.',
  IMPORT_NOT_CONFIRMABLE: 'This import has errors and cannot be confirmed yet.',
  IMPORT_CONFLICT:
    'The import conflicts with data that changed since the check. Run the check again.',

  // money
  OVERPAYMENT: 'That is more than the balance due on this invoice.',
  INVOICE_NOT_PAYABLE: 'This invoice cannot take payments in its current state.',
  ALREADY_REVERSED: 'This has already been reversed.',
  FEE_PLAN_INACTIVE: 'That fee plan is switched off.',
  LEDGER_ENTRY_READ_ONLY: 'This entry is linked to a payment and cannot be edited here.',
  UPI_NOT_CONFIGURED: 'UPI is not set up for this community. Add a UPI ID in Settings.',
  PAY_LINK_UNAVAILABLE: 'This payment link is not valid. It may have expired.',

  // engagement
  COMPLAINT_CLOSED: 'This complaint is closed.',
  THREAD_CLOSED: 'This conversation is closed.',
  ANNOUNCEMENT_NOT_EDITABLE: 'This announcement has already been sent and cannot be changed.',
  ANNOUNCEMENT_STATE: 'That is not possible for an announcement in its current state.',

  // exports and storage
  EXPORT_IN_PROGRESS: 'An export is already being prepared. It will be emailed when it is ready.',
  EXPORT_LINK_UNAVAILABLE: 'This download link is not valid. It may have expired.',
  STORAGE_UNAVAILABLE: 'File storage is not available right now. Please try again shortly.',

  // plan and community status
  COMMUNITY_SUSPENDED: 'This community is suspended or archived, so changes are switched off.',
  PLAN_INACTIVE: 'That plan is no longer available.',
  PLAN_LIMIT_EXCEEDED: 'This is over the limit of your current plan. Contact us to upgrade.',
  PLAN_FEATURE_UNAVAILABLE: 'This feature is not included in your current plan.',

  // raised on the client, never by the API
  [CLIENT_ERROR_CODES.network]:
    'We could not reach the server. Check your connection and try again.',
  [CLIENT_ERROR_CODES.timeout]: 'The server took too long to answer. Please try again.',
  [CLIENT_ERROR_CODES.cancelled]: 'The request was cancelled.',
  [CLIENT_ERROR_CODES.unknown]: 'Something went wrong. Please try again.',
}

/** Codes whose server `detail` carries the useful part (which field, which limit, which date). */
const SPECIFIC_DETAIL_CODES = new Set([
  'PLAN_LIMIT_EXCEEDED',
  'PAYLOAD_TOO_LARGE',
  'PASSWORD_POLICY_VIOLATION',
])

/** "in 2 minutes", "in 45 seconds". */
export function formatWait(seconds: number): string {
  if (seconds < 60) {
    const whole = Math.max(1, Math.ceil(seconds))
    return whole === 1 ? '1 second' : `${whole} seconds`
  }
  const minutes = Math.ceil(seconds / 60)
  return minutes === 1 ? '1 minute' : `${minutes} minutes`
}

/** The text to show a person for any failed call. Never exposes stack traces or raw server text. */
export function describeError(error: unknown): string {
  const apiError: ApiError = toApiError(error)

  const base =
    (SPECIFIC_DETAIL_CODES.has(apiError.code) && apiError.detail) ||
    ERROR_MESSAGES[apiError.code] ||
    (apiError.isServerError ? ERROR_MESSAGES.INTERNAL_ERROR : undefined) ||
    ERROR_MESSAGES[CLIENT_ERROR_CODES.unknown] ||
    'Something went wrong. Please try again.'

  if (
    (apiError.code === 'RATE_LIMITED' || apiError.code === 'ACCOUNT_LOCKED') &&
    apiError.retryAfterSeconds !== undefined
  ) {
    return `${base} You can try again in ${formatWait(apiError.retryAfterSeconds)}.`
  }
  return base
}

/** The message for one field, as the API worded it (already specific: "must not be blank"). */
export function describeFieldErrors(error: unknown): Record<string, string> {
  return toApiError(error).fieldErrorMap()
}
