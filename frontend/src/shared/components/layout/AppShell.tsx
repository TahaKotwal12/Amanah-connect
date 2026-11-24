import { useEffect, type ReactNode } from 'react'
import { Outlet, useNavigate } from 'react-router'
import { logout } from '@/lib/session'
import { paths } from '@/routes/paths'
import { prefetchWhenIdle } from '@/routes/prefetch'
import { useMediaQuery } from '@/shared/hooks/useMediaQuery'
import { useSidebarCollapsed } from '@/shared/hooks/useSidebarCollapsed'
import { useAuthStore } from '@/store/authStore'
import type { NavItem } from './nav'
import { MobileNav } from './MobileNav'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

export type { NavItem } from './nav'

interface AppShellProps {
  /** Shown under the wordmark: "Platform" or "Community". */
  subtitle: string
  nav: readonly NavItem[]
  /** Where the help dialog sends people. */
  supportPath: string
  settingsPath?: string
  notificationCount?: number
  notifications?: ReactNode
  /** The community's logo for the header. */
  contextLogoUrl?: string | null
  /** Banners (plan expiring, suspended) shown between the header and the page. */
  banner?: ReactNode
  /** Dialogs and other overlays that belong to the whole shell. */
  overlays?: ReactNode
}

/**
 * Signed-in layout: collapsible deep-teal sidebar and top bar on wide screens, bottom tab bar on
 * phones. Renders the active route in <main>.
 */
export function AppShell({
  subtitle,
  nav,
  supportPath,
  settingsPath,
  notificationCount,
  notifications,
  contextLogoUrl,
  banner,
  overlays,
}: AppShellProps) {
  const navigate = useNavigate()
  const communityName = useAuthStore((state) => state.community?.name)
  const [collapsed, toggleCollapsed] = useSidebarCollapsed()
  // One navigation at a time: rendering both would give screen readers two 'Main' landmarks.
  const isDesktop = useMediaQuery('(min-width: 768px)')

  // Once the first page is up and the browser is idle, fetch the code of the next few pages people
  // usually open, so those clicks feel instant. (Hover and focus on a link prefetch that page too.)
  useEffect(() => prefetchWhenIdle(nav.slice(1, 4).map((item) => item.to)), [nav])

  async function signOut() {
    await logout()
    await navigate(paths.login, { replace: true })
  }

  return (
    <div className="flex min-h-screen">
      {isDesktop ? (
        <Sidebar subtitle={subtitle} nav={nav} collapsed={collapsed} onToggle={toggleCollapsed} />
      ) : null}
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar
          contextName={communityName ?? subtitle}
          {...(contextLogoUrl ? { contextLogoUrl } : {})}
          supportPath={supportPath}
          onSignOut={() => void signOut()}
          {...(settingsPath ? { settingsPath } : {})}
          {...(notificationCount !== undefined ? { notificationCount } : {})}
          {...(notifications ? { notifications } : {})}
        />
        {banner}
        <main
          id="main"
          tabIndex={-1}
          className="flex-1 px-4 py-6 pb-24 outline-none md:px-8 md:pb-8"
        >
          <Outlet />
        </main>
      </div>
      {isDesktop ? null : <MobileNav nav={nav} />}
      {overlays}
    </div>
  )
}
