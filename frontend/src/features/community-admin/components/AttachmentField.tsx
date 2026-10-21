import { Paperclip, X } from 'lucide-react'
import { formatBytes } from '@/lib/upload'
import { Button } from '@/shared/components/ui/button'
import { SUPPORT_ATTACHMENT_TYPES, type useAttachment } from '../hooks/useAttachment'

/** The "Attach a file" button, the chosen file with a remove button, and any problem with it. */
export function AttachmentField({
  attachment,
  disabled,
  inputId,
}: {
  attachment: ReturnType<typeof useAttachment>
  disabled?: boolean
  inputId: string
}) {
  const { file, input, choose, clear, error } = attachment
  return (
    <div className="grid gap-1">
      <div className="flex min-w-0 items-center gap-2">
        <input
          ref={input}
          id={inputId}
          aria-label="Attach a file"
          type="file"
          accept={SUPPORT_ATTACHMENT_TYPES.join(',')}
          className="sr-only"
          disabled={disabled}
          onChange={(event) => choose(event.target.files?.[0])}
        />
        <Button
          type="button"
          variant="secondary"
          disabled={disabled}
          onClick={() => input.current?.click()}
        >
          <Paperclip aria-hidden="true" className="size-4" />
          {file ? 'Choose another file' : 'Attach a file'}
        </Button>
        {file ? (
          <span className="flex min-w-0 items-center gap-1 text-sm">
            <span className="truncate">
              {file.name} ({formatBytes(file.size)})
            </span>
            <button
              type="button"
              aria-label={`Remove ${file.name}`}
              disabled={disabled}
              onClick={clear}
              className="flex size-8 shrink-0 items-center justify-center rounded hover:bg-brand-50"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </span>
        ) : null}
      </div>
      <p className="text-xs text-muted">PNG, JPEG, WebP or PDF, up to 5 MB.</p>
      {error ? (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : null}
    </div>
  )
}
