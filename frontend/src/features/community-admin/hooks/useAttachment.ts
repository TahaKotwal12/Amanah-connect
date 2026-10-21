import { useRef, useState } from 'react'
import { formatBytes, uploadToPresignedUrl, validateFile } from '@/lib/upload'
import { requestAttachmentUpload } from '../api/support'

export const SUPPORT_ATTACHMENT_TYPES = [
  'image/png',
  'image/jpeg',
  'image/webp',
  'application/pdf',
] as const

export interface UploadedAttachment {
  attachmentKey: string
  attachmentName: string
}

/**
 * One optional file for a support message: choose it, check it, and upload it straight to storage
 * when the message is sent. Thrown errors from upload() are plain Errors with a readable message.
 */
export function useAttachment() {
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const input = useRef<HTMLInputElement>(null)

  function choose(chosen: File | undefined) {
    setError(null)
    if (!chosen) return
    const problem = validateFile(chosen, { accept: SUPPORT_ATTACHMENT_TYPES })
    if (problem) {
      setError(problem)
      return
    }
    setFile(chosen)
  }

  function clear() {
    setFile(null)
    setError(null)
    if (input.current) input.current.value = ''
  }

  /** Uploads the chosen file, if any. Resolves with what to send with the message. */
  async function upload(): Promise<UploadedAttachment | undefined> {
    if (!file) return undefined
    setProgress(0)
    try {
      const instructions = await requestAttachmentUpload(file)
      if (file.size > instructions.maxBytes) {
        throw new Error(
          `${file.name} is ${formatBytes(file.size)}; the limit is ${formatBytes(instructions.maxBytes)}.`,
        )
      }
      await uploadToPresignedUrl(instructions, file, { onProgress: setProgress })
      return { attachmentKey: instructions.attachmentKey, attachmentName: file.name }
    } finally {
      setProgress(null)
    }
  }

  return { file, error, setError, progress, input, choose, clear, upload }
}
