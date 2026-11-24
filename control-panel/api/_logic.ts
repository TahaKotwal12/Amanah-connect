import { createHash, timingSafeEqual } from 'node:crypto'

/** What the visitor sees. One word each, so the page can show a clear message for every case. */
export type Phase = 'stopped' | 'starting' | 'booting' | 'ready' | 'stopping' | 'unavailable'

/**
 * EC2 knows "running" as soon as the machine is on, but the app needs another minute or two to
 * start inside it. "booting" is that gap: the machine is on and the site is not answering yet.
 */
export function toPhase(ec2State: string | undefined, siteUp: boolean): Phase {
  switch (ec2State) {
    case 'stopped':
      return 'stopped'
    case 'pending':
      return 'starting'
    case 'running':
      return siteUp ? 'ready' : 'booting'
    case 'stopping':
    case 'shutting-down':
      return 'stopping'
    default:
      return 'unavailable'
  }
}

/** Compares in constant time so the time a check takes never hints at how much of a code matched. */
export function codeMatches(given: string | undefined, expected: string | undefined): boolean {
  if (!expected || !given) return false
  const a = createHash('sha256').update(given).digest()
  const b = createHash('sha256').update(expected).digest()
  return timingSafeEqual(a, b)
}

/** Whether a start or stop request makes sense right now. Anything else is answered, not performed. */
export function canStart(state: string | undefined): boolean {
  return state === 'stopped'
}
export function canStop(state: string | undefined): boolean {
  return state === 'running' || state === 'pending'
}
