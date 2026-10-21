import { toCsv } from './csv'

interface Row {
  name: string
  amount: string
  note?: string
}
const columns = [
  { header: 'Name', value: (r: Row) => r.name },
  { header: 'Amount', value: (r: Row) => r.amount },
  { header: 'Note', value: (r: Row) => r.note },
]

const BOM = String.fromCharCode(0xfeff)
const body = (csv: string) => csv.replace(BOM, '')

describe('toCsv', () => {
  it('writes a BOM, a header row and CRLF line ends', () => {
    const csv = toCsv([{ name: 'Asha Rao', amount: '1500.00' }], columns)
    expect(csv.startsWith(BOM)).toBe(true)
    expect(body(csv)).toBe('Name,Amount,Note\r\nAsha Rao,1500.00,\r\n')
  })

  it('quotes commas, quotes and line breaks', () => {
    const csv = toCsv([{ name: 'Rao, Asha', amount: '1.00', note: 'said "hi"\nbye' }], columns)
    expect(body(csv)).toContain('"Rao, Asha",1.00,"said ""hi""\nbye"')
  })

  it('defuses spreadsheet formulas typed into a name or note', () => {
    const csv = toCsv(
      [
        { name: '=HYPERLINK("http://evil")', amount: '1.00', note: '@cmd' },
        { name: '+91 98765 43210', amount: '2.00', note: '-note' },
      ],
      columns,
    )
    expect(body(csv)).toContain(`"'=HYPERLINK(""http://evil"")",1.00,'@cmd`)
    expect(body(csv)).toContain(`'+91 98765 43210,2.00,'-note`)
  })

  it('leaves money alone, including negative amounts', () => {
    const csv = toCsv([{ name: 'Reversal', amount: '-150.00' }], columns)
    expect(body(csv)).toContain('Reversal,-150.00,')
  })

  it('handles an empty list', () => {
    expect(body(toCsv([], columns))).toBe('Name,Amount,Note\r\n')
  })
})
