import { ArrowLeft, Paperclip, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { formatBytes, uploadToPresignedUrl, validateFile } from '@/lib/upload'
import { paths } from '@/routes/paths'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Textarea } from '@/shared/components/ui/input'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { cn } from '@/shared/utils/cn'
import type { MessageView, Priority, ThreadView, Uuid } from '@/types'
import { requestAttachmentUpload } from '../api/support'
import {
  useMarkThreadRead,
  usePostMessage,
  useThread,
  useThreadMessages,
  useUpdateThread,
} from '../hooks/useSupport'

const MAX_BODY = 5000
const ATTACHMENT_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'application/pdf'] as const
const PRIORITIES = [
  { value: 'LOW', label: 'Low' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HIGH', label: 'High' },
  { value: 'URGENT', label: 'Urgent' },
]

function Bubble({ message }: { message: MessageView }) {
  const ours = message.side === 'PLATFORM'
  return (
    <li className={cn('flex', ours ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[85%] rounded border p-3 text-sm',
          ours ? 'border-brand-200 bg-brand-100' : 'border-brand-100 bg-white',
        )}
      >
        <p className="mb-1 text-xs text-muted">
          <span className="font-medium text-brand-900">{message.senderName}</span>
          {ours ? ' (support)' : ''}, {formatDateTime(message.createdAt)}
        </p>
        <p className="whitespace-pre-wrap break-words">{message.body}</p>
        {message.attachment ? (
          <p className="mt-2">
            <a
              href={message.attachment.downloadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 text-brand-700 underline underline-offset-2"
            >
              <Paperclip aria-hidden="true" className="size-4" />
              {message.attachment.name}
              <span className="text-muted no-underline">
                ({formatBytes(message.attachment.size)})
              </span>
            </a>
          </p>
        ) : null}
      </div>
    </li>
  )
}

function Composer({ thread }: { thread: ThreadView }) {
  const post = usePostMessage(thread.id)
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const closed = thread.status === 'CLOSED'
  const busy = post.isPending || progress !== null
  const trimmed = text.trim()

  function choose(chosen: File | undefined) {
    setError(null)
    if (!chosen) return
    const problem = validateFile(chosen, { accept: ATTACHMENT_TYPES })
    if (problem) {
      setError(problem)
      return
    }
    setFile(chosen)
  }

  async function send() {
    if (trimmed === '' || busy) return
    setError(null)
    try {
      let attachment: { attachmentKey: string; attachmentName: string } | undefined
      if (file) {
        setProgress(0)
        const instructions = await requestAttachmentUpload(thread.id, file)
        if (file.size > instructions.maxBytes) {
          throw new Error(
            `${file.name} is ${formatBytes(file.size)}; the limit is ${formatBytes(instructions.maxBytes)}.`,
          )
        }
        await uploadToPresignedUrl(instructions, file, { onProgress: setProgress })
        attachment = { attachmentKey: instructions.attachmentKey, attachmentName: file.name }
      }
      await post.mutateAsync({ body: trimmed, ...attachment })
      setText('')
      setFile(null)
      if (fileInput.current) fileInput.current.value = ''
    } catch (caught) {
      setError(
        caught instanceof Error && !('code' in caught)
          ? caught.message
          : toApiError(caught).message,
      )
    } finally {
      setProgress(null)
    }
  }

  if (closed) {
    return (
      <p className="rounded border border-brand-100 bg-brand-50 p-3 text-sm">
        This conversation is closed. Reopen it to reply.
      </p>
    )
  }
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        void send()
      }}
      className="grid gap-2"
    >
      <label htmlFor="support-reply" className="text-sm font-medium">
        Reply
      </label>
      <Textarea
        id="support-reply"
        value={text}
        rows={3}
        maxLength={MAX_BODY}
        disabled={busy}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault()
            void send()
          }
        }}
        aria-describedby="support-reply-hint"
      />
      <p id="support-reply-hint" className="text-xs text-muted">
        Ctrl and Enter send. {text.length}/{MAX_BODY}
      </p>
      {error ? (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <input
            ref={fileInput}
            id="support-attachment"
            aria-label="Attach a file"
            type="file"
            accept={ATTACHMENT_TYPES.join(',')}
            className="sr-only"
            disabled={busy}
            onChange={(event) => choose(event.target.files?.[0])}
          />
          <Button
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={() => fileInput.current?.click()}
          >
            <Paperclip aria-hidden="true" className="size-4" />
            Attach a file
          </Button>
          {file ? (
            <span className="flex min-w-0 items-center gap-1 text-sm">
              <span className="truncate">
                {file.name} ({formatBytes(file.size)})
              </span>
              <button
                type="button"
                aria-label={`Remove ${file.name}`}
                disabled={busy}
                onClick={() => {
                  setFile(null)
                  if (fileInput.current) fileInput.current.value = ''
                }}
                className="flex size-8 shrink-0 items-center justify-center rounded hover:bg-brand-50"
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </span>
          ) : null}
        </div>
        <Button type="submit" disabled={trimmed === ''} loading={busy}>
          {progress !== null && progress < 100 ? `Uploading ${progress}%` : 'Send reply'}
        </Button>
      </div>
    </form>
  )
}

interface SupportConversationProps {
  threadId: Uuid
  onBack: () => void
}

/** The right column: one conversation, its controls, and the reply box. */
export function SupportConversation({ threadId, onBack }: SupportConversationProps) {
  const me = useAuthStore((state) => state.user)
  const thread = useThread(threadId)
  const messages = useThreadMessages(threadId)
  const update = useUpdateThread()
  const markRead = useMarkThreadRead()
  const [confirmClose, setConfirmClose] = useState(false)
  const bottom = useRef<HTMLDivElement>(null)
  const markedUpTo = useRef<{ thread: string; cursor: number } | null>(null)

  const count = messages.data?.items.length ?? 0
  const cursor = messages.data?.cursor ?? 0
  const unread = thread.data?.unreadCount ?? 0

  // New messages scroll into view.
  useEffect(() => {
    bottom.current?.scrollIntoView?.({ block: 'end' })
  }, [count, threadId])

  // Reading a conversation marks it read, once per new message, and only while the tab is shown.
  useEffect(() => {
    if (unread === 0 || cursor === 0) return
    if (document.visibilityState !== 'visible') return
    const done = markedUpTo.current
    if (done && done.thread === threadId && done.cursor === cursor) return
    markedUpTo.current = { thread: threadId, cursor }
    markRead.mutate({ id: threadId, upToSeq: cursor })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- markRead is stable enough; run per cursor
  }, [unread, cursor, threadId])

  async function change(body: Parameters<typeof update.mutateAsync>[0]['body'], done?: string) {
    try {
      await update.mutateAsync({ id: threadId, body })
      if (done) notify.success(done)
    } catch (error) {
      notify.apiError(error)
      throw error
    }
  }

  if (thread.isError) {
    return (
      <div className="grid gap-3">
        <Button variant="ghost" onClick={onBack} className="w-fit lg:hidden">
          <ArrowLeft aria-hidden="true" className="size-4" />
          All conversations
        </Button>
        <ErrorState
          headingLevel={2}
          error={thread.error}
          onRetry={() => void thread.refetch()}
          retrying={thread.isFetching}
        />
      </div>
    )
  }
  if (!thread.data) {
    return <Skeleton className="h-96" role="status" aria-label="Loading conversation" />
  }
  const data = thread.data
  const active = data.status === 'OPEN' || data.status === 'WAITING'
  const mine = data.assignedTo !== null && data.assignedTo === me?.id

  return (
    <section aria-label="Conversation" className="grid gap-4">
      <header className="grid gap-3 rounded-card border border-brand-100 bg-white p-4">
        <Button variant="ghost" onClick={onBack} className="w-fit lg:hidden">
          <ArrowLeft aria-hidden="true" className="size-4" />
          All conversations
        </Button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-heading text-xl font-semibold lining-nums text-brand-900">
              {data.subject}
            </h2>
            <p className="text-sm text-muted">
              <Link
                to={paths.superadmin.community(data.communityId)}
                className="text-brand-700 underline-offset-2 hover:underline"
              >
                {data.communityName}
              </Link>
              , started by {data.createdByName}
            </p>
          </div>
          <StatusBadge status={data.status} />
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid gap-1">
            <label htmlFor="thread-priority" className="text-xs text-muted">
              Priority
            </label>
            <SimpleSelect
              id="thread-priority"
              options={PRIORITIES}
              value={data.priority}
              onValueChange={(value) =>
                void change({ priority: value as Priority }).catch(() => undefined)
              }
              className="w-36"
            />
          </div>
          <p className="pb-2 text-sm">
            <span className="text-muted">Assigned to </span>
            <span className="font-medium">{data.assignedToName ?? 'nobody'}</span>
          </p>
          {mine ? (
            <Button
              variant="secondary"
              onClick={() => void change({ unassign: true }, 'Unassigned').catch(() => undefined)}
            >
              Unassign
            </Button>
          ) : me ? (
            <Button
              variant="secondary"
              onClick={() =>
                void change({ assignedTo: me.id }, 'Assigned to you').catch(() => undefined)
              }
            >
              Assign to me
            </Button>
          ) : null}
          <div className="ml-auto flex gap-2">
            {active ? (
              <>
                <Button
                  variant="secondary"
                  onClick={() =>
                    void change({ status: 'RESOLVED' }, 'Marked resolved').catch(() => undefined)
                  }
                >
                  Resolve
                </Button>
                <Button variant="danger" onClick={() => setConfirmClose(true)}>
                  Close
                </Button>
              </>
            ) : (
              <Button
                variant="secondary"
                onClick={() => void change({ status: 'OPEN' }, 'Reopened').catch(() => undefined)}
              >
                Reopen
              </Button>
            )}
          </div>
        </div>
      </header>

      <div
        aria-label="Messages"
        role="log"
        aria-live="polite"
        className="max-h-[55vh] min-h-48 overflow-y-auto rounded-card border border-brand-100 bg-brand-50 p-4"
      >
        {messages.isError && !messages.data ? (
          <ErrorState
            headingLevel={3}
            error={messages.error}
            onRetry={() => void messages.refetch()}
            retrying={messages.isFetching}
          />
        ) : !messages.data ? (
          <Skeleton className="h-40" role="status" aria-label="Loading messages" />
        ) : (
          <ol className="grid gap-3">
            {messages.data.items.map((message) => (
              <Bubble key={message.id} message={message} />
            ))}
          </ol>
        )}
        <div ref={bottom} />
      </div>

      <Composer thread={data} />

      <ConfirmDialog
        open={confirmClose}
        onOpenChange={setConfirmClose}
        variant="danger"
        title="Close this conversation?"
        description="Nobody can reply until it is reopened."
        confirmLabel="Close conversation"
        onConfirm={() => change({ status: 'CLOSED' }, 'Conversation closed')}
      />
    </section>
  )
}
