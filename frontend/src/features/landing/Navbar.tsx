import { Menu } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { paths } from '@/routes/paths'
import { BrandMark } from '@/shared/components/BrandMark'
import { Button } from '@/shared/components/ui/button'
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/shared/components/ui/sheet'
import { DEMO_ID, SECTION_LINKS } from './nav'

const linkClasses =
  'inline-flex min-h-11 items-center rounded px-3 text-sm font-medium text-brand-900 hover:bg-brand-50'

/** Sticky top bar. Section links are real links to /#section so they work from the legal pages too. */
export function Navbar() {
  const [open, setOpen] = useState(false)
  return (
    <header className="sticky top-0 z-40 border-b border-brand-100 bg-white/95">
      <div className="container flex min-h-16 items-center justify-between gap-4">
        <Link
          to={paths.home}
          className="flex items-center gap-3 rounded"
          aria-label="Amanah Connect home"
        >
          <BrandMark />
          <span
            aria-hidden="true"
            className="font-display text-base font-semibold tracking-[0.12em] text-brand-900 sm:text-lg"
          >
            AMANAH CONNECT
          </span>
        </Link>

        <nav aria-label="Sections" className="hidden items-center gap-1 lg:flex">
          {SECTION_LINKS.map((link) => (
            <Link key={link.id} to={`/#${link.id}`} className={linkClasses}>
              {link.label}
            </Link>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          <Button asChild variant="secondary" className="hidden sm:inline-flex">
            <Link to={paths.login}>Login</Link>
          </Button>
          <Button asChild className="hidden sm:inline-flex">
            <Link to={`/#${DEMO_ID}`}>Request a demo</Link>
          </Button>

          <Sheet open={open} onOpenChange={setOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open menu">
                <Menu aria-hidden="true" className="size-6" />
              </Button>
            </SheetTrigger>
            <SheetContent side="right">
              <SheetHeader>
                <SheetTitle>Menu</SheetTitle>
                <SheetDescription className="sr-only">
                  Sections of this page and sign in
                </SheetDescription>
              </SheetHeader>
              <nav aria-label="Mobile sections">
                <ul className="grid gap-1">
                  {SECTION_LINKS.map((link) => (
                    <li key={link.id}>
                      <SheetClose asChild>
                        <Link
                          to={`/#${link.id}`}
                          className="flex min-h-12 items-center rounded px-3 text-base font-medium text-brand-900 hover:bg-brand-50"
                        >
                          {link.label}
                        </Link>
                      </SheetClose>
                    </li>
                  ))}
                </ul>
              </nav>
              <div className="mt-2 grid gap-3">
                <SheetClose asChild>
                  <Button asChild variant="secondary">
                    <Link to={paths.login}>Login</Link>
                  </Button>
                </SheetClose>
                <SheetClose asChild>
                  <Button asChild>
                    <Link to={`/#${DEMO_ID}`}>Request a demo</Link>
                  </Button>
                </SheetClose>
              </div>
            </SheetContent>
          </Sheet>
        </div>
      </div>
    </header>
  )
}
