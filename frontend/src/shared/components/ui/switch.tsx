import * as SwitchPrimitive from '@radix-ui/react-switch'
import { useId, type ComponentProps, type ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'
import { Label } from './label'

interface SwitchProps extends ComponentProps<typeof SwitchPrimitive.Root> {
  label?: ReactNode
  description?: ReactNode
}

export function Switch({ className, label, description, id, ...props }: SwitchProps) {
  const autoId = useId()
  const inputId = id ?? autoId
  const control = (
    <SwitchPrimitive.Root
      id={inputId}
      className={cn(
        'peer inline-flex h-6 w-11 shrink-0 items-center rounded-full border border-transparent bg-muted transition-colors disabled:cursor-not-allowed disabled:opacity-60 data-[state=checked]:bg-brand-600',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb className="block size-5 translate-x-0.5 rounded-full bg-white shadow transition-transform data-[state=checked]:translate-x-[22px]" />
    </SwitchPrimitive.Root>
  )
  if (!label) return control
  return (
    <div className="flex min-h-11 items-start gap-3 py-2">
      {control}
      <div className="grid gap-0.5">
        <Label htmlFor={inputId} className="cursor-pointer leading-snug">
          {label}
        </Label>
        {description ? <p className="text-sm text-muted">{description}</p> : null}
      </div>
    </div>
  )
}
