import {
  cleanup,
  configure,
  createEvent,
  fireEvent,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type { CommentView, ComplaintView } from '@/types'
import { describeEmailOutcome, describeSla, newComplaintSchema } from './complaintSchemas'

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

const complaint = (over: Partial<ComplaintView> = {}): ComplaintView => ({
  id: 'c1',
  subject: 'Water leak in B block',
  description: 'The ceiling is dripping.',
  status: 'OPEN',
  priority: 'HIGH',
  category: 'Water',
  memberId: 'm1',
  memberNo: 'M-001',
  memberName: 'Asha Rao',
  assignedTo: null,
  assignedToName: null,
  createdAt: '2026-10-10T10:00:00Z',
  updatedAt: '2026-10-10T10:00:00Z',
  resolvedAt: null,
  closedAt: null,
  ageDays: 2,
  slaTargetDays: 3,
  slaBreached: false,
  commentCount: 0,
  emailOutcome: null,
  ...over,
})

describe('complaint helpers', () => {
  it('describes age against the target', () => {
    expect(describeSla(complaint())).toBe('2 days old, target 3')
    expect(describeSla(complaint({ ageDays: 5, slaBreached: true }))).toBe(
      '2 days past the 3-day target',
    )
    expect(describeSla(complaint({ status: 'RESOLVED', ageDays: 1 }))).toBe('Finished in 1 day')
    expect(describeSla(complaint({ status: 'CLOSED', ageDays: 0 }))).toBe('Finished the same day')
  })

  it('says what happened to an email', () => {
    expect(describeEmailOutcome('QUEUED')).toMatch(/being emailed/)
    expect(describeEmailOutcome('NO_CONSENT')).toMatch(/not agreed/)
    expect(describeEmailOutcome(null)).toBeNull()
  })

  it('needs a subject and a description', () => {
    const base = {
      memberId: '',
      subject: '',
      description: '',
      priority: 'MEDIUM',
      category: '',
      assignedTo: '',
    }
    expect(newComplaintSchema.safeParse(base).success).toBe(false)
    expect(newComplaintSchema.safeParse({ ...base, subject: 'x', description: 'y' }).success).toBe(
      true,
    )
  })
})

describe('complaints page', () => {
  it('lists complaints with priority and age, and filters by status', async () => {
    const list = vi.fn<MockHandler>(() => ({ status: 200, data: page([complaint()]) }))
    api({ 'GET /community/complaints': list })
    const user = userEvent.setup()
    render('/admin/complaints')
    expect(await screen.findByRole('button', { name: 'Water leak in B block' })).toBeInTheDocument()
    expect(screen.getByText('High')).toBeInTheDocument()
    expect(screen.getByText('2 days old, target 3')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Resolved' }))
    await waitFor(() =>
      expect((list.mock.calls.at(-1)![0].params as URLSearchParams).getAll('status')).toEqual([
        'RESOLVED',
      ]),
    )
  })

  it('logs a complaint on behalf of a member', async () => {
    const create = vi.fn<MockHandler>(() => ({ status: 201, data: complaint({ id: 'c9' }) }))
    api({
      'POST /community/complaints': create,
      'GET /community/complaints/c9': ok(complaint({ id: 'c9' })),
      'GET /community/complaints/c9/comments': ok([]),
      'GET /community/members': ok(
        page([{ id: 'm1', memberNo: 'M-001', fullName: 'Asha Rao', status: 'ACTIVE' }]),
      ),
    })
    const user = userEvent.setup()
    render('/admin/complaints')
    await user.click(await screen.findByRole('button', { name: 'Log a complaint' }))
    const sheet = await screen.findByRole('dialog', { name: 'Log a complaint' })
    await user.click(within(sheet).getByRole('button', { name: 'Log complaint' }))
    expect(await within(sheet).findByText('Enter a subject')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()

    await user.type(within(sheet).getByLabelText(/^Subject/), 'Water leak')
    await user.type(within(sheet).getByLabelText(/^What happened/), 'Dripping ceiling')
    await user.click(within(sheet).getByRole('combobox', { name: /On behalf of/ }))
    await user.click(await screen.findByRole('option', { name: /Asha Rao/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Log complaint' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(body(create.mock.calls[0]![0])).toMatchObject({
      subject: 'Water leak',
      description: 'Dripping ceiling',
      memberId: 'm1',
      priority: 'MEDIUM',
    })
  })
})

describe('complaint drawer', () => {
  const comment = (over: Partial<CommentView>): CommentView => ({
    id: 'k1',
    authorId: 'u1',
    authorName: 'Community Admin',
    body: 'Called the plumber.',
    visibility: 'INTERNAL',
    createdAt: '2026-10-11T10:00:00Z',
    emailOutcome: null,
    ...over,
  })

  function drawerApi(extra: Record<string, MockHandler> = {}) {
    return api({
      'GET /community/complaints': ok(page([complaint()])),
      'GET /community/complaints/c1': ok(complaint()),
      'GET /community/complaints/c1/comments': ok([comment({})]),
      'GET /community/complaints/assignees': ok([
        { id: 'u1', fullName: 'Community Admin', email: 'a@x.in' },
      ]),
      ...extra,
    })
  }

  it('keeps internal notes and member updates apart, and sends the chosen visibility', async () => {
    const add = vi.fn<MockHandler>((config) => ({
      status: 201,
      data: comment({ id: 'k2', body: String(body(config).body), visibility: 'MEMBER' }),
    }))
    drawerApi({ 'POST /community/complaints/c1/comments': add })
    const user = userEvent.setup()
    render('/admin/complaints?open=c1')
    const drawer = await screen.findByRole('dialog', { name: 'Water leak in B block' })
    const thread = await within(drawer).findByRole('list', { name: 'Notes and updates' })
    expect(within(thread).getByText('Internal note')).toBeInTheDocument()

    await user.click(within(drawer).getByRole('radio', { name: /Update the member can see/ }))
    await user.type(within(drawer).getByLabelText('Update for the member'), 'Plumber comes today')
    await user.click(within(drawer).getByRole('checkbox', { name: /Notify the member/ }))
    await user.click(within(drawer).getByRole('button', { name: 'Add update' }))
    await waitFor(() => expect(add).toHaveBeenCalledTimes(1))
    expect(body(add.mock.calls[0]![0])).toEqual({
      body: 'Plumber comes today',
      visibility: 'MEMBER',
      notifyMember: true,
    })
  })

  it('will not add an empty note', async () => {
    const add = vi.fn<MockHandler>(() => ({ status: 201, data: comment({}) }))
    drawerApi({ 'POST /community/complaints/c1/comments': add })
    const user = userEvent.setup()
    render('/admin/complaints?open=c1')
    const drawer = await screen.findByRole('dialog', { name: 'Water leak in B block' })
    await user.click(await within(drawer).findByRole('button', { name: 'Add note' }))
    expect(await within(drawer).findByText('Write something first')).toBeInTheDocument()
    expect(add).not.toHaveBeenCalled()
  })

  it('assigns the complaint', async () => {
    const patch = vi.fn<MockHandler>(() => ({ status: 200, data: complaint({ assignedTo: 'u1' }) }))
    drawerApi({ 'PATCH /community/complaints/c1': patch })
    const user = userEvent.setup()
    render('/admin/complaints?open=c1')
    const drawer = await screen.findByRole('dialog', { name: 'Water leak in B block' })
    await user.click(await within(drawer).findByRole('combobox', { name: 'Assigned to' }))
    await user.click(await screen.findByRole('option', { name: 'Community Admin' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(body(patch.mock.calls[0]![0])).toEqual({ assignedTo: 'u1' })
  })

  it('changes the status with a note and an email to the member', async () => {
    const status = vi.fn<MockHandler>(() => ({
      status: 200,
      data: complaint({ status: 'RESOLVED', emailOutcome: 'QUEUED' }),
    }))
    drawerApi({ 'POST /community/complaints/c1/status': status })
    const user = userEvent.setup()
    render('/admin/complaints?open=c1')
    const drawer = await screen.findByRole('dialog', { name: 'Water leak in B block' })
    await user.click(await within(drawer).findByRole('button', { name: 'Change status' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change status' })
    await user.click(within(dialog).getByRole('radio', { name: 'Resolved' }))
    await user.type(within(dialog).getByLabelText('Note (optional)'), 'Fixed')
    await user.click(within(dialog).getByRole('checkbox', { name: /Notify the member/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Save status' }))
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1))
    expect(body(status.mock.calls[0]![0])).toEqual({
      status: 'RESOLVED',
      note: 'Fixed',
      notifyMember: true,
    })
  })

  it('cannot email a member when the complaint has none', async () => {
    drawerApi({
      'GET /community/complaints/c1': ok(
        complaint({ memberId: null, memberName: null, memberNo: null }),
      ),
    })
    const user = userEvent.setup()
    render('/admin/complaints?open=c1')
    const drawer = await screen.findByRole('dialog', { name: 'Water leak in B block' })
    await user.click(await within(drawer).findByRole('button', { name: 'Change status' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change status' })
    expect(within(dialog).getByRole('checkbox', { name: /Notify the member/ })).toBeDisabled()
  })
})

describe('complaint board', () => {
  const byStatus: Record<string, ComplaintView[]> = {
    OPEN: [complaint()],
    IN_PROGRESS: [],
    RESOLVED: [],
    CLOSED: [],
  }
  function boardApi(change: MockHandler) {
    return api({
      'GET /community/complaints': (config) => {
        const status = (config.params as URLSearchParams).get('status') ?? 'OPEN'
        return { status: 200, data: page(byStatus[status] ?? []) }
      },
      'POST /community/complaints/c1/status': change,
    })
  }

  it('moves a card by dragging it to another column', async () => {
    const change = vi.fn<MockHandler>(() => ({
      status: 200,
      data: complaint({ status: 'IN_PROGRESS' }),
    }))
    boardApi(change)
    render('/admin/complaints?view=board')
    const card = (await screen.findByRole('button', { name: 'Water leak in B block' })).closest(
      'li',
    )!
    const target = screen.getByRole('region', { name: 'In progress complaints' })
    const dataTransfer = { setData: vi.fn(), effectAllowed: '' }
    fireEvent.dragStart(card, { dataTransfer })
    fireEvent(target, createEvent.dragOver(target, { dataTransfer }))
    fireEvent.drop(target, { dataTransfer })
    await waitFor(() => expect(change).toHaveBeenCalledTimes(1))
    expect(body(change.mock.calls[0]![0])).toEqual({ status: 'IN_PROGRESS' })
  })

  it('moves a card from its menu, for keyboard and screen reader users', async () => {
    const change = vi.fn<MockHandler>(() => ({
      status: 200,
      data: complaint({ status: 'RESOLVED' }),
    }))
    boardApi(change)
    const user = userEvent.setup()
    render('/admin/complaints?view=board')
    await user.click(
      await screen.findByRole('button', { name: 'Actions for Water leak in B block' }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Resolved' }))
    await waitFor(() => expect(change).toHaveBeenCalledTimes(1))
    expect(body(change.mock.calls[0]![0])).toEqual({ status: 'RESOLVED' })
  })
})
