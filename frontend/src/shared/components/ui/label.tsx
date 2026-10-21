import * as LabelPrimitive from '@radix-ui/react-label'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

export function Label({ className, ...props }: ComponentProps<typeof LabelPrimitive.Root>) {
  return (
    <LabelPrimitive.Root
      className={cn('text-sm font-medium text-ink peer-disabled:opacity-70', className)}
      {...props}
    />
  )
}
