import { scrubEvent, scrubText, scrubUrl } from './scrub'

describe('scrubText', () => {
  it('removes emails, bearer tokens, JWTs and phone numbers', () => {
    const out = scrubText(
      'Failed for asha@example.com, +91 98765 43210, Bearer abc.def-ghi and eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig',
    )
    expect(out).not.toMatch(/asha|98765|abc\.def|eyJ/)
    expect(out).toContain('[email]')
    expect(out).toContain('[phone]')
    expect(out).toContain('Bearer [token]')
  })

  it('leaves short numbers and ordinary text alone', () => {
    expect(scrubText('Status 500 on page 3 of 12, code INTERNAL_ERROR')).toBe(
      'Status 500 on page 3 of 12, code INTERNAL_ERROR',
    )
  })

  it('hides member link tokens in paths', () => {
    expect(scrubText('/pay/AbC123xyz and /join/Q9')).toBe('/pay/[token] and /join/[token]')
  })
})

describe('scrubUrl', () => {
  it('drops the query string and fragment (reset and invite links carry tokens there)', () => {
    expect(scrubUrl('https://app.test/reset-password?token=secret#x')).toBe(
      'https://app.test/reset-password',
    )
  })
})

describe('scrubEvent', () => {
  it('drops user, cookies, headers and bodies, and cleans what remains', () => {
    const out = scrubEvent({
      message: 'Could not email ravi@example.org',
      user: { id: 'u1', email: 'ravi@example.org' },
      request: {
        url: 'https://app.test/pay/TOKEN123?x=1',
        cookies: 'a=b',
        headers: { Authorization: 'Bearer x' },
        data: { name: 'Ravi' },
      },
      extra: { cookie: 'x', note: 'call +91 98765 43210', depth: { authorization: 'x', keep: 1 } },
    })
    expect(JSON.stringify(out)).not.toMatch(/ravi|98765|TOKEN123|Bearer x|a=b|Authorization/i)
    expect(out.request).toEqual({ url: 'https://app.test/pay/[token]' })
    expect((out as { extra: { depth: { keep: number } } }).extra.depth.keep).toBe(1)
    expect('user' in out).toBe(false)
  })
})
