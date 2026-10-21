import { LazyMotion, MotionConfig, m } from 'framer-motion'
import type { ReactNode } from 'react'

// The animation features load as a separate small chunk, after first paint.
const loadFeatures = () => import('./motionFeatures').then((module) => module.default)

/** Subtle fade-and-slide only. `reducedMotion="user"` drops the movement for people who ask for less. */
export function MotionProvider({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={loadFeatures} strict>
      <MotionConfig reducedMotion="user">{children}</MotionConfig>
    </LazyMotion>
  )
}

interface RevealProps {
  children: ReactNode
  className?: string
  /** Seconds, to stagger siblings a little. */
  delay?: number
}

/** Fades and lifts 12 px once, when scrolled into view. Without IntersectionObserver it just shows. */
export function Reveal({ children, className, delay = 0 }: RevealProps) {
  if (typeof IntersectionObserver === 'undefined')
    return <div className={className}>{children}</div>
  return (
    <m.div
      className={className}
      initial={{ opacity: 0, y: 12 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '0px 0px -60px 0px' }}
      transition={{ duration: 0.4, delay, ease: 'easeOut' }}
    >
      {children}
    </m.div>
  )
}
