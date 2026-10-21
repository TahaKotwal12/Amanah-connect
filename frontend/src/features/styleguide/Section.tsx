import type { ReactNode } from 'react'

export function Section({
  id,
  title,
  children,
}: {
  id: string
  title: string
  children: ReactNode
}) {
  return (
    <section aria-labelledby={`${id}-title`} id={id} className="grid scroll-mt-6 gap-6">
      <h2
        id={`${id}-title`}
        className="border-b border-brand-100 pb-2 font-heading text-2xl font-semibold"
      >
        {title}
      </h2>
      {children}
    </section>
  )
}

/** One labelled specimen. `column` stacks the content instead of wrapping it in a row. */
export function Demo({
  title,
  children,
  column = false,
}: {
  title: string
  children: ReactNode
  column?: boolean
}) {
  return (
    <div className="grid gap-3">
      <h3 className="text-sm font-semibold text-muted">{title}</h3>
      <div className={column ? 'grid max-w-xl gap-4' : 'flex flex-wrap items-center gap-3'}>
        {children}
      </div>
    </div>
  )
}
