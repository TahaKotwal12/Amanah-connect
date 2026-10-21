import {
  addMoney,
  compareMoney,
  formatMoney,
  fromMinorUnits,
  isMoneyString,
  isPositiveMoney,
  isZeroMoney,
  maxMoney,
  minMoney,
  negateMoney,
  normalizeMoney,
  parseMoneyInput,
  subtractMoney,
  sumMoney,
  toMinorUnits,
} from './money'

describe('money arithmetic', () => {
  it('is exact where floating point is not', () => {
    // 0.1 + 0.2 === 0.30000000000000004 as numbers
    expect(addMoney('0.10', '0.20')).toBe('0.30')
    expect(sumMoney(['1500.10', '1500.20', '0.01'])).toBe('3000.31')
    expect(subtractMoney('1500.00', '0.01')).toBe('1499.99')
  })

  it('adds a long run of small amounts without drift', () => {
    const hundred = Array.from({ length: 100 }, () => '0.10')
    expect(sumMoney(hundred)).toBe('10.00')
  })

  it('handles negatives and never produces negative zero', () => {
    expect(subtractMoney('10.00', '25.50')).toBe('-15.50')
    expect(negateMoney('15.50')).toBe('-15.50')
    expect(negateMoney('0.00')).toBe('0.00')
    expect(addMoney('-0.50', '0.50')).toBe('0.00')
  })

  it('handles the largest amount the API accepts', () => {
    expect(addMoney('999999999999.99', '0.01')).toBe('1000000000000.00')
  })

  it('compares, and classifies sign', () => {
    expect(compareMoney('10.00', '9.99')).toBe(1)
    expect(compareMoney('9.99', '10.00')).toBe(-1)
    expect(compareMoney('10', '10.00')).toBe(0)
    expect(isPositiveMoney('0.01')).toBe(true)
    expect(isPositiveMoney('0.00')).toBe(false)
    expect(isZeroMoney('0')).toBe(true)
    expect(minMoney('5.5', '7')).toBe('5.50')
    expect(maxMoney('5.5', '7')).toBe('7.00')
  })

  it('works in minor units (paise)', () => {
    expect(toMinorUnits('1500.5')).toBe(150050n)
    expect(toMinorUnits('-0.05')).toBe(-5n)
    expect(fromMinorUnits(5n)).toBe('0.05')
    expect(fromMinorUnits(-150050n)).toBe('-1500.50')
  })
})

describe('money validation', () => {
  it.each(['1.234', '1e3', '1,500.00', '', ' ', 'abc', '1.', '.5', '--1', '1234567890123'])(
    'rejects %j, as the API would',
    (bad) => {
      expect(isMoneyString(bad)).toBe(false)
      expect(() => toMinorUnits(bad)).toThrow(RangeError)
    },
  )

  it('normalizes to two decimals', () => {
    expect(normalizeMoney('1500')).toBe('1500.00')
    expect(normalizeMoney('1500.5')).toBe('1500.50')
    expect(normalizeMoney(' 7.05 ')).toBe('7.05')
  })
})

describe('parseMoneyInput', () => {
  it.each([
    ['1500', '1500.00'],
    ['1,50,000.5', '150000.50'],
    ['1,500,000', '1500000.00'],
    ['₹ 2,500', '2500.00'],
    ['Rs. 99.99', '99.99'],
  ])('reads %j as %j', (typed, expected) => {
    expect(parseMoneyInput(typed)).toBe(expected)
  })

  it('refuses to round: three decimals is invalid, not 12.35', () => {
    expect(parseMoneyInput('12.345')).toBeNull()
  })

  it.each(['', 'abc', '12.3.4', '1e5'])('returns null for %j', (typed) => {
    expect(parseMoneyInput(typed)).toBeNull()
  })
})

describe('formatMoney (en-IN)', () => {
  it('uses lakh and crore grouping with the rupee sign', () => {
    expect(formatMoney('1500')).toBe('₹1,500.00')
    expect(formatMoney('150000')).toBe('₹1,50,000.00')
    expect(formatMoney('15000000.5')).toBe('₹1,50,00,000.50')
  })

  it('formats negatives and zero', () => {
    expect(formatMoney('-1500.5')).toBe('-₹1,500.50')
    expect(formatMoney('0')).toBe('₹0.00')
  })

  it('can drop the symbol', () => {
    expect(formatMoney('150000', { symbol: false })).toBe('1,50,000.00')
  })

  it('keeps all digits of a large amount (no float rounding)', () => {
    expect(formatMoney('999999999999.99', { symbol: false })).toBe('9,99,99,99,99,999.99')
  })

  it('formats another currency', () => {
    expect(formatMoney('1500', { currency: 'USD' })).toContain('1,500.00')
  })
})
