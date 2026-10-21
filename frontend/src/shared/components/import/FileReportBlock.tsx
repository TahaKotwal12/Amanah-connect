import type { ImportFileReport } from '@/types'

const MAX_ROWS_SHOWN = 100

/** One uploaded file's dry-run result: counts, file-level problems, and the rows that need fixing. */
export function FileReportBlock({ title, report }: { title: string; report: ImportFileReport }) {
  if (!report.provided) return null
  return (
    <section aria-label={`${title} results`} className="grid gap-2">
      <h3 className="font-semibold text-brand-900">{title}</h3>
      <p className="text-sm">
        {report.totalRows.toLocaleString('en-IN')} rows: {report.validRows.toLocaleString('en-IN')}{' '}
        ready, {report.invalidRows.toLocaleString('en-IN')} with problems.
      </p>
      {report.errors.map((message) => (
        <p key={message} role="alert" className="text-sm text-danger">
          {message}
        </p>
      ))}
      {report.warnings.map((message) => (
        <p key={message} className="text-sm text-warning">
          {message}
        </p>
      ))}
      {report.invalid.length > 0 ? (
        <div className="max-h-64 overflow-auto rounded border border-brand-100">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">{title}: rows with problems</caption>
            <thead className="sticky top-0 bg-brand-50">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">
                  Row
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Identifier
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  What is wrong
                </th>
              </tr>
            </thead>
            <tbody>
              {report.invalid.slice(0, MAX_ROWS_SHOWN).map((row) => (
                <tr key={row.row} className="border-t border-brand-50 align-top">
                  <td className="px-3 py-2 tabular-nums">{row.row}</td>
                  <td className="px-3 py-2">{row.key}</td>
                  <td className="px-3 py-2">{row.errors.join('; ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {report.invalid.length > MAX_ROWS_SHOWN ? (
        <p className="text-sm text-muted">
          Showing the first {MAX_ROWS_SHOWN} of {report.invalid.length.toLocaleString('en-IN')} rows
          with problems. Fix the file and upload it again to see the rest.
        </p>
      ) : null}
    </section>
  )
}
