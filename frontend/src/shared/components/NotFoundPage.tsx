import { LinkButton } from './LinkButton'

export default function NotFoundPage() {
  return (
    <section className="container flex flex-col items-center gap-4 py-24 text-center">
      <title>Page not found | Amanah Connect</title>
      <p className="eyebrow">Error 404</p>
      <h1 className="text-4xl">We could not find that page</h1>
      <p className="max-w-prose text-muted">
        The link may be old or mistyped. Check the address, or head back to the home page.
      </p>
      <LinkButton to="/">Go to the home page</LinkButton>
    </section>
  )
}
