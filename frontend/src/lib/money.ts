import type { MoneyString } from '@/types'

/**
 * Money is a decimal string end to end ("1500.00"), matching the API and the NUMERIC(14,2)
 * column. JS numbers are binary floats (0.1 + 0.2 !== 0.3), so amounts are never converted to
 * `number` here. Arithmetic runs on integer minor units (paise) held in a bigint, which is exact,
 * and goes straight back to a string.
 */

/** Same shape the backend accepts (Money.PLAIN): up to 12 whole digits and 2 decimals. */
const PLAIN_AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/

export function isMoneyString(value: string): boolean {
  return PLAIN_AMOUNT.test(value.trim())
}

/** "1500.5" to 150050n. Throws on anything the API would reject (3 decimals, exponents, commas). */
export function toMinorUnits(amount: MoneyString): bigint {
  const text = amount.trim()
  if (!PLAIN_AMOUNT.test(text)) {
    throw new RangeError(`Not a valid amount: "${amount}"`)
  }
  const negative = text.startsWith('-')
  const [whole = '0', fraction = ''] = (negative ? text.slice(1) : text).split('.')
  const minor = BigInt(whole + fraction.padEnd(2, '0'))
  return negative ? -minor : minor
}

/** 150050n to "1500.50". Zero is always "0.00", never "-0.00". */
export function fromMinorUnits(minor: bigint): MoneyString {
  const negative = minor < 0n
  const digits = (negative ? -minor : minor).toString().padStart(3, '0')
  const sign = negative ? '-' : ''
  return `${sign}${digits.slice(0, -2)}.${digits.slice(-2)}`
}

/** "1500" or "1500.5" to the canonical "1500.00" / "1500.50". */
export function normalizeMoney(amount: MoneyString): MoneyString {
  return fromMinorUnits(toMinorUnits(amount))
}

/**
 * Read what a person typed into an amount box: tolerates the rupee sign, "Rs", spaces and
 * Indian or Western grouping commas ("1,50,000.5"). Returns null when it is not a valid amount
 * (including more than 2 decimals, which is never silently rounded).
 */
export function parseMoneyInput(text: string): MoneyString | null {
  const cleaned = text.replace(/₹|rs\.?|inr/gi, '').replace(/[,\s]/g, '')
  if (cleaned === '' || !PLAIN_AMOUNT.test(cleaned)) return null
  return normalizeMoney(cleaned)
}

export function addMoney(...amounts: MoneyString[]): MoneyString {
  return fromMinorUnits(amounts.reduce((total, amount) => total + toMinorUnits(amount), 0n))
}

export function subtractMoney(from: MoneyString, amount: MoneyString): MoneyString {
  return fromMinorUnits(toMinorUnits(from) - toMinorUnits(amount))
}

export function negateMoney(amount: MoneyString): MoneyString {
  return fromMinorUnits(-toMinorUnits(amount))
}

export function sumMoney(amounts: readonly MoneyString[]): MoneyString {
  return addMoney(...amounts)
}

/** -1, 0 or 1. */
export function compareMoney(a: MoneyString, b: MoneyString): -1 | 0 | 1 {
  const left = toMinorUnits(a)
  const right = toMinorUnits(b)
  return left < right ? -1 : left > right ? 1 : 0
}

export function isPositiveMoney(amount: MoneyString): boolean {
  return toMinorUnits(amount) > 0n
}

export function isZeroMoney(amount: MoneyString): boolean {
  return toMinorUnits(amount) === 0n
}

export function minMoney(a: MoneyString, b: MoneyString): MoneyString {
  return compareMoney(a, b) <= 0 ? normalizeMoney(a) : normalizeMoney(b)
}

export function maxMoney(a: MoneyString, b: MoneyString): MoneyString {
  return compareMoney(a, b) >= 0 ? normalizeMoney(a) : normalizeMoney(b)
}

export interface FormatMoneyOptions {
  /** ISO 4217. The community's own currency comes from the API (`currency`); INR by default. */
  currency?: string
  /** false drops the currency symbol: "1,50,000.00". */
  symbol?: boolean
}

const formatters = new Map<string, Intl.NumberFormat>()

function formatterFor(currency: string, symbol: boolean): Intl.NumberFormat {
  const key = `${currency}|${symbol}`
  let formatter = formatters.get(key)
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-IN', {
      ...(symbol ? { style: 'currency', currency } : { style: 'decimal' }),
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
    formatters.set(key, formatter)
  }
  return formatter
}

/**
 * Display an amount in Indian grouping: "1500" gives "₹1,500.00" and "15000000.5" gives
 * "₹1,50,00,000.50". The string goes to Intl as a string (an exact decimal), not through Number.
 * Use for display only: never parse the result.
 */
export function formatMoney(amount: MoneyString, options: FormatMoneyOptions = {}): string {
  const exact = normalizeMoney(amount) as `${number}`
  return formatterFor(options.currency ?? 'INR', options.symbol ?? true).format(exact)
}
