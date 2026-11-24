import * as AvatarPrimitive from '@radix-ui/react-avatar'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/shared/utils/cn'

const avatarVariants = cva(
  'relative inline-flex shrink-0 select-none items-center justify-center overflow-hidden rounded-full bg-brand-100 font-semibold text-brand-900',
  {
    variants: { size: { sm: 'size-8 text-xs', md: 'size-10 text-sm', lg: 'size-14 text-lg' } },
    defaultVariants: { size: 'md' },
  },
)

export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const first = words[0]?.charAt(0) ?? ''
  const last = words.length > 1 ? (words[words.length - 1]?.charAt(0) ?? '') : ''
  return (first + last).toUpperCase()
}

interface AvatarProps extends VariantProps<typeof avatarVariants> {
  /** Full name: used for the initials fallback and the accessible name. */
  name: string
  src?: string | null
  className?: string
  /** The name is written out next to the avatar: hide it from assistive technology. */
  decorative?: boolean
}

export function Avatar({ name, src, size, className, decorative = false }: AvatarProps) {
  return (
    <AvatarPrimitive.Root
      className={cn(
        avatarVariants({ size }),
        // Decorative: the initials are drawn by CSS, so they are not text in the page. A control
        // whose accessible name is its own label would otherwise fail "label in name" (WCAG 2.5.3)
        // because the visible letters are not part of that name.
        decorative && 'after:content-[attr(data-initials)]',
        className,
      )}
      {...(decorative
        ? { 'aria-hidden': true, 'data-initials': initialsOf(name) }
        : { role: 'img', 'aria-label': name.trim() || 'Unknown user' })}
    >
      {src ? <AvatarPrimitive.Image src={src} alt="" className="size-full object-cover" /> : null}
      {decorative ? null : (
        <AvatarPrimitive.Fallback aria-hidden="true" delayMs={src ? 300 : 0}>
          {initialsOf(name)}
        </AvatarPrimitive.Fallback>
      )}
    </AvatarPrimitive.Root>
  )
}
