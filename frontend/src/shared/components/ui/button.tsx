import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

export const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded font-semibold transition-colors disabled:pointer-events-none disabled:opacity-60 aria-disabled:pointer-events-none aria-disabled:opacity-60',
  {
    variants: {
      variant: {
        primary: 'bg-brand-600 text-white hover:bg-brand-700',
        secondary: 'border border-brand-600 bg-transparent text-brand-700 hover:bg-brand-50',
        ghost: 'bg-transparent text-brand-700 hover:bg-brand-50',
        danger: 'bg-danger text-white hover:bg-[#912018]',
        // Gold is the premium accent: dark text on gold keeps 7:1 contrast.
        premium: 'bg-gold-500 text-brand-950 hover:bg-[#B8921F]',
      },
      size: {
        sm: 'min-h-9 px-3 text-sm',
        md: 'min-h-11 px-5 text-sm',
        lg: 'min-h-12 px-7 text-base',
        // Icon-only buttons need an aria-label; 44px keeps the touch target.
        icon: 'size-11 p-0',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export interface ButtonProps extends ComponentProps<'button'>, VariantProps<typeof buttonVariants> {
  /** Render the child element (for example a router Link) with button styles. */
  asChild?: boolean
  /** Shows a spinner and blocks clicks. The label stays so the width does not jump. */
  loading?: boolean
}

export function Button({
  className,
  variant,
  size,
  asChild = false,
  loading = false,
  disabled,
  type = 'button',
  children,
  ...props
}: ButtonProps) {
  const classes = cn(buttonVariants({ variant, size }), className)

  if (asChild) {
    return (
      <Slot className={classes} {...props}>
        {children}
      </Slot>
    )
  }

  return (
    <button
      type={type}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? <Loader2 aria-hidden="true" className="size-4 animate-spin" /> : null}
      {children}
      {loading ? <span className="sr-only">Loading</span> : null}
    </button>
  )
}
