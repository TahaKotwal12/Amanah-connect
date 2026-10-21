import { useState } from 'react'
import { formatMoney, normalizeMoney } from '@/lib/money'
import type { MoneyString } from '@/types'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

interface MoneyInputProps {
  /** Canonical decimal string ("1500.50") or "" when empty. Never a number. */
  value: MoneyString
  /** Called with the canonical string, or "" while the box is empty. */
  onValueChange: (value: MoneyString) => void
  onBlur?: () => void
  id?: string
  name?: string
  disabled?: boolean
  placeholder?: string
  /** Shown before the number. Display only. */
  symbol?: string
  /** ISO currency used for grouping when formatting at rest. */
  currency?: string
  className?: string
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string
  'aria-label'?: string
  'aria-required'?: boolean
}

/** At most 12 whole digits and 2 decimals: what the API accepts. */
const ALLOWED_TYPING = /^\d{0,12}(\.\d{0,2})?$/

function toCanonical(text: string): MoneyString {
  if (text === '' || text === '.') return ''
  const whole = text.startsWith('.') ? `0${text}` : text // ".5" is 0.5
  return normalizeMoney(whole.endsWith('.') ? whole.slice(0, -1) : whole) // "15." is 15
}

/**
 * An amount box that formats as Indian grouping at rest ("1,50,000.00") and edits as plain digits.
 * It only lets valid keystrokes through (no letters, no third decimal), and reports a decimal
 * string, so the value never passes through a JS number.
 */
export function MoneyInput({
  value,
  onValueChange,
  onBlur,
  symbol = '₹',
  currency = 'INR',
  className,
  ...props
}: MoneyInputProps) {
  const [focused, setFocused] = useState(false)
  const [typed, setTyped] = useState('')

  // At rest the box shows the grouped value; while editing it shows what the person typed.
  const shown = focused ? typed : value ? formatMoney(value, { currency, symbol: false }) : ''

  return (
    <div className="relative">
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted"
      >
        {symbol}
      </span>
      <input
        type="text"
        inputMode="decimal"
        autoComplete="off"
        className={cn(fieldClasses, 'pl-8 text-right tabular-nums', className)}
        value={shown}
        onFocus={() => {
          setTyped(value)
          setFocused(true)
        }}
        onChange={(event) => {
          // Pasted "₹ 1,50,000.50" is welcome: strip the decoration, then validate.
          const cleaned = event.target.value.replace(/[₹,\s]/g, '')
          if (!ALLOWED_TYPING.test(cleaned)) return
          setTyped(cleaned)
          onValueChange(toCanonical(cleaned))
        }}
        onBlur={() => {
          setFocused(false)
          onBlur?.()
        }}
        {...props}
      />
    </div>
  )
}
