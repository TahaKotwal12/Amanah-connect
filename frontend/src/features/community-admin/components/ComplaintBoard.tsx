import { MoreHorizontal } from 'lucide-react'
import { useState } from 'react'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/shared/components/ui/dropdown-menu'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { cn } from '@/shared/utils/cn'
import type { ComplaintStatus, ComplaintView, Uuid } from '@/types'
import { COMPLAINT_STATUSES, statusLabel } from '../complaintSchemas'
import { useChangeComplaintStatus, useComplaints } from '../hooks/useComplaints'
import { useWriteAccess } from '../hooks/usePlan'
import type { ComplaintListParams } from '../queryKeys'
import { PriorityBadge, SlaIndicator } from './ComplaintParts'

const COLUMN_SIZE = 50

interface BoardProps {
  /** The filters shared with the table (priority, mine, search ...), without status. */
  filters: Omit<ComplaintListParams, 'status' | 'page' | 'size' | 'sort'>
  onOpen: (id: Uuid) => void
  /** Open the status dialog (note, email the member) for a card. */
  onChangeStatus: (complaint: ComplaintView, to: ComplaintStatus) => void
}

/**
 * Four columns, one per status. A card can be dragged to another column, or moved from its menu
 * (the keyboard and screen reader route to the same thing). Dropping saves straight away with no
 * note and no email; use "Change status" on the card to add either.
 */
export function ComplaintBoard({ filters, onOpen, onChangeStatus }: BoardProps) {
  const change = useChangeComplaintStatus()
  const write = useWriteAccess()
  const [dragging, setDragging] = useState<ComplaintView | null>(null)
  const [over, setOver] = useState<ComplaintStatus | null>(null)
  const [announcement, setAnnouncement] = useState('')

  async function move(complaint: ComplaintView, to: ComplaintStatus) {
    if (complaint.status === to) return
    try {
      await change.mutateAsync({ id: complaint.id, body: { status: to } })
      const text = `${complaint.subject} moved to ${statusLabel(to)}`
      setAnnouncement(text)
      notify.success(`Moved to ${statusLabel(to)}`)
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <>
      <p className="mb-3 text-sm text-muted">
        Drag a card to another column, or use the menu on a card to move it.
      </p>
      <div role="status" aria-live="polite" className="sr-only">
        {announcement}
      </div>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {COMPLAINT_STATUSES.map((column) => (
          <Column
            key={column.value}
            status={column.value}
            label={column.label}
            filters={filters}
            highlighted={over === column.value && dragging?.status !== column.value}
            canDrop={write.allowed}
            onDragOver={() => setOver(column.value)}
            onDragLeave={() => setOver((current) => (current === column.value ? null : current))}
            onDrop={() => {
              setOver(null)
              if (dragging) void move(dragging, column.value)
              setDragging(null)
            }}
            renderCard={(complaint) => (
              <Card
                key={complaint.id}
                complaint={complaint}
                disabled={!write.allowed}
                dragging={dragging?.id === complaint.id}
                onOpen={() => onOpen(complaint.id)}
                onDragStart={() => setDragging(complaint)}
                onDragEnd={() => {
                  setDragging(null)
                  setOver(null)
                }}
                onMove={(to) => void move(complaint, to)}
                onChangeStatus={() => onChangeStatus(complaint, complaint.status)}
              />
            )}
          />
        ))}
      </div>
    </>
  )
}

function Column({
  status,
  label,
  filters,
  highlighted,
  canDrop,
  onDragOver,
  onDragLeave,
  onDrop,
  renderCard,
}: {
  status: ComplaintStatus
  label: string
  filters: BoardProps['filters']
  highlighted: boolean
  canDrop: boolean
  onDragOver: () => void
  onDragLeave: () => void
  onDrop: () => void
  renderCard: (complaint: ComplaintView) => React.ReactNode
}) {
  const query = useComplaints({
    ...filters,
    status,
    size: COLUMN_SIZE,
    sort: ['updatedAt,desc'],
  })
  const items = query.data?.items ?? []
  const total = query.data?.total ?? 0
  return (
    <section
      aria-label={`${label} complaints`}
      onDragOver={(event) => {
        if (!canDrop) return
        event.preventDefault()
        onDragOver()
      }}
      onDragLeave={onDragLeave}
      onDrop={(event) => {
        if (!canDrop) return
        event.preventDefault()
        onDrop()
      }}
      className={cn(
        'flex min-h-48 flex-col gap-3 rounded border bg-brand-50 p-3',
        highlighted ? 'border-brand-600 ring-2 ring-brand-600' : 'border-brand-100',
      )}
    >
      <h3 className="flex items-center justify-between font-heading text-base font-semibold text-brand-900">
        {label}
        <span className="text-sm font-normal text-muted">{total}</span>
      </h3>
      {query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <Skeleton className="h-24" role="status" aria-label={`Loading ${label}`} />
      ) : items.length === 0 ? (
        <p className="text-sm text-muted">Nothing here.</p>
      ) : (
        <ul className="grid gap-3">{items.map((complaint) => renderCard(complaint))}</ul>
      )}
      {total > items.length ? (
        <p className="text-xs text-muted">
          Showing the {items.length} most recent of {total}. Use the table to see all.
        </p>
      ) : null}
    </section>
  )
}

function Card({
  complaint,
  disabled,
  dragging,
  onOpen,
  onDragStart,
  onDragEnd,
  onMove,
  onChangeStatus,
}: {
  complaint: ComplaintView
  disabled: boolean
  dragging: boolean
  onOpen: () => void
  onDragStart: () => void
  onDragEnd: () => void
  onMove: (to: ComplaintStatus) => void
  onChangeStatus: () => void
}) {
  return (
    <li
      draggable={!disabled}
      onDragStart={(event) => {
        event.dataTransfer.setData('text/plain', complaint.id)
        event.dataTransfer.effectAllowed = 'move'
        onDragStart()
      }}
      onDragEnd={onDragEnd}
      className={cn(
        'grid gap-2 rounded border border-brand-100 bg-white p-3 shadow-soft',
        !disabled && 'cursor-grab',
        dragging && 'opacity-50',
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <button
          type="button"
          onClick={onOpen}
          className="min-w-0 text-left text-sm font-semibold text-brand-900 underline-offset-2 hover:underline"
        >
          {complaint.subject}
        </button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="sm"
              aria-label={`Actions for ${complaint.subject}`}
              className="shrink-0"
            >
              <MoreHorizontal aria-hidden="true" className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onSelect={onOpen}>Open</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuLabel>Move to</DropdownMenuLabel>
            {COMPLAINT_STATUSES.filter((s) => s.value !== complaint.status).map((s) => (
              <DropdownMenuItem key={s.value} disabled={disabled} onSelect={() => onMove(s.value)}>
                {s.label}
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem disabled={disabled} onSelect={onChangeStatus}>
              Change status with a note…
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <p className="text-xs text-muted">
        {complaint.memberName ?? 'Not about one member'}
        {complaint.assignedToName ? `, assigned to ${complaint.assignedToName}` : ', unassigned'}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <PriorityBadge priority={complaint.priority} />
        <SlaIndicator complaint={complaint} />
      </div>
    </li>
  )
}
