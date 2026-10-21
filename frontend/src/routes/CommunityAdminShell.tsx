import {
  Banknote,
  CreditCard,
  Gauge,
  LifeBuoy,
  Megaphone,
  MessageSquareWarning,
  Settings,
  UserPlus,
  Users,
} from 'lucide-react'
import { CommunityBanners } from '@/features/community-admin/components/CommunityBanners'
import { NotificationList } from '@/features/community-admin/components/NotificationList'
import { useBellCount } from '@/features/community-admin/hooks/useBell'
import { PlanLimitDialog } from '@/features/community-admin/components/PlanLimitDialog'
import { useSettings } from '@/features/community-admin/hooks/useSettings'
import { AppShell, type NavItem } from '@/shared/components/layout/AppShell'
import { paths } from './paths'

const nav: readonly NavItem[] = [
  { to: paths.admin.dashboard, label: 'Dashboard', icon: Gauge },
  { to: paths.admin.members, label: 'Members', icon: Users },
  { to: paths.admin.invites, label: 'Invites', icon: UserPlus },
  { to: paths.admin.billing, label: 'Billing', icon: CreditCard },
  { to: paths.admin.budget, label: 'Budget', icon: Banknote },
  { to: paths.admin.complaints, label: 'Complaints', icon: MessageSquareWarning },
  { to: paths.admin.announcements, label: 'Announcements', icon: Megaphone },
  { to: paths.admin.support, label: 'Support', icon: LifeBuoy },
  { to: paths.admin.settings, label: 'Settings', icon: Settings },
]

export default function CommunityAdminShell() {
  const settings = useSettings()
  const bellCount = useBellCount()
  return (
    <AppShell
      subtitle="Community"
      nav={nav}
      supportPath={paths.admin.support}
      settingsPath={paths.admin.settings}
      contextLogoUrl={settings.data?.logoUrl ?? null}
      notificationCount={bellCount}
      notifications={<NotificationList />}
      banner={<CommunityBanners />}
      overlays={<PlanLimitDialog />}
    />
  )
}
