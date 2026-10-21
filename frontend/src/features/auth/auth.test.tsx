import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authStore } from '@/store/authStore'
import { expectNoA11yViolations } from '@/test/a11y'
import { meFor, resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { emptyCommunityApi } from '@/test/communityApi'
import { emptySuperadminApi } from '@/test/superadminApi'
import { cameFrom, renderApp } from '@/test/renderApp'
import type { UserRole } from '@/types'

const TOKEN = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  mfaSetupRequired: false,
}
const ok =
  (data: unknown): MockHandler =>
  () => ({ status: 200, data })

let mock: ReturnType<typeof installMockApi> | undefined
function api(handlers: Record<string, MockHandler>) {
  mock = installMockApi(handlers)
  return mock
}
const body = (config: { data?: unknown }) =>
  JSON.parse(config.data as string) as Record<string, unknown>

beforeEach(() => {
  setSession(null)
})
afterEach(() => {
  mock?.restore()
  mock = undefined
  resetSession()
})

async function signInWith(
  user: ReturnType<typeof userEvent.setup>,
  email = 'asha@example.com',
  password = 'correct-password-1',
) {
  await user.type(await screen.findByLabelText(/^Email/), email)
  await user.type(screen.getByLabelText(/^Password/), password)
  await user.click(screen.getByRole('button', { name: /^Sign in$|^Try again in/ }))
}

describe('Sign in', () => {
  it('has a labelled form with the email box focused, and passes axe', async () => {
    api({})
    const { container } = renderApp('/login')
    const email = await screen.findByLabelText(/^Email/, undefined, { timeout: 15_000 })
    await waitFor(() => {
      expect(email).toHaveFocus()
    })
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
    // The left panel carries the logo mark, the tagline and the four values.
    expect(screen.getByText('Connecting communities with trust')).toBeInTheDocument()
    for (const value of ['Trust', 'Integrity', 'Collaboration', 'Excellence']) {
      expect(screen.getByText(value)).toBeInTheDocument()
    }
    await expectNoA11yViolations(container)
  })

  it('does not shout about an empty box just because focus moved on (that would shove the links away)', async () => {
    api({})
    const user = userEvent.setup()
    renderApp('/login')
    await waitFor(() => {
      expect(screen.getByLabelText(/^Email/)).toHaveFocus()
    })
    await user.tab() // leave the empty email box
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Forgot your password?' }))
    await screen.findByRole('heading', { level: 1, name: 'Forgot your password?' })
  })

  it('checks both boxes before sending anything', async () => {
    const mocked = api({})
    const user = userEvent.setup()
    renderApp('/login')
    await user.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect((await screen.findAllByRole('alert')).map((a) => a.textContent)).toEqual([
      'Enter your email address',
      'Enter your password',
    ])
    await user.type(screen.getByLabelText(/^Email/), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText(/Enter a valid email address/)).toBeInTheDocument()
    expect(mocked.calls).toHaveLength(0)
  })

  it.each<[UserRole, string, string]>([
    ['SUPER_ADMIN', '/superadmin/dashboard', 'Platform dashboard'],
    ['COMMUNITY_ADMIN', '/admin/dashboard', 'Dashboard'],
  ])('sends a %s to %s', async (role, path, heading) => {
    const mocked = api({
      ...emptySuperadminApi(), // the dashboard behind the redirect loads its own data
      ...emptyCommunityApi(),
      'POST /auth/login': ok(TOKEN),
      'GET /auth/me': ok(meFor(role)),
    })
    const user = userEvent.setup()
    const { router } = renderApp('/login')
    await signInWith(user)
    await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe(path)
    const authCalls = mocked.calls.filter((c) => c.url.startsWith('/auth'))
    expect(authCalls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /auth/login',
      'GET /auth/me',
    ])
    expect(authCalls[1]?.authorization).toBe('Bearer access-1')
    expect(authStore.getState().user?.role).toBe(role)
  })

  it('sends the typed email and password, and nothing else, to /auth/login', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/login': (config) => {
        sent = body(config)
        return { status: 200, data: TOKEN }
      },
      'GET /auth/me': ok(meFor('COMMUNITY_ADMIN')),
    })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user, '  asha@example.com ', 'pa55 word!')
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' }, { timeout: 15_000 })
    expect(sent).toEqual({ email: 'asha@example.com', password: 'pa55 word!' })
  })

  it('returns to the page they were heading to, when it is in their own area', async () => {
    api({ 'POST /auth/login': ok(TOKEN), 'GET /auth/me': ok(meFor('COMMUNITY_ADMIN')) })
    const user = userEvent.setup()
    const { router } = renderApp(cameFrom('/admin/members', '?page=2'))
    await signInWith(user)
    await screen.findByRole('heading', { level: 1, name: 'Members' }, { timeout: 15_000 })
    expect(router.state.location.pathname + router.state.location.search).toBe(
      '/admin/members?page=2',
    )
  })

  it('does not send a community admin to a super admin page they were bounced from', async () => {
    api({ 'POST /auth/login': ok(TOKEN), 'GET /auth/me': ok(meFor('COMMUNITY_ADMIN')) })
    const user = userEvent.setup()
    const { router } = renderApp(cameFrom('/superadmin/plans'))
    await signInWith(user)
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe('/admin/dashboard')
  })

  it('forces the 2FA setup when the token says it is required', async () => {
    api({
      'POST /auth/login': ok({ ...TOKEN, mfaSetupRequired: true }),
      'GET /auth/me': ok(meFor('SUPER_ADMIN', { mfaSetupRequired: true, totpEnabled: false })),
      'POST /auth/2fa/setup': ok({
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP',
      }),
    })
    const user = userEvent.setup()
    const { router } = renderApp('/login')
    await signInWith(user)
    await screen.findByRole(
      'heading',
      { level: 1, name: 'Set up two-step verification' },
      { timeout: 15_000 },
    )
    expect(router.state.location.pathname).toBe('/2fa/setup')
  })

  it('moves to the second step when the account has 2FA, holding the challenge and no token', async () => {
    api({ 'POST /auth/login': ok({ mfaRequired: true, mfaToken: 'mfa-1', expiresIn: 300 }) })
    const user = userEvent.setup()
    const { router } = renderApp('/login')
    await signInWith(user)
    await screen.findByRole(
      'heading',
      { level: 1, name: 'Two-step verification' },
      { timeout: 15_000 },
    )
    expect(router.state.location.pathname).toBe('/login/2fa')
    expect(authStore.getState().accessToken).toBeNull()
    expect(authStore.getState().mfa.status).toBe('challenge')
  })

  it('gives one answer for a wrong password and for an address with no account', async () => {
    const replies = [problem(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.')]
    api({ 'POST /auth/login': () => replies[0] ?? { status: 500 } })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user, 'real.person@example.com', 'wrong-password-1')
    const first = (await screen.findByRole('alert')).textContent
    await user.clear(screen.getByLabelText(/^Email/))
    await signInWith(user, 'nobody.here@example.com', 'wrong-password-2')
    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe(first)
    })
    expect(first).toBe('The email or password is not correct.')
    expect(first).not.toMatch(/account|exist|register|found/i)
  })

  it('empties the password box after a failed attempt but keeps the email', async () => {
    api({ 'POST /auth/login': () => problem(401, 'INVALID_CREDENTIALS') })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user)
    await screen.findByRole('alert')
    expect(screen.getByLabelText(/^Password/)).toHaveValue('')
    expect(screen.getByLabelText(/^Email/)).toHaveValue('asha@example.com')
  })

  it('shows a rate limit with the wait time, counts down on the button, and holds the form', async () => {
    api({
      'POST /auth/login': () => problem(429, 'RATE_LIMITED', 'slow', {}, { 'retry-after': '90' }),
    })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many attempts. Please wait a little and try again. You can try again in 2 minutes.',
    )
    const button = screen.getByRole('button', { name: /^Try again in 1:3\d$|^Try again in 1:30$/ })
    expect(button).toBeDisabled()
  })

  it('explains a locked account, and offers the way out', async () => {
    api({
      'POST /auth/login': () =>
        problem(423, 'ACCOUNT_LOCKED', 'Too many failed attempts. Try again in a few minutes.'),
    })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(/locked for a short while/i)
    // The server sends no retry time for a lockout, so none is invented; sign-in is not blocked by a made-up timer.
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toBeInTheDocument()
  })

  it('says plainly when the server cannot be reached', async () => {
    api({ 'POST /auth/login': () => problem(503, 'INTERNAL_ERROR') })
    const user = userEvent.setup()
    renderApp('/login')
    await signInWith(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(/something went wrong on our side/i)
  })

  it('drops the half-made session if the user cannot be loaded after the password was accepted', async () => {
    api({ 'POST /auth/login': ok(TOKEN), 'GET /auth/me': () => problem(500, 'INTERNAL_ERROR') })
    const user = userEvent.setup()
    const { router } = renderApp('/login')
    await signInWith(user)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(authStore.getState().accessToken).toBeNull()
  })

  it('welcomes someone whose session ended with a friendly note, not an error', async () => {
    api({})
    authStore.getState().clearSession({ expired: true })
    renderApp('/login')
    const note = await screen.findByText(
      'Your session ended, so we signed you out. Please sign in again.',
    )
    expect(note.closest('[role="status"]')).not.toBeNull() // announced politely, not as an alert
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each([
    ['mfa-expired', /verification step timed out/],
    ['password-reset', /Your password has been changed/],
    ['invite-accepted', /Your account is ready/],
  ])('shows a note for the reason "%s"', async (reason, text) => {
    api({})
    renderApp({ pathname: '/login', state: { reason } })
    const note = await screen.findByText(text)
    expect(note.closest('[role="status"]')).not.toBeNull()
  })

  it('shows and hides the password', async () => {
    api({})
    const user = userEvent.setup()
    renderApp('/login')
    const password = await screen.findByLabelText(/^Password/)
    expect(password).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
  })

  it('sends someone who is already signed in straight to their dashboard', async () => {
    api({})
    setSession('SUPER_ADMIN')
    const { router } = renderApp('/login')
    await screen.findByRole(
      'heading',
      { level: 1, name: 'Platform dashboard' },
      { timeout: 15_000 },
    )
    expect(router.state.location.pathname).toBe('/superadmin/dashboard')
  })
})

describe('Two-step verification (login second step)', () => {
  function startChallenge(expiresInSeconds = 300) {
    authStore.getState().startMfaChallenge('mfa-1', expiresInSeconds)
  }

  it('goes back to sign in when there is no challenge in progress', async () => {
    api({})
    const { router } = renderApp('/login/2fa')
    await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe('/login')
  })

  it('checks the code as soon as the sixth digit is typed, and signs in', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/login/2fa': (config) => {
        sent = body(config)
        return { status: 200, data: TOKEN }
      },
      'GET /auth/me': ok(meFor('COMMUNITY_ADMIN')),
    })
    startChallenge()
    const user = userEvent.setup()
    const { container, router } = renderApp('/login/2fa')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await expectNoA11yViolations(container)
    await user.keyboard('123456')
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' }, { timeout: 15_000 })
    expect(sent).toEqual({ mfaToken: 'mfa-1', code: '123456' })
    expect(router.state.location.pathname).toBe('/admin/dashboard')
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('on a wrong code says so, empties the boxes and puts the cursor back at the start', async () => {
    api({ 'POST /auth/login/2fa': () => problem(401, 'INVALID_MFA_CODE') })
    startChallenge()
    const user = userEvent.setup()
    renderApp('/login/2fa')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('000000')
    expect(await screen.findByRole('alert')).toHaveTextContent(/not correct or has expired/)
    await waitFor(() => {
      const boxes = screen.getAllByRole<HTMLInputElement>('textbox')
      expect(boxes.map((box) => box.value)).toEqual(['', '', '', '', '', ''])
      expect(boxes[0]).toHaveFocus()
    })
    expect(authStore.getState().mfa.status).toBe('challenge') // still allowed to try again
  })

  it('accepts a recovery code instead', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/login/2fa': (config) => {
        sent = body(config)
        return { status: 200, data: TOKEN }
      },
      'GET /auth/me': ok(meFor('SUPER_ADMIN')),
    })
    startChallenge()
    const user = userEvent.setup()
    renderApp('/login/2fa')
    await user.click(
      await screen.findByRole(
        'button',
        { name: 'Use a recovery code instead' },
        { timeout: 15_000 },
      ),
    )
    expect(screen.queryByRole('group', { name: 'Authenticator code' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled() // nothing typed yet
    await user.type(screen.getByLabelText('Recovery code'), ' abcd-efgh-ijkl ')
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    await screen.findByRole(
      'heading',
      { level: 1, name: 'Platform dashboard' },
      { timeout: 15_000 },
    )
    expect(sent).toEqual({ mfaToken: 'mfa-1', recoveryCode: 'abcd-efgh-ijkl' })
  })

  it('can switch back to the authenticator app', async () => {
    api({})
    startChallenge()
    const user = userEvent.setup()
    renderApp('/login/2fa')
    await user.click(
      await screen.findByRole(
        'button',
        { name: 'Use a recovery code instead' },
        { timeout: 15_000 },
      ),
    )
    await user.click(screen.getByRole('button', { name: 'Use my authenticator app instead' }))
    expect(screen.getByRole('group', { name: 'Authenticator code' })).toBeInTheDocument()
  })

  it('returns to sign in with a note when the server says the challenge has expired', async () => {
    api({ 'POST /auth/login/2fa': () => problem(401, 'UNAUTHENTICATED') })
    startChallenge()
    const user = userEvent.setup()
    const { router } = renderApp('/login/2fa')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByText(/timed out/)).toBeInTheDocument()
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('returns to sign in on its own when the five minutes run out', async () => {
    api({})
    startChallenge(0.05)
    renderApp('/login/2fa')
    await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 15_000 })
    expect(await screen.findByText(/timed out/)).toBeInTheDocument()
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('has a way back to the password step', async () => {
    api({})
    startChallenge()
    const user = userEvent.setup()
    renderApp('/login/2fa')
    await user.click(
      await screen.findByRole('button', { name: 'Back to sign in' }, { timeout: 15_000 }),
    )
    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('shows a rate limit and holds the boxes until the wait is over', async () => {
    api({
      'POST /auth/login/2fa': () =>
        problem(429, 'RATE_LIMITED', 'slow', {}, { 'retry-after': '45' }),
    })
    startChallenge()
    const user = userEvent.setup()
    renderApp('/login/2fa')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    expect(await screen.findByRole('alert')).toHaveTextContent(/Too many attempts/)
    expect(await screen.findByText(/Try again in 0:4\d/)).toBeInTheDocument()
    for (const box of screen.getAllByRole('textbox')) expect(box).toBeDisabled()
  })

  it('carries the page they were heading to through the second step', async () => {
    api({
      'POST /auth/login': ok({ mfaRequired: true, mfaToken: 'mfa-1', expiresIn: 300 }),
      'POST /auth/login/2fa': ok(TOKEN),
      'GET /auth/me': ok(meFor('COMMUNITY_ADMIN')),
    })
    const user = userEvent.setup()
    const { router } = renderApp(cameFrom('/admin/billing'))
    await signInWith(user)
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('654321')
    await screen.findByRole('heading', { level: 1, name: 'Billing' }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe('/admin/billing')
  })
})

describe('Forgot password', () => {
  it('checks the address, asks the API, and gives the same answer whatever the API knows', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/password/forgot': (config) => {
        sent = body(config)
        return { status: 202 }
      },
    })
    const user = userEvent.setup()
    const { container } = renderApp('/forgot-password')
    await user.click(
      await screen.findByRole('button', { name: 'Send the link' }, { timeout: 15_000 }),
    )
    expect(await screen.findByText('Enter your email address')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.type(screen.getByLabelText(/^Email/), 'someone@example.com')
    await user.click(screen.getByRole('button', { name: 'Send the link' }))
    const status = await screen.findByRole('status')
    expect(sent).toEqual({ email: 'someone@example.com' })
    expect(status).toHaveTextContent(
      'If there is an account for someone@example.com, we have sent a link',
    )
    expect(status).not.toHaveTextContent(/no account|not found|does not exist|registered/i)
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('shows a rate limit with the wait', async () => {
    api({
      'POST /auth/password/forgot': () =>
        problem(429, 'RATE_LIMITED', 'slow', {}, { 'retry-after': '3600' }),
    })
    const user = userEvent.setup()
    renderApp('/forgot-password')
    await user.type(
      await screen.findByLabelText(/^Email/, undefined, { timeout: 15_000 }),
      'a@b.co',
    )
    await user.click(screen.getByRole('button', { name: 'Send the link' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('60 minutes')
  })
})

describe('Choose a new password (reset and invitation)', () => {
  const longEnough = 'Correct-Horse-9'

  it.each([
    ['/reset-password', 'This link cannot be used'],
    ['/accept-invite', 'This invitation cannot be used'],
  ])('%s without a token says the link is unusable and offers a way on', async (path, heading) => {
    api({})
    renderApp(path)
    expect(
      await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 15_000 }),
    ).toBeInTheDocument()
  })

  it('takes the token out of the address bar as soon as it has read it', async () => {
    api({})
    const { router } = renderApp('/reset-password?token=secret-token')
    await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 })
    await waitFor(() => {
      expect(router.state.location.search).toBe('')
    })
    expect(router.state.location.pathname).toBe('/reset-password')
  })

  it('shows strength and the unmet rules as you type, and checks the two boxes match', async () => {
    api({})
    const user = userEvent.setup()
    const { container } = renderApp('/reset-password?token=t')
    const password = await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 })
    await user.type(password, 'short')
    expect(screen.getByText('Password strength: Too short')).toBeInTheDocument()
    await user.clear(password)
    await user.type(password, longEnough)
    expect(screen.getByText(/Password strength: (Fair|Good)/)).toBeInTheDocument()
    expect(screen.getByText('This password meets the rules.')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Confirm new password/), 'something-else')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('The two passwords do not match')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('refuses a password that breaks the rules before sending it', async () => {
    const mocked = api({})
    const user = userEvent.setup()
    renderApp('/reset-password?token=t')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      'alllowercaseletters',
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), 'alllowercaseletters')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(
      await screen.findByText('Choose a password that meets the rules below'),
    ).toBeInTheDocument()
    expect(mocked.calls).toHaveLength(0)
  })

  it('resets, then lands on sign in with a note, holding no session', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/password/reset': (config) => {
        sent = body(config)
        return { status: 204 }
      },
    })
    const user = userEvent.setup()
    const { router } = renderApp('/reset-password?token=link-token')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      longEnough,
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), longEnough)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 15_000 })
    expect(sent).toEqual({ token: 'link-token', newPassword: longEnough })
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByText(/Your password has been changed/)).toBeInTheDocument()
    expect(authStore.getState().accessToken).toBeNull()
  })

  it('lists every rule the server says the password broke', async () => {
    api({
      'POST /auth/password/reset': () =>
        problem(422, 'PASSWORD_POLICY_VIOLATION', 'The password does not meet the policy.', {
          errors: [
            'That password is too common or predictable.',
            'Do not include your email name in the password.',
          ],
        }),
    })
    const user = userEvent.setup()
    renderApp('/reset-password?token=t')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      longEnough,
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), longEnough)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    const alert = await screen.findByRole('alert')
    expect(
      within(alert)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      'That password is too common or predictable.',
      'Do not include your email name in the password.',
    ])
  })

  it('a used or expired link shows the way to ask for a new one', async () => {
    api({ 'POST /auth/password/reset': () => problem(400, 'INVALID_TOKEN') })
    const user = userEvent.setup()
    renderApp('/reset-password?token=old')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      longEnough,
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), longEnough)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'This link cannot be used' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Send me a new link' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
  })

  it('accepts an invitation and says what happens next, including forced 2FA', async () => {
    let sent: Record<string, unknown> = {}
    api({
      'POST /auth/accept-invite': (config) => {
        sent = body(config)
        return { status: 200, data: { mfaSetupRequired: true } }
      },
    })
    const user = userEvent.setup()
    renderApp('/accept-invite?token=invite-token')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      longEnough,
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), longEnough)
    await user.click(screen.getByRole('button', { name: 'Create my account' }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your account is ready' }),
    ).toBeInTheDocument()
    expect(sent).toEqual({ token: 'invite-token', newPassword: longEnough })
    expect(screen.getByRole('status')).toHaveTextContent(
      /set up two-step verification right after you sign in/,
    )
    expect(screen.getByRole('link', { name: 'Go to sign in' })).toHaveAttribute('href', '/login')
  })

  it('an expired invitation says who to ask', async () => {
    api({ 'POST /auth/accept-invite': () => problem(400, 'INVALID_TOKEN') })
    const user = userEvent.setup()
    renderApp('/accept-invite?token=old')
    await user.type(
      await screen.findByLabelText(/^New password/, undefined, { timeout: 15_000 }),
      longEnough,
    )
    await user.type(screen.getByLabelText(/^Confirm new password/), longEnough)
    await user.click(screen.getByRole('button', { name: 'Create my account' }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'This invitation cannot be used' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Ask the person who invited you/)).toBeInTheDocument()
  })
})

describe('Two-step verification setup', () => {
  const SETUP = {
    secret: 'JBSWY3DPEHPK3PXP',
    otpauthUri:
      'otpauth://totp/Amanah%20Connect:root@example.test?secret=JBSWY3DPEHPK3PXP&issuer=Amanah%20Connect',
  }
  const CODES = ['AAAA-1111', 'BBBB-2222', 'CCCC-3333', 'DDDD-4444']

  function forcedSetup() {
    setSession('SUPER_ADMIN', { mfaSetupRequired: true, totpEnabled: false })
  }

  it('is for signed-in users only', async () => {
    api({})
    const { router } = renderApp('/2fa/setup')
    await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 15_000 })
    expect(router.state.location.pathname).toBe('/login')
  })

  it('shows a QR code, the key to type by hand, and starts the setup only once', async () => {
    const mocked = api({ 'POST /auth/2fa/setup': ok(SETUP) })
    forcedSetup()
    const { container } = renderApp('/2fa/setup')
    expect(
      await screen.findByRole('img', { name: /QR code/ }, { timeout: 15_000 }),
    ).toBeInTheDocument()
    const key = screen.getByLabelText('Setup key')
    expect(key).toHaveValue('JBSWY3DPEHPK3PXP')
    expect(mocked.count('POST /auth/2fa/setup')).toBe(1)
    expect(screen.getByRole('button', { name: 'Turn on two-step verification' })).toBeDisabled()
    await expectNoA11yViolations(container)
  })

  it('explains a failed start and can try again', async () => {
    let calls = 0
    api({
      'POST /auth/2fa/setup': () => {
        calls += 1
        return calls === 1 ? problem(503, 'INTERNAL_ERROR') : { status: 200, data: SETUP }
      },
    })
    forcedSetup()
    const user = userEvent.setup()
    renderApp('/2fa/setup')
    expect(await screen.findByRole('alert', undefined, { timeout: 15_000 })).toHaveTextContent(
      /could not start the setup/i,
    )
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('img', { name: /QR code/ })).toBeInTheDocument()
  })

  it('says so when two-step verification is already on', async () => {
    api({ 'POST /auth/2fa/setup': () => problem(409, 'MFA_ALREADY_ENABLED') })
    setSession('COMMUNITY_ADMIN', { totpEnabled: true })
    renderApp('/2fa/setup')
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Two-step verification is already on' },
        { timeout: 15_000 },
      ),
    ).toBeInTheDocument()
  })

  it('on a wrong code shows the message and clears the boxes', async () => {
    api({
      'POST /auth/2fa/setup': ok(SETUP),
      'POST /auth/2fa/enable': () => problem(401, 'INVALID_MFA_CODE'),
    })
    forcedSetup()
    const user = userEvent.setup()
    renderApp('/2fa/setup')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('111111')
    await user.click(screen.getByRole('button', { name: 'Turn on two-step verification' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/not correct or has expired/)
    await waitFor(() => {
      const digits = within(
        screen.getByRole('group', { name: 'Authenticator code' }),
      ).getAllByRole<HTMLInputElement>('textbox')
      expect(digits.every((box) => box.value === '')).toBe(true)
    })
  })

  it('shows the recovery codes once, needs a tick before continuing, then signs in cleanly', async () => {
    let enabled: Record<string, unknown> = {}
    const mocked = api({
      'POST /auth/2fa/setup': ok(SETUP),
      'POST /auth/2fa/enable': (config) => {
        enabled = body(config)
        return { status: 200, data: { recoveryCodes: CODES } }
      },
      'POST /auth/refresh': ok({ ...TOKEN, accessToken: 'access-2' }),
      'GET /auth/me': ok(meFor('SUPER_ADMIN', { mfaSetupRequired: false, totpEnabled: true })),
    })
    forcedSetup()
    const user = userEvent.setup()
    const { container, router } = renderApp('/2fa/setup')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    await user.click(screen.getByRole('button', { name: 'Turn on two-step verification' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Save your recovery codes' }),
    ).toBeInTheDocument()
    expect(enabled).toEqual({ code: '123456' })
    expect(
      within(screen.getByRole('list', { name: 'Recovery codes' }))
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(CODES)
    await expectNoA11yViolations(container)

    const proceed = screen.getByRole('button', { name: 'Continue' })
    expect(proceed).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /I have saved these recovery codes/ }))
    expect(proceed).toBeEnabled()
    await user.click(proceed)

    await screen.findByRole(
      'heading',
      { level: 1, name: 'Platform dashboard' },
      { timeout: 15_000 },
    )
    expect(router.state.location.pathname).toBe('/superadmin/dashboard')
    // A fresh token was fetched (the old one only allowed setup), and the flag is gone.
    expect(mocked.count('POST /auth/refresh')).toBe(1)
    expect(authStore.getState().mfaSetupRequired).toBe(false)
    expect(authStore.getState().accessToken).toBe('access-2')
  })

  it('lets the codes be copied and downloaded', async () => {
    api({
      'POST /auth/2fa/setup': ok(SETUP),
      'POST /auth/2fa/enable': ok({ recoveryCodes: CODES }),
    })
    forcedSetup()
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    const createObjectURL = vi.fn(() => 'blob:codes')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    renderApp('/2fa/setup')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    await user.click(screen.getByRole('button', { name: 'Turn on two-step verification' }))
    await screen.findByRole('heading', { name: 'Save your recovery codes' })

    await user.click(screen.getByRole('button', { name: 'Copy codes' }))
    expect(writeText).toHaveBeenCalledWith(CODES.join('\n'))
    expect(await screen.findByText('Copied to the clipboard.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Download' }))
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalled()
    const blob = (createObjectURL.mock.calls[0] as unknown as [Blob])[0]
    expect(await blob.text()).toContain('AAAA-1111')
    click.mockRestore()
  })

  it('keeps the codes out of every cache and out of the page once it is left', async () => {
    api({
      'POST /auth/2fa/setup': ok(SETUP),
      'POST /auth/2fa/enable': ok({ recoveryCodes: CODES }),
    })
    forcedSetup()
    const user = userEvent.setup()
    const { client, unmount } = renderApp('/2fa/setup')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    await user.click(screen.getByRole('button', { name: 'Turn on two-step verification' }))
    await screen.findByRole('heading', { name: 'Save your recovery codes' })

    const cached = JSON.stringify([
      client
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
      client
        .getMutationCache()
        .getAll()
        .map((m) => m.state.data),
    ])
    expect(cached).not.toContain('AAAA-1111')
    expect(localStorage.length + sessionStorage.length).toBe(0)
    unmount()
    expect(document.body.textContent).not.toContain('AAAA-1111')
  })

  it('warns before the tab is closed while the codes are unsaved', async () => {
    api({
      'POST /auth/2fa/setup': ok(SETUP),
      'POST /auth/2fa/enable': ok({ recoveryCodes: CODES }),
    })
    forcedSetup()
    const user = userEvent.setup()
    renderApp('/2fa/setup')
    await screen.findByRole('group', { name: 'Authenticator code' }, { timeout: 15_000 })
    await user.keyboard('123456')
    await user.click(screen.getByRole('button', { name: 'Turn on two-step verification' }))
    await screen.findByRole('heading', { name: 'Save your recovery codes' })

    const unsaved = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unsaved)
    expect(unsaved.defaultPrevented).toBe(true)

    await user.click(screen.getByRole('checkbox', { name: /I have saved/ }))
    const saved = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(saved)
    expect(saved.defaultPrevented).toBe(false)
  })

  it('lets someone stuck on the forced setup sign out', async () => {
    api({ 'POST /auth/2fa/setup': ok(SETUP), 'POST /auth/logout': () => ({ status: 204 }) })
    forcedSetup()
    const user = userEvent.setup()
    const { router } = renderApp('/2fa/setup')
    await user.click(await screen.findByRole('button', { name: 'Sign out' }, { timeout: 15_000 }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login')
    })
    expect(authStore.getState().user).toBeNull()
  })
})

describe('Unauthorized', () => {
  it('sends a signed-in user to their own dashboard', async () => {
    api({})
    setSession('COMMUNITY_ADMIN')
    renderApp('/unauthorized')
    const home = await screen.findByRole(
      'link',
      { name: 'Go to my dashboard' },
      { timeout: 15_000 },
    )
    expect(home).toHaveAttribute('href', '/admin/dashboard')
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('offers sign in to a visitor, and passes axe', async () => {
    api({})
    const { container } = renderApp('/unauthorized')
    expect(
      await screen.findByRole('link', { name: 'Sign in' }, { timeout: 15_000 }),
    ).toHaveAttribute('href', '/login')
    await expectNoA11yViolations(container)
  })
})
