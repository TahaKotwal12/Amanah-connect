import { useState } from 'react'
import { Link } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Badge } from '@/shared/components/ui/badge'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Textarea } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { ComplaintView, ComplaintVisibility, Uuid } from '@/types'
import { commentSchema, describeEmailOutcome } from '../complaintSchemas'
import {
  useAddComment,
  useAssignees,
  useComments,
  useComplaint,
  useUpdateComplaint,
} from '../hooks/useComplaints'
import { useWriteAccess } from '../hooks/usePlan'
import { PriorityBadge, SlaIndicator } from './ComplaintParts'
import { ComplaintStatusDialog } from './ComplaintStatusDialog'

const UNASSIGNED = 'none'

interface Props {
  /** The open complaint's id, or null when the drawer is closed. */
  id: Uuid | null
  onClose: () => void
}

/** One complaint: its details, who it is assigned to, and the thread of notes and updates. */
export function ComplaintDrawer({ id, onClose }: Props) {
  const complaint = useComplaint(id)
  const [changing, setChanging] = useState(false)
  const data = complaint.data
  return (
    <>
      <Sheet open={id !== null} onOpenChange={(open) => !open && onClose()}>
        <SheetContent className="max-w-xl">
          <SheetHeader>
            <SheetTitle>{data?.subject ?? 'Complaint'}</SheetTitle>
            <SheetDescription>
              {data?.memberName ? `About ${data.memberName}` : 'Not about one member'}
            </SheetDescription>
          </SheetHeader>
          {complaint.isError ? (
            <ErrorState error={complaint.error} onRetry={() => void complaint.refetch()} />
          ) : !data ? (
            <div role="status" aria-label="Loading complaint" className="grid gap-3">
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-24" />
              <Skeleton className="h-40" />
            </div>
          ) : (
            <Body complaint={data} onChangeStatus={() => setChanging(true)} />
          )}
        </SheetContent>
      </Sheet>
      <ComplaintStatusDialog
        complaint={changing && data ? data : null}
        onClose={() => setChanging(false)}
      />
    </>
  )
}

function Body({
  complaint,
  onChangeStatus,
}: {
  complaint: ComplaintView
  onChangeStatus: () => void
}) {
  const write = useWriteAccess()
  const assignees = useAssignees()
  const update = useUpdateComplaint()
  const closed = complaint.status === 'CLOSED'

  async function assign(value: string) {
    try {
      await update.mutateAsync({
        id: complaint.id,
        body: value === UNASSIGNED ? { unassign: true } : { assignedTo: value },
      })
      notify.success(value === UNASSIGNED ? 'Unassigned' : 'Assigned')
    } catch (caught) {
      notify.apiError(caught)
    }
  }

  return (
    <div className="grid gap-5">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge status={complaint.status} />
        <PriorityBadge priority={complaint.priority} />
        {complaint.category ? <Badge>{complaint.category}</Badge> : null}
        <SlaIndicator complaint={complaint} />
      </div>

      <p className="whitespace-pre-wrap text-sm">{complaint.description}</p>

      <dl className="grid gap-2 text-sm">
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Member</dt>
          <dd>
            {complaint.memberId ? (
              <Link
                to={paths.admin.member(complaint.memberId)}
                className="font-medium text-brand-700 underline"
              >
                {complaint.memberName} ({complaint.memberNo})
              </Link>
            ) : (
              'None'
            )}
          </dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Logged</dt>
          <dd>{formatDateTime(complaint.createdAt)}</dd>
        </div>
        {complaint.resolvedAt ? (
          <div className="flex justify-between gap-3">
            <dt className="text-muted">Resolved</dt>
            <dd>{formatDateTime(complaint.resolvedAt)}</dd>
          </div>
        ) : null}
      </dl>

      <div className="grid gap-1.5">
        <Label htmlFor="assign-to">Assigned to</Label>
        <SimpleSelect
          id="assign-to"
          options={[
            { value: UNASSIGNED, label: 'Nobody' },
            ...(assignees.data ?? []).map((a) => ({ value: a.id, label: a.fullName })),
          ]}
          value={complaint.assignedTo ?? UNASSIGNED}
          onValueChange={(value) => void assign(value)}
          disabled={!write.allowed || closed || update.isPending}
        />
        {closed ? (
          <p className="text-xs text-muted">
            A closed complaint cannot be changed. Reopen it first.
          </p>
        ) : null}
      </div>

      <div>
        <Button onClick={onChangeStatus} disabled={!write.allowed}>
          Change status
        </Button>
      </div>

      <Thread complaint={complaint} />
    </div>
  )
}

function Thread({ complaint }: { complaint: ComplaintView }) {
  const comments = useComments(complaint.id)
  const add = useAddComment()
  const write = useWriteAccess()
  const [body, setBody] = useState('')
  const [visibility, setVisibility] = useState<ComplaintVisibility>('INTERNAL')
  const [emailMember, setEmailMember] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const closed = complaint.status === 'CLOSED'
  const canEmail = visibility === 'MEMBER' && complaint.memberId !== null

  async function submit() {
    const parsed = commentSchema.safeParse({ body })
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? 'Write something first')
      return
    }
    setError(null)
    try {
      const done = await add.mutateAsync({
        id: complaint.id,
        body: {
          body: parsed.data.body,
          visibility,
          ...(canEmail && emailMember ? { notifyMember: true } : {}),
        },
      })
      setBody('')
      notify.success(visibility === 'MEMBER' ? 'Update added' : 'Note added')
      const outcome = describeEmailOutcome(done.emailOutcome)
      if (outcome && canEmail && emailMember) notify.info(outcome)
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  return (
    <section aria-label="Conversation" className="grid gap-3 border-t border-brand-100 pt-4">
      <h3 className="font-heading text-lg font-semibold text-brand-900">Conversation</h3>
      {comments.isError ? (
        <ErrorState error={comments.error} onRetry={() => void comments.refetch()} />
      ) : comments.isLoading ? (
        <Skeleton className="h-20" role="status" aria-label="Loading conversation" />
      ) : (comments.data ?? []).length === 0 ? (
        <p className="text-sm text-muted">No notes or updates yet.</p>
      ) : (
        <ul aria-label="Notes and updates" className="grid gap-3">
          {comments.data?.map((comment) => (
            <li
              key={comment.id}
              className={
                comment.visibility === 'INTERNAL'
                  ? 'rounded border border-gold-200 bg-gold-50 p-3'
                  : 'rounded border border-brand-100 bg-white p-3'
              }
            >
              <div className="mb-1 flex flex-wrap items-center gap-2 text-xs text-muted">
                <span className="font-semibold text-ink">{comment.authorName}</span>
                <span>{formatDateTime(comment.createdAt)}</span>
                <Badge variant={comment.visibility === 'INTERNAL' ? 'gold' : 'brand'}>
                  {comment.visibility === 'INTERNAL' ? 'Internal note' : 'Visible to member'}
                </Badge>
              </div>
              <p className="whitespace-pre-wrap text-sm">{comment.body}</p>
            </li>
          ))}
        </ul>
      )}

      {closed ? (
        <p className="text-sm text-muted">This complaint is closed. Reopen it to add more.</p>
      ) : (
        <div className="grid gap-3">
          <RadioGroup
            value={visibility}
            onValueChange={(value) => setVisibility(value as ComplaintVisibility)}
            aria-label="Who can see this"
            className="grid-cols-1 sm:grid-cols-2"
          >
            <RadioGroupItem value="INTERNAL" label="Internal note (admins only)" />
            <RadioGroupItem value="MEMBER" label="Update the member can see" />
          </RadioGroup>
          <div className="grid gap-1.5">
            <Label htmlFor="comment-body">
              {visibility === 'INTERNAL' ? 'Internal note' : 'Update for the member'}
            </Label>
            <Textarea
              id="comment-body"
              rows={3}
              maxLength={5000}
              value={body}
              aria-invalid={error !== null}
              onChange={(event) => setBody(event.target.value)}
            />
            {error ? (
              <p role="alert" className="text-sm text-danger">
                {error}
              </p>
            ) : null}
          </div>
          {visibility === 'MEMBER' ? (
            <Checkbox
              label="Notify the member by email"
              description={
                complaint.memberId
                  ? 'Only if the member has an address and agreed to email.'
                  : 'This complaint is not about a member.'
              }
              checked={canEmail && emailMember}
              disabled={complaint.memberId === null}
              onCheckedChange={(checked) => setEmailMember(checked === true)}
            />
          ) : null}
          <div>
            <Button onClick={() => void submit()} loading={add.isPending} disabled={!write.allowed}>
              {visibility === 'INTERNAL' ? 'Add note' : 'Add update'}
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
