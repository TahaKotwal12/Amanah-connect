import { splitPreview } from './splitPreview'

describe('splitPreview', () => {
  it('an even division gives every member the same share', () => {
    expect(splitPreview('1000.00', 4)).toEqual({ low: '250.00', high: '250.00', payingHigh: 0 })
  })

  it('shows both amounts when it does not divide evenly, as the server will bill them', () => {
    expect(splitPreview('1000.00', 3)).toEqual({ low: '333.33', high: '333.34', payingHigh: 1 })
    expect(splitPreview('100.02', 4)).toEqual({ low: '25.00', high: '25.01', payingHigh: 2 })
  })

  it('one member pays everything', () => {
    expect(splitPreview('12.34', 1)).toEqual({ low: '12.34', high: '12.34', payingHigh: 0 })
  })

  it('has no answer for nobody, or for less than a paisa each', () => {
    expect(splitPreview('10.00', 0)).toBeNull()
    expect(splitPreview('0.01', 2)).toBeNull()
  })

  it('never loses a paisa, whatever the numbers', () => {
    for (const members of [1, 2, 3, 7, 11, 13, 40]) {
      for (const total of ['0.99', '1.00', '7.77', '12345.67', '999999.99']) {
        const preview = splitPreview(total, members)
        if (!preview) continue
        const toPaise = (value: string) => BigInt(value.replace('.', ''))
        const high = BigInt(preview.payingHigh)
        const paid = toPaise(preview.high) * high + toPaise(preview.low) * (BigInt(members) - high)
        expect(paid, `${total} over ${members}`).toBe(toPaise(total))
      }
    }
  })
})
