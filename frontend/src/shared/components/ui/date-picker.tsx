import * as PopoverPrimitive from '@radix-ui/react-popover'
import { format, isValid, parseISO } from 'date-fns'
import { CalendarDays } from 'lucide-react'
import { useState } from 'react'
import { DayPicker } from 'react-day-picker'
import 'react-day-picker/style.css'
import type { IsoDate } from '@/types'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

interface DatePickerProps {
  /** A calendar date as yyyy-MM-dd (never a Date: dates here have no time zone). */
  value?: IsoDate | undefined
  onValueChange?: (value: IsoDate | undefined) => void
  placeholder?: string
  /** Earliest and latest selectable day, yyyy-MM-dd. */
  min?: IsoDate
  max?: IsoDate
  id?: string
  disabled?: boolean
  className?: string
  onBlur?: () => void
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string
  'aria-label'?: string
}

function parse(value: IsoDate | undefined): Date | undefined {
  if (!value) return undefined
  const date = parseISO(value)
  return isValid(date) ? date : undefined
}

export function DatePicker({
  value,
  onValueChange,
  placeholder = 'Pick a date',
  min,
  max,
  id,
  disabled,
  className,
  onBlur,
  ...aria
}: DatePickerProps) {
  const [open, setOpen] = useState(false)
  const selected = parse(value)
  const from = parse(min)
  const to = parse(max)
  // With nothing chosen, open on this month, or the nearest month that has an allowed day.
  const today = new Date()
  const startMonth = selected ?? (from && today < from ? from : to && today > to ? to : today)

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          id={id}
          disabled={disabled}
          onBlur={onBlur}
          aria-haspopup="dialog"
          className={cn(fieldClasses, 'items-center justify-between gap-2 text-left', className)}
          {...aria}
        >
          <span className={cn(!selected && 'text-muted')}>
            {selected ? format(selected, 'dd MMM yyyy') : placeholder}
          </span>
          <CalendarDays aria-hidden="true" className="size-4 shrink-0 text-muted" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          aria-label="Choose a date"
          className="amanah-calendar z-50 rounded border border-brand-100 bg-white p-3 shadow-lift"
        >
          <DayPicker
            mode="single"
            {...(selected ? { selected } : {})}
            defaultMonth={startMonth}
            onSelect={(day) => {
              onValueChange?.(day ? format(day, 'yyyy-MM-dd') : undefined)
              if (day) setOpen(false)
            }}
            disabled={[...(from ? [{ before: from }] : []), ...(to ? [{ after: to }] : [])]}
            showOutsideDays
          />
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}
