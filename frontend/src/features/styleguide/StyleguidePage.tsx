import { ActionsSection } from './sections/ActionsSection'
import { DataTableSection } from './sections/DataTableSection'
import { FeedbackSection } from './sections/FeedbackSection'
import { FormsSection } from './sections/FormsSection'
import { LayoutSection } from './sections/LayoutSection'

const TOC = [
  ['actions', 'Actions and labels'],
  ['forms', 'Form controls'],
  ['surfaces', 'Surfaces and overlays'],
  ['data-table', 'DataTable'],
  ['layout', 'Layout shells'],
] as const

/**
 * Every shared component in every state, on one page. Development only: the route is not
 * registered in production builds. Used by hand for review and by the axe test for coverage.
 */
export default function StyleguidePage() {
  return (
    <div className="mx-auto grid max-w-5xl gap-12 px-4 py-10">
      <title>Styleguide | Amanah Connect</title>
      <header className="grid gap-3">
        <p className="eyebrow">Development only</p>
        <h1 className="font-heading text-4xl font-semibold">Styleguide</h1>
        <p className="max-w-prose text-muted">
          The shared design system in <code>src/shared/components/ui</code>. Import from{' '}
          <code>@/shared/components/ui</code>.
        </p>
        <nav aria-label="Sections">
          <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
            {TOC.map(([id, label]) => (
              <li key={id}>
                <a href={`#${id}`} className="rounded text-brand-700 underline">
                  {label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main id="main" tabIndex={-1} className="grid gap-14 outline-none">
        <ActionsSection />
        <FormsSection />
        <FeedbackSection />
        <DataTableSection />
        <LayoutSection />
      </main>
    </div>
  )
}
