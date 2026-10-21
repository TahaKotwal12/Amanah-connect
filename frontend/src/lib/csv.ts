export interface CsvColumn<TRow> {
  header: string
  /** Plain text for the cell. Money stays the API's decimal string. */
  value: (row: TRow) => string | number | boolean | null | undefined
}

/** A cell that starts with these is run as a formula by Excel and Sheets: prefix it to defuse. */
const FORMULA_START = /^[=+\-@\t\r]/
/** Plain decimals such as the API's money strings ("-150.00") are data, not formulas. */
const PLAIN_NUMBER = /^-?\d+(\.\d+)?$/

function cell(raw: string | number | boolean | null | undefined): string {
  if (raw === null || raw === undefined) return ''
  let text = String(raw)
  // Spreadsheet formula injection: member names and notes are typed by users, so text that would
  // run as a formula gets a leading apostrophe. Numbers and plain decimals are left alone.
  if (typeof raw === 'string' && FORMULA_START.test(text) && !PLAIN_NUMBER.test(text)) {
    text = `'${text}`
  }
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const BOM = String.fromCharCode(0xfeff)

/** RFC 4180 CSV with CRLF line ends and a BOM, so Excel opens UTF-8 names correctly. */
export function toCsv<TRow>(rows: readonly TRow[], columns: readonly CsvColumn<TRow>[]): string {
  const lines = [
    columns.map((column) => cell(column.header)).join(','),
    ...rows.map((row) => columns.map((column) => cell(column.value(row))).join(',')),
  ]
  return `${BOM}${lines.join('\r\n')}\r\n`
}

export function downloadCsv(filename: string, csv: string): void {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`
  link.click()
  URL.revokeObjectURL(url)
}
