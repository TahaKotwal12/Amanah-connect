/** The share of the limit at which the page starts suggesting a bigger plan. */
export const UPGRADE_HINT_AT = 0.9

/** Percent used, or null when the plan has no member limit. */
export function usagePercent(used: number, limit: number | null | undefined): number | null {
  if (limit === null || limit === undefined || limit <= 0) return null
  return Math.min(100, Math.round((used / limit) * 100))
}
