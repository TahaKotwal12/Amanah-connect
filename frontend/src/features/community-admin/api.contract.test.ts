import type { components } from '@/types/api.generated'
import type {
  CommunityPlanView,
  DefaulterView,
  GeneratePreview,
  InvoiceTotals,
  MemberCommunication,
  MemberView,
  PublicInviteView,
  PublicPayView,
  RegistrationView,
  RemindResult,
} from '@/types'

/**
 * Compile-time checks that the hand-written community admin types still match the backend's
 * OpenAPI document (npm run gen:api). If the backend adds, renames or retypes a field, `tsc` fails here.
 */
type Schemas = components['schemas']
type SameKeys<A, B> = keyof A extends keyof B ? (keyof B extends keyof A ? true : never) : never

const plan: SameKeys<Schemas['CommunityPlanView'], CommunityPlanView> = true
const subscription: SameKeys<
  Schemas['SubscriptionState'],
  NonNullable<CommunityPlanView['subscription']>
> = true
const usage: SameKeys<Schemas['Usage'], CommunityPlanView['usage']> = true
const communication: SameKeys<Schemas['CommunicationView'], MemberCommunication> = true
const memberView: SameKeys<Schemas['MemberView'], MemberView> = true
const registration: SameKeys<Schemas['RegistrationView'], RegistrationView> = true
const publicInvite: SameKeys<Schemas['PublicInviteView'], PublicInviteView> = true
const publicPay: SameKeys<Schemas['PublicPayView'], PublicPayView> = true
const totals: SameKeys<Schemas['InvoiceTotals'], InvoiceTotals> = true
const genPreview: SameKeys<Schemas['GeneratePreview'], GeneratePreview> = true
const defaulter: SameKeys<Schemas['DefaulterView'], DefaulterView> = true
const remind: SameKeys<Schemas['RemindResult'], RemindResult> = true

describe('community admin types match the backend schema', () => {
  it('have exactly the fields the API sends', () => {
    expect([
      plan,
      subscription,
      usage,
      communication,
      memberView,
      registration,
      publicInvite,
      publicPay,
      totals,
      genPreview,
      defaulter,
      remind,
    ]).toEqual(Array.from({ length: 12 }, () => true))
  })
})
