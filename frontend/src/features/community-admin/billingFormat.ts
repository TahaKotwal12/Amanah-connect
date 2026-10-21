import type { GenerateResult } from '@/types'

/** "32 created, 5 already existed": what a run did, in words. */
export function describeRun(result: Pick<GenerateResult, 'created' | 'alreadyBilled'>): string {
  const created = `${result.created.toLocaleString('en-IN')} created`
  if (result.alreadyBilled === 0) return created
  return `${created}, ${result.alreadyBilled.toLocaleString('en-IN')} already existed`
}
