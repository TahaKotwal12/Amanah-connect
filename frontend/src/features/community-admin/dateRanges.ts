import { addMonths, endOfMonth, format, parseISO, startOfMonth } from 'date-fns'
import type { IsoDate } from '@/types'

export type RangePreset = 'month' | 'quarter' | 'year' | 'custom'

export interface IsoRange {
  from: IsoDate
  to: IsoDate
}

const day = (value: Date): IsoDate => format(value, 'yyyy-MM-dd')

/** The calendar month containing `today`. */
export function thisMonth(today: IsoDate): IsoRange {
  const base = parseISO(today)
  return { from: day(startOfMonth(base)), to: day(endOfMonth(base)) }
}

/** The calendar quarter (January to March, and so on) containing `today`. */
export function thisQuarter(today: IsoDate): IsoRange {
  const base = parseISO(today)
  const startMonth = Math.floor(base.getMonth() / 3) * 3
  const start = new Date(base.getFullYear(), startMonth, 1)
  return { from: day(start), to: day(endOfMonth(addMonths(start, 2))) }
}

/**
 * The financial year containing `today`, for a year that starts in `startMonth` (1 to 12; 4 is
 * April, the Indian default). The year's end is the last day of the month before it starts again.
 */
export function financialYear(today: IsoDate, startMonth: number): IsoRange {
  const base = parseISO(today)
  const month = startMonth - 1
  const startYear = base.getMonth() >= month ? base.getFullYear() : base.getFullYear() - 1
  const start = new Date(startYear, month, 1)
  return { from: day(start), to: day(endOfMonth(addMonths(start, 11))) }
}

/** The range for a preset. `custom` has no range of its own: the caller supplies it. */
export function rangeFor(
  preset: Exclude<RangePreset, 'custom'>,
  today: IsoDate,
  financialYearStartMonth: number,
): IsoRange {
  if (preset === 'month') return thisMonth(today)
  if (preset === 'quarter') return thisQuarter(today)
  return financialYear(today, financialYearStartMonth)
}

/** "1 Apr 2026 to 31 Mar 2027", for a heading. */
export function describeRange(range: IsoRange): string {
  const show = (value: IsoDate) => format(parseISO(value), 'd MMM yyyy')
  return `${show(range.from)} to ${show(range.to)}`
}
