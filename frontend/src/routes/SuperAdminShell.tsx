import {
  Building2,
  CreditCard,
  Gauge,
  LifeBuoy,
  Megaphone,
  ScrollText,
  Target,
  UserPlus,
  Wallet,
} from 'lucide-react'
import { AppShell, type NavItem } from '@/shared/components/layout/AppShell'
import { paths } from './paths'

const nav: readonly NavItem[] = [
  { to: paths.superadmin.dashboard, label: 'Dashboard', icon: Gauge },
  { to: paths.superadmin.communities, label: 'Communities', icon: Building2 },
  { to: paths.superadmin.plans, label: 'Plans', icon: Wallet },
  { to: paths.superadmin.subscriptions, label: 'Subscriptions', icon: CreditCard },
  { to: paths.superadmin.invites, label: 'Invites', icon: UserPlus },
  { to: paths.superadmin.leads, label: 'Leads', icon: Target },
  { to: paths.superadmin.support, label: 'Support', icon: LifeBuoy },
  { to: paths.superadmin.announcements, label: 'Announcements', icon: Megaphone },
  { to: paths.superadmin.audit, label: 'Audit', icon: ScrollText },
]

/** Loaded on demand, so the sign-in-free marketing site never downloads the app shell. */
export default function SuperAdminShell() {
  return <AppShell subtitle="Platform" nav={nav} supportPath={paths.superadmin.support} />
}
