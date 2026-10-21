import * as SelectPrimitive from '@radix-ui/react-select'
import { Check, ChevronDown } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

export const Select = SelectPrimitive.Root
export const SelectValue = SelectPrimitive.Value

export function SelectTrigger({
  className,
  children,
  ...props
}: ComponentProps<typeof SelectPrimitive.Trigger>) {
  return (
    <SelectPrimitive.Trigger
      className={cn(
        fieldClasses,
        'items-center justify-between gap-2 text-left data-[placeholder]:text-muted',
        className,
      )}
      {...props}
    >
      {children}
      <SelectPrimitive.Icon asChild>
        <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-muted" />
      </SelectPrimitive.Icon>
    </SelectPrimitive.Trigger>
  )
}

export function SelectContent({
  className,
  children,
  position = 'popper',
  ...props
}: ComponentProps<typeof SelectPrimitive.Content>) {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Content
        position={position}
        sideOffset={4}
        className={cn(
          'z-50 max-h-72 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded border border-brand-100 bg-white text-ink shadow-lift',
          className,
        )}
        {...props}
      >
        <SelectPrimitive.Viewport className="p-1">{children}</SelectPrimitive.Viewport>
      </SelectPrimitive.Content>
    </SelectPrimitive.Portal>
  )
}

export function SelectItem({
  className,
  children,
  ...props
}: ComponentProps<typeof SelectPrimitive.Item>) {
  return (
    <SelectPrimitive.Item
      className={cn(
        'relative flex min-h-10 cursor-pointer select-none items-center rounded py-2 pl-8 pr-3 text-sm outline-none data-[disabled]:pointer-events-none data-[highlighted]:bg-brand-50 data-[disabled]:opacity-50',
        className,
      )}
      {...props}
    >
      <span className="absolute left-2 flex size-4 items-center justify-center">
        <SelectPrimitive.ItemIndicator>
          <Check aria-hidden="true" className="size-4 text-brand-600" />
        </SelectPrimitive.ItemIndicator>
      </span>
      <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
    </SelectPrimitive.Item>
  )
}

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

interface SimpleSelectProps extends Pick<
  ComponentProps<typeof SelectPrimitive.Trigger>,
  'id' | 'className' | 'onBlur'
> {
  options: readonly SelectOption[]
  value?: string
  onValueChange?: (value: string) => void
  placeholder?: string
  disabled?: boolean
  name?: string
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string
  'aria-label'?: string
}

/** The common case in one element: <SimpleSelect options={...} value onValueChange />. */
export function SimpleSelect({
  options,
  value,
  onValueChange,
  placeholder = 'Select',
  disabled,
  name,
  ...triggerProps
}: SimpleSelectProps) {
  return (
    <Select
      {...(value !== undefined ? { value } : {})}
      {...(onValueChange ? { onValueChange } : {})}
      {...(name ? { name } : {})}
      {...(disabled !== undefined ? { disabled } : {})}
    >
      <SelectTrigger {...triggerProps}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => (
          <SelectItem key={option.value} value={option.value} disabled={option.disabled ?? false}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
