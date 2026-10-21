import { Check, Copy } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'
import { Input } from './input'
import { Label } from './label'

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

interface CopyFieldProps {
  label: string
  value: string
  className?: string
}

/** A read-only value (an invite link, a payment link) with a copy button. */
export function CopyField({ label, value, className }: CopyFieldProps) {
  const id = useId()
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle')
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => () => clearTimeout(timer.current), [])

  async function copy() {
    const ok = await copyText(value)
    setStatus(ok ? 'copied' : 'failed')
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setStatus('idle')
    }, 2500)
  }

  return (
    <div className={cn('grid gap-1.5', className)}>
      <Label htmlFor={id}>{label}</Label>
      <div className="flex gap-2">
        <Input
          id={id}
          readOnly
          value={value}
          onFocus={(event) => {
            event.target.select()
          }}
        />
        <Button variant="secondary" onClick={() => void copy()} className="shrink-0">
          {status === 'copied' ? (
            <Check aria-hidden="true" className="size-4" />
          ) : (
            <Copy aria-hidden="true" className="size-4" />
          )}
          {status === 'copied' ? 'Copied' : 'Copy'}
          <span className="sr-only"> {label}</span>
        </Button>
      </div>
      <p role="status" aria-live="polite" className="min-h-5 text-sm text-muted">
        {status === 'copied' ? 'Copied to the clipboard.' : null}
        {status === 'failed' ? 'Could not copy. Select the text and copy it by hand.' : null}
      </p>
    </div>
  )
}
