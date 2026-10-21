import { act, cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type * as Upload from '@/lib/upload'
import type { MessagePage, MessageView, SupportSummary, ThreadView } from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 30_000 })

vi.mock('@/lib/upload', async (importOriginal) => ({
  ...(await importOriginal<typeof Upload>()),
  uploadToPresignedUrl: vi.fn(() => Promise.resolve()),
}))

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const page = <T,>(items: T[]) => ({ items: [...items], total: items.length, page: 0, size: 30 })

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
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
})

const thread = (over: Partial<ThreadView> = {}): ThreadView => ({
  id: 't1',
  communityId: 'c1',
  communityName: 'Lotus Residents',
  subject: 'Cannot download a receipt',
  status: 'OPEN',
  priority: 'MEDIUM',
  assignedTo: null,
  assignedToName: null,
  createdBy: 'u1',
  createdByName: 'Community Admin',
  createdAt: '2026-10-10T10:00:00Z',
  lastMessageAt: '2026-10-10T10:00:00Z',
  closedAt: null,
  messageCount: 1,
  unreadCount: 0,
  lastMessagePreview: 'The PDF is blank.',
  ...over,
})

const message = (over: Partial<MessageView> = {}): MessageView => ({
  id: 'm1',
  seq: 1,
  side: 'COMMUNITY',
  senderId: 'u1',
  senderName: 'Community Admin',
  body: 'The PDF is blank.',
  attachment: null,
  createdAt: '2026-10-10T10:00:00Z',
  readAt: null,
  ...over,
})

const messagesPage = (items: MessageView[], cursor = items.at(-1)?.seq ?? 0): MessagePage => ({
  items,
  cursor,
  hasMore: false,
  thread: {
    status: 'OPEN',
    priority: 'MEDIUM',
    assignedTo: null,
    messageCount: items.length,
    lastMessageAt: '2026-10-10T10:00:00Z',
  },
})

const summary = (over: Partial<SupportSummary> = {}): SupportSummary => ({
  unreadMessages: 0,
  unreadThreads: 0,
  activeThreads: 1,
  threads: [],
  ...over,
})

function oneThread(extra: Record<string, MockHandler> = {}, t: ThreadView = thread()) {
  return {
    'GET /community/support/threads': ok(page([t])),
    'GET /community/support/threads/t1': ok(t),
    'GET /community/support/threads/t1/messages': ok(messagesPage([message()])),
    ...extra,
  }
}

describe('thread list', () => {
  it('shows each conversation with its status and an unread badge', async () => {
    api(oneThread({}, thread({ unreadCount: 2, status: 'WAITING' })))
    render('/admin/support')
    const list = await screen.findByRole('list', { name: 'Conversations' })
    expect(within(list).getByText('Cannot download a receipt')).toBeInTheDocument()
    expect(within(list).getByText('2 new replies')).toBeInTheDocument()
    expect(within(list).getByText('Waiting')).toBeInTheDocument()
  })

  it('asks only for unread conversations in that view, and for live ones by default', async () => {
    const list = vi.fn<MockHandler>(() => ({ status: 200, data: page([thread()]) }))
    api(oneThread({ 'GET /community/support/threads': list }))
    const user = userEvent.setup()
    render('/admin/support')
    await screen.findByRole('list', { name: 'Conversations' })
    expect((list.mock.calls[0]![0].params as URLSearchParams).getAll('status')).toEqual([
      'OPEN',
      'WAITING',
      'RESOLVED',
    ])
    await user.click(screen.getByRole('button', { name: /^Unread/ }))
    await waitFor(() =>
      expect((list.mock.calls.at(-1)![0].params as URLSearchParams).get('unread')).toBe('true'),
    )
  })

  it('invites a conversation when none are active, and shows everything on request', async () => {
    api()
    const user = userEvent.setup()
    render('/admin/support')
    expect(await screen.findByText('No active conversations')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show all conversations' }))
    expect(await screen.findByText('No conversations yet')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Start a conversation' }).length).toBeGreaterThan(
      0,
    )
  })
})

describe('conversation', () => {
  it('shows our messages and the support team’s, and marks their replies read once', async () => {
    const reads = vi.fn<MockHandler>(() => ({ status: 200, data: summary() }))
    api(
      oneThread({
        'GET /community/support/threads/t1/messages': ok(
          messagesPage([
            message(),
            message({
              id: 'm2',
              seq: 2,
              side: 'PLATFORM',
              senderName: 'Priya',
              body: 'Looking now.',
            }),
          ]),
        ),
        'POST /community/support/threads/t1/read': reads,
      }),
    )
    render('/admin/support?thread=t1')
    const log = await screen.findByRole('list', { name: 'Messages' })
    expect(within(log).getByText('The PDF is blank.')).toBeInTheDocument()
    expect(within(log).getByText('Looking now.')).toBeInTheDocument()
    expect(within(log).getByText(/\(support team\)/)).toBeInTheDocument()
    await waitFor(() => expect(reads).toHaveBeenCalledTimes(1))
    expect(body(reads.mock.calls[0]![0])).toEqual({ upToSeq: 2 })
  })

  it('does not mark anything read while the tab is hidden', async () => {
    const reads = vi.fn<MockHandler>(() => ({ status: 200, data: summary() }))
    api(
      oneThread({
        'GET /community/support/threads/t1/messages': ok(
          messagesPage([message({ id: 'm2', seq: 2, side: 'PLATFORM', body: 'Looking now.' })]),
        ),
        'POST /community/support/threads/t1/read': reads,
      }),
    )
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    render('/admin/support?thread=t1')
    await screen.findByText('Looking now.')
    expect(reads).not.toHaveBeenCalled()
  })

  it('sends a reply with Ctrl and Enter and clears the box', async () => {
    const post = vi.fn<MockHandler>(() => ({
      status: 201,
      data: message({ id: 'm9', seq: 2, body: 'Thanks' }),
    }))
    api(oneThread({ 'POST /community/support/threads/t1/messages': post }))
    const user = userEvent.setup()
    render('/admin/support?thread=t1')
    const box = await screen.findByRole('textbox', { name: 'Reply' })
    await user.type(box, 'Thanks')
    await user.keyboard('{Control>}{Enter}{/Control}')
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    expect(body(post.mock.calls[0]![0])).toEqual({ body: 'Thanks' })
    await waitFor(() => expect(box).toHaveValue(''))
  })

  it('keeps what was typed when sending fails', async () => {
    api(
      oneThread({
        'POST /community/support/threads/t1/messages': () => problem(500, 'INTERNAL_ERROR'),
      }),
    )
    const user = userEvent.setup()
    render('/admin/support?thread=t1')
    const box = await screen.findByRole('textbox', { name: 'Reply' })
    await user.type(box, 'Please help')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(box).toHaveValue('Please help')
  })

  it('uploads an attachment first, then sends its key with the reply', async () => {
    const { uploadToPresignedUrl } = await import('@/lib/upload')
    const post = vi.fn<MockHandler>(() => ({ status: 201, data: message({ id: 'm9', seq: 2 }) }))
    api(
      oneThread({
        'POST /community/support/attachments/upload-url': ok({
          uploadUrl: 'https://files.example.test/up',
          method: 'PUT',
          headers: {},
          attachmentKey: 'support/c1/abc.png',
          expiresAt: '2026-10-18T11:00:00Z',
          maxBytes: 5_000_000,
        }),
        'POST /community/support/threads/t1/messages': post,
      }),
    )
    const user = userEvent.setup()
    render('/admin/support?thread=t1')
    await user.type(await screen.findByRole('textbox', { name: 'Reply' }), 'See the screenshot')
    await user.upload(
      screen.getByLabelText('Attach a file'),
      new File(['x'], 'shot.png', { type: 'image/png' }),
    )
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    expect(uploadToPresignedUrl).toHaveBeenCalled()
    expect(body(post.mock.calls[0]![0])).toEqual({
      body: 'See the screenshot',
      attachmentKey: 'support/c1/abc.png',
      attachmentName: 'shot.png',
    })
  })

  it('refuses a file type that is not allowed, before asking to upload', async () => {
    const asked = vi.fn<MockHandler>(() => ({ status: 200, data: {} }))
    api(oneThread({ 'POST /community/support/attachments/upload-url': asked }))
    const user = userEvent.setup({ applyAccept: false })
    render('/admin/support?thread=t1')
    await user.upload(
      await screen.findByLabelText('Attach a file'),
      new File(['x'], 'virus.exe', { type: 'application/x-msdownload' }),
    )
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(asked).not.toHaveBeenCalled()
  })

  it('has no reply box on a closed conversation', async () => {
    api(oneThread({}, thread({ status: 'CLOSED' })))
    render('/admin/support?thread=t1')
    expect(await screen.findByText(/This conversation is closed/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Reply' })).not.toBeInTheDocument()
  })

  it('says a reply reopens a resolved conversation', async () => {
    api(oneThread({}, thread({ status: 'RESOLVED' })))
    render('/admin/support?thread=t1')
    expect(await screen.findByText('Reply (this reopens the conversation)')).toBeInTheDocument()
  })
})

describe('polling', () => {
  it('asks for news every 10 seconds, only for messages after the last one it has', async () => {
    const afters: string[] = []
    let batch = 0
    api(
      oneThread({
        'GET /community/support/threads/t1/messages': (config) => {
          batch += 1
          afters.push(String((config.params as { after: number }).after))
          return {
            status: 200,
            data:
              batch === 1
                ? messagesPage([message()])
                : messagesPage([
                    message({ id: 'm2', seq: 2, side: 'PLATFORM', body: 'A new reply' }),
                  ]),
          }
        },
      }),
    )
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render('/admin/support?thread=t1')
    const log = await screen.findByRole('list', { name: 'Messages' })
    expect(within(log).getByText('The PDF is blank.')).toBeInTheDocument()
    expect(afters).toEqual(['0'])
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500)
    })
    expect(await within(log).findByText('A new reply')).toBeInTheDocument()
    expect(afters[1]).toBe('1')
    expect(within(log).getByText('The PDF is blank.')).toBeInTheDocument()
  })

  it('pauses while the tab is hidden and catches up when it is shown again', async () => {
    const polls = vi.fn<MockHandler>(() => ({ status: 200, data: summary() }))
    api(oneThread({ 'GET /community/support/summary': polls }))
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render('/admin/support')
    await screen.findByRole('list', { name: 'Conversations' })

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    const before = polls.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(35_000)
    })
    expect(polls.mock.calls.length).toBe(before)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    await waitFor(() => expect(polls.mock.calls.length).toBeGreaterThan(before))
  })

  it('refreshes the list when the summary shows something moved', async () => {
    let summaries = 0
    let lists = 0
    api(
      oneThread({
        'GET /community/support/threads': () => {
          lists += 1
          return { status: 200, data: page([thread()]) }
        },
        'GET /community/support/summary': () => {
          summaries += 1
          return {
            status: 200,
            data: summary({
              threads: [
                {
                  id: 't1',
                  messageSeq: summaries === 1 ? 1 : 2,
                  status: 'OPEN',
                  unreadCount: 0,
                  lastMessageAt: '2026-10-10T10:00:00Z',
                },
              ],
            }),
          }
        },
      }),
    )
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render('/admin/support')
    await screen.findByRole('list', { name: 'Conversations' })
    const before = lists
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500)
    })
    await waitFor(() => expect(lists).toBeGreaterThan(before))
  })
})

describe('new conversation', () => {
  it('needs a subject and a message', async () => {
    const create = vi.fn<MockHandler>(() => ({ status: 201, data: thread() }))
    api({ 'POST /community/support/threads': create })
    const user = userEvent.setup()
    render('/admin/support')
    await user.click((await screen.findAllByRole('button', { name: 'Start a conversation' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Start a conversation' })
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(await within(dialog).findByText('Enter a subject')).toBeInTheDocument()
    expect(within(dialog).getByText('Describe what you need help with')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  it('starts a conversation and opens it', async () => {
    const create = vi.fn<MockHandler>(() => ({ status: 201, data: thread({ id: 't1' }) }))
    api(oneThread({ 'POST /community/support/threads': create }))
    const user = userEvent.setup()
    render('/admin/support')
    await user.click((await screen.findAllByRole('button', { name: 'Start a conversation' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Start a conversation' })
    await user.type(within(dialog).getByLabelText('Subject'), 'Receipt problem')
    await user.type(within(dialog).getByLabelText('Message'), 'The PDF is blank.')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(body(create.mock.calls[0]![0])).toEqual({
      subject: 'Receipt problem',
      body: 'The PDF is blank.',
      priority: 'MEDIUM',
    })
    expect(await screen.findByRole('region', { name: 'Conversation' })).toBeInTheDocument()
  })
})
