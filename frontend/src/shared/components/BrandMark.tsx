import { cn } from '@/shared/utils/cn'

interface BrandMarkProps {
  /** Rendered width in CSS pixels; the image is 124 x 80 (2x of the 62 x 40 default). */
  width?: number
  className?: string
}

/** The AC logo mark, as AVIF or WebP where supported (5 to 8 KB) instead of the 223 KB master PNG. */
export function BrandMark({ width = 62, className }: BrandMarkProps) {
  const height = Math.round((width * 80) / 124)
  return (
    <picture>
      <source srcSet="/brand/logo-mark-124.avif" type="image/avif" />
      <source srcSet="/brand/logo-mark-124.webp" type="image/webp" />
      <img
        src="/brand/logo-mark-124.png"
        alt=""
        width={width}
        height={height}
        decoding="async"
        className={cn('h-auto', className)}
        style={{ width }}
      />
    </picture>
  )
}
