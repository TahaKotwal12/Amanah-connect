import * as MenuPrimitive from '@radix-ui/react-dropdown-menu'
import { Check } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/shared/utils/cn'

export const DropdownMenu = MenuPrimitive.Root
export const DropdownMenuTrigger = MenuPrimitive.Trigger
export const DropdownMenuGroup = MenuPrimitive.Group

export function DropdownMenuContent({
  className,
  sideOffset = 6,
  align = 'end',
  ...props
}: ComponentProps<typeof MenuPrimitive.Content>) {
  return (
    <MenuPrimitive.Portal>
      <MenuPrimitive.Content
        sideOffset={sideOffset}
        align={align}
        className={cn(
          'z-50 min-w-48 overflow-hidden rounded border border-brand-100 bg-white p-1 text-ink shadow-lift',
          className,
        )}
        {...props}
      />
    </MenuPrimitive.Portal>
  )
}

const itemClasses =
  'relative flex min-h-10 cursor-pointer select-none items-center gap-2 rounded px-3 py-2 text-sm outline-none data-[disabled]:pointer-events-none data-[highlighted]:bg-brand-50 data-[disabled]:opacity-50'

export function DropdownMenuItem({
  className,
  destructive,
  ...props
}: ComponentProps<typeof MenuPrimitive.Item> & { destructive?: boolean }) {
  return (
    <MenuPrimitive.Item
      className={cn(
        itemClasses,
        destructive && 'text-danger data-[highlighted]:bg-danger-50',
        className,
      )}
      {...props}
    />
  )
}

export function DropdownMenuCheckboxItem({
  className,
  children,
  ...props
}: ComponentProps<typeof MenuPrimitive.CheckboxItem>) {
  return (
    <MenuPrimitive.CheckboxItem className={cn(itemClasses, 'pl-8', className)} {...props}>
      <span className="absolute left-2 flex size-4 items-center justify-center">
        <MenuPrimitive.ItemIndicator>
          <Check aria-hidden="true" className="size-4 text-brand-600" />
        </MenuPrimitive.ItemIndicator>
      </span>
      {children}
    </MenuPrimitive.CheckboxItem>
  )
}

export function DropdownMenuLabel({
  className,
  ...props
}: ComponentProps<typeof MenuPrimitive.Label>) {
  return (
    <MenuPrimitive.Label
      className={cn('px-3 py-1.5 text-xs font-semibold text-muted', className)}
      {...props}
    />
  )
}

export function DropdownMenuSeparator({
  className,
  ...props
}: ComponentProps<typeof MenuPrimitive.Separator>) {
  return (
    <MenuPrimitive.Separator className={cn('-mx-1 my-1 h-px bg-brand-100', className)} {...props} />
  )
}
