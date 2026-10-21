import {
  addDaysToDate,
  daysBetween,
  formatDate,
  formatDateTime,
  formatMonth,
  todayInIst,
} from './dates'

describe('dates', () => {
  it('shows a calendar date as the day it names, whatever the machine time zone', () => {
    expect(formatDate('2026-04-08')).toBe('08 Apr 2026')
    expect(formatDate('2026-01-01')).toBe('01 Jan 2026')
    expect(formatDate('2026-12-31')).toBe('31 Dec 2026')
  })

  it('shows an instant in India time', () => {
    // 20:00 UTC on 8 April is 01:30 on 9 April in IST.
    expect(formatDate('2026-04-08T20:00:00Z')).toBe('09 Apr 2026')
    expect(formatDateTime('2026-04-08T20:00:00Z')).toBe('09 Apr 2026, 1:30 am IST')
    expect(formatDateTime('2026-04-08T10:15:30Z')).toBe('08 Apr 2026, 3:45 pm IST')
  })

  it('returns an empty string for missing or invalid input', () => {
    expect(formatDate(null)).toBe('')
    expect(formatDate(undefined)).toBe('')
    expect(formatDate('not a date')).toBe('')
    expect(formatDateTime('nonsense')).toBe('')
  })

  it('formats a month', () => {
    expect(formatMonth('2026-04')).toBe('Apr 2026')
  })

  it("finds today's date in India, not UTC", () => {
    // 19:00 UTC on 8 April is already 9 April in India.
    expect(todayInIst(new Date('2026-04-08T19:00:00Z'))).toBe('2026-04-09')
    expect(todayInIst(new Date('2026-04-08T05:00:00Z'))).toBe('2026-04-08')
  })

  it('does date arithmetic on calendar days', () => {
    expect(addDaysToDate('2026-02-27', 3)).toBe('2026-03-02')
    expect(daysBetween('2026-04-01', '2026-04-10')).toBe(9)
    expect(daysBetween('2026-04-10', '2026-04-01')).toBe(-9)
  })
})
