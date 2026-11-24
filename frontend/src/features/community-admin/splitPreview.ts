import { fromMinorUnits, toMinorUnits } from '@/lib/money'
import type { MoneyString } from '@/types'

/**
 * What each member would pay if `total` is divided equally among `members`. Mirrors the server's rule (the
 * shares add up to the total to the paisa; the leftover paise go one each to the first members), so the
 * preview is the real answer, not a rounded guess. All arithmetic is in whole paise: no floating point.
 */
export interface SplitPreview {
  /** The smaller share (or the only one). */
  low: MoneyString
  /** The larger share; equal to `low` when it divides evenly. */
  high: MoneyString
  /** How many members pay the larger share. */
  payingHigh: number
}

export function splitPreview(total: MoneyString, members: number): SplitPreview | null {
  if (!Number.isInteger(members) || members < 1) return null
  const minor = toMinorUnits(total)
  const count = BigInt(members)
  if (minor < count) return null // less than a paisa each
  const base = minor / count
  const extra = Number(minor % count)
  return {
    low: fromMinorUnits(base),
    high: fromMinorUnits(extra === 0 ? base : base + 1n),
    payingHigh: extra,
  }
}
