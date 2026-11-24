import { useSearchParams } from 'react-router'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import { DataTab } from '../components/settings/DataTab'
import { NotificationsTab } from '../components/settings/NotificationsTab'
import { PlanTab } from '../components/settings/PlanTab'
import { ProfileTab } from '../components/settings/ProfileTab'
import { SecurityTab } from '../components/settings/SecurityTab'
import { useWriteAccess } from '../hooks/usePlan'

// Phase 1 is cash only, so there is no Payments tab (UPI ID, payee name and the test QR). The settings
// endpoint still accepts them; add { id: 'payments', label: 'Payments' } and its tab when UPI returns.
const TABS = [
  { id: 'profile', label: 'Community profile' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'security', label: 'Security' },
  { id: 'plan', label: 'Plan and usage' },
  { id: 'data', label: 'Data' },
] as const
type TabId = (typeof TABS)[number]['id']
const isTab = (value: string | null): value is TabId => TABS.some((tab) => tab.id === value)

export default function SettingsPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const requested = params.get('tab')
  const tab: TabId = isTab(requested) ? requested : 'profile'

  return (
    <>
      <PageHeader
        title="Settings"
        description="Your community's profile, reminders, security, plan and data."
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}
      <Tabs
        value={tab}
        onValueChange={(next) =>
          setParams(
            () => {
              const updated = new URLSearchParams()
              if (next !== 'profile') updated.set('tab', next)
              return updated
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Settings sections">
          {TABS.map(({ id, label }) => (
            <TabsTrigger key={id} value={id}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="profile">
          <ProfileTab />
        </TabsContent>
        <TabsContent value="notifications">
          <NotificationsTab />
        </TabsContent>
        <TabsContent value="security">
          <SecurityTab />
        </TabsContent>
        <TabsContent value="plan">
          <PlanTab />
        </TabsContent>
        <TabsContent value="data">
          <DataTab />
        </TabsContent>
      </Tabs>
    </>
  )
}
