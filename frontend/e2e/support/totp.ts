import { createHmac } from 'node:crypto'

/**
 * Time-based one-time passwords (RFC 6238: SHA-1, 30-second steps, 6 digits), the same scheme the
 * backend checks. Used only by live tests against the seeded development accounts, whose shared
 * secret is fixed and public (backend DevSeedData.TOTP_SECRET).
 */
export const DEV_SEED_TOTP_SECRET = 'DEVSEEDDEVSEEDDEVSEEDDEVSEED2222'
export const DEV_SEED_RECOVERY_CODE = 'DEVSEED-RECOV1'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

function base32Decode(text: string): Buffer {
  let bits = ''
  for (const char of text.replace(/=+$/, '').toUpperCase()) {
    const value = ALPHABET.indexOf(char)
    if (value < 0) throw new Error(`Not base32: ${char}`)
    bits += value.toString(2).padStart(5, '0')
  }
  const bytes: number[] = []
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2))
  return Buffer.from(bytes)
}

/** The code for the 30-second step containing `atMs` (default: now). */
export function totp(secret: string = DEV_SEED_TOTP_SECRET, atMs: number = Date.now()): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(atMs / 1000 / 30)))
  const hash = createHmac('sha1', base32Decode(secret)).update(counter).digest()
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f
  const binary =
    (((hash[offset] ?? 0) & 0x7f) << 24) |
    (((hash[offset + 1] ?? 0) & 0xff) << 16) |
    (((hash[offset + 2] ?? 0) & 0xff) << 8) |
    ((hash[offset + 3] ?? 0) & 0xff)
  return String(binary % 1_000_000).padStart(6, '0')
}

let lastStep = -1

/**
 * A code the server will accept: it refuses a code from a step it has already seen for that
 * account (replay protection), so a second sign-in within the same 30 seconds waits for the next.
 */
export async function freshTotp(): Promise<string> {
  let step = Math.floor(Date.now() / 30_000)
  if (step === lastStep) {
    await new Promise((resolve) => setTimeout(resolve, 30_000 - (Date.now() % 30_000) + 500))
    step = Math.floor(Date.now() / 30_000)
  }
  lastStep = step
  return totp()
}
