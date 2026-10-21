import * as CheckboxPrimitive from '@radix-ui/react-checkbox'
import { Check, Minus } from 'lucide-react'
import { useId, type ComponentProps, type ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'
import { Label } from './label'

interface CheckboxProps extends Omit<ComponentProps<typeof CheckboxPrimitive.Root>, 'children'> {
  /** Visible label. The whole row is the click target (44px tall). */
  label?: ReactNode
  description?: ReactNode
}

export function Checkbox({ className, label, description, id, ...props }: CheckboxProps) {
  const autoId = useId()
  const inputId = id ?? autoId
  const box = (
    <CheckboxPrimitive.Root
      id={inputId}
      className={cn(
        'peer flex size-5 shrink-0 items-center justify-center rounded border border-muted bg-white disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-danger data-[state=checked]:border-brand-600 data-[state=indeterminate]:border-brand-600 data-[state=checked]:bg-brand-600 data-[state=indeterminate]:bg-brand-600',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator className="text-white">
        {props.checked === 'indeterminate' ? (
          <Minus aria-hidden="true" className="size-3.5" />
        ) : (
          <Check aria-hidden="true" className="size-3.5" />
        )}
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
  if (!label) return box
  return (
    <div className="flex min-h-11 items-start gap-3 py-2">
      <div className="pt-0.5">{box}</div>
      <div className="grid gap-0.5">
        <Label htmlFor={inputId} className="cursor-pointer leading-snug">
          {label}
        </Label>
        {description ? <p className="text-sm text-muted">{description}</p> : null}
      </div>
    </div>
  )
}
