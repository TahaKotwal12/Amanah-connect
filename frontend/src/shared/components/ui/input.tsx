import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

/** Shared look of every text-like control, so inputs, selects and pickers line up. */
export const fieldClasses =
  'flex min-h-11 w-full rounded border border-muted bg-white px-3 py-2 text-base text-ink placeholder:text-muted disabled:cursor-not-allowed disabled:bg-brand-50 disabled:opacity-70 aria-[invalid=true]:border-danger aria-[invalid=true]:ring-1 aria-[invalid=true]:ring-danger sm:text-sm'

export function Input({ className, type = 'text', ...props }: ComponentProps<'input'>) {
  return <input type={type} className={cn(fieldClasses, className)} {...props} />
}

export function Textarea({ className, rows = 4, ...props }: ComponentProps<'textarea'>) {
  return <textarea rows={rows} className={cn(fieldClasses, 'resize-y', className)} {...props} />
}
