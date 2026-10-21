import { Banknote, Gauge, LifeBuoy, Users } from 'lucide-react'
import { useState } from 'react'
import { Sidebar } from '@/shared/components/layout/Sidebar'
import type { NavItem } from '@/shared/components/layout/nav'
import { Demo, Section } from '../Section'

const NAV: NavItem[] = [
  { to: '/styleguide', label: 'Dashboard', icon: Gauge },
  { to: '/admin/members', label: 'Members', icon: Users },
  { to: '/admin/budget', label: 'Budget', icon: Banknote },
  { to: '/admin/support', label: 'Support', icon: LifeBuoy },
]

/** The sidebar in a fixed-height frame. The links are inert here (clicks are blocked); this page's own address makes the first item the active one. */
function SidebarPreview({ initiallyCollapsed }: { initiallyCollapsed: boolean }) {
  const [collapsed, setCollapsed] = useState(initiallyCollapsed)
  return (
    <div
      onClickCapture={(event) => {
        if ((event.target as HTMLElement).closest('a')) event.preventDefault()
      }}
      className="h-96 overflow-hidden rounded-card border border-brand-100 [&>aside]:!sticky [&>aside]:!flex [&>aside]:!h-full"
    >
      <Sidebar
        navLabel={initiallyCollapsed ? 'Sample sidebar, collapsed' : 'Sample sidebar, expanded'}
        subtitle="Community"
        nav={NAV}
        collapsed={collapsed}
        onToggle={() => {
          setCollapsed(!collapsed)
        }}
      />
    </div>
  )
}

export function LayoutSection() {
  return (
    <Section id="layout" title="Layout shells">
      <p className="max-w-prose text-sm text-muted">
        AppShell (sidebar, top bar with help, notifications and user menu, and the bottom tab bar on
        phones) wraps every signed-in page: sign in and open /admin or /superadmin to see it whole.
        PublicLayout wraps the marketing and sign-in pages. The sidebar is shown here on its own.
      </p>
      <Demo title="Sidebar: expanded and collapsed (the active item has a gold bar)">
        <div className="grid w-full gap-4 md:grid-cols-2">
          <SidebarPreview initiallyCollapsed={false} />
          <SidebarPreview initiallyCollapsed />
        </div>
      </Demo>
    </Section>
  )
}
