import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

/** A placeholder block while data loads. Decorative: wrap a group in role="status" with a label. */
export function Skeleton({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      aria-hidden="true"
      className={cn('animate-pulse rounded bg-brand-100', className)}
      {...props}
    />
  )
}
