import { useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

interface OtpInputProps {
  /** Number of digits. 6 for authenticator apps. */
  length?: number
  /** Accessible name of the whole group, e.g. "Authenticator code". */
  label: string
  /** Called with the digits typed so far (fewer than `length` while incomplete). */
  onChange?: (value: string) => void
  /** Called once every box is filled, by typing or by pasting a whole code. */
  onComplete?: (value: string) => void
  disabled?: boolean
  invalid?: boolean
  /** Describes an error or hint for the group. */
  'aria-describedby'?: string
  /** Focus the first box on mount. */
  focusOnMount?: boolean
}

/**
 * One box per digit. Typing a digit moves on, Backspace on an empty box moves back, the arrow keys
 * move around, and pasting (or an SMS/password-manager autofill) spreads the digits across the boxes.
 * It keeps its own digits: remount it with a new `key` to clear it after a wrong code.
 */
export function OtpInput({
  length = 6,
  label,
  onChange,
  onComplete,
  disabled,
  invalid,
  focusOnMount,
  ...aria
}: OtpInputProps) {
  const [digits, setDigits] = useState<string[]>(() => Array<string>(length).fill(''))
  const boxes = useRef<Array<HTMLInputElement | null>>([])

  useEffect(() => {
    if (focusOnMount) boxes.current[0]?.focus()
  }, [focusOnMount])

  function commit(next: string[], focusAt?: number) {
    setDigits(next)
    const value = next.join('')
    onChange?.(value)
    if (focusAt !== undefined) boxes.current[Math.min(focusAt, length - 1)]?.focus()
    if (next.every((digit) => digit !== '')) onComplete?.(value)
  }

  /** Put `incoming` digits into the boxes starting at `start`. */
  function fill(start: number, incoming: string) {
    const clean = incoming.replace(/\D/g, '')
    if (clean === '') return
    const next = [...digits]
    let position = start
    for (const digit of clean) {
      if (position >= length) break
      next[position] = digit
      position += 1
    }
    commit(next, position)
  }

  function onKeyDown(index: number, event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Backspace' && digits[index] === '' && index > 0) {
      event.preventDefault()
      const next = [...digits]
      next[index - 1] = ''
      commit(next, index - 1)
    } else if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault()
      boxes.current[index - 1]?.focus()
    } else if (event.key === 'ArrowRight' && index < length - 1) {
      event.preventDefault()
      boxes.current[index + 1]?.focus()
    }
  }

  function onPaste(index: number, event: ClipboardEvent<HTMLInputElement>) {
    event.preventDefault()
    fill(index, event.clipboardData.getData('text'))
  }

  return (
    <div role="group" aria-label={label} className="flex justify-between gap-2" {...aria}>
      {digits.map((digit, index) => (
        <input
          key={index}
          ref={(node) => {
            boxes.current[index] = node
          }}
          value={digit}
          inputMode="numeric"
          // The first box takes the browser's one-time-code autofill; it arrives as a whole code.
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          pattern="[0-9]*"
          aria-label={`${label}, digit ${index + 1} of ${length}`}
          aria-invalid={invalid || undefined}
          disabled={disabled}
          onFocus={(event) => {
            event.target.select()
          }}
          onChange={(event) => {
            const typed = event.target.value.replace(/\D/g, '')
            if (typed === '') {
              const next = [...digits]
              next[index] = ''
              commit(next)
            } else if (typed.length === 1) {
              const next = [...digits]
              next[index] = typed
              commit(next, index + 1)
            } else {
              fill(index, typed) // autofill or a fast paste that skipped onPaste
            }
          }}
          onKeyDown={(event) => {
            onKeyDown(index, event)
          }}
          onPaste={(event) => {
            onPaste(index, event)
          }}
          className={cn(
            fieldClasses,
            'min-h-12 w-full min-w-0 px-0 text-center text-xl font-semibold tabular-nums',
          )}
        />
      ))}
    </div>
  )
}
