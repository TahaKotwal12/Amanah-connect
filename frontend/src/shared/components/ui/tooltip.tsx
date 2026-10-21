import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import type { ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'

export const TooltipProvider = TooltipPrimitive.Provider

interface TooltipProps {
  /** The tip. Keep it short; it is not available on touch, so never hide essential info here. */
  content: ReactNode
  /** One focusable element: the tip shows on hover and on keyboard focus. */
  children: ReactNode
  side?: 'top' | 'right' | 'bottom' | 'left'
  className?: string
}

export function Tooltip({ content, children, side = 'top', className }: TooltipProps) {
  return (
    <TooltipPrimitive.Provider delayDuration={200}>
      <TooltipPrimitive.Root>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <TooltipPrimitive.Content
            side={side}
            sideOffset={6}
            className={cn(
              'z-50 max-w-xs rounded bg-brand-950 px-3 py-1.5 text-xs font-medium text-white shadow-lift',
              className,
            )}
          >
            {content}
            <TooltipPrimitive.Arrow className="fill-brand-950" />
          </TooltipPrimitive.Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  )
}
