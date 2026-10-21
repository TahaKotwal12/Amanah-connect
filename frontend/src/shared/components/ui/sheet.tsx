import * as DialogPrimitive from '@radix-ui/react-dialog'
import { cva, type VariantProps } from 'class-variance-authority'
import { X } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'
import { DialogDescription, DialogHeader, DialogOverlay, DialogTitle } from './dialog'

export const Sheet = DialogPrimitive.Root
export const SheetTrigger = DialogPrimitive.Trigger
export const SheetClose = DialogPrimitive.Close
export const SheetTitle = DialogTitle
export const SheetDescription = DialogDescription
export const SheetHeader = DialogHeader

const sheetVariants = cva(
  'fixed z-50 flex flex-col gap-4 bg-white p-6 shadow-lift overflow-y-auto',
  {
    variants: {
      side: {
        right: 'inset-y-0 right-0 w-full max-w-md border-l border-brand-100',
        left: 'inset-y-0 left-0 w-full max-w-sm border-r border-brand-100',
        bottom: 'inset-x-0 bottom-0 max-h-[85vh] rounded-t-card border-t border-brand-100',
      },
    },
    defaultVariants: { side: 'right' },
  },
)

interface SheetContentProps
  extends ComponentProps<typeof DialogPrimitive.Content>, VariantProps<typeof sheetVariants> {}

/** A panel that slides in from an edge: forms beside a list (right) or navigation on mobile (bottom). */
export function SheetContent({ className, side, children, ...props }: SheetContentProps) {
  return (
    <DialogPrimitive.Portal>
      <DialogOverlay />
      <DialogPrimitive.Content className={cn(sheetVariants({ side }), className)} {...props}>
        {children}
        <DialogPrimitive.Close
          aria-label="Close"
          className="absolute right-3 top-3 flex size-10 items-center justify-center rounded text-muted hover:bg-brand-50 hover:text-ink"
        >
          <X aria-hidden="true" className="size-5" />
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
}
