import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type { AnnouncementPreviewView, AnnouncementView } from '@/types'
import {
  announcementSchema,
  describeDelivery,
  parseSchedule,
  plainText,
  toUpdateAnnouncementRequest,
} from './announcementSchemas'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 30_000 })

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const page = <T,>(items: T[]) => ({ items: [...items], total: items.length, page: 0, size: 20 })

let mock: ReturnType<typeof installMockApi> | undefined
function api(handlers: Record<string, MockHandler> = {}) {
  mock = installMockApi({ ...emptyCommunityApi(), ...handlers })
  return mock
}
function render(entry: string) {
  const client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false }, mutations: { retry: false } })
  return renderApp(entry, { client })
}
beforeEach(() => setSession('COMMUNITY_ADMIN'))
afterEach(() => {
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
})

const announcement = (over: Partial<AnnouncementView> = {}): AnnouncementView => ({
  id: 'a1',
  title: 'AGM on Sunday',
  bodyHtml: '<p>Please come to the <strong>AGM</strong>.</p>',
  bodyText: 'Please come to the AGM.',
  audience: 'ALL_ACTIVE',
  group: null,
  memberIds: [],
  sendEmail: true,
  status: 'DRAFT',
  scheduledAt: null,
  sentAt: null,
  delivery: null,
  createdBy: 'u1',
  createdAt: '2026-10-10T10:00:00Z',
  updatedAt: '2026-10-10T10:00:00Z',
  ...over,
})

const preview = (over: Partial<AnnouncementPreviewView> = {}): AnnouncementPreviewView => ({
  title: 'AGM on Sunday',
  bodyHtml: '<p>Please come to the <strong>AGM</strong>.</p>',
  bodyText: 'Please come to the AGM.',
  audience: 'ALL_ACTIVE',
  sendEmail: true,
  audienceSize: 40,
  eligibleForEmail: 34,
  noEmail: 4,
  noConsent: 2,
  quotaRemaining: 100,
  wouldSkipForQuota: 0,
  ...over,
})

const plan = ok({
  planCode: 'STARTER',
  planName: 'Starter',
  limits: { emails_per_month: 500 },
  features: {},
  usage: { members: 40, emailsThisMonth: 120 },
  communityStatus: 'ACTIVE',
  subscription: null,
  expiringWindowDays: 30,
})

describe('announcement helpers', () => {
  it('tells an empty editor from a message', () => {
    expect(plainText('<p></p>')).toBe('')
    expect(plainText('<p>&nbsp;</p>')).toBe('')
    expect(plainText('<p>Hello <strong>all</strong></p>')).toBe('Hello all')
  })

  it('needs a title, a message and an audience detail', () => {
    const base = {
      title: 'T',
      body: '<p>x</p>',
      audience: 'ALL_ACTIVE',
      group: '',
      memberIds: [],
      sendEmail: true,
    } as const
    expect(announcementSchema.safeParse(base).success).toBe(true)
    expect(announcementSchema.safeParse({ ...base, title: '' }).success).toBe(false)
    expect(announcementSchema.safeParse({ ...base, body: '<p></p>' }).success).toBe(false)
    expect(announcementSchema.safeParse({ ...base, audience: 'GROUP' }).success).toBe(false)
    expect(announcementSchema.safeParse({ ...base, audience: 'GROUP', group: 'A' }).success).toBe(
      true,
    )
    expect(announcementSchema.safeParse({ ...base, audience: 'SELECTED' }).success).toBe(false)
  })

  it('clears the audience fields that do not apply when updating', () => {
    const values = announcementSchema.parse({
      title: 'T',
      body: '<p>x</p>',
      audience: 'ALL_ACTIVE',
      group: 'Old',
      memberIds: ['m1'],
      sendEmail: false,
    })
    expect(toUpdateAnnouncementRequest(values)).toMatchObject({
      group: '',
      memberIds: [],
      sendEmail: false,
    })
  })

  it('accepts only a schedule in the future, in India time', () => {
    const now = new Date('2026-10-18T10:00:00Z')
    expect(parseSchedule('', '09:00', now)).toEqual({ ok: false, message: 'Choose a date' })
    expect(parseSchedule('2026-10-18', '', now)).toEqual({ ok: false, message: 'Choose a time' })
    expect(parseSchedule('2026-10-18', '15:00', now).ok).toBe(false) // 09:30 UTC, already past
    expect(parseSchedule('2026-10-18', '17:00', now)).toEqual({
      ok: true,
      at: '2026-10-18T11:30:00.000Z',
    })
  })

  it('describes a send in words', () => {
    expect(
      describeDelivery({
        recipientsTotal: 40,
        emailsQueued: 30,
        skippedNoEmail: 4,
        skippedNoConsent: 2,
        skippedQuota: 4,
      }),
    ).toEqual([
      '40 in the audience, 30 emailed',
      '4 have no email address',
      '2 have not agreed to email',
      "4 not emailed: the plan's email allowance ran out",
    ])
  })
})

describe('announcements page', () => {
  it('lists announcements with their delivery stats and filters by status', async () => {
    const list = vi.fn<MockHandler>(() => ({
      status: 200,
      data: page([
        announcement({
          status: 'SENT',
          sentAt: '2026-10-11T10:00:00Z',
          delivery: {
            recipientsTotal: 40,
            emailsQueued: 30,
            skippedNoEmail: 4,
            skippedNoConsent: 2,
            skippedQuota: 4,
          },
        }),
      ]),
    }))
    api({ 'GET /community/announcements': list })
    const user = userEvent.setup()
    render('/admin/announcements')
    expect(await screen.findByRole('button', { name: 'AGM on Sunday' })).toBeInTheDocument()
    expect(screen.getByText('30 emailed of 40')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Drafts' }))
    await waitFor(() =>
      expect((list.mock.calls.at(-1)![0].params as URLSearchParams).get('status')).toBe('DRAFT'),
    )
  })

  it('shows what a sent announcement did, with its message in a sandboxed frame', async () => {
    api({
      'GET /community/announcements': ok(
        page([
          announcement({
            status: 'SENT',
            sentAt: '2026-10-11T10:00:00Z',
            delivery: {
              recipientsTotal: 40,
              emailsQueued: 30,
              skippedNoEmail: 4,
              skippedNoConsent: 2,
              skippedQuota: 4,
            },
          }),
        ]),
      ),
    })
    const user = userEvent.setup()
    render('/admin/announcements')
    await user.click(await screen.findByRole('button', { name: 'AGM on Sunday' }))
    const dialog = await screen.findByRole('dialog', { name: 'AGM on Sunday' })
    const frame = within(dialog).getByTitle('The announcement')
    expect(frame).toHaveAttribute('sandbox', '')
    expect(within(dialog).getByText('4 have no email address')).toBeInTheDocument()
  })
})

describe('composer', () => {
  function composerApi(extra: Record<string, MockHandler> = {}) {
    return api({
      'GET /community/plan': plan,
      'GET /community/announcements': ok(page([announcement()])),
      'GET /community/announcements/a1/preview': ok(preview()),
      ...extra,
    })
  }

  async function openDraft() {
    const user = userEvent.setup()
    render('/admin/announcements')
    await screen.findByRole('button', { name: 'AGM on Sunday' })
    await user.click(screen.getByRole('button', { name: 'Actions for AGM on Sunday' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Edit and send' }))
    const sheet = await screen.findByRole('dialog', { name: 'Write an announcement' })
    return { user, sheet }
  }

  it('shows the rich text toolbar and the email allowance', async () => {
    composerApi()
    const { sheet } = await openDraft()
    const toolbar = await within(sheet).findByRole('toolbar', { name: 'Formatting' })
    for (const name of [
      'Bold',
      'Italic',
      'Underline',
      'Heading',
      'Bulleted list',
      'Numbered list',
      'Quote',
      'Link',
    ]) {
      expect(within(toolbar).getByRole('button', { name })).toBeInTheDocument()
    }
    expect(within(sheet).getByText(/120 of 500/)).toBeInTheDocument()
  })

  it('refuses a link that is not http, https or mailto', async () => {
    composerApi()
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Link' }))
    await user.type(within(sheet).getByLabelText('Link address'), 'javascript:alert(1)')
    await user.click(within(sheet).getByRole('button', { name: 'Apply' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(/https:\/\//)
  })

  it('saves the draft, then shows who it reaches and what the quota allows', async () => {
    const update = vi.fn<MockHandler>(() => ({ status: 200, data: announcement() }))
    composerApi({
      'PATCH /community/announcements/a1': update,
      'GET /community/announcements/a1/preview': ok(
        preview({ wouldSkipForQuota: 5, quotaRemaining: 29 }),
      ),
    })
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Check and send' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check and send' })
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(body(update.mock.calls[0]![0])).toMatchObject({
      title: 'AGM on Sunday',
      audience: 'ALL_ACTIVE',
      sendEmail: true,
    })

    const reach = await within(dialog)
      .findByRole('list', { name: 'Who it reaches' })
      .catch(() => null)
    expect(reach ?? within(dialog).getByLabelText('Who it reaches')).toBeInTheDocument()
    expect(within(dialog).getByText('40')).toBeInTheDocument()
    expect(within(dialog).getByText('29')).toBeInTheDocument() // 34 eligible minus 5 over the quota
    expect(within(dialog).getByText(/will not cover everyone/)).toBeInTheDocument()
    expect(within(dialog).getByTitle('How the announcement will look')).toHaveAttribute(
      'sandbox',
      '',
    )
  })

  it('sends a test to the signed-in admin', async () => {
    const test = vi.fn<MockHandler>(() => ({
      status: 200,
      data: { sentTo: 'admin@example.test', queued: true },
    }))
    composerApi({
      'PATCH /community/announcements/a1': ok(announcement()),
      'POST /community/announcements/a1/send-test': test,
    })
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Check and send' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check and send' })
    await user.click(await within(dialog).findByRole('button', { name: 'Send a test to me' }))
    await waitFor(() => expect(test).toHaveBeenCalledTimes(1))
  })

  it('asks before sending now, with the count, and sends once', async () => {
    const send = vi.fn<MockHandler>(() => ({
      status: 200,
      data: announcement({
        status: 'SENT',
        delivery: {
          recipientsTotal: 40,
          emailsQueued: 34,
          skippedNoEmail: 4,
          skippedNoConsent: 2,
          skippedQuota: 0,
        },
      }),
    }))
    composerApi({
      'PATCH /community/announcements/a1': ok(announcement()),
      'POST /community/announcements/a1/send': send,
    })
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Check and send' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check and send' })
    await user.click(await within(dialog).findByRole('button', { name: 'Send now' }))
    const confirm = await screen.findByRole('alertdialog')
    expect(confirm).toHaveTextContent('34 of 40 members')
    expect(send).not.toHaveBeenCalled()
    await user.click(within(confirm).getByRole('button', { name: 'Send now' }))
    await waitFor(() => expect(send).toHaveBeenCalledTimes(1))
  })

  it('schedules for a future time and refuses a missing date', async () => {
    const schedule = vi.fn<MockHandler>(() => ({
      status: 200,
      data: announcement({ status: 'SCHEDULED' }),
    }))
    composerApi({
      'PATCH /community/announcements/a1': ok(announcement()),
      'POST /community/announcements/a1/schedule': schedule,
    })
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Check and send' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check and send' })
    await user.click(await within(dialog).findByRole('button', { name: 'Schedule' }))
    expect(await within(dialog).findByText('Choose a date')).toBeInTheDocument()
    expect(schedule).not.toHaveBeenCalled()
  })

  it('shows the server refusing to send twice', async () => {
    composerApi({
      'PATCH /community/announcements/a1': ok(announcement()),
      'POST /community/announcements/a1/send': () =>
        problem(409, 'ANNOUNCEMENT_STATE', 'Already sent.'),
    })
    const { user, sheet } = await openDraft()
    await user.click(await within(sheet).findByRole('button', { name: 'Check and send' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check and send' })
    await user.click(await within(dialog).findByRole('button', { name: 'Send now' }))
    const confirm = await screen.findByRole('alertdialog')
    await user.click(within(confirm).getByRole('button', { name: 'Send now' }))
    await waitFor(() => expect(mock?.count('POST /community/announcements/a1/send')).toBe(1))
  })
})
