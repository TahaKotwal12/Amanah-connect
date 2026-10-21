import type { UploadInstructions } from '@/types'

export interface FileRules {
  /** Allowed MIME types, e.g. ['image/png', 'application/pdf']. Omit to allow any. */
  accept?: readonly string[]
  maxBytes?: number
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Reason the file cannot be uploaded, as text for the person, or null when it is fine. */
export function validateFile(file: File, rules: FileRules): string | null {
  if (rules.accept && rules.accept.length > 0 && !rules.accept.includes(file.type)) {
    return `${file.name}: this file type is not allowed. Use ${rules.accept
      .map((type) => type.split('/')[1]?.toUpperCase() ?? type)
      .join(', ')}.`
  }
  if (rules.maxBytes !== undefined && file.size > rules.maxBytes) {
    return `${file.name}: the file is ${formatBytes(file.size)}, over the ${formatBytes(rules.maxBytes)} limit.`
  }
  if (file.size === 0) return `${file.name}: the file is empty.`
  return null
}

export class UploadError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = 'UploadError'
    this.status = status
  }
}

/**
 * PUT a file straight to storage with a presigned URL, reporting progress (0 to 100). Uses
 * XMLHttpRequest because fetch cannot report upload progress. Sends no cookies and no bearer token:
 * the signature in the URL is the credential, and the storage origin must never see ours.
 */
export function uploadToPresignedUrl(
  instructions: Pick<UploadInstructions, 'uploadUrl' | 'method' | 'headers'>,
  file: Blob,
  options: { onProgress?: (percent: number) => void; signal?: AbortSignal } = {},
): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open(instructions.method || 'PUT', instructions.uploadUrl)
    request.withCredentials = false
    for (const [name, value] of Object.entries(instructions.headers)) {
      request.setRequestHeader(name, value)
    }
    request.upload.onprogress = (event) => {
      if (event.lengthComputable)
        options.onProgress?.(Math.round((event.loaded / event.total) * 100))
    }
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) {
        options.onProgress?.(100)
        resolve()
      } else {
        reject(new UploadError('The upload was refused by the file store.', request.status))
      }
    }
    request.onerror = () => {
      reject(new UploadError('The upload failed. Check your connection and try again.', 0))
    }
    request.onabort = () => {
      reject(new DOMException('Upload cancelled', 'AbortError'))
    }
    options.signal?.addEventListener('abort', () => {
      request.abort()
    })
    request.send(file)
  })
}
