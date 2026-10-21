interface PagePlaceholderProps {
  title: string
  /** One line on what will live here. */
  description?: string
}

/** Stand-in for a page that a later prompt builds. Every route renders one until then. */
export function PagePlaceholder({ title, description }: PagePlaceholderProps) {
  return (
    <section className="container py-10">
      <title>{`${title} | Amanah Connect`}</title>
      <p className="eyebrow">Placeholder</p>
      <h1 className="mt-2 text-3xl">{title}</h1>
      {description ? <p className="mt-3 max-w-prose text-muted">{description}</p> : null}
    </section>
  )
}
