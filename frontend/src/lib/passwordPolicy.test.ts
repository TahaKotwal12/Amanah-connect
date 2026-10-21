import { checkPassword } from './passwordPolicy'

describe('checkPassword (mirrors the backend policy)', () => {
  it('has nothing to say about an empty box', () => {
    expect(checkPassword('')).toEqual({ problems: [], ok: false, score: 0, label: '' })
  })

  it('requires ten characters', () => {
    const result = checkPassword('Abc123!')
    expect(result.ok).toBe(false)
    expect(result.label).toBe('Too short')
    expect(result.problems).toEqual(['Use at least 10 characters.'])
  })

  it('wants three of four character kinds, or a long passphrase', () => {
    expect(checkPassword('alllowercaseletters').problems.join(' ')).toMatch(/at least three/)
    expect(checkPassword('lowercase1234').problems.join(' ')).toMatch(/at least three/) // only two kinds
    expect(checkPassword('Lowercase1234').ok).toBe(true) // three kinds
    expect(checkPassword('correct horse battery staple').ok).toBe(true) // long passphrase, two kinds
  })

  it('refuses common and sequential passwords, however they are dressed', () => {
    expect(checkPassword('Password123!!').problems.join(' ')).toMatch(/common/) // letters only: "password"
    expect(checkPassword('abcdefghijkl').problems.join(' ')).toMatch(/common|predictable/)
    expect(checkPassword('01234567890').problems.join(' ')).toMatch(/common|predictable/)
  })

  it('wants some variety', () => {
    expect(checkPassword('aaaaaaaaaaA1').problems.join(' ')).toMatch(/wider variety/)
  })

  it('refuses a password built from the email name', () => {
    expect(checkPassword('Asha.Rao2026!', 'asha.rao@example.com').problems.join(' ')).toMatch(
      /email name/,
    )
    expect(checkPassword('Different-Words-7', 'asha.rao@example.com').ok).toBe(true)
    // Short local parts are ignored, as the server does.
    expect(checkPassword('Abc-Words-Here-7', 'abc@example.com').ok).toBe(true)
  })

  it('counts bytes, not characters, against bcrypt’s 72-byte limit', () => {
    expect(checkPassword('Ab1!Cd2@'.repeat(9)).ok).toBe(true) // exactly 72 bytes
    expect(checkPassword('Ab1!Cd2@'.repeat(10)).problems.join(' ')).toMatch(/72 bytes/)
    expect(checkPassword('é1A!Cd2@'.repeat(9)).problems.join(' ')).toMatch(/72 bytes/) // 72 characters, 81 bytes
  })

  it('grades acceptable passwords by length', () => {
    expect(checkPassword('Abcdefg123').label).toBe('Fair')
    expect(checkPassword('Abcdefghij123').label).toBe('Good')
    expect(checkPassword('Abcdefghijklmn1234').label).toBe('Strong')
  })
})
