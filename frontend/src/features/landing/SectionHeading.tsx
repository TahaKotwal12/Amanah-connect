import { cn } from '@/shared/utils/cn'

interface SectionHeadingProps {
  eyebrow: string
  title: string
  intro?: string
  /** On the deep-teal sections the colours flip. */
  onDark?: boolean
  align?: 'left' | 'center'
  id?: string
}

/** Cinzel eyebrow, Playfair heading, optional intro: the head of every landing section. */
export function SectionHeading({
  eyebrow,
  title,
  intro,
  onDark = false,
  align = 'center',
  id,
}: SectionHeadingProps) {
  return (
    <div className={cn('mx-auto grid max-w-2xl gap-3', align === 'center' && 'text-center')}>
      <p className={cn('eyebrow', onDark && 'text-gold-500')}>{eyebrow}</p>
      <h2
        id={id}
        className={cn(
          'font-heading text-3xl font-semibold sm:text-4xl',
          onDark ? 'text-white' : 'text-brand-900',
        )}
      >
        {title}
      </h2>
      {intro ? (
        <p className={cn('text-lg', onDark ? 'text-brand-100' : 'text-muted')}>{intro}</p>
      ) : null}
    </div>
  )
}
