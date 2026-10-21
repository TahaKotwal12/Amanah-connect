import { ChevronDown } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { downloadBlob } from '@/lib/download'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/shared/components/ui/dropdown-menu'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import { exportCommunity } from '../api/communities'
import {
  CommunityActionDialogs,
  type CommunityActionTarget,
} from '../components/CommunityActionDialogs'
import {
  ActivityTab,
  ContactTab,
  OverviewTab,
  PlanTab,
  SupportTab,
} from '../components/CommunityTabs'
import { ImportWizard } from '../components/ImportWizard'
import { useCommunity } from '../hooks/useCommunities'

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'contact', label: 'Admin and contact' },
  { id: 'plan', label: 'Plan and subscription' },
  { id: 'activity', label: 'Activity' },
  { id: 'support', label: 'Support' },
] as const
type TabId = (typeof TABS)[number]['id']

function isTab(value: string | null): value is TabId {
  return TABS.some((tab) => tab.id === value)
}

export default function CommunityDetailPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const community = useCommunity(id)
  const [action, setAction] = useState<CommunityActionTarget | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const requested = params.get('tab')
  const tab: TabId = isTab(requested) ? requested : 'overview'

  async function download(format: 'json' | 'csv') {
    try {
      const { blob, filename } = await exportCommunity(id, format)
      downloadBlob(filename, blob)
    } catch (error) {
      notify.apiError(error)
    }
  }

  if (community.isError) {
    return (
      <>
        <PageHeader
          title="Community"
          breadcrumbs={[
            { label: 'Communities', to: paths.superadmin.communities },
            { label: 'Community' },
          ]}
        />
        <ErrorState
          headingLevel={2}
          error={community.error}
          onRetry={() => void community.refetch()}
          retrying={community.isFetching}
        />
      </>
    )
  }

  if (!community.data) {
    return (
      <div role="status" aria-label="Loading community" className="grid gap-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-64" />
      </div>
    )
  }

  const data = community.data
  const target = (kind: CommunityActionTarget['kind']): CommunityActionTarget => ({
    kind,
    id: data.id,
    name: data.name,
    status: data.status,
  })

  return (
    <>
      <PageHeader
        title={data.name}
        description={`${data.plan.name} plan, ${data.memberCount.toLocaleString('en-IN')} members`}
        breadcrumbs={[
          { label: 'Communities', to: paths.superadmin.communities },
          { label: data.name },
        ]}
        actions={
          <>
            <StatusBadge status={data.status} />
            <Button asChild variant="secondary">
              <Link to={paths.superadmin.communitySupportView(data.id)}>Support view</Link>
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="secondary">
                  Actions
                  <ChevronDown aria-hidden="true" className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {data.status === 'ACTIVE' ? (
                  <DropdownMenuItem destructive onSelect={() => setAction(target('suspend'))}>
                    Suspend
                  </DropdownMenuItem>
                ) : (
                  <DropdownMenuItem onSelect={() => setAction(target('activate'))}>
                    {data.status === 'ARCHIVED' ? 'Restore' : 'Activate'}
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem onSelect={() => setAction(target('reset'))}>
                  Reset admin password
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => setImportOpen(true)}>
                  Import members and balances
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => void download('csv')}>
                  Export details (CSV)
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => void download('json')}>
                  Export details (JSON)
                </DropdownMenuItem>
                {data.status !== 'ARCHIVED' ? (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem destructive onSelect={() => setAction(target('archive'))}>
                      Archive
                    </DropdownMenuItem>
                  </>
                ) : null}
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        }
      />

      <Tabs
        value={tab}
        onValueChange={(next) =>
          setParams(
            (previous) => {
              const updated = new URLSearchParams(previous)
              if (next === 'overview') updated.delete('tab')
              else updated.set('tab', next)
              return updated
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Community sections">
          {TABS.map(({ id: value, label }) => (
            <TabsTrigger key={value} value={value}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="overview">
          <OverviewTab community={data} />
        </TabsContent>
        <TabsContent value="contact">
          <ContactTab community={data} onResetPassword={() => setAction(target('reset'))} />
        </TabsContent>
        <TabsContent value="plan">
          <PlanTab community={data} />
        </TabsContent>
        <TabsContent value="activity">
          <ActivityTab community={data} />
        </TabsContent>
        <TabsContent value="support">
          <SupportTab community={data} />
        </TabsContent>
      </Tabs>

      <CommunityActionDialogs target={action} onClose={() => setAction(null)} />
      <ImportWizard
        open={importOpen}
        onOpenChange={setImportOpen}
        communityId={data.id}
        communityName={data.name}
      />
    </>
  )
}
