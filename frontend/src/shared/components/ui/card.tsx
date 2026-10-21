import type { ComponentProps, ElementType } from 'react'
import { cn } from '@/shared/utils/cn'

export function Card({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn(
        'rounded-card border border-brand-100 bg-white text-ink shadow-soft',
        className,
      )}
      {...props}
    />
  )
}

export function CardHeader({ className, ...props }: ComponentProps<'div'>) {
  return <div className={cn('flex flex-col gap-1 p-5 pb-3', className)} {...props} />
}

interface CardTitleProps extends ComponentProps<'h2'> {
  /** Heading level to render: pick the one that fits the page outline. */
  as?: ElementType
}

export function CardTitle({ className, as: Tag = 'h2', ...props }: CardTitleProps) {
  return (
    <Tag
      className={cn('font-heading text-lg font-semibold lining-nums text-brand-900', className)}
      {...props}
    />
  )
}

export function CardDescription({ className, ...props }: ComponentProps<'p'>) {
  return <p className={cn('text-sm text-muted', className)} {...props} />
}

export function CardContent({ className, ...props }: ComponentProps<'div'>) {
  return <div className={cn('p-5 pt-2', className)} {...props} />
}

export function CardFooter({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn('flex items-center gap-3 border-t border-brand-100 p-5 pt-4', className)}
      {...props}
    />
  )
}
