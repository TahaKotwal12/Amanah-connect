import type { JsonObject } from '@/types'
import { diffObjects } from '../auditDiff'

function show(value: unknown): string {
  if (value === null) return 'empty'
  if (typeof value === 'string') return value === '' ? '(blank)' : value
  return JSON.stringify(value)
}

const WORD = { added: 'Added', removed: 'Removed', changed: 'Changed', same: 'Unchanged' } as const

/**
 * Before and after of one audit entry. Colour is never the only signal: each line also says
 * Added, Removed or Changed, and a change reads "was X, now Y".
 */
export function AuditDiff({
  before,
  after,
}: {
  before: JsonObject | null
  after: JsonObject | null
}) {
  const lines = diffObjects(before, after)
  const changes = lines.filter((line) => line.kind !== 'same')
  const unchanged = lines.length - changes.length

  if (lines.length === 0) {
    return <p className="text-sm text-muted">No details were recorded for this entry.</p>
  }
  return (
    <div>
      <table className="w-full text-left text-sm">
        <caption className="sr-only">What changed</caption>
        <thead className="text-muted">
          <tr>
            <th scope="col" className="py-1 pr-4 font-medium">
              Field
            </th>
            <th scope="col" className="py-1 pr-4 font-medium">
              Change
            </th>
            <th scope="col" className="py-1 font-medium">
              Value
            </th>
          </tr>
        </thead>
        <tbody>
          {(changes.length > 0 ? changes : lines).map((line) => (
            <tr key={line.key} className="border-t border-brand-100 align-top">
              <th scope="row" className="py-1.5 pr-4 font-mono text-xs font-medium">
                {line.key}
              </th>
              <td className="py-1.5 pr-4">
                <span
                  className={
                    line.kind === 'added'
                      ? 'font-medium text-success-700'
                      : line.kind === 'removed'
                        ? 'font-medium text-danger'
                        : line.kind === 'changed'
                          ? 'font-medium text-warning'
                          : 'text-muted'
                  }
                >
                  {WORD[line.kind]}
                </span>
              </td>
              <td className="break-words py-1.5">
                {line.kind === 'changed' ? (
                  <>
                    <span className="text-muted line-through">{show(line.before)}</span>
                    {' → '}
                    <span className="font-medium">{show(line.after)}</span>
                  </>
                ) : line.kind === 'removed' ? (
                  <span className="text-muted line-through">{show(line.before)}</span>
                ) : (
                  show(line.after)
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {changes.length > 0 && unchanged > 0 ? (
        <p className="mt-2 text-xs text-muted">
          {unchanged} {unchanged === 1 ? 'field' : 'fields'} did not change.
        </p>
      ) : null}
    </div>
  )
}
