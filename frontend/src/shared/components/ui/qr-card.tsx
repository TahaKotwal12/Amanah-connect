import { Download } from 'lucide-react'
import { QRCodeCanvas } from 'qrcode.react'
import { useRef } from 'react'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'
import { Card } from './card'

interface QrCardProps {
  title: string
  caption?: string
  /** Text to encode: a link or a upi:// payment URI. Drawn in the browser. */
  value?: string
  /** Or a ready-made PNG from the API (base64, no data: prefix), such as an invoice QR. */
  pngBase64?: string
  /** What the code opens, for people who cannot scan it. */
  alt: string
  /** File name for the download, without extension. */
  downloadName?: string
  size?: number
  className?: string
}

export function QrCard({
  title,
  caption,
  value,
  pngBase64,
  alt,
  downloadName = 'qr-code',
  size = 192,
  className,
}: QrCardProps) {
  const canvasWrapper = useRef<HTMLDivElement>(null)

  function download() {
    const href = pngBase64
      ? `data:image/png;base64,${pngBase64}`
      : canvasWrapper.current?.querySelector('canvas')?.toDataURL('image/png')
    if (!href) return
    const link = document.createElement('a')
    link.href = href
    link.download = `${downloadName}.png`
    link.click()
  }

  return (
    <Card
      className={cn('flex w-fit max-w-full flex-col items-center gap-3 p-5 text-center', className)}
    >
      <h3 className="font-heading text-lg font-semibold text-brand-900">{title}</h3>
      {/* A QR code needs a light quiet zone around it to scan, whatever the page colour is. */}
      <div ref={canvasWrapper} className="rounded bg-white p-3 ring-1 ring-brand-100">
        {pngBase64 ? (
          <img
            src={`data:image/png;base64,${pngBase64}`}
            alt={alt}
            width={size}
            height={size}
            style={{ width: size, height: size }}
          />
        ) : value ? (
          <QRCodeCanvas
            value={value}
            size={size}
            marginSize={0}
            level="M"
            role="img"
            aria-label={alt}
          />
        ) : null}
      </div>
      {caption ? <p className="max-w-xs text-sm text-muted">{caption}</p> : null}
      <Button variant="secondary" size="sm" onClick={download}>
        <Download aria-hidden="true" className="size-4" />
        Download PNG
      </Button>
    </Card>
  )
}
