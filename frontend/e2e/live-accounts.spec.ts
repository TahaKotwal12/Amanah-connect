import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { DEV_SEED_RECOVERY_CODE, totp } from './support/totp.ts'

/**
 * Every kind of sign-in, against the REAL backend, using the development test accounts that the
 * backend seeds (docs/dev-seed.md). No stubs.
 *
 *   # backend (local profile) started with DEV_SEED_ENABLED=true and DEV_SEED_PASSWORD=<password>
 *   # frontend dev server on :5173 (the only origin the backend accepts for cookie calls)
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5173 \
 *     npx playwright test e2e/live-accounts.spec.ts
 *
 * Mind the backend's login rate limit (10 a minute per address): every login here is paced.
 * Two tests spend single-use state (a recovery code, a 2FA enrolment); restarting the backend with
 * the seed on puts every account back.
 */
const password = process.env.E2E_DEV_SEED_PASSWORD
test.skip(!process.env.E2E_LIVE || !password, 'set E2E_LIVE=1 and E2E_DEV_SEED_PASSWORD')
test.describe.configure({ mode: 'serial' })

const DOMAIN = '@dev-seed.example.test'
const email = (local: string) => `${local}${DOMAIN}`
const JSON_HEADERS = { 'Content-Type': 'application/json' }

// ---- pacing: the backend allows 10 sign-ins a minute per address -------------------------------
let lastLogin = 0
async function paceLogin() {
  const wait = lastLogin + 6500 - Date.now()
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
  lastLogin = Date.now()
}

interface Reply {
  status: number
  body: Record<string, unknown>
}

async function post(
  request: APIRequestContext,
  path: string,
  data: unknown,
  headers = {},
): Promise<Reply> {
  const response = await request.post(`/api/v1${path}`, {
    data,
    headers: { ...JSON_HEADERS, ...headers },
  })
  const text = await response.text()
  return {
    status: response.status(),
    body: text ? (JSON.parse(text) as Record<string, unknown>) : {},
  }
}

async function apiLogin(
  request: APIRequestContext,
  local: string,
  pass = password ?? '',
): Promise<Reply> {
  await paceLogin()
  return post(request, '/auth/login', { email: email(local), password: pass })
}

interface Profile {
  user: Record<string, unknown>
  community: Record<string, unknown> | null
  flags: Record<string, unknown>
}

async function me(request: APIRequestContext, token: string) {
  const response = await request.get('/api/v1/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  return { status: response.status(), body: (await response.json()) as Profile }
}

// ---- the accounts, through the API ---------------------------------------------------------------

test.describe('API: what each kind of account gets back', () => {
  test('community owner: straight in, scoped to their community', async ({ request }) => {
    const login = await apiLogin(request, 'owner')
    expect(login.status).toBe(200)
    expect(login.body).toMatchObject({ tokenType: 'Bearer', mfaSetupRequired: false })

    const profile = await me(request, login.body.accessToken as string)
    expect(profile.body.user).toMatchObject({
      email: email('owner'),
      role: 'COMMUNITY_ADMIN',
      status: 'ACTIVE',
    })
    expect(profile.body.community).toMatchObject({ name: 'Dev Seed Community', status: 'ACTIVE' })
    expect(profile.body.flags).toMatchObject({
      totpEnabled: false,
      mfaSetupRequired: false,
      mfaMandatory: false,
    })
  })

  test('super admin with 2FA: a challenge first, then a session with the right code', async ({
    request,
  }) => {
    const login = await apiLogin(request, 'superadmin.2fa')
    expect(login.status).toBe(200)
    expect(login.body).toMatchObject({ mfaRequired: true })
    expect(login.body).not.toHaveProperty('accessToken')

    const wrong = await post(request, '/auth/login/2fa', {
      mfaToken: login.body.mfaToken,
      code: '000000',
    })
    expect(wrong.status).toBe(401)
    expect(wrong.body.code).toBe('INVALID_MFA_CODE')

    const right = await post(request, '/auth/login/2fa', {
      mfaToken: login.body.mfaToken,
      code: totp(),
    })
    expect(right.status).toBe(200)
    expect(right.body).toMatchObject({ tokenType: 'Bearer', mfaSetupRequired: false })

    const profile = await me(request, right.body.accessToken as string)
    expect(profile.body.user).toMatchObject({ role: 'SUPER_ADMIN' })
    expect(profile.body.community).toBeNull()
    expect(profile.body.flags).toMatchObject({ totpEnabled: true, mfaMandatory: true })
  })

  test('community admin with 2FA: a recovery code works once (restart the seed to get it back)', async ({
    request,
  }) => {
    const login = await apiLogin(request, 'admin.2fa')
    expect(login.body.mfaRequired).toBe(true)
    const used = await post(request, '/auth/login/2fa', {
      mfaToken: login.body.mfaToken,
      recoveryCode: DEV_SEED_RECOVERY_CODE,
    })
    expect(used.status).toBe(200)
    expect(used.body.accessToken).toBeTruthy()

    const again = await apiLogin(request, 'admin.2fa')
    const reused = await post(request, '/auth/login/2fa', {
      mfaToken: again.body.mfaToken,
      recoveryCode: DEV_SEED_RECOVERY_CODE,
    })
    expect(reused.status).toBe(401) // single use
  })

  test('a super admin who has not set up 2FA is held to the setup step', async ({ request }) => {
    const login = await apiLogin(request, 'superadmin.setup')
    expect(login.status).toBe(200)
    expect(login.body.mfaSetupRequired).toBe(true)

    const token = login.body.accessToken as string
    const profile = await me(request, token)
    expect(profile.body.flags).toMatchObject({
      mfaSetupRequired: true,
      mfaMandatory: true,
      totpEnabled: false,
    })

    // Everything else is refused until 2FA is on.
    const blocked = await request.get('/api/v1/admin/communities', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(blocked.status()).toBe(403)
    expect(((await blocked.json()) as { code: string }).code).toBe('MFA_SETUP_REQUIRED')
  })

  test('a community that requires 2FA forces its owner through setup', async ({ request }) => {
    const login = await apiLogin(request, 'owner.must2fa')
    expect(login.body.mfaSetupRequired).toBe(true)
    const profile = await me(request, login.body.accessToken as string)
    expect(profile.body.flags).toMatchObject({
      mfaMandatory: true,
      totpEnabled: false,
      mfaSetupRequired: true,
    })
  })

  test('the owner of a suspended community can sign in but cannot change anything', async ({
    request,
  }) => {
    const login = await apiLogin(request, 'owner.suspended')
    expect(login.status).toBe(200)
    const token = login.body.accessToken as string
    const profile = await me(request, token)
    expect(profile.body.community).toMatchObject({ status: 'SUSPENDED' })

    const write = await post(
      request,
      '/community/members',
      { fullName: 'Should Not Be Created' },
      { Authorization: `Bearer ${token}` },
    )
    expect(write.status).toBe(403)
    expect(write.body.code).toBe('COMMUNITY_SUSPENDED')
  })

  test('a locked account is told so (423), and a disabled or invited one gets the same answer as a stranger', async ({
    request,
  }) => {
    const locked = await apiLogin(request, 'locked')
    expect(locked.status).toBe(423)
    expect(locked.body.code).toBe('ACCOUNT_LOCKED')

    const stranger = await apiLogin(request, 'nobody.at.all')
    const disabled = await apiLogin(request, 'disabled')
    const invited = await apiLogin(request, 'invited')
    for (const reply of [stranger, disabled, invited]) {
      expect(reply.status).toBe(401)
      expect(reply.body.code).toBe('INVALID_CREDENTIALS')
    }
    // Nothing in the answer tells these three apart: no account enumeration.
    expect(disabled.body.detail).toBe(stranger.body.detail)
    expect(invited.body.detail).toBe(stranger.body.detail)
  })
})

// ---- the same accounts, through the real UI ------------------------------------------------------

async function uiSignIn(page: Page, local: string, pass = password ?? '') {
  await paceLogin()
  await page.getByLabel(/^Email/).fill(email(local))
  await page.getByLabel(/^Password/).fill(pass)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

async function typeCode(page: Page, code: string) {
  await page.getByRole('group', { name: 'Authenticator code' }).getByRole('textbox').first().click()
  await page.keyboard.type(code)
}

test.describe('UI: signing in as each kind of account', () => {
  test('community owner: lands on the community dashboard, survives a reload, signs out', async ({
    page,
  }) => {
    await page.goto('/admin/members')
    await expect(page).toHaveURL(/\/login$/)
    await uiSignIn(page, 'owner')
    await expect(page).toHaveURL(/\/admin\/members$/) // where they were going
    await expect(page.getByText('Dev Seed Community').first()).toBeVisible()

    await page.reload() // real refresh cookie, real rotation
    await expect(page).toHaveURL(/\/admin\/members$/)
    await expect(
      page.getByRole('button', { name: 'Account menu for Dev Community Owner' }),
    ).toBeVisible()

    await page.getByRole('button', { name: 'Account menu for Dev Community Owner' }).click()
    await page.getByRole('menuitem', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/\/login$/)
    await page.goto('/admin/dashboard')
    await expect(page).toHaveURL(/\/login$/) // the cookie really was revoked
  })

  test('super admin with 2FA: password, then the code, then the platform dashboard', async ({
    page,
  }) => {
    await page.goto('/login')
    await uiSignIn(page, 'superadmin.2fa')
    await expect(page).toHaveURL(/\/login\/2fa$/)
    await typeCode(page, '000000')
    await expect(page.getByRole('alert')).toContainText('not correct or has expired')
    await page.waitForTimeout(500)
    await typeCode(page, totp())
    await expect(page).toHaveURL(/\/superadmin\/dashboard$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Platform dashboard' })).toBeVisible()
  })

  test('super admin without 2FA is sent to the setup page and cannot reach anything else', async ({
    page,
  }) => {
    await page.goto('/login')
    await uiSignIn(page, 'superadmin.setup')
    await expect(page).toHaveURL(/\/2fa\/setup$/)
    await expect(page.getByRole('img', { name: /QR code/ })).toBeVisible()
    await page.goto('/superadmin/dashboard')
    await expect(page).toHaveURL(/\/2fa\/setup$/)
  })

  test('the owner of a community that requires 2FA is also held at setup', async ({ page }) => {
    await page.goto('/login')
    await uiSignIn(page, 'owner.must2fa')
    await expect(page).toHaveURL(/\/2fa\/setup$/)
  })

  test('the owner of a suspended community still gets in', async ({ page }) => {
    await page.goto('/login')
    await uiSignIn(page, 'owner.suspended')
    await expect(page).toHaveURL(/\/admin\/dashboard$/, { timeout: 20_000 }) // remote database: slow
    await expect(page.getByText('Dev Seed Community (suspended)').first()).toBeVisible()
  })

  test('a locked account sees why, a disabled one sees the generic message', async ({ page }) => {
    await page.goto('/login')
    await uiSignIn(page, 'locked')
    await expect(page.getByRole('alert')).toContainText(/locked for a short while/i)

    await page.reload()
    await uiSignIn(page, 'disabled')
    await expect(page.getByRole('alert')).toHaveText('The email or password is not correct.')
  })
})
