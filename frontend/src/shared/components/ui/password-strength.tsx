import { Check, Circle } from 'lucide-react'
import { checkPassword } from '@/lib/passwordPolicy'
import { cn } from '@/shared/utils/cn'

const BAR = ['bg-danger', 'bg-warning', 'bg-gold-500', 'bg-success'] as const

interface PasswordStrengthProps {
  password: string
  /** The person's email, so a password built from it is called out as the server would. */
  email?: string
  className?: string
}

/**
 * A four-segment meter with the verdict in words (never colour alone) and the rules still unmet.
 * The verdict is announced politely as it changes.
 */
export function PasswordStrength({ password, email, className }: PasswordStrengthProps) {
  const result = checkPassword(password, email)
  return (
    <div className={cn('grid gap-2', className)}>
      <div aria-hidden="true" className="flex gap-1">
        {[1, 2, 3, 4].map((segment) => (
          <span
            key={segment}
            className={cn(
              'h-1.5 flex-1 rounded-full',
              result.score >= segment ? BAR[Math.max(0, result.score - 1)] : 'bg-brand-100',
            )}
          />
        ))}
      </div>
      <p role="status" aria-live="polite" className="min-h-5 text-sm font-medium text-ink">
        {result.label ? `Password strength: ${result.label}` : null}
      </p>
      {password !== '' ? (
        <ul className="grid gap-1 text-sm">
          {result.ok ? (
            <li className="flex items-start gap-2 text-success-700">
              <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              This password meets the rules.
            </li>
          ) : (
            result.problems.map((problem) => (
              <li key={problem} className="flex items-start gap-2 text-muted">
                <Circle aria-hidden="true" className="mt-1 size-3 shrink-0" />
                {problem}
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  )
}
