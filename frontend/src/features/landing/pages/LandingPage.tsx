import { lazy } from 'react'
import { DeferredSection } from '../DeferredSection'
import { AboutIntro, Hero } from '../Hero'
import { MotionProvider } from '../motion'

// Everything below the first screen is fetched when the visitor is about to reach it.
const UseCases = lazy(() => import('../sections/UseCases'))
const WhoWeServe = lazy(() => import('../sections/WhoWeServe'))
const Features = lazy(() => import('../sections/Features'))
const Values = lazy(() => import('../sections/Values'))
const HowItWorks = lazy(() => import('../sections/HowItWorks'))
const Security = lazy(() => import('../sections/Security'))
const Pricing = lazy(() => import('../sections/Pricing'))
const Faq = lazy(() => import('../sections/Faq'))
const DemoForm = lazy(() => import('../sections/DemoForm'))
const Contact = lazy(() => import('../sections/Contact'))
const FinalCta = lazy(() => import('../sections/FinalCta'))

const TITLE = 'Amanah Connect | Community management with trust'
const DESCRIPTION =
  'Run membership, dues and donations, budgets, complaints and announcements for your community in one secure place. Members never need to log in.'
const siteUrl = import.meta.env.VITE_SITE_URL?.replace(/\/+$/, '')

export default function LandingPage() {
  return (
    <MotionProvider>
      <title>{TITLE}</title>
      <meta name="description" content={DESCRIPTION} />
      {siteUrl ? <link rel="canonical" href={`${siteUrl}/`} /> : null}

      <Hero />
      <AboutIntro />
      <DeferredSection id="solutions" minHeight={760}>
        <UseCases />
      </DeferredSection>
      <DeferredSection id="who-we-serve" minHeight={640}>
        <WhoWeServe />
      </DeferredSection>
      <DeferredSection id="features" minHeight={900}>
        <Features />
      </DeferredSection>
      <DeferredSection id="values" minHeight={520}>
        <Values />
      </DeferredSection>
      <DeferredSection id="how-it-works" minHeight={460}>
        <HowItWorks />
      </DeferredSection>
      <DeferredSection id="security" minHeight={640}>
        <Security />
      </DeferredSection>
      <DeferredSection id="pricing" minHeight={560}>
        <Pricing />
      </DeferredSection>
      <DeferredSection id="faq" minHeight={820}>
        <Faq />
      </DeferredSection>
      <DeferredSection id="demo" minHeight={760}>
        <DemoForm />
      </DeferredSection>
      <DeferredSection id="contact" minHeight={420}>
        <Contact />
      </DeferredSection>
      <DeferredSection id="start" minHeight={320}>
        <FinalCta />
      </DeferredSection>
    </MotionProvider>
  )
}
