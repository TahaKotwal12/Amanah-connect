import { TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

/** A fact the owner still has to supply. Highlighted so it cannot be missed in review. */
export function Fill({ children }: { children: ReactNode }) {
  return (
    <mark className="rounded bg-gold-200 px-1 font-medium text-brand-950">
      [To be completed: {children}]
    </mark>
  )
}

interface LegalPageProps {
  title: string
  children: ReactNode
}

/** Shared frame for the legal pages: a draft banner, the title, and readable prose. */
export function LegalPage({ title, children }: LegalPageProps) {
  return (
    <article className="container max-w-3xl py-12 sm:py-16">
      <title>{`${title} | Amanah Connect`}</title>
      {/* Drafts stay out of search results until a lawyer has approved them. */}
      <meta name="robots" content="noindex" />
      <div
        role="note"
        className="mb-8 flex gap-3 rounded-card border-2 border-gold-500 bg-gold-50 p-4"
      >
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-gold-600" />
        <p className="text-sm text-ink">
          <strong className="font-semibold">Draft for legal review.</strong> This text has not been
          reviewed by a lawyer and is not final. It must be checked, completed and approved before
          the site is launched.
        </p>
      </div>
      <h1 className="font-heading text-4xl font-semibold text-brand-900">{title}</h1>
      <div className="mt-8 grid gap-4 text-ink [&_h2]:mt-6 [&_h2]:font-heading [&_h2]:text-2xl [&_h2]:font-semibold [&_h2]:text-brand-900 [&_li]:ml-5 [&_li]:list-disc [&_ul]:grid [&_ul]:gap-1">
        {children}
      </div>
    </article>
  )
}
