/**
 * The password rules, checked in the browser so people see what is wrong while they type.
 * This mirrors backend/.../auth/PasswordPolicy.java and is advisory: the server decides, and its
 * answer is shown if it disagrees. If the backend rules change, change this list with them.
 */

const MIN_LENGTH = 10
const MAX_BYTES = 72

const COMMON_BASES = new Set([
  'password',
  'passw0rd',
  'qwerty',
  'qwertyuiop',
  'qwertyuiopasdf',
  'letmein',
  'welcome',
  'admin',
  'administrator',
  'iloveyou',
  'monkey',
  'dragon',
  'football',
  'baseball',
  'changeme',
  'trustno',
  'abc',
  'amanah',
  'amanahconnect',
  'community',
  'superadmin',
  'default',
  'secret',
  'master',
  'login',
  'princess',
  'sunshine',
  'whatever',
])

export type StrengthLabel = 'Too short' | 'Weak' | 'Fair' | 'Good' | 'Strong'

export interface PasswordCheck {
  /** Rules still unmet, as sentences. Empty when the password is acceptable. */
  problems: string[]
  ok: boolean
  /** 0 (nothing typed) to 4, for the meter. */
  score: 0 | 1 | 2 | 3 | 4
  label: StrengthLabel | ''
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).length
}

function isSequence(lowered: string): boolean {
  return '01234567890123456789'.includes(lowered) || 'abcdefghijklmnopqrstuvwxyz'.includes(lowered)
}

/** `email` lets the check refuse a password built from the person's own address, as the server does. */
export function checkPassword(password: string, email?: string): PasswordCheck {
  if (password === '') return { problems: [], ok: false, score: 0, label: '' }

  const problems: string[] = []
  if (password.length < MIN_LENGTH) {
    problems.push(`Use at least ${MIN_LENGTH} characters.`)
  } else {
    if (byteLength(password) > MAX_BYTES)
      problems.push('Use at most 72 bytes (about 72 characters).')

    const classes = [/\p{Ll}/u, /\p{Lu}/u, /\d/, /[^\p{L}\d]/u].filter((rule) =>
      rule.test(password),
    ).length
    const longPassphrase = password.length >= 16 && classes >= 2
    if (classes < 3 && !longPassphrase) {
      problems.push(
        'Mix at least three of: lower case, upper case, digits and symbols, or use a longer passphrase.',
      )
    }
    if (new Set(password).size < 5) problems.push('Use a wider variety of characters.')

    const lowered = password.toLowerCase()
    const lettersOnly = lowered.replace(/[^a-z]/g, '')
    if (COMMON_BASES.has(lowered) || COMMON_BASES.has(lettersOnly) || isSequence(lowered)) {
      problems.push('That password is too common or predictable.')
    }
    const local = (email?.split('@')[0] ?? '').toLowerCase()
    if (local.length >= 4 && lowered.includes(local)) {
      problems.push('Do not include your email name in the password.')
    }
  }

  const ok = problems.length === 0
  if (password.length < MIN_LENGTH) return { problems, ok, score: 1, label: 'Too short' }
  if (!ok) return { problems, ok, score: 1, label: 'Weak' }
  if (password.length < 12) return { problems, ok, score: 2, label: 'Fair' }
  if (password.length < 16) return { problems, ok, score: 3, label: 'Good' }
  return { problems, ok, score: 4, label: 'Strong' }
}
