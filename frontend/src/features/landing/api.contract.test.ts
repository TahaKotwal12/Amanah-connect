import type { components } from '@/types/api.generated'
import type { PublicPlan } from './api'

/**
 * The hand-written PublicPlan must stay in step with what the backend really publishes. These are
 * compile-time checks against the types generated from its OpenAPI document (npm run gen:api): if
 * the backend adds, renames or retypes a field, `tsc` (and so the build) fails here.
 */
type Schema = components['schemas']['PublicPlanView']

// Same field names, in both directions.
type SameKeys = keyof Schema extends keyof PublicPlan
  ? keyof PublicPlan extends keyof Schema
    ? true
    : never
  : never
const sameKeys: SameKeys = true

// A PublicPlan is a valid backend PublicPlanView (prices are strings, limits and features are maps).
const asSchema = (plan: PublicPlan): Schema => plan

describe('PublicPlan matches the backend schema', () => {
  it('has exactly the fields the API sends', () => {
    expect(sameKeys).toBe(true)
    expect(asSchema).toBeTypeOf('function')
  })
})
