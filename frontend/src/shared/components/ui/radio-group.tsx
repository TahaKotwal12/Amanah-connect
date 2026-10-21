import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import { useId, type ComponentProps, type ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'
import { Label } from './label'

export function RadioGroup({
  className,
  ...props
}: ComponentProps<typeof RadioGroupPrimitive.Root>) {
  return <RadioGroupPrimitive.Root className={cn('grid gap-1', className)} {...props} />
}

interface RadioGroupItemProps extends ComponentProps<typeof RadioGroupPrimitive.Item> {
  label: ReactNode
  description?: ReactNode
}

/** One option with its label: arrow keys move between options, Space selects. */
export function RadioGroupItem({
  className,
  label,
  description,
  id,
  ...props
}: RadioGroupItemProps) {
  const autoId = useId()
  const inputId = id ?? autoId
  return (
    <div className="flex min-h-11 items-start gap-3 py-2">
      <RadioGroupPrimitive.Item
        id={inputId}
        className={cn(
          'mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full border border-muted bg-white disabled:cursor-not-allowed disabled:opacity-60 data-[state=checked]:border-brand-600',
          className,
        )}
        {...props}
      >
        <RadioGroupPrimitive.Indicator className="size-2.5 rounded-full bg-brand-600" />
      </RadioGroupPrimitive.Item>
      <div className="grid gap-0.5">
        <Label htmlFor={inputId} className="cursor-pointer leading-snug">
          {label}
        </Label>
        {description ? <p className="text-sm text-muted">{description}</p> : null}
      </div>
    </div>
  )
}
