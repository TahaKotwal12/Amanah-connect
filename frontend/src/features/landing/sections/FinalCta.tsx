import { Link } from 'react-router'
import { Button } from '@/shared/components/ui/button'
import { DEMO_ID } from '../nav'

export default function FinalCta() {
  return (
    <section
      aria-labelledby="final-cta-title"
      className="bg-gradient-to-br from-brand-950 via-brand-900 to-brand-800 py-16 text-white sm:py-20"
    >
      <div className="container grid justify-items-center gap-6 text-center">
        <h2
          id="final-cta-title"
          className="max-w-2xl font-heading text-3xl font-semibold text-white sm:text-4xl"
        >
          Ready to run your community with more clarity?
        </h2>
        <p className="max-w-xl text-lg text-brand-100">
          Request a demo and we will show you how it would work for your members and your records.
        </p>
        <Button asChild variant="premium" size="lg">
          <Link to={`/#${DEMO_ID}`}>Request a demo</Link>
        </Button>
      </div>
    </section>
  )
}
