import { useCallback } from 'react'
import { downloadCsv, toCsv, type CsvColumn } from '@/lib/csv'

/**
 * Download rows as a CSV file.
 *
 *   const exportCsv = useCsvExport('members', [{ header: 'Name', value: (m) => m.fullName }])
 *   <Button onClick={() => exportCsv(members)}>Export</Button>
 *
 * Exports what you pass. For a full export of a server-paged list, fetch every page (or the
 * backend export endpoint) first and pass those rows.
 */
export function useCsvExport<TRow>(filename: string, columns: readonly CsvColumn<TRow>[]) {
  return useCallback(
    (rows: readonly TRow[]) => {
      downloadCsv(filename, toCsv(rows, columns))
    },
    [filename, columns],
  )
}
