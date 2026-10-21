import type { JsonObject } from '@/types'

export interface DiffLine {
  key: string
  kind: 'added' | 'removed' | 'changed' | 'same'
  before?: unknown
  after?: unknown
}

/** Field by field: what was added, removed or changed between two snapshots. Keys are sorted. */
export function diffObjects(before: JsonObject | null, after: JsonObject | null): DiffLine[] {
  const left = before ?? {}
  const right = after ?? {}
  const keys = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()
  return keys.map((key): DiffLine => {
    const inBefore = key in left
    const inAfter = key in right
    if (inBefore && !inAfter) return { key, kind: 'removed', before: left[key] }
    if (!inBefore && inAfter) return { key, kind: 'added', after: right[key] }
    const same = JSON.stringify(left[key]) === JSON.stringify(right[key])
    return same
      ? { key, kind: 'same', before: left[key], after: right[key] }
      : { key, kind: 'changed', before: left[key], after: right[key] }
  })
}
