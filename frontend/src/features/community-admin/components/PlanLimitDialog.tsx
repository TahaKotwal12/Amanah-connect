import { Link } from 'react-router'
import { paths } from '@/routes/paths'
import { usePlanLimitStore } from '@/store/planLimitStore'
import { Button } from '@/shared/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'

const LIMIT_WORDS: Record<string, { title: string; unit: string }> = {
  max_members: { title: 'Your plan has no room for more members', unit: 'members' },
  storage_mb: { title: 'Your plan has no room for more files', unit: 'MB of storage' },
  emails_per_month: { title: 'Your plan has no emails left this month', unit: 'emails this month' },
  emails_per_day: { title: 'Your plan has no emails left today', unit: 'emails today' },
}

/**
 * The answer to PLAN_LIMIT_EXCEEDED (HTTP 402) from anywhere in the app: what the limit is, how
 * much is used, and the way forward, instead of a toast that says "error".
 */
export function PlanLimitDialog() {
  const notice = usePlanLimitStore((state) => state.notice)
  const dismiss = usePlanLimitStore((state) => state.dismiss)
  const words = notice?.limitKey ? LIMIT_WORDS[notice.limitKey] : undefined
  return (
    <Dialog open={notice !== null} onOpenChange={(open) => !open && dismiss()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{words?.title ?? 'This is over your plan’s limit'}</DialogTitle>
          <DialogDescription>{notice?.message}</DialogDescription>
        </DialogHeader>
        {notice && notice.limit !== null && notice.current !== null ? (
          <p className="rounded border border-brand-100 bg-brand-50 p-3 text-sm">
            <span className="font-semibold tabular-nums">
              {notice.current.toLocaleString('en-IN')} of {notice.limit.toLocaleString('en-IN')}
            </span>{' '}
            {words?.unit ?? 'used'}
            {notice.plan ? ` on the ${notice.plan} plan` : ''}.
          </p>
        ) : null}
        <p className="text-sm text-muted">
          To go beyond it, ask the platform team to move you to a bigger plan. Nothing you have
          already entered is lost.
        </p>
        <DialogFooter>
          <Button variant="secondary" onClick={dismiss}>
            Not now
          </Button>
          <Button asChild onClick={dismiss}>
            <Link to={paths.admin.support}>Ask about upgrading</Link>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
