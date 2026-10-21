import {
  addDays,
  differenceInCalendarDays,
  format,
  formatDistanceToNowStrict,
  parseISO,
} from 'date-fns'
import type { IsoDate, IsoInstant } from '@/types'

/**
 * Two kinds of value come from the API and they must not be mixed up:
 *  - IsoInstant ("2026-04-08T10:15:30Z"): a moment. Shown in India time (IST, UTC+5:30, no DST).
 *  - IsoDate ("2026-04-08"): a calendar day (due date, received on). It has no time zone, so it is
 *    shown exactly as the day it names. `new Date("2026-04-08")` parses as UTC midnight and shows
 *    the wrong day west of UTC: parseISO reads it as a local calendar date instead.
 */

export const DISPLAY_TIME_ZONE = 'Asia/Kolkata'

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

const partsFormatter = new Intl.DateTimeFormat('en-IN', {
  timeZone: DISPLAY_TIME_ZONE,
  year: 'numeric',
  month: 'short',
  day: '2-digit',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
})

interface IstParts {
  day: string
  month: string
  year: string
  hour: string
  minute: string
  period: string
}

function istParts(instant: Date): IstParts {
  const parts: Record<string, string> = {}
  for (const part of partsFormatter.formatToParts(instant)) {
    parts[part.type] = part.value
  }
  return {
    day: parts.day ?? '',
    month: parts.month ?? '',
    year: parts.year ?? '',
    hour: parts.hour ?? '',
    minute: parts.minute ?? '',
    period: (parts.dayPeriod ?? '').toLowerCase(),
  }
}

function toInstant(value: IsoInstant | Date): Date | null {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** "08 Apr 2026". Accepts an instant (shown in IST) or a plain date (shown as written). */
export function formatDate(value: string | null | undefined): string {
  if (!value) return ''
  if (DATE_ONLY.test(value)) {
    const day = parseISO(value)
    return Number.isNaN(day.getTime()) ? '' : format(day, 'dd MMM yyyy')
  }
  const instant = toInstant(value)
  if (!instant) return ''
  const p = istParts(instant)
  return `${p.day} ${p.month} ${p.year}`
}

/** "08 Apr 2026, 3:45 pm IST". Instants only. */
export function formatDateTime(value: IsoInstant | Date | null | undefined): string {
  if (!value) return ''
  const instant = toInstant(value)
  if (!instant) return ''
  const p = istParts(instant)
  return `${p.day} ${p.month} ${p.year}, ${p.hour}:${p.minute} ${p.period} IST`
}

/** "3 hours ago" / "in 2 days". */
export function formatRelative(value: IsoInstant | null | undefined): string {
  if (!value) return ''
  const instant = toInstant(value)
  return instant ? formatDistanceToNowStrict(instant, { addSuffix: true }) : ''
}

/** "2026-04" to "Apr 2026". */
export function formatMonth(month: string): string {
  const parsed = parseISO(`${month}-01`)
  return Number.isNaN(parsed.getTime()) ? month : format(parsed, 'MMM yyyy')
}

/** Today's calendar date in India, as an IsoDate: the right default for "received on". */
export function todayInIst(now: Date = new Date()): IsoDate {
  const p = new Intl.DateTimeFormat('en-CA', {
    timeZone: DISPLAY_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
  return p // en-CA is yyyy-MM-dd
}

export function addDaysToDate(date: IsoDate, days: number): IsoDate {
  return format(addDays(parseISO(date), days), 'yyyy-MM-dd')
}

/** Whole calendar days from `from` to `to` (negative when `to` is earlier). */
export function daysBetween(from: IsoDate, to: IsoDate): number {
  return differenceInCalendarDays(parseISO(to), parseISO(from))
}
