import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, MemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '@/routes'
import { expectNoA11yViolations } from '@/test/a11y'
import { installMockApi, problem } from '@/test/mockApi'
import { Navbar } from './Navbar'
import Faq from './sections/Faq'
import DemoForm from './sections/DemoForm'
import Pricing from './sections/Pricing'
import { leadSchema, toLeadRequest } from './leadSchema'
import { siteMeta } from '../../../vite-plugin-site-meta.ts'

function withClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

let mock: ReturnType<typeof installMockApi> | undefined
afterEach(() => {
  mock?.restore()
  mock = undefined
})

describe('the whole landing page', () => {
  it('renders every section, has one h1, and has no axe violations', async () => {
    mock = installMockApi({ 'GET /public/plans': () => problem(404, 'NOT_FOUND') })
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { container } = render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    // Without IntersectionObserver (jsdom) the deferred sections mount straight away.
    const headings = [
      'Everything your community runs on',
      'Built for the people who keep a community running',
      'An amanah is something held in trust',
      'Up and running in three steps',
      'Handling your members’ details with care',
      'Simple plans, no surprises',
      'Questions people ask first',
      'See it with your own community in mind',
      'Ready to run your community with more clarity?',
    ]
    for (const name of headings) {
      expect(
        await screen.findByRole('heading', { level: 2, name }, { timeout: 5000 }),
      ).toBeInTheDocument()
    }
    expect(await screen.findByText('Plans sized to your community')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('main')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 30_000)

  it('has the five use cases and eight features asked for', async () => {
    mock = installMockApi({ 'GET /public/plans': () => problem(404, 'NOT_FOUND') })
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    const solutions = await screen.findByRole(
      'region',
      { name: 'Everything your community runs on' },
      { timeout: 5000 },
    )
    expect(within(solutions).getAllByRole('heading', { level: 3 })).toHaveLength(5)
    const features = await screen.findByRole('region', { name: /Built for the people/ })
    expect(within(features).getAllByRole('heading', { level: 3 })).toHaveLength(8)
  })

  it('makes no invented claims: no ratings, testimonials or customer counts', async () => {
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
    mock = installMockApi({ 'GET /public/plans': () => problem(404, 'NOT_FOUND') })
    const { container } = render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    await screen.findByRole('heading', { name: 'Questions people ask first' }, { timeout: 5000 })
    const text = container.textContent ?? ''
    expect(text).not.toMatch(
      /testimonial|\d+\+ (communities|clients|customers)|rated|\bawards?\b|trusted by/i,
    )
  })
})

describe('Navbar', () => {
  it('links to each section and opens a mobile drawer with the same links', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <Navbar />
      </MemoryRouter>,
    )
    const bar = screen.getByRole('navigation', { name: 'Sections' })
    for (const label of ['Solutions', 'Features', 'Security', 'Pricing', 'FAQ']) {
      expect(within(bar).getByRole('link', { name: label })).toHaveAttribute(
        'href',
        `/#${label.toLowerCase()}`,
      )
    }
    expect(screen.getByRole('link', { name: 'Login' })).toHaveAttribute('href', '/login')
    expect(screen.getByRole('link', { name: 'Request a demo' })).toHaveAttribute('href', '/#demo')

    await user.click(screen.getByRole('button', { name: 'Open menu' }))
    const drawer = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(drawer).getAllByRole('link')).toHaveLength(7)
    await user.click(within(drawer).getByRole('link', { name: 'Pricing' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
    })
  })
})

describe('Pricing', () => {
  it('shows an honest fallback when the plans endpoint is missing, with no invented prices', async () => {
    mock = installMockApi({ 'GET /public/plans': () => problem(404, 'NOT_FOUND') })
    const { container } = withClient(<Pricing />)
    expect(await screen.findByText('Plans sized to your community')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/₹/)
    expect(screen.getByRole('link', { name: 'Request a demo' })).toHaveAttribute('href', '/#demo')
  })

  it('shows loaded plans with a billing toggle, a highlighted middle plan and a purple Enterprise card', async () => {
    const plan = (
      code: string,
      name: string,
      monthly: string,
      yearly: string,
      limits = {},
      features = {},
    ) => ({
      code,
      name,
      priceMonthly: monthly,
      priceYearly: yearly,
      limits,
      features,
    })
    mock = installMockApi({
      'GET /public/plans': () => ({
        status: 200,
        data: [
          plan(
            'A',
            'Plan A',
            '0.00',
            '0.00',
            { max_members: 100, storage_mb: 500 },
            { upi_qr: true, pdf_reports: false },
          ),
          plan(
            'B',
            'Plan B',
            '500.00',
            '5000.00',
            { max_members: 1000, storage_mb: 5120 },
            { pdf_reports: true },
          ),
          plan(
            'ENTERPRISE',
            'Plan C',
            '2000.00',
            '20000.00',
            { max_members: null },
            { priority_support: true },
          ),
        ],
      }),
    })
    const user = userEvent.setup()
    const { container } = withClient(<Pricing />)

    expect(await screen.findByRole('heading', { name: 'Plan B' })).toBeInTheDocument()
    const cards = screen.getAllByRole('listitem').filter((li) => li.querySelector('h3'))
    expect(cards).toHaveLength(3)
    expect(within(cards[0] as HTMLElement).getByText('Free')).toBeInTheDocument()
    expect(within(cards[0] as HTMLElement).getByText('Up to 100 members')).toBeInTheDocument()
    expect(within(cards[0] as HTMLElement).getByText('UPI QR payments')).toBeInTheDocument()
    expect(within(cards[0] as HTMLElement).queryByText('PDF reports')).not.toBeInTheDocument()
    expect(within(cards[1] as HTMLElement).getByText('Recommended')).toBeInTheDocument() // the middle one
    expect(within(cards[1] as HTMLElement).getByText('₹500')).toBeInTheDocument()
    expect(within(cards[1] as HTMLElement).getByText('5 GB of file storage')).toBeInTheDocument()
    expect(within(cards[2] as HTMLElement).getByText('Unlimited members')).toBeInTheDocument()
    expect(
      within(cards[2] as HTMLElement).getByRole('link', { name: 'Contact us' }),
    ).toBeInTheDocument()
    expect(within(cards[2] as HTMLElement).queryByText('₹2,000')).not.toBeInTheDocument() // Enterprise shows Contact us

    await user.click(screen.getByRole('button', { name: 'Yearly' }))
    expect(within(cards[1] as HTMLElement).getByText('₹5,000')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Yearly' })).toHaveAttribute('aria-pressed', 'true')
    await expectNoA11yViolations(container)
  })

  it('ignores malformed plan data rather than showing nonsense', async () => {
    mock = installMockApi({
      'GET /public/plans': () => ({
        status: 200,
        data: [{ code: 'X', name: 'Broken', priceMonthly: 'free' }, 42],
      }),
    })
    withClient(<Pricing />)
    expect(await screen.findByText('Plans sized to your community')).toBeInTheDocument()
  })
})

describe('FAQ', () => {
  it('has at least eight questions and opens one answer at a time', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <Faq />
      </MemoryRouter>,
    )
    const questions = screen.getAllByRole('button', { expanded: false })
    expect(questions.length).toBeGreaterThanOrEqual(8)
    const first = screen.getByRole('button', { name: 'Do members need to log in?' })
    await user.click(first)
    expect(first).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/Only admins have accounts/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'How do payments work?' }))
    expect(first).toHaveAttribute('aria-expanded', 'false')
  })
})

describe('Demo request form', () => {
  const fill = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(screen.getByLabelText(/Your name/), 'Asha Rao')
    await user.type(screen.getByLabelText(/^Email/), 'asha@example.com')
  }

  it('shows accessible errors for the required fields and sends nothing', async () => {
    mock = installMockApi({})
    const user = userEvent.setup()
    const { container } = withClient(<DemoForm />)
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.map((a) => a.textContent)).toEqual([
      'Enter your name',
      'Enter your email address',
    ])
    expect(screen.getByLabelText(/Your name/)).toHaveAttribute('aria-invalid', 'true')
    expect(mock.calls).toHaveLength(0)
    await expectNoA11yViolations(container)
  })

  it('validates phone and size before sending', async () => {
    mock = installMockApi({})
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.type(screen.getByLabelText(/Phone/), 'call me')
    await user.type(screen.getByLabelText(/Approximate number/), '0')
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    expect(await screen.findByText('Enter a valid phone number')).toBeInTheDocument()
    expect(screen.getByText('Enter a whole number between 1 and 10,000,000')).toBeInTheDocument()
    expect(mock.calls).toHaveLength(0)
  })

  it('posts only what was filled in to /public/leads, then shows a thank-you', async () => {
    let body: unknown
    mock = installMockApi({
      'POST /public/leads': (config) => {
        body = JSON.parse(config.data as string)
        return { status: 202, data: { received: true } }
      },
    })
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.type(screen.getByLabelText(/Community name/), 'Lotus Residents')
    await user.type(screen.getByLabelText(/Approximate number/), '240')
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))

    expect(await screen.findByRole('status')).toHaveTextContent('asha@example.com')
    expect(body).toEqual({
      name: 'Asha Rao',
      email: 'asha@example.com',
      communityName: 'Lotus Residents',
      size: 240,
      website: '',
    })
    expect(mock.calls[0]?.authorization).toBeUndefined() // a public form never sends a token
    expect(screen.getByRole('status')).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Send another request' }))
    expect(screen.getByLabelText(/Your name/)).toHaveValue('')
  })

  it('hides the honeypot from people and assistive technology', () => {
    withClient(<DemoForm />)
    const trap = screen.getByLabelText('Leave this field empty', { selector: 'input' })
    expect(trap).toHaveAttribute('tabindex', '-1')
    expect(trap.closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it('passes a filled honeypot to the API, which silently drops it', async () => {
    let body: Record<string, unknown> = {}
    mock = installMockApi({
      'POST /public/leads': (config) => {
        body = JSON.parse(config.data as string) as Record<string, unknown>
        return { status: 202, data: { received: true } }
      },
    })
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.type(
      screen.getByLabelText('Leave this field empty', { selector: 'input' }),
      'http://spam.example',
    )
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    await screen.findByRole('status')
    expect(body.website).toBe('http://spam.example')
  })

  it('shows server field errors on the field', async () => {
    mock = installMockApi({
      'POST /public/leads': () =>
        problem(400, 'VALIDATION_FAILED', 'One or more fields are invalid.', {
          errors: [{ field: 'email', message: 'must be a well-formed email address' }],
        }),
    })
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Email/)).toHaveAttribute('aria-invalid', 'true')
  })

  it('tells people how long to wait when rate limited, and keeps what they typed', async () => {
    mock = installMockApi({
      'POST /public/leads': () =>
        problem(429, 'RATE_LIMITED', 'slow', {}, { 'retry-after': '120' }),
    })
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/Too many attempts.*2 minutes/)
    expect(screen.getByLabelText(/Your name/)).toHaveValue('Asha Rao')
  })

  it('says so plainly when the server cannot be reached', async () => {
    mock = installMockApi({ 'POST /public/leads': () => problem(503, 'INTERNAL_ERROR') })
    const user = userEvent.setup()
    withClient(<DemoForm />)
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Request a demo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/something went wrong on our side/i)
  })
})

describe('lead schema', () => {
  it('accepts the minimum and builds a request without empty fields', () => {
    const parsed = leadSchema.parse({
      name: '  Asha ',
      email: ' a@b.co ',
      phone: '',
      communityName: '',
      size: '',
      message: '',
      website: '',
    })
    expect(toLeadRequest(parsed)).toEqual({ name: 'Asha', email: 'a@b.co', website: '' })
  })

  it.each([['0'], ['10000001'], ['1.5'], ['abc'], ['-3']])('rejects a size of %s', (size) => {
    const result = leadSchema.safeParse({
      name: 'A',
      email: 'a@b.co',
      phone: '',
      communityName: '',
      size,
      message: '',
      website: '',
    })
    expect(result.success).toBe(false)
  })
})

describe('site meta plugin', () => {
  type Handler = (html: string) => string
  const transform = (url: string, html: string): string => {
    const plugin = siteMeta(url)
    const hook = plugin.transformIndexHtml as { handler: Handler }
    return hook.handler(html)
  }
  const html = [
    '<head>',
    '<meta property="og:title" content="T" />',
    '<meta property="og:image" content="__SITE_URL__/brand/og-image.png" data-needs-site-url />',
    '<!--JSON_LD-->',
    '</head>',
  ].join('\n')

  it('uses the configured address and writes JSON-LD for the organisation and the software', () => {
    const out = transform('https://example.test/', html)
    expect(out).toContain('content="https://example.test/brand/og-image.png"')
    const json = /<script type="application\/ld\+json">(.*)<\/script>/.exec(out)?.[1] ?? '{}'
    const graph = (JSON.parse(json) as { '@graph': Array<Record<string, unknown>> })['@graph']
    expect(graph.map((node) => node['@type'])).toEqual(['Organization', 'SoftwareApplication'])
    expect(graph[0]?.url).toBe('https://example.test/')
    expect(JSON.stringify(graph)).not.toMatch(/aggregateRating|offers|review/i)
  })

  it('drops address-dependent tags rather than guessing a domain', () => {
    const out = transform('', html)
    expect(out).not.toContain('__SITE_URL__')
    expect(out).not.toContain('og:image')
    expect(out).toContain('og:title')
    expect(out).toContain('"@type":"Organization"')
  })
})
