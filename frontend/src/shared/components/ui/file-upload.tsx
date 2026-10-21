import { CheckCircle2, FileUp, RotateCcw, X } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import { describeError } from '@/lib/errors'
import { formatBytes, validateFile, type FileRules } from '@/lib/upload'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'

type ItemStatus = 'uploading' | 'done' | 'error'

interface UploadItem {
  id: number
  file: File
  status: ItemStatus
  percent: number
  error?: string
}

export interface UploadContext {
  onProgress: (percent: number) => void
  signal: AbortSignal
}

interface FileUploadProps<TResult> extends FileRules {
  /**
   * Do the upload and resolve with whatever the caller needs afterwards (usually the storage key).
   * Typically: request presigned instructions from the API, then uploadToPresignedUrl().
   */
  upload: (file: File, context: UploadContext) => Promise<TResult>
  onUploaded?: (file: File, result: TResult) => void
  label?: string
  /** Text under the drop area: say what is allowed. */
  hint?: string
  multiple?: boolean
  disabled?: boolean
  className?: string
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

/**
 * Drag-and-drop (or button) file picker with per-file validation, live progress and cancel/retry.
 * Files are checked here before any request, but the server checks again: this is for feedback.
 */
export function FileUpload<TResult>({
  upload,
  onUploaded,
  accept,
  maxBytes,
  label = 'Upload a file',
  hint,
  multiple = false,
  disabled = false,
  className,
}: FileUploadProps<TResult>) {
  const [items, setItems] = useState<UploadItem[]>([])
  const [rejections, setRejections] = useState<string[]>([])
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const controllers = useRef(new Map<number, AbortController>())
  const nextId = useRef(1)

  function patch(id: number, changes: Partial<UploadItem>) {
    setItems((current) => current.map((item) => (item.id === id ? { ...item, ...changes } : item)))
  }

  async function start(id: number, file: File) {
    const controller = new AbortController()
    controllers.current.set(id, controller)
    patch(id, { status: 'uploading', percent: 0 })
    try {
      const result = await upload(file, {
        signal: controller.signal,
        onProgress: (percent) => {
          patch(id, { percent })
        },
      })
      patch(id, { status: 'done', percent: 100 })
      onUploaded?.(file, result)
    } catch (error) {
      if (isAbort(error)) {
        setItems((current) => current.filter((item) => item.id !== id))
      } else {
        patch(id, { status: 'error', error: describeError(error) })
      }
    } finally {
      controllers.current.delete(id)
    }
  }

  function accepted(files: FileList | File[]) {
    const rules: FileRules = {
      ...(accept ? { accept } : {}),
      ...(maxBytes !== undefined ? { maxBytes } : {}),
    }
    const chosen = multiple ? [...files] : [...files].slice(0, 1)
    const problems: string[] = []
    const ok: File[] = []
    for (const file of chosen) {
      const problem = validateFile(file, rules)
      if (problem) problems.push(problem)
      else ok.push(file)
    }
    setRejections(problems)
    if (!multiple && ok.length > 0) setItems([])
    for (const file of ok) {
      const id = nextId.current++
      setItems((current) => [...current, { id, file, status: 'uploading', percent: 0 }])
      void start(id, file)
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!disabled) accepted(event.dataTransfer.files)
  }

  return (
    <div className={cn('grid gap-3', className)}>
      <div
        onDragOver={(event) => {
          event.preventDefault()
          if (!disabled) setDragging(true)
        }}
        onDragLeave={() => {
          setDragging(false)
        }}
        onDrop={onDrop}
        className={cn(
          'flex flex-col items-center gap-2 rounded-card border-2 border-dashed px-6 py-8 text-center',
          dragging ? 'border-brand-600 bg-brand-50' : 'border-muted bg-white',
          disabled && 'opacity-60',
        )}
      >
        <FileUp aria-hidden="true" className="size-8 text-brand-500" strokeWidth={1.5} />
        <p className="text-sm text-ink">Drag {multiple ? 'files' : 'a file'} here, or</p>
        <Button
          variant="secondary"
          disabled={disabled}
          onClick={() => {
            input.current?.click()
          }}
        >
          {label}
        </Button>
        {hint ? <p className="text-xs text-muted">{hint}</p> : null}
        <input
          ref={input}
          type="file"
          hidden
          multiple={multiple}
          accept={accept?.join(',')}
          onChange={(event) => {
            if (event.target.files) accepted(event.target.files)
            event.target.value = '' // allow choosing the same file again
          }}
        />
      </div>

      {rejections.length > 0 ? (
        <ul role="alert" className="grid gap-1 text-sm font-medium text-danger">
          {rejections.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      ) : null}

      {items.length > 0 ? (
        <ul className="grid gap-2" aria-label="Files">
          {items.map((item) => (
            <li key={item.id} className="rounded border border-brand-100 bg-white p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="min-w-0 truncate text-sm font-medium">
                  {item.file.name}{' '}
                  <span className="font-normal text-muted">({formatBytes(item.file.size)})</span>
                </p>
                <div className="flex items-center gap-1">
                  {item.status === 'done' ? (
                    <span className="flex items-center gap-1 text-sm font-medium text-success-700">
                      <CheckCircle2 aria-hidden="true" className="size-4" /> Uploaded
                    </span>
                  ) : null}
                  {item.status === 'error' ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => void start(item.id, item.file)}
                      aria-label={`Retry ${item.file.name}`}
                    >
                      <RotateCcw aria-hidden="true" className="size-4" /> Retry
                    </Button>
                  ) : null}
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-9"
                    aria-label={
                      item.status === 'uploading'
                        ? `Cancel ${item.file.name}`
                        : `Remove ${item.file.name}`
                    }
                    onClick={() => {
                      controllers.current.get(item.id)?.abort()
                      setItems((current) => current.filter((entry) => entry.id !== item.id))
                    }}
                  >
                    <X aria-hidden="true" className="size-4" />
                  </Button>
                </div>
              </div>
              {item.status === 'uploading' ? (
                <div
                  role="progressbar"
                  aria-label={`Uploading ${item.file.name}`}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={item.percent}
                  className="mt-2 h-2 overflow-hidden rounded-full bg-brand-100"
                >
                  <div className="h-full bg-brand-600" style={{ width: `${item.percent}%` }} />
                </div>
              ) : null}
              {item.status === 'error' ? (
                <p role="alert" className="mt-1 text-sm font-medium text-danger">
                  {item.error}
                </p>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
