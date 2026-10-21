import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Command } from 'cmdk'
import { Check, ChevronsUpDown } from 'lucide-react'
import { useId, useState } from 'react'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

export interface ComboboxOption {
  value: string
  label: string
  /** Second line, e.g. a member number or email. Also searched. */
  description?: string
  disabled?: boolean
}

interface ComboboxProps {
  options: readonly ComboboxOption[]
  value?: string | undefined
  onValueChange?: (value: string) => void
  placeholder?: string
  searchPlaceholder?: string
  emptyText?: string
  id?: string
  disabled?: boolean
  className?: string
  onBlur?: () => void
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string
  'aria-label'?: string
}

/**
 * Searchable single select. The trigger is a combobox button; the list is a filtered listbox
 * driven by the arrow keys, with Enter to choose and Escape to close. For server-side search of
 * long lists, filter `options` yourself and pass `shouldFilter={false}` behaviour via an
 * already-filtered array: the built-in filter then only narrows what you give it.
 */
export function Combobox({
  options,
  value,
  onValueChange,
  placeholder = 'Select',
  searchPlaceholder = 'Search',
  emptyText = 'Nothing found.',
  id,
  disabled,
  className,
  onBlur,
  ...aria
}: ComboboxProps) {
  const [open, setOpen] = useState(false)
  const listId = useId()
  const selected = options.find((option) => option.value === value)

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          id={id}
          role="combobox"
          aria-expanded={open}
          aria-controls={open ? listId : undefined}
          aria-haspopup="listbox"
          disabled={disabled}
          onBlur={onBlur}
          className={cn(fieldClasses, 'items-center justify-between gap-2 text-left', className)}
          {...aria}
        >
          <span className={cn('truncate', !selected && 'text-muted')}>
            {selected?.label ?? placeholder}
          </span>
          <ChevronsUpDown aria-hidden="true" className="size-4 shrink-0 text-muted" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          aria-label={aria['aria-label'] ?? placeholder}
          align="start"
          sideOffset={4}
          className="z-50 w-[var(--radix-popover-trigger-width)] min-w-56 rounded border border-brand-100 bg-white shadow-lift"
        >
          <Command label={placeholder}>
            <Command.Input
              placeholder={searchPlaceholder}
              aria-label={searchPlaceholder}
              className="min-h-11 w-full border-b border-brand-100 bg-transparent px-3 text-sm outline-none placeholder:text-muted"
            />
            <Command.List id={listId} className="max-h-60 overflow-y-auto p-1">
              <Command.Empty className="px-3 py-6 text-center text-sm text-muted">
                {emptyText}
              </Command.Empty>
              {options.map((option) => (
                <Command.Item
                  key={option.value}
                  value={`${option.label} ${option.description ?? ''} ${option.value}`}
                  disabled={option.disabled ?? false}
                  onSelect={() => {
                    onValueChange?.(option.value)
                    setOpen(false)
                  }}
                  className="flex min-h-10 cursor-pointer items-center gap-2 rounded px-2 py-2 text-sm data-[selected=true]:bg-brand-50 data-[disabled=true]:opacity-50"
                >
                  <Check
                    aria-hidden="true"
                    className={cn(
                      'size-4 shrink-0 text-brand-600',
                      option.value === value ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  <span className="min-w-0">
                    <span className="block truncate">{option.label}</span>
                    {option.description ? (
                      <span className="block truncate text-xs text-muted">
                        {option.description}
                      </span>
                    ) : null}
                  </span>
                </Command.Item>
              ))}
            </Command.List>
          </Command>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}
