import { PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import { NavLink } from 'react-router'
import { cn } from '@/shared/utils/cn'
import { BrandMark } from '../BrandMark'
import { Tooltip } from '../ui/tooltip'
import type { NavItem } from './nav'

interface SidebarProps {
  subtitle: string
  nav: readonly NavItem[]
  collapsed: boolean
  onToggle: () => void
  /** Name of the navigation landmark. */
  navLabel?: string
}

/** Deep-teal navigation for wide screens. Collapses to icons; names stay available to screen readers and tooltips. */
export function Sidebar({ subtitle, nav, collapsed, onToggle, navLabel = 'Main' }: SidebarProps) {
  return (
    <aside
      className={cn(
        'sticky top-0 hidden h-screen shrink-0 flex-col bg-brand-900 text-white md:flex',
        collapsed ? 'w-[4.5rem]' : 'w-64',
      )}
    >
      <div className={cn('flex items-center gap-3 px-4 py-5', collapsed && 'justify-center px-2')}>
        {/* The logo files are for light backgrounds, so the mark sits on an ivory tile. */}
        <span className="flex size-10 shrink-0 items-center justify-center rounded bg-ivory">
          <BrandMark width={32} />
        </span>
        {collapsed ? null : (
          <div className="min-w-0">
            <p className="truncate font-display text-base font-semibold tracking-[0.12em]">
              AMANAH CONNECT
            </p>
            <p className="truncate text-xs text-brand-100">{subtitle}</p>
          </div>
        )}
      </div>

      <nav aria-label={navLabel} className="flex-1 overflow-y-auto px-3 py-2">
        <ul className="grid gap-1">
          {nav.map((item) => {
            const link = (
              <NavLink
                to={item.to}
                className={({ isActive }) =>
                  cn(
                    'relative flex min-h-11 items-center gap-3 rounded px-3 text-sm font-medium text-brand-50 hover:bg-brand-800',
                    collapsed && 'justify-center px-0',
                    // Gold bar on the active item, so the current page is not told by colour alone.
                    isActive &&
                      'bg-brand-800 font-semibold text-white before:absolute before:inset-y-2 before:left-0 before:w-1 before:rounded-r before:bg-gold-500',
                  )
                }
              >
                <item.icon aria-hidden="true" className="size-5 shrink-0" />
                <span className={cn(collapsed && 'sr-only')}>{item.label}</span>
              </NavLink>
            )
            return (
              <li key={item.to}>
                {collapsed ? (
                  <Tooltip content={item.label} side="right">
                    {link}
                  </Tooltip>
                ) : (
                  link
                )}
              </li>
            )
          })}
        </ul>
      </nav>

      <div className="border-t border-brand-800 p-3">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className={cn(
            'flex min-h-11 w-full items-center gap-3 rounded px-3 text-sm text-brand-100 hover:bg-brand-800 hover:text-white',
            collapsed && 'justify-center px-0',
          )}
        >
          {collapsed ? (
            <PanelLeftOpen aria-hidden="true" className="size-5" />
          ) : (
            <>
              <PanelLeftClose aria-hidden="true" className="size-5" />
              Collapse
            </>
          )}
        </button>
      </div>
    </aside>
  )
}
