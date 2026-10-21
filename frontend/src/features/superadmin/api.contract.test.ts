import type { components } from '@/types/api.generated'
import type {
  InviteRow,
  MonthlySeries,
  PlanView,
  PlatformAnnouncementView,
  PlatformStats,
  SubscriptionView,
} from '@/types'

/**
 * Compile-time checks that the hand-written super admin types still match the backend's OpenAPI
 * document (npm run gen:api). If the backend adds, renames or retypes a field, `tsc` fails here.
 */
type Schemas = components['schemas']

type SameKeys<A, B> = keyof A extends keyof B ? (keyof B extends keyof A ? true : never) : never

const stats: SameKeys<Schemas['Stats'], PlatformStats> = true
const monthly: SameKeys<Schemas['Monthly'], MonthlySeries> = true
const monthPoint: SameKeys<Schemas['MonthPoint'], MonthlySeries['months'][number]> = true
const plan: SameKeys<Schemas['PlanView'], PlanView> = true
const invite: SameKeys<Schemas['InviteRow'], InviteRow> = true
const announcement: SameKeys<Schemas['PlatformAnnouncementView'], PlatformAnnouncementView> = true
const subscription: SameKeys<Schemas['SubscriptionView'], SubscriptionView> = true

describe('super admin types match the backend schema', () => {
  it('have exactly the fields the API sends', () => {
    expect([stats, monthly, monthPoint, plan, subscription, invite, announcement]).toEqual(
      Array.from({ length: 7 }, () => true),
    )
  })
})
