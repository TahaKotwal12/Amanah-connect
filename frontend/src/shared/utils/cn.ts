import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Join class names and resolve Tailwind conflicts, so a caller's `className` wins over a
 * component's default: cn('px-4', isOpen && 'bg-brand-50', className).
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
