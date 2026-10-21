import { formatBytes, validateFile } from './upload'

function file(name: string, type: string, size: number): File {
  return new File([new Uint8Array(size)], name, { type })
}

describe('validateFile', () => {
  const rules = { accept: ['image/png', 'application/pdf'], maxBytes: 1024 * 1024 }

  it('accepts a file that meets the rules', () => {
    expect(validateFile(file('logo.png', 'image/png', 2048), rules)).toBeNull()
  })

  it('rejects a type that is not allowed, naming the allowed ones', () => {
    const problem = validateFile(file('virus.exe', 'application/x-msdownload', 100), rules)
    expect(problem).toContain('virus.exe')
    expect(problem).toContain('PNG, PDF')
  })

  it('rejects a file over the size limit, with both sizes', () => {
    const problem = validateFile(file('big.png', 'image/png', 3 * 1024 * 1024), rules)
    expect(problem).toContain('3.0 MB')
    expect(problem).toContain('1.0 MB')
  })

  it('rejects an empty file', () => {
    expect(validateFile(file('empty.png', 'image/png', 0), {})).toContain('empty')
  })

  it('allows anything when there are no rules', () => {
    expect(validateFile(file('a.bin', 'application/octet-stream', 5), {})).toBeNull()
  })
})

describe('formatBytes', () => {
  it.each([
    [500, '500 B'],
    [2048, '2 KB'],
    [5 * 1024 * 1024, '5.0 MB'],
  ])('%d bytes is %s', (bytes, text) => {
    expect(formatBytes(bytes)).toBe(text)
  })
})
