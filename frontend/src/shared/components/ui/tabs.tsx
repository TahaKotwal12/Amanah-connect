import * as TabsPrimitive from '@radix-ui/react-tabs'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

export const Tabs = TabsPrimitive.Root

export function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      className={cn('flex gap-1 overflow-x-auto border-b border-brand-100', className)}
      {...props}
    />
  )
}

/** Arrow keys move between tabs; the active one carries a gold underline as well as bolder text. */
export function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        '-mb-px inline-flex min-h-11 items-center whitespace-nowrap border-b-2 border-transparent px-4 text-sm font-medium text-muted hover:text-brand-900 disabled:opacity-50 data-[state=active]:border-gold-500 data-[state=active]:font-semibold data-[state=active]:text-brand-900',
        className,
      )}
      {...props}
    />
  )
}

export function TabsContent({ className, ...props }: ComponentProps<typeof TabsPrimitive.Content>) {
  return <TabsPrimitive.Content className={cn('pt-5 outline-none', className)} {...props} />
}
