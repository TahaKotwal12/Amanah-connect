import type { IsoInstant } from '@/types'

/** All times the person types are India time, like the rest of the app. */
const IST_OFFSET = '+05:30'

/** "2026-11-01" and "09:30" (India time) as an instant. */
export function istToInstant(date: string, time: string): IsoInstant {
  return new Date(`${date}T${time}:00${IST_OFFSET}`).toISOString()
}

/** The last second of a calendar day in India: when "until 1 November" ends. */
export function endOfDayInstant(date: string): IsoInstant {
  return new Date(`${date}T23:59:59${IST_OFFSET}`).toISOString()
}

/** An instant as the India date and time the form shows. */
export function instantToIst(instant: IsoInstant): { date: string; time: string } {
  const shifted = new Date(new Date(instant).getTime() + 5.5 * 60 * 60 * 1000)
  const iso = shifted.toISOString()
  return { date: iso.slice(0, 10), time: iso.slice(11, 16) }
}
