import { Ellipsis } from 'lucide-react'
import { useState } from 'react'
import { NavLink } from 'react-router'
import { cn } from '@/shared/utils/cn'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '../ui/sheet'
import type { NavItem } from './nav'

const MAX_IN_BAR = 4

/** Bottom tab bar for phones. Four main items plus "More", which opens the full list in a bottom sheet. */
export function MobileNav({ nav }: { nav: readonly NavItem[] }) {
  const [open, setOpen] = useState(false)
  const needsMore = nav.length > MAX_IN_BAR + 1
  const inBar = needsMore ? nav.slice(0, MAX_IN_BAR) : nav

  const itemClasses = ({ isActive }: { isActive: boolean }) =>
    cn(
      'flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 border-t-2 text-xs font-medium',
      isActive ? 'border-gold-500 text-brand-900' : 'border-transparent text-muted',
    )

  return (
    <nav
      aria-label="Main"
      className="fixed inset-x-0 bottom-0 z-40 flex border-t border-brand-100 bg-white md:hidden"
    >
      {inBar.map((item) => (
        <NavLink key={item.to} to={item.to} className={itemClasses}>
          <item.icon aria-hidden="true" className="size-5" />
          {item.label}
        </NavLink>
      ))}
      {needsMore ? (
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger asChild>
            <button
              type="button"
              className="flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 border-t-2 border-transparent text-xs font-medium text-muted"
            >
              <Ellipsis aria-hidden="true" className="size-5" />
              More
            </button>
          </SheetTrigger>
          <SheetContent side="bottom">
            <SheetHeader>
              <SheetTitle>Menu</SheetTitle>
              <SheetDescription className="sr-only">All sections</SheetDescription>
            </SheetHeader>
            <ul className="grid gap-1">
              {nav.map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    onClick={() => {
                      setOpen(false)
                    }}
                    className={({ isActive }) =>
                      cn(
                        'flex min-h-12 items-center gap-3 rounded px-3 text-base font-medium',
                        isActive
                          ? 'bg-brand-50 text-brand-900 ring-1 ring-inset ring-gold-500'
                          : 'text-ink hover:bg-brand-50',
                      )
                    }
                  >
                    <item.icon aria-hidden="true" className="size-5 text-brand-600" />
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </SheetContent>
        </Sheet>
      ) : null}
    </nav>
  )
}
