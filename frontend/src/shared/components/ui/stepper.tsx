import { Check } from 'lucide-react'
import { cn } from '@/shared/utils/cn'

export interface Step {
  id: string
  label: string
  description?: string
}

interface StepperProps {
  steps: readonly Step[]
  /** Index of the current step, zero-based. Earlier steps show as done. */
  current: number
  className?: string
  /** Label for the list, e.g. "Create community". */
  label?: string
}

/** Progress through a wizard. Not interactive: the form's own Back and Next buttons move it. */
export function Stepper({ steps, current, className, label = 'Progress' }: StepperProps) {
  return (
    <nav aria-label={label} className={className}>
      <ol className="flex items-start gap-2">
        {steps.map((step, index) => {
          const done = index < current
          const active = index === current
          return (
            <li
              key={step.id}
              aria-current={active ? 'step' : undefined}
              className="flex min-w-0 flex-1 flex-col gap-2"
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    'flex size-8 shrink-0 items-center justify-center rounded-full border text-sm font-semibold',
                    done && 'border-brand-600 bg-brand-600 text-white',
                    active && 'border-gold-500 bg-gold-50 text-brand-900 ring-2 ring-gold-500',
                    !done && !active && 'border-muted bg-white text-muted',
                  )}
                >
                  {done ? <Check aria-hidden="true" className="size-4" /> : index + 1}
                </span>
                {index < steps.length - 1 ? (
                  <span
                    aria-hidden="true"
                    className={cn('h-0.5 flex-1', done ? 'bg-brand-600' : 'bg-brand-100')}
                  />
                ) : null}
              </div>
              <div className="min-w-0">
                <p
                  className={cn('text-sm', active ? 'font-semibold text-brand-900' : 'text-muted')}
                >
                  {step.label}
                  <span className="sr-only">
                    {done ? ' (completed)' : active ? ' (current step)' : ''}
                  </span>
                </p>
                {step.description ? (
                  <p className="hidden text-xs text-muted sm:block">{step.description}</p>
                ) : null}
              </div>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
