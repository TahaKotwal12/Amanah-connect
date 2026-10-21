import {
  EMPTY_ANNOUNCEMENT,
  announcementSchema,
  endOfDayInstant,
  instantToIst,
  istToInstant,
  toCreateRequest,
  toUpdateRequest,
  type AnnouncementInput,
} from './announcementSchema'
import { diffObjects } from './auditDiff'

describe('diffObjects', () => {
  it('sorts keys and labels each as added, removed, changed or the same', () => {
    expect(
      diffObjects({ b: 1, a: 'x', gone: true }, { b: 2, a: 'x', fresh: [1] }).map((line) => [
        line.key,
        line.kind,
      ]),
    ).toEqual([
      ['a', 'same'],
      ['b', 'changed'],
      ['fresh', 'added'],
      ['gone', 'removed'],
    ])
  })

  it('treats a missing side as empty, and null differently from a missing key', () => {
    expect(diffObjects(null, { a: 1 })[0]?.kind).toBe('added')
    expect(diffObjects({ a: 1 }, null)[0]?.kind).toBe('removed')
    expect(diffObjects({ a: null }, { a: 0 })[0]?.kind).toBe('changed')
    expect(diffObjects(null, null)).toEqual([])
  })

  it('compares nested values by content', () => {
    expect(diffObjects({ a: { x: 1 } }, { a: { x: 1 } })[0]?.kind).toBe('same')
    expect(diffObjects({ a: { x: 1 } }, { a: { x: 2 } })[0]?.kind).toBe('changed')
  })
})

describe('India time conversion', () => {
  it('turns a date and time in India into the right instant, and back', () => {
    expect(istToInstant('2026-11-01', '09:30')).toBe('2026-11-01T04:00:00.000Z')
    expect(instantToIst('2026-11-01T04:00:00.000Z')).toEqual({ date: '2026-11-01', time: '09:30' })
  })

  it('crosses midnight correctly', () => {
    expect(istToInstant('2026-11-01', '00:15')).toBe('2026-10-31T18:45:00.000Z')
    expect(instantToIst('2026-10-31T18:45:00.000Z')).toEqual({ date: '2026-11-01', time: '00:15' })
  })

  it('ends a day at its last second in India', () => {
    expect(endOfDayInstant('2026-11-01')).toBe('2026-11-01T18:29:59.000Z')
  })
})

describe('announcement form', () => {
  const valid: AnnouncementInput = { ...EMPTY_ANNOUNCEMENT, title: 'Hi', body: 'There' }
  const issues = (value: AnnouncementInput) =>
    announcementSchema.safeParse(value).error?.issues.map((issue) => issue.path[0]) ?? []

  it('accepts a draft by email, or a banner alone', () => {
    expect(announcementSchema.safeParse(valid).success).toBe(true)
    expect(announcementSchema.safeParse({ ...valid, sendEmail: false, banner: true }).success).toBe(
      true,
    )
  })

  it('needs a channel, a title, a message and chosen communities when not sending to all', () => {
    expect(issues({ ...valid, sendEmail: false, banner: false })).toContain('sendEmail')
    expect(issues({ ...valid, title: ' ' })).toContain('title')
    expect(issues({ ...valid, body: '' })).toContain('body')
    expect(issues({ ...valid, audience: 'selected' })).toContain('communityIds')
  })

  it('refuses a schedule in the past or half filled in, and an expiry that is not in the future', () => {
    expect(issues({ ...valid, when: 'later' })).toContain('scheduleDate')
    expect(
      issues({ ...valid, when: 'later', scheduleDate: '2020-01-01', scheduleTime: '09:00' }),
    ).toContain('scheduleDate')
    expect(
      issues({ ...valid, when: 'later', scheduleDate: '2099-01-01', scheduleTime: '' }),
    ).toContain('scheduleTime')
    expect(
      announcementSchema.safeParse({
        ...valid,
        when: 'later',
        scheduleDate: '2099-01-01',
        scheduleTime: '09:00',
      }).success,
    ).toBe(true)
    expect(issues({ ...valid, expiresOn: '2020-01-01' })).toContain('expiresOn')
  })

  it('builds a create request that leaves out what was not chosen', () => {
    const parsed = announcementSchema.parse(valid)
    expect(toCreateRequest(parsed)).toEqual({
      title: 'Hi',
      body: 'There',
      kind: 'ANNOUNCEMENT',
      sendEmail: true,
      banner: false,
    })
  })

  it('builds an update request that clears what was emptied', () => {
    const parsed = announcementSchema.parse(valid)
    expect(toUpdateRequest(parsed)).toMatchObject({
      allCommunities: true,
      clearExpiry: true,
      unschedule: true,
    })
  })
})
