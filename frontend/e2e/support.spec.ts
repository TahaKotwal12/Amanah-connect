import { expect, test, type BrowserContext, type Route } from '@playwright/test'

/**
 * A support message makes the whole trip in real browsers: a community admin starts a
 * conversation, the platform SuperAdmin (a second, separate session) sees it and answers, and the
 * community admin sees the answer, with an unread badge, without reloading. Both sessions talk to
 * one shared stand-in for the support inbox.
 */
const COMMUNITY_ME = {
  user: {
    id: 'u1',
    email: 'admin@example.test',
    fullName: 'Community Admin',
    role: 'COMMUNITY_ADMIN',
    status: 'ACTIVE',
  },
  community: { id: 'c1', name: 'Lotus Residents', slug: 'lotus', status: 'ACTIVE' },
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: false },
}
const PLATFORM_ME = {
  user: {
    id: 'root1',
    email: 'root@example.test',
    fullName: 'Priya Support',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
  },
  community: null,
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: true },
}

interface Message {
  id: string
  seq: number
  side: 'COMMUNITY' | 'PLATFORM'
  body: string
  readByCommunity: boolean
  readByPlatform: boolean
}
interface Thread {
  id: string
  subject: string
  status: 'OPEN' | 'WAITING' | 'RESOLVED' | 'CLOSED'
  messages: Message[]
}

/** The inbox both sessions share. */
function newInbox() {
  const threads: Thread[] = []
  return { threads, reads: { community: 0, platform: 0 } }
}
type Inbox = ReturnType<typeof newInbox>

async function installApi(context: BrowserContext, inbox: Inbox, who: 'community' | 'platform') {
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })
  const page = <T>(items: T[]) => ({ items, total: items.length, page: 0, size: 30 })
  const prefix = who === 'community' ? '/community/support' : '/admin/support'
  const side = who === 'community' ? 'COMMUNITY' : 'PLATFORM'
  const unreadFor = (t: Thread) =>
    t.messages.filter(
      (m) => m.side !== side && !(who === 'community' ? m.readByCommunity : m.readByPlatform),
    ).length

  const threadView = (t: Thread) => {
    const last = t.messages.at(-1)
    return {
      id: t.id,
      communityId: 'c1',
      communityName: 'Lotus Residents',
      subject: t.subject,
      status: t.status,
      priority: 'MEDIUM',
      assignedTo: null,
      assignedToName: null,
      createdBy: 'u1',
      createdByName: 'Community Admin',
      createdAt: '2026-10-18T10:00:00Z',
      lastMessageAt: '2026-10-18T10:00:00Z',
      closedAt: null,
      messageCount: t.messages.length,
      unreadCount: unreadFor(t),
      lastMessagePreview: last?.body ?? null,
    }
  }
  const messageView = (m: Message) => ({
    id: m.id,
    seq: m.seq,
    side: m.side,
    senderId: m.side === 'COMMUNITY' ? 'u1' : 'root1',
    senderName: m.side === 'COMMUNITY' ? 'Community Admin' : 'Priya Support',
    body: m.body,
    attachment: null,
    createdAt: '2026-10-18T10:00:00Z',
    readAt: (who === 'community' ? m.readByCommunity : m.readByPlatform)
      ? '2026-10-18T10:05:00Z'
      : null,
  })
  const summary = () => ({
    unreadMessages: inbox.threads.reduce((n, t) => n + unreadFor(t), 0),
    unreadThreads: inbox.threads.filter((t) => unreadFor(t) > 0).length,
    activeThreads: inbox.threads.length,
    threads: inbox.threads.map((t) => ({
      id: t.id,
      messageSeq: t.messages.at(-1)?.seq ?? 0,
      status: t.status,
      unreadCount: unreadFor(t),
      lastMessageAt: '2026-10-18T10:00:00Z',
    })),
  })

  await context.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const key = `${request.method()} ${path}`
    const body = (request.postDataJSON() ?? {}) as Record<string, string | number | undefined>

    if (key === 'POST /auth/refresh')
      return json(route, {
        accessToken: who,
        tokenType: 'Bearer',
        expiresIn: 900,
        mfaSetupRequired: false,
      })
    if (key === 'GET /auth/me') return json(route, who === 'community' ? COMMUNITY_ME : PLATFORM_ME)
    if (key === 'GET /community/plan')
      return json(route, {
        planCode: 'STARTER',
        planName: 'Starter',
        limits: {},
        features: {},
        usage: { members: 1, emailsThisMonth: 0 },
        communityStatus: 'ACTIVE',
        subscription: null,
        expiringWindowDays: 30,
      })
    if (key === 'GET /community/notifications/summary')
      return json(route, { unread: 0, banners: [] })
    if (key === 'GET /community/settings')
      return json(route, {
        name: 'Lotus Residents',
        logoUrl: null,
        currency: 'INR',
        memberGroupLabel: 'Group',
      })
    if (key === 'GET /admin/support/counts')
      return json(route, {
        total: inbox.threads.length,
        open: inbox.threads.filter((t) => t.status === 'OPEN').length,
        waiting: 0,
        resolved: 0,
        closed: 0,
        activeUnassigned: inbox.threads.length,
        urgentActive: 0,
        unreadThreads: inbox.threads.filter((t) => unreadFor(t) > 0).length,
      })

    if (key === `GET ${prefix}/summary`) return json(route, summary())
    if (key === `GET ${prefix}/threads`) return json(route, page(inbox.threads.map(threadView)))
    if (key === `POST ${prefix}/threads` && who === 'community') {
      const made: Thread = {
        id: `t${inbox.threads.length + 1}`,
        subject: String(body.subject),
        status: 'OPEN',
        messages: [
          {
            id: 'm1',
            seq: 1,
            side: 'COMMUNITY',
            body: String(body.body),
            readByCommunity: true,
            readByPlatform: false,
          },
        ],
      }
      inbox.threads.push(made)
      return json(route, threadView(made), 201)
    }
    const one = new RegExp(`^(GET|POST) ${prefix}/threads/(t\\d+)(/messages|/read)?$`).exec(key)
    if (one) {
      const found = inbox.threads.find((t) => t.id === one[2])
      if (!found) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      if (!one[3]) return json(route, threadView(found))
      if (one[3] === '/messages' && one[1] === 'GET') {
        const after = Number(url.searchParams.get('after') ?? 0)
        const items = found.messages.filter((m) => m.seq > after).map(messageView)
        return json(route, {
          items,
          cursor: found.messages.at(-1)?.seq ?? 0,
          hasMore: false,
          thread: {
            status: found.status,
            priority: 'MEDIUM',
            assignedTo: null,
            messageCount: found.messages.length,
            lastMessageAt: '2026-10-18T10:00:00Z',
          },
        })
      }
      if (one[3] === '/messages') {
        const made: Message = {
          id: `m${found.messages.length + 1}`,
          seq: found.messages.length + 1,
          side,
          body: String(body.body),
          readByCommunity: who === 'community',
          readByPlatform: who === 'platform',
        }
        found.messages.push(made)
        found.status = who === 'platform' ? 'WAITING' : 'OPEN'
        return json(route, messageView(made), 201)
      }
      // /read
      for (const m of found.messages) {
        if (m.side !== side) {
          if (who === 'community') m.readByCommunity = true
          else m.readByPlatform = true
        }
      }
      inbox.reads[who] += 1
      return json(route, summary())
    }
    return json(route, page([]))
  })
}

test('a message from the community reaches the support team, and the answer comes back', async ({
  browser,
}) => {
  const inbox = newInbox()
  const communityContext = await browser.newContext()
  const platformContext = await browser.newContext()
  await installApi(communityContext, inbox, 'community')
  await installApi(platformContext, inbox, 'platform')
  const community = await communityContext.newPage()
  const platform = await platformContext.newPage()

  // 1. The community admin starts a conversation.
  await community.goto('/admin/support')
  await expect(community.getByRole('heading', { level: 1, name: 'Support' })).toBeVisible()
  await community.getByRole('button', { name: 'Start a conversation' }).first().click()
  const dialog = community.getByRole('dialog', { name: 'Start a conversation' })
  await dialog.getByLabel('Subject').fill('Cannot download a receipt')
  await dialog.getByLabel('Message').fill('The PDF is blank.')
  await dialog.getByRole('button', { name: 'Send' }).click()
  await expect(
    community.getByRole('heading', { level: 2, name: 'Cannot download a receipt' }),
  ).toBeVisible()
  expect(inbox.threads).toHaveLength(1)

  // 2. The SuperAdmin session sees it, unread, opens it and answers.
  await platform.goto('/superadmin/support')
  const row = platform.getByRole('button', { name: /Cannot download a receipt/ })
  await expect(row).toBeVisible()
  await expect(row).toContainText('1 unread')
  await row.click()
  const platformLog = platform.getByRole('log', { name: 'Messages' })
  await expect(platformLog).toContainText('The PDF is blank.')
  await expect.poll(() => inbox.reads.platform).toBeGreaterThan(0)
  await platform.getByRole('textbox', { name: 'Reply' }).fill('We are looking into it now.')
  await platform.getByRole('button', { name: 'Send reply' }).click()
  await expect(platformLog).toContainText('We are looking into it now.')

  // 3. The community admin sees the answer arrive (the page polls every 10 seconds).
  await community.clock.fastForward(11_000)
  const log = community.getByRole('list', { name: 'Messages' })
  await expect(log).toContainText('We are looking into it now.', { timeout: 15_000 })
  await expect(log).toContainText('(support team)')
  await expect.poll(() => inbox.reads.community).toBeGreaterThan(0)
  await expect(community.getByText('Waiting').first()).toBeVisible()
})
