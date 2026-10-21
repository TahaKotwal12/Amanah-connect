import { Eye, EyeOff } from 'lucide-react'
import { useState, type ComponentProps, type KeyboardEvent } from 'react'
import { cn } from '@/shared/utils/cn'
import { Input } from './input'

interface PasswordInputProps extends Omit<ComponentProps<'input'>, 'type'> {
  /** Say so when Caps Lock is on while typing. On by default. */
  capsLockHint?: boolean
}

/**
 * A password box with a show/hide button and a Caps Lock warning. The button is a real, labelled
 * toggle (aria-pressed), and the warning is a polite live message, so neither depends on sight.
 */
export function PasswordInput({
  className,
  capsLockHint = true,
  onKeyDown,
  onKeyUp,
  onBlur,
  ...props
}: PasswordInputProps) {
  const [visible, setVisible] = useState(false)
  const [capsLock, setCapsLock] = useState(false)

  const watchCapsLock = (event: KeyboardEvent<HTMLInputElement>) => {
    // getModifierState is missing on some synthetic events; treat "unknown" as off.
    setCapsLock(event.getModifierState?.('CapsLock') ?? false)
  }

  return (
    <div className="grid gap-1.5">
      <div className="relative">
        <Input
          type={visible ? 'text' : 'password'}
          className={cn('pr-12', className)}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          onKeyDown={(event) => {
            watchCapsLock(event)
            onKeyDown?.(event)
          }}
          onKeyUp={(event) => {
            watchCapsLock(event)
            onKeyUp?.(event)
          }}
          onBlur={(event) => {
            setCapsLock(false)
            onBlur?.(event)
          }}
          {...props}
        />
        <button
          type="button"
          onClick={() => {
            setVisible((current) => !current)
          }}
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-pressed={visible}
          className="absolute right-1 top-1/2 flex size-10 -translate-y-1/2 items-center justify-center rounded text-muted hover:text-ink"
        >
          {visible ? (
            <EyeOff aria-hidden="true" className="size-5" />
          ) : (
            <Eye aria-hidden="true" className="size-5" />
          )}
        </button>
      </div>
      {capsLockHint ? (
        <p role="status" aria-live="polite" className="min-h-5 text-sm font-medium text-warning">
          {capsLock ? 'Caps Lock is on.' : null}
        </p>
      ) : null}
    </div>
  )
}
