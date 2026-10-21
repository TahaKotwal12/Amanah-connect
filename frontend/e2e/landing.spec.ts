import { expect, test, type Page } from '@playwright/test'

async function stubSession(page: Page) {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ status: 401, json: { code: 'INVALID_REFRESH_TOKEN' } }),
  )
}

test('the landing page paints from the HTML, then the live page takes over without a jump', async ({
  page,
}) => {
  await stubSession(page)
  const failures: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') failures.push(message.text())
  })
  page.on('pageerror', (error) => failures.push(error.message))

  // Before any script runs, the headline is already in the document.
  const response = await page.request.get('/')
  expect(await response.text()).toContain(
    'Run your community with clarity, care and complete transparency.',
  )

  await page.goto('/')
  await expect(page.locator('#poster')).toBeHidden({ timeout: 10_000 }) // handed over to React
  const headings = page.getByRole('heading', { level: 1 })
  await expect(headings).toHaveCount(1)
  await expect(headings).toHaveText(/Run your community with clarity/)
  expect(failures.filter((text) => !text.includes('401'))).toEqual([])
})

test('no other page shows the landing poster', async ({ page }) => {
  await stubSession(page)
  await page.goto('/login')
  await expect(page.locator('#poster')).toBeHidden()
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()
})

test('the sections load as you scroll, and section links jump to them', async ({ page }) => {
  await stubSession(page)
  await page.route('**/api/v1/public/plans', (route) =>
    route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } }),
  )
  await page.goto('/')
  await page
    .getByRole('navigation', { name: 'Sections', exact: true })
    .getByRole('link', { name: 'FAQ' })
    .click()
  await expect(
    page.getByRole('heading', { level: 2, name: 'Questions people ask first' }),
  ).toBeVisible()
  await expect(page).toHaveURL(/#faq$/)

  const question = page.getByRole('button', { name: 'Do members need to log in?' })
  await question.click()
  await expect(page.getByText(/Only admins have accounts/)).toBeVisible()
})

test('the demo form sends the request to /public/leads and confirms it', async ({ page }) => {
  await stubSession(page)
  await page.route('**/api/v1/public/plans', (route) =>
    route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } }),
  )
  let sent: Record<string, unknown> | undefined
  await page.route('**/api/v1/public/leads', async (route) => {
    sent = route.request().postDataJSON() as Record<string, unknown>
    await route.fulfill({ status: 202, json: { received: true } })
  })

  await page.goto('/#demo')
  const form = page.getByRole('form', { name: 'Request a demo' })
  await expect(form).toBeVisible()

  await form.getByRole('button', { name: 'Request a demo' }).click()
  await expect(form.getByText('Enter your name')).toBeVisible() // validated in the browser first
  expect(sent).toBeUndefined()

  await form.getByLabel(/Your name/).fill('Asha Rao')
  await form.getByLabel(/^Email/).fill('asha@example.com')
  await form.getByLabel(/Community name/).fill('Lotus Residents')
  await form.getByLabel(/Approximate number/).fill('240')
  await form.getByRole('button', { name: 'Request a demo' }).click()

  await expect(page.getByRole('status').filter({ hasText: 'Thank you' })).toBeVisible()
  expect(sent).toEqual({
    name: 'Asha Rao',
    email: 'asha@example.com',
    communityName: 'Lotus Residents',
    size: 240,
    website: '',
  })
})

test('on a phone the menu opens as a drawer', async ({ page }) => {
  await stubSession(page)
  await page.setViewportSize({ width: 390, height: 800 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open menu' }).click()
  const drawer = page.getByRole('dialog', { name: 'Menu' })
  await expect(drawer.getByRole('link', { name: 'Pricing' })).toBeVisible()
  await drawer.getByRole('link', { name: 'Pricing' }).click()
  await expect(drawer).toBeHidden()
  await expect(
    page.getByRole('heading', { level: 2, name: 'Simple plans, no surprises' }),
  ).toBeVisible()
})

test('legal pages are clearly drafts and stay out of search results', async ({ page }) => {
  await stubSession(page)
  await page.goto('/privacy')
  await expect(page.getByRole('note')).toContainText('Draft for legal review')
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
  await expect(page.locator('#poster')).toBeHidden()
})
