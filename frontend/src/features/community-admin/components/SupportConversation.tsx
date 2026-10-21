import { ArrowLeft, Paperclip } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { formatDateTime } from '@/lib/dates'
import { formatBytes } from '@/lib/upload'
import { Button } from '@/shared/components/ui/button'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Textarea } from '@/shared/components/ui/input'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { cn } from '@/shared/utils/cn'
import type { MessageView, ThreadView, Uuid } from '@/types'
import { useAttachment } from '../hooks/useAttachment'
import {
  useMarkThreadRead,
  usePostMessage,
  useThread,
  useThreadMessages,
} from '../hooks/useSupport'
import { useWriteAccess } from '../hooks/usePlan'
import { AttachmentField } from './AttachmentField'

const MAX_BODY = 5000

function Bubble({ message }: { message: MessageView }) {
  const ours = message.side === 'COMMUNITY'
  return (
    <li className={cn('flex', ours ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[85%] rounded border p-3 text-sm',
          ours ? 'border-brand-200 bg-brand-100' : 'border-gold-200 bg-gold-50',
        )}
      >
        <p className="mb-1 text-xs text-muted">
          <span className="font-medium text-brand-900">{ours ? 'You' : message.senderName}</span>
          {ours ? '' : ' (support team)'}, {formatDateTime(message.createdAt)}
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
  const write = useWriteAccess()
  const attachment = useAttachment()
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const busy = post.isPending || attachment.progress !== null
  const trimmed = text.trim()

  async function send() {
    if (trimmed === '' || busy) return
    setError(null)
    try {
      const uploaded = await attachment.upload()
      await post.mutateAsync({ body: trimmed, ...uploaded })
      setText('')
      attachment.clear()
    } catch (caught) {
      setError(
        caught instanceof Error && !('code' in caught)
          ? caught.message
          : toApiError(caught).message,
      )
    }
  }

  if (thread.status === 'CLOSED') {
    return (
      <p className="rounded border border-brand-100 bg-brand-50 p-3 text-sm">
        This conversation is closed. Start a new one if you need more help.
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
        {thread.status === 'RESOLVED' ? 'Reply (this reopens the conversation)' : 'Reply'}
      </label>
      <Textarea
        id="support-reply"
        value={text}
        rows={3}
        maxLength={MAX_BODY}
        disabled={busy || !write.allowed}
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
      <div className="flex flex-wrap items-start justify-between gap-2">
        <AttachmentField attachment={attachment} disabled={busy} inputId="support-attachment" />
        <Button type="submit" disabled={trimmed === '' || !write.allowed} loading={busy}>
          {attachment.progress !== null && attachment.progress < 100
            ? `Uploading ${attachment.progress}%`
            : 'Send reply'}
        </Button>
      </div>
    </form>
  )
}

interface ConversationProps {
  id: Uuid
  /** On a phone the conversation replaces the list; this goes back. */
  onBack: () => void
}

/** One conversation with the support team: the messages, kept up to date, and a reply box. */
export function SupportConversation({ id, onBack }: ConversationProps) {
  const thread = useThread(id)
  const messages = useThreadMessages(id)
  const markRead = useMarkThreadRead()
  const end = useRef<HTMLLIElement>(null)
  const markedUpTo = useRef(0)
  const items = messages.data?.items ?? []
  const lastSeq = items.at(-1)?.seq ?? 0
  const unreadFromSupport = items.some((m) => m.side === 'PLATFORM' && m.readAt === null)

  // Replies on screen are read: tell the server, once per new message, only while the tab is shown.
  const { mutate } = markRead
  useEffect(() => {
    if (!unreadFromSupport || lastSeq <= markedUpTo.current) return
    if (document.visibilityState !== 'visible') return
    markedUpTo.current = lastSeq
    mutate({ id, upToSeq: lastSeq })
  }, [unreadFromSupport, lastSeq, id, mutate])

  // New message: scroll to it.
  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' })
  }, [lastSeq])

  if (thread.isError)
    return (
      <ErrorState headingLevel={2} error={thread.error} onRetry={() => void thread.refetch()} />
    )
  const data = thread.data

  return (
    <section aria-label="Conversation" className="grid gap-4">
      <div className="flex items-start gap-2">
        <Button
          variant="ghost"
          size="sm"
          onClick={onBack}
          className="lg:hidden"
          aria-label="Back to all conversations"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
        </Button>
        <div className="min-w-0 flex-1">
          <h2 className="truncate font-heading text-xl font-semibold text-brand-900">
            {data?.subject ?? 'Conversation'}
          </h2>
          {data ? (
            <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted">
              <StatusBadge status={data.status} />
              {data.assignedToName ? `Looked after by ${data.assignedToName}` : 'Not yet picked up'}
            </p>
          ) : null}
        </div>
      </div>

      {messages.isError ? (
        <ErrorState
          headingLevel={3}
          error={messages.error}
          onRetry={() => void messages.refetch()}
        />
      ) : messages.isLoading ? (
        <div role="status" aria-label="Loading messages" className="grid gap-3">
          <Skeleton className="h-16 w-2/3" />
          <Skeleton className="ml-auto h-16 w-2/3" />
        </div>
      ) : (
        <ul aria-label="Messages" className="grid max-h-[55vh] gap-3 overflow-y-auto pr-1">
          {items.map((message) => (
            <Bubble key={message.id} message={message} />
          ))}
          <li ref={end} aria-hidden="true" className="h-0" />
        </ul>
      )}

      {data ? <Composer key={data.id} thread={data} /> : null}
    </section>
  )
}
