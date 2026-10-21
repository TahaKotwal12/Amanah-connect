import { act, cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import { emptySuperadminApi } from '@/test/superadminApi'
import type {
  MessagePage,
  MessageView,
  PageResponse,
  PlatformSupportCounts,
  SupportSummary,
  ThreadView,
} from '@/types'

vi.mock('@/lib/upload', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  uploadToPresignedUrl: vi.fn(() => Promise.resolve()),
}))

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 25_000 })

const ME_ID = '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11' // the signed-in super admin in test/fixtures

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const query = (config: InternalAxiosRequestConfig) => (config.params as URLSearchParams).toString()
const page = <T,>(items: T[]): PageResponse<T> => ({
  items,
  total: items.length,
  page: 0,
  size: 30,
})

let mock: ReturnType<typeof installMockApi> | undefined
function api(handlers: Record<string, MockHandler>) {
  mock = installMockApi({ ...emptySuperadminApi(), ...DESK, ...handlers })
  return mock
}
beforeEach(() => {
  setSession('SUPER_ADMIN')
})
afterEach(() => {
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
})

// ---- fixtures ---------------------------------------------------------------------------------

const COUNTS: PlatformSupportCounts = {
  total: 9,
  open: 3,
  waiting: 2,
  resolved: 3,
  closed: 1,
  activeUnassigned: 2,
  urgentActive: 1,
  unreadThreads: 4,
}
const SUMMARY: SupportSummary = {
  unreadMessages: 0,
  unreadThreads: 0,
  activeThreads: 0,
  threads: [],
}
const DESK: Record<string, MockHandler> = {
  'GET /admin/support/counts': ok(COUNTS),
  'GET /admin/support/summary': ok(SUMMARY),
}

const thread = (over: Partial<ThreadView> = {}): ThreadView => ({
  id: 't-1',
  communityId: 'com-1',
  communityName: 'Lotus Residents',
  subject: 'Cannot download receipts',
  status: 'OPEN',
  priority: 'MEDIUM',
  assignedTo: null,
  assignedToName: null,
  createdBy: 'u-9',
  createdByName: 'Asha Rao',
  createdAt: '2026-10-17T08:00:00Z',
  lastMessageAt: '2026-10-17T09:00:00Z',
  closedAt: null,
  messageCount: 2,
  unreadCount: 0,
  lastMessagePreview: 'It shows an error',
  ...over,
})
const message = (over: Partial<MessageView> = {}): MessageView => ({
  id: 'm-1',
  seq: 1,
  side: 'COMMUNITY',
  senderId: 'u-9',
  senderName: 'Asha Rao',
  body: 'I cannot download a receipt.',
  attachment: null,
  createdAt: '2026-10-17T08:00:00Z',
  readAt: null,
  ...over,
})
const messagesPage = (
  items: MessageView[],
  status: ThreadView['status'] = 'OPEN',
): MessagePage => ({
  items,
  cursor: items.at(-1)?.seq ?? 0,
  hasMore: false,
  thread: {
    status,
    priority: 'MEDIUM',
    assignedTo: null,
    messageCount: items.length,
    lastMessageAt: '2026-10-17T09:00:00Z',
  },
})

/** A desk with one thread; each test overrides what it cares about. */
function oneThread(over: Partial<ThreadView> = {}, msgs: MessageView[] = [message()]) {
  return {
    'GET /admin/support/threads': ok(page([thread(over)])),
    'GET /admin/support/threads/t-1': ok(thread(over)),
    'GET /admin/support/threads/t-1/messages': ok(messagesPage(msgs, over.status ?? 'OPEN')),
  }
}

// ---- inbox ------------------------------------------------------------------------------------

describe('Support inbox', () => {
  it('lists conversations with status, unread and assignee, shows the counts, and passes axe', async () => {
    api({
      'GET /admin/support/threads': ok(
        page([
          thread({ unreadCount: 2, priority: 'URGENT' }),
          thread({
            id: 't-2',
            subject: 'Plan question',
            assignedToName: 'Priya',
            status: 'WAITING',
          }),
        ]),
      ),
    })
    const { container } = renderApp('/superadmin/support')

    const list = await screen.findByRole('list', { name: 'Conversations' })
    expect(within(list).getByText('Cannot download receipts')).toBeInTheDocument()
    expect(within(list).getByText('2 unread')).toBeInTheDocument()
    expect(within(list).getByText('Urgent')).toBeInTheDocument()
    expect(within(list).getByText('Priya')).toBeInTheDocument()
    expect(within(list).getByText('Unassigned')).toBeInTheDocument()
    // Counts on the filters: open + waiting, unread threads, unassigned, urgent.
    expect(await screen.findByRole('button', { name: /Actives*,s*5/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Unreads*,s*4/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Unassigneds*,s*2/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Urgents*,s*1/ })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('asks for the active conversations by default, and the filter you pick otherwise', async () => {
    const seen: string[] = []
    api({
      'GET /admin/support/threads': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?communityId=com-1')

    expect(await screen.findByText('No conversations match')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('status=OPEN')
    expect(seen.at(-1)).toContain('status=WAITING')
    expect(seen.at(-1)).toContain('communityId=com-1')

    await user.click(screen.getByRole('button', { name: /Unassigned/ }))
    await waitFor(() => {
      expect(seen.at(-1)).toContain('unassigned=true')
    })
    await user.click(screen.getByRole('button', { name: /Mine/ }))
    await waitFor(() => {
      expect(seen.at(-1)).toContain('mine=true')
    })
    await user.click(screen.getByRole('button', { name: /Closed/ }))
    await waitFor(() => {
      expect(seen.at(-1)).toContain('status=CLOSED')
    })
    expect(seen.at(-1)).not.toContain('status=OPEN')
  })

  it('has an empty state, and an error state with a retry', async () => {
    api({ 'GET /admin/support/threads': ok(page([])) })
    renderApp('/superadmin/support')
    expect(await screen.findByText('Nothing needs an answer')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({ 'GET /admin/support/threads': () => problem(500, 'INTERNAL_ERROR') })
    renderApp('/superadmin/support')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('pages the list with "Load more"', async () => {
    const seen: string[] = []
    api({
      'GET /admin/support/threads': (config) => {
        const text = query(config)
        seen.push(text)
        const first = text.includes('page=0')
        return {
          status: 200,
          data: first
            ? { items: [thread()], total: 2, page: 0, size: 1 }
            : {
                items: [thread({ id: 't-2', subject: 'Second page' })],
                total: 2,
                page: 1,
                size: 1,
              },
        }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support')

    await user.click(await screen.findByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('Second page')).toBeInTheDocument()
    expect(screen.getByText('Cannot download receipts')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('page=1')
  })
})

// ---- conversation -----------------------------------------------------------------------------

describe('Support conversation', () => {
  it('shows the messages, marks the customer’s as read, and passes axe', async () => {
    let read: Record<string, unknown> | undefined
    api({
      ...oneThread({ unreadCount: 1 }, [
        message(),
        message({ id: 'm-2', seq: 2, side: 'PLATFORM', senderName: 'Priya', body: 'Looking now.' }),
      ]),
      'POST /admin/support/threads/t-1/read': (config) => {
        read = body(config)
        return { status: 200, data: SUMMARY }
      },
    })
    const { container } = renderApp('/superadmin/support?thread=t-1')

    const log = await screen.findByRole('log', { name: 'Messages' })
    expect(await within(log).findByText('I cannot download a receipt.')).toBeInTheDocument()
    expect(within(log).getByText('Looking now.')).toBeInTheDocument()
    expect(within(log).getByText(/\(support\)/)).toBeInTheDocument()
    await waitFor(() => {
      expect(read).toEqual({ upToSeq: 2 })
    })
    await expectNoA11yViolations(container)
  })

  it('does not mark anything read when nothing is unread, or while the tab is hidden', async () => {
    const reads = vi.fn(() => ({ status: 200, data: SUMMARY }))
    api({ ...oneThread({ unreadCount: 0 }), 'POST /admin/support/threads/t-1/read': reads })
    renderApp('/superadmin/support?thread=t-1')
    await screen.findByText('I cannot download a receipt.')
    expect(reads).not.toHaveBeenCalled()
    cleanup()
    mock?.restore()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    api({ ...oneThread({ unreadCount: 3 }), 'POST /admin/support/threads/t-1/read': reads })
    renderApp('/superadmin/support?thread=t-1')
    await screen.findByText('I cannot download a receipt.')
    expect(reads).not.toHaveBeenCalled()
  })

  it('sends a reply with Ctrl and Enter, clears the box, and keeps it when sending fails', async () => {
    const sent: Array<Record<string, unknown>> = []
    let fail = true
    api({
      ...oneThread(),
      'POST /admin/support/threads/t-1/messages': (config) => {
        if (fail) return problem(500, 'INTERNAL_ERROR')
        sent.push(body(config))
        return {
          status: 201,
          data: message({ id: 'm-9', seq: 3, side: 'PLATFORM', body: 'Fixed it.' }),
        }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?thread=t-1')

    const box = await screen.findByLabelText('Reply')
    await user.type(box, 'Fixed it.')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(box).toHaveValue('Fixed it.') // not lost

    fail = false
    await user.type(box, '{Control>}{Enter}{/Control}')
    await waitFor(() => {
      expect(sent).toEqual([{ body: 'Fixed it.' }])
    })
    await waitFor(() => {
      expect(box).toHaveValue('')
    })
  })

  it('will not send an empty reply', async () => {
    api(oneThread())
    renderApp('/superadmin/support?thread=t-1')
    expect(await screen.findByRole('button', { name: 'Send reply' })).toBeDisabled()
  })

  it('uploads an attachment first, then sends its key with the reply', async () => {
    const { uploadToPresignedUrl } = await import('@/lib/upload')
    let posted: Record<string, unknown> | undefined
    api({
      ...oneThread(),
      'POST /admin/support/threads/t-1/attachments/upload-url': (config) => {
        expect(body(config)).toEqual({ contentType: 'image/png', sizeBytes: 3 })
        return {
          status: 200,
          data: {
            uploadUrl: 'https://files.example.test/up',
            method: 'PUT',
            headers: {},
            attachmentKey: 'support/key-1',
            expiresAt: '2026-10-18T10:00:00Z',
            maxBytes: 5_000_000,
          },
        }
      },
      'POST /admin/support/threads/t-1/messages': (config) => {
        posted = body(config)
        return { status: 201, data: message({ id: 'm-9', seq: 3, side: 'PLATFORM' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?thread=t-1')

    await user.type(await screen.findByLabelText('Reply'), 'See the screenshot')
    await user.upload(
      document.querySelector<HTMLInputElement>('#support-attachment')!,
      new File(['abc'], 'shot.png', { type: 'image/png' }),
    )
    expect(screen.getByText('shot.png (3 B)')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    await waitFor(() => {
      expect(posted).toEqual({
        body: 'See the screenshot',
        attachmentKey: 'support/key-1',
        attachmentName: 'shot.png',
      })
    })
    expect(uploadToPresignedUrl).toHaveBeenCalled()
  })

  it('refuses an attachment type that is not allowed, before anything is sent', async () => {
    const asked = vi.fn(() => ({ status: 200, data: {} }))
    api({ ...oneThread(), 'POST /admin/support/threads/t-1/attachments/upload-url': asked })
    const user = userEvent.setup({ applyAccept: false })
    renderApp('/superadmin/support?thread=t-1')

    await screen.findByLabelText('Reply')
    await user.upload(
      document.querySelector<HTMLInputElement>('#support-attachment')!,
      new File(['x'], 'run.exe', { type: 'application/x-msdownload' }),
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(/not allowed/)
    expect(asked).not.toHaveBeenCalled()
  })

  it('shows an attachment as a download link', async () => {
    api(
      oneThread({}, [
        message({
          attachment: {
            name: 'receipt.pdf',
            contentType: 'application/pdf',
            size: 2048,
            downloadUrl: 'https://files.example.test/d',
          },
        }),
      ]),
    )
    renderApp('/superadmin/support?thread=t-1')
    const link = await screen.findByRole('link', { name: /receipt\.pdf/ })
    expect(link).toHaveAttribute('href', 'https://files.example.test/d')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('assigns to me, changes the priority, and resolves', async () => {
    const sent: Array<Record<string, unknown>> = []
    api({
      ...oneThread(),
      'PATCH /admin/support/threads/t-1': (config) => {
        sent.push(body(config))
        return { status: 200, data: thread() }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?thread=t-1')

    await user.click(await screen.findByRole('button', { name: 'Assign to me' }))
    await waitFor(() => {
      expect(sent).toContainEqual({ assignedTo: ME_ID })
    })
    await user.click(screen.getByRole('combobox', { name: 'Priority' }))
    await user.click(await screen.findByRole('option', { name: 'Urgent' }))
    await waitFor(() => {
      expect(sent).toContainEqual({ priority: 'URGENT' })
    })
    await user.click(screen.getByRole('button', { name: 'Resolve' }))
    await waitFor(() => {
      expect(sent).toContainEqual({ status: 'RESOLVED' })
    })
  })

  it('offers to unassign when the thread is mine', async () => {
    const sent: Array<Record<string, unknown>> = []
    api({
      ...oneThread({ assignedTo: ME_ID, assignedToName: 'Platform Admin' }),
      'PATCH /admin/support/threads/t-1': (config) => {
        sent.push(body(config))
        return { status: 200, data: thread() }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?thread=t-1')

    await user.click(await screen.findByRole('button', { name: 'Unassign' }))
    await waitFor(() => {
      expect(sent).toEqual([{ unassign: true }])
    })
  })

  it('closes only after a confirmation, and a closed thread cannot be replied to until reopened', async () => {
    const sent: Array<Record<string, unknown>> = []
    api({
      ...oneThread(),
      'PATCH /admin/support/threads/t-1': (config) => {
        sent.push(body(config))
        return { status: 200, data: thread({ status: 'CLOSED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/support?thread=t-1')

    await user.click(await screen.findByRole('button', { name: 'Close' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(sent).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Close conversation' }))
    await waitFor(() => {
      expect(sent).toEqual([{ status: 'CLOSED' }])
    })
    cleanup()
    mock?.restore()

    api(oneThread({ status: 'CLOSED' }))
    renderApp('/superadmin/support?thread=t-1')
    expect(
      await screen.findByText('This conversation is closed. Reopen it to reply.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('Reply')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reopen' })).toBeInTheDocument()
  })

  it('shows an error with a way back when the conversation cannot be loaded', async () => {
    api({
      'GET /admin/support/threads': ok(page([])),
      'GET /admin/support/threads/t-1': () => problem(404, 'NOT_FOUND'),
      'GET /admin/support/threads/t-1/messages': () => problem(404, 'NOT_FOUND'),
    })
    renderApp('/superadmin/support?thread=t-1')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /All conversations/ })).toBeInTheDocument()
  })
})

// ---- polling ----------------------------------------------------------------------------------

describe('Support polling', () => {
  it('asks for only what is new every 10 seconds, and appends it', async () => {
    const afters: string[] = []
    let batch = 0
    api({
      ...oneThread(),
      'GET /admin/support/threads/t-1/messages': (config) => {
        const params = config.params as { after: number }
        afters.push(String(params.after))
        batch += 1
        return {
          status: 200,
          data:
            batch === 1
              ? messagesPage([message()])
              : {
                  ...messagesPage([message({ id: 'm-2', seq: 2, body: 'A new reply' })]),
                  cursor: 2,
                },
        }
      },
    })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderApp('/superadmin/support?thread=t-1')

    expect(await screen.findByText('I cannot download a receipt.')).toBeInTheDocument()
    expect(afters).toEqual(['0'])
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500)
    })
    expect(await screen.findByText('A new reply')).toBeInTheDocument()
    expect(afters[1]).toBe('1') // after the last message it had
    expect(screen.getByText('I cannot download a receipt.')).toBeInTheDocument()
  })

  it('stops while the tab is hidden and catches up when it is shown again', async () => {
    const messages = vi.fn(() => ({ status: 200, data: messagesPage([message()]) }))
    api({ ...oneThread(), 'GET /admin/support/threads/t-1/messages': messages })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderApp('/superadmin/support?thread=t-1')
    await screen.findByText('I cannot download a receipt.')

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    const before = messages.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(35_000)
    })
    expect(messages.mock.calls.length).toBe(before) // nothing while hidden

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    await waitFor(() => {
      expect(messages.mock.calls.length).toBeGreaterThan(before)
    })
  })

  it('refreshes the list and counts when the summary shows that something moved', async () => {
    let summaryCalls = 0
    let listCalls = 0
    api({
      'GET /admin/support/threads': () => {
        listCalls += 1
        return { status: 200, data: page([thread()]) }
      },
      'GET /admin/support/summary': () => {
        summaryCalls += 1
        const seq = summaryCalls === 1 ? 2 : 3 // a new message arrives between polls
        return {
          status: 200,
          data: {
            unreadMessages: 0,
            unreadThreads: 0,
            activeThreads: 1,
            threads: [
              {
                id: 't-1',
                messageSeq: seq,
                status: 'OPEN',
                unreadCount: 0,
                lastMessageAt: '2026-10-17T09:00:00Z',
              },
            ],
          },
        }
      },
    })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderApp('/superadmin/support')

    await screen.findByText('Cannot download receipts')
    await waitFor(() => {
      expect(summaryCalls).toBe(1)
    })
    const listBefore = listCalls
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500)
    })
    await waitFor(() => {
      expect(summaryCalls).toBeGreaterThanOrEqual(2)
      expect(listCalls).toBeGreaterThan(listBefore)
    })
  })
})
