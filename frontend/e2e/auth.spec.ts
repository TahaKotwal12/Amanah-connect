import { expect, test, type Page, type Route } from '@playwright/test'

type Role = 'SUPER_ADMIN' | 'COMMUNITY_ADMIN'

const TOKEN = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  mfaSetupRequired: false,
}

function me(role: Role, flags: { mfaSetupRequired?: boolean } = {}) {
  const root = role === 'SUPER_ADMIN'
  return {
    user: {
      id: '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11',
      email: root ? 'root@example.test' : 'admin@example.test',
      fullName: root ? 'Platform Admin' : 'Community Admin',
      role,
      status: 'ACTIVE',
    },
    community: root
      ? null
      : { id: 'c1', name: 'Test Community', slug: 'test-community', status: 'ACTIVE' },
    flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: root, ...flags },
  }
}

/**
 * A small stand-in for the API with one piece of state: whether the refresh cookie is "set". It
 * behaves like the real one where it matters here: /auth/refresh answers 401 until someone has
 * signed in, and /auth/logout ends the session.
 */
async function fakeApi(
  page: Page,
  options: { role?: Role; twoFactor?: boolean; mfaSetupRequired?: boolean } = {},
) {
  const role = options.role ?? 'COMMUNITY_ADMIN'
  const state = {
    signedIn: false,
    calls: [] as string[],
    mfaSetupRequired: options.mfaSetupRequired ?? false,
  }
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, json: body })
  const problem = (route: Route, status: number, code: string) =>
    route.fulfill({
      status,
      contentType: 'application/problem+json',
      json: { status, code, detail: code },
    })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    state.calls.push(`${request.method()} ${path}`)
    const body = (request.postDataJSON() ?? {}) as Record<string, string>

    switch (`${request.method()} ${path}`) {
      case 'POST /auth/refresh':
        return state.signedIn
          ? json(route, 200, { ...TOKEN, mfaSetupRequired: state.mfaSetupRequired })
          : problem(route, 401, 'INVALID_REFRESH_TOKEN')
      case 'POST /auth/login':
        if (body.password !== 'correct-password-1')
          return problem(route, 401, 'INVALID_CREDENTIALS')
        if (options.twoFactor) {
          return json(route, 200, { mfaRequired: true, mfaToken: 'mfa-1', expiresIn: 300 })
        }
        state.signedIn = true
        return json(route, 200, { ...TOKEN, mfaSetupRequired: state.mfaSetupRequired })
      case 'POST /auth/login/2fa':
        if (body.code !== '123456' && body.recoveryCode !== 'RECOVERY-1') {
          return problem(route, 401, 'INVALID_MFA_CODE')
        }
        state.signedIn = true
        return json(route, 200, TOKEN)
      case 'GET /auth/me':
        return json(route, 200, me(role, { mfaSetupRequired: state.mfaSetupRequired }))
      case 'POST /auth/logout':
        state.signedIn = false
        return route.fulfill({ status: 204 })
      case 'POST /auth/2fa/setup':
        return json(route, 200, {
          secret: 'JBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/Amanah:root@example.test?secret=JBSWY3DPEHPK3PXP',
        })
      case 'POST /auth/2fa/enable':
        if (body.code !== '123456') return problem(route, 401, 'INVALID_MFA_CODE')
        state.mfaSetupRequired = false // after this, /auth/refresh gives a token without the restriction
        return json(route, 200, { recoveryCodes: ['AAAA-1111', 'BBBB-2222'] })
      default:
        return problem(route, 404, 'NOT_FOUND')
    }
  })
  return state
}

async function signIn(page: Page, email = 'asha@example.com', password = 'correct-password-1') {
  await page.getByLabel(/^Email/).fill(email)
  await page.getByLabel(/^Password/).fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

test('a visitor is sent to sign in, returns to the page they wanted, and signs out again', async ({
  page,
}) => {
  const api = await fakeApi(page)
  await page.goto('/admin/members')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()

  await signIn(page)
  await expect(page).toHaveURL(/\/admin\/members$/) // where they were going, not just the dashboard
  await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Account menu for Community Admin' })).toBeVisible()

  // A reload restores the session from the refresh cookie: no second sign-in.
  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible()

  await page.getByRole('button', { name: 'Account menu for Community Admin' }).click()
  await page.getByRole('menuitem', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(api.calls).toContain('POST /auth/logout')

  // Signed out for real: a protected page sends them back.
  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/login$/)
})

test('signing in from the login page goes to the dashboard for each role', async ({ page }) => {
  await fakeApi(page, { role: 'SUPER_ADMIN' })
  await page.goto('/login')
  await signIn(page, 'root@example.test')
  await expect(page).toHaveURL(/\/superadmin\/dashboard$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Platform dashboard' })).toBeVisible()
})

test('a community admin lands on the community dashboard', async ({ page }) => {
  await fakeApi(page, { role: 'COMMUNITY_ADMIN' })
  await page.goto('/login')
  await signIn(page)
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
})

test('a wrong password gets one plain message and no hint about accounts', async ({ page }) => {
  await fakeApi(page)
  await page.goto('/login')
  await signIn(page, 'nobody@example.com', 'wrong-password-1')
  const alert = page.getByRole('alert')
  await expect(alert).toHaveText('The email or password is not correct.')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel(/^Password/)).toHaveValue('')
})

test('the whole sign-in works from the keyboard alone', async ({ page }) => {
  await fakeApi(page)
  await page.goto('/login')
  await expect(page.getByLabel(/^Email/)).toBeFocused()
  await page.keyboard.type('asha@example.com')
  await page.keyboard.press('Tab')
  await page.keyboard.type('correct-password-1')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
})

test('the password can be shown and hidden', async ({ page }) => {
  await fakeApi(page)
  await page.goto('/login')
  const password = page.getByLabel(/^Password/)
  await password.fill('hunter2-hunter2')
  await expect(password).toHaveAttribute('type', 'password')
  await page.getByRole('button', { name: 'Show password' }).click()
  await expect(password).toHaveAttribute('type', 'text')
})

test('two-step sign-in: a wrong code is refused, the right one signs in', async ({ page }) => {
  await fakeApi(page, { twoFactor: true })
  await page.goto('/admin/billing')
  await signIn(page)
  await expect(page).toHaveURL(/\/login\/2fa$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Two-step verification' })).toBeVisible()

  const first = page.getByRole('group', { name: 'Authenticator code' }).getByRole('textbox').first()
  await expect(first).toBeFocused()
  await page.keyboard.type('000000')
  await expect(page.getByRole('alert')).toContainText('not correct or has expired')
  await expect(first).toBeFocused() // boxes are empty again, cursor at the start

  await page.keyboard.type('123456') // checked as soon as the sixth digit lands
  await expect(page).toHaveURL(/\/admin\/billing$/)
})

test('two-step sign-in with a recovery code', async ({ page }) => {
  await fakeApi(page, { twoFactor: true })
  await page.goto('/login')
  await signIn(page)
  await page.getByRole('button', { name: 'Use a recovery code instead' }).click()
  await page.getByLabel('Recovery code').fill('RECOVERY-1')
  await page.getByRole('button', { name: 'Verify' }).click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
})

test('an account that must set up two-step verification is held on the setup page until it does', async ({
  page,
}) => {
  await fakeApi(page, { role: 'SUPER_ADMIN', mfaSetupRequired: true })
  await page.goto('/login')
  await signIn(page, 'root@example.test')
  await expect(page).toHaveURL(/\/2fa\/setup$/)

  // Every other page sends them back here.
  await page.goto('/superadmin/dashboard')
  await expect(page).toHaveURL(/\/2fa\/setup$/)

  await expect(page.getByRole('img', { name: /QR code/ })).toBeVisible()
  await page.getByText('Cannot scan? Enter the key by hand').click()
  await expect(page.getByLabel('Setup key')).toHaveValue('JBSWY3DPEHPK3PXP')

  await page.getByRole('group', { name: 'Authenticator code' }).getByRole('textbox').first().click()
  await page.keyboard.type('123456')
  await page.getByRole('button', { name: 'Turn on two-step verification' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: 'Save your recovery codes' }),
  ).toBeVisible()
  await expect(page.getByRole('list', { name: 'Recovery codes' }).getByRole('listitem')).toHaveText(
    ['AAAA-1111', 'BBBB-2222'],
  )

  const proceed = page.getByRole('button', { name: 'Continue' })
  await expect(proceed).toBeDisabled()
  await page.getByRole('checkbox', { name: /I have saved these recovery codes/ }).check()
  await proceed.click()
  await expect(page).toHaveURL(/\/superadmin\/dashboard$/)
})

test('forgot password gives the same answer for any address', async ({ page }) => {
  await page.route('**/api/v1/auth/password/forgot', (route) => route.fulfill({ status: 202 }))
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ status: 401, json: { code: 'INVALID_REFRESH_TOKEN' } }),
  )
  await page.goto('/login')
  await page.getByRole('link', { name: 'Forgot your password?' }).click()
  await page.getByLabel(/^Email/).fill('anyone@example.com')
  await page.getByRole('button', { name: 'Send the link' }).click()
  await expect(page.getByRole('status')).toContainText(
    'If there is an account for anyone@example.com',
  )
})

test('reset link: the token leaves the address bar, and a new password signs the user in afresh', async ({
  page,
}) => {
  let sent: Record<string, string> | undefined
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ status: 401, json: { code: 'INVALID_REFRESH_TOKEN' } }),
  )
  await page.route('**/api/v1/auth/password/reset', (route) => {
    sent = route.request().postDataJSON() as Record<string, string>
    return route.fulfill({ status: 204 })
  })
  await page.goto('/reset-password?token=one-time-token')
  await expect(page.getByRole('heading', { level: 1, name: 'Choose a new password' })).toBeVisible()
  await expect(page).toHaveURL(/\/reset-password$/) // no ?token= left to leak

  await page.getByLabel(/^New password/).fill('Correct-Horse-9')
  await expect(page.getByText(/Password strength: (Fair|Good)/)).toBeVisible()
  await page.getByLabel(/^Confirm new password/).fill('Correct-Horse-9')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText(/Your password has been changed/)).toBeVisible()
  expect(sent).toEqual({ token: 'one-time-token', newPassword: 'Correct-Horse-9' })
})

test('on a phone the sign-in page is a single column with the brand on top', async ({ page }) => {
  await fakeApi(page)
  await page.setViewportSize({ width: 375, height: 760 })
  await page.goto('/login')
  await expect(page.getByRole('link', { name: /AMANAH CONNECT/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  ) // no sideways scroll
  await signIn(page)
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
})
