import { cn } from '@/shared/utils/cn'

interface SafeHtmlFrameProps {
  title: string
  /** HTML the server has already sanitised. */
  html: string
  className?: string
}

const STYLE = `
  body { font-family: system-ui, sans-serif; font-size: 15px; line-height: 1.5; color: #111; margin: 12px; }
  a { color: #037077; } blockquote { border-left: 4px solid #D6E6E8; margin-left: 0; padding-left: 12px; }
  h1, h2, h3, h4 { margin: 12px 0 6px; }
`

/**
 * Shows announcement HTML the way a reader sees it. The frame is sandboxed with no permissions
 * (no scripts, no forms, no navigation), so even markup that slipped past the sanitiser could not
 * run or reach the app.
 */
export function SafeHtmlFrame({ title, html, className }: SafeHtmlFrameProps) {
  return (
    <iframe
      title={title}
      sandbox=""
      srcDoc={`<!doctype html><meta charset="utf-8"><style>${STYLE}</style><body>${html}</body>`}
      className={cn('h-64 w-full rounded border border-brand-100 bg-white', className)}
    />
  )
}
