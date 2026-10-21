import { cva, type VariantProps } from 'class-variance-authority'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

export const badgeVariants = cva(
  'inline-flex items-center gap-1.5 rounded border px-2 py-0.5 text-xs font-semibold',
  {
    variants: {
      variant: {
        neutral: 'border-brand-100 bg-brand-50 text-brand-900',
        brand: 'border-brand-200 bg-brand-100 text-brand-900',
        gold: 'border-gold-200 bg-gold-50 text-gold-600',
        success: 'border-success/30 bg-success-50 text-success-700',
        warning: 'border-warning/30 bg-warning-50 text-warning',
        danger: 'border-danger/30 bg-danger-50 text-danger',
        premium: 'border-premium/30 bg-premium-50 text-premium',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
)

export interface BadgeProps extends ComponentProps<'span'>, VariantProps<typeof badgeVariants> {}

/** A small label. Meaning must never rest on colour alone: always include text (StatusBadge adds an icon). */
export function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />
}
