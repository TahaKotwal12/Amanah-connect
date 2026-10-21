import { Suspense, useEffect, useRef, useState, type ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'

interface DeferredSectionProps {
  /** The anchor target. It exists from the start, so /#pricing works before the content has loaded. */
  id: string
  /** Reserved height while loading, so the page does not jump when the section arrives. */
  minHeight: number
  children: ReactNode
  className?: string
}

/**
 * Renders its children (usually a React.lazy section) only when the visitor is about to reach
 * them, so the first paint downloads nothing below the fold. Without IntersectionObserver
 * (old browsers, tests) it renders at once.
 */
export function DeferredSection({ id, minHeight, children, className }: DeferredSectionProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [near, setNear] = useState(typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    const node = ref.current
    if (near || !node) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setNear(true)
          observer.disconnect()
        }
      },
      { rootMargin: '800px 0px' },
    )
    observer.observe(node)
    return () => {
      observer.disconnect()
    }
  }, [near])

  // Following a link such as /#faq: open the section now rather than waiting for the scroll.
  useEffect(() => {
    const open = () => {
      if (window.location.hash === `#${id}`) setNear(true)
    }
    open()
    window.addEventListener('hashchange', open)
    return () => {
      window.removeEventListener('hashchange', open)
    }
  }, [id])

  return (
    <div
      ref={ref}
      id={id}
      className={cn('scroll-mt-20', className)}
      style={near ? undefined : { minHeight }}
    >
      {near ? <Suspense fallback={<div style={{ minHeight }} />}>{children}</Suspense> : null}
    </div>
  )
}
