import { useState } from 'react'
import { z } from 'zod'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { checkPassword } from '@/lib/passwordPolicy'
import { Button } from '@/shared/components/ui/button'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { useZodForm } from '@/shared/components/ui/form'
import { PasswordInput } from '@/shared/components/ui/password-input'
import { PasswordStrength } from '@/shared/components/ui/password-strength'
import { formatClock, useCountdown } from '@/shared/hooks/useCountdown'
import { AuthAlert } from './components'

const schema = z
  .object({
    password: z
      .string()
      .min(1, 'Enter a new password')
      .refine((value) => checkPassword(value).ok, 'Choose a password that meets the rules below'),
    confirm: z.string().min(1, 'Type the new password again'),
  })
  .refine((values) => values.password === values.confirm, {
    path: ['confirm'],
    message: 'The two passwords do not match',
  })

interface NewPasswordFormProps {
  submitLabel: string
  /** Send the password. Throw the API error to have it explained here. */
  onSubmit: (password: string) => Promise<void>
  /** The link's token was refused (used, expired or wrong): the page offers a way forward. */
  onInvalidToken: () => void
}

/** Choose a password twice, with live strength feedback. Shared by "reset" and "accept invitation". */
export function NewPasswordForm({ submitLabel, onSubmit, onInvalidToken }: NewPasswordFormProps) {
  const form = useZodForm(schema, { defaultValues: { password: '', confirm: '' } })
  const countdown = useCountdown()
  const [error, setError] = useState<{ message: string; problems: string[] } | null>(null)
  const password = form.watch('password')

  return (
    <Form {...form}>
      <form
        noValidate
        className="grid gap-5"
        onSubmit={(event) => {
          setError(null)
          void form.handleSubmit(async (values) => {
            try {
              await onSubmit(values.password)
            } catch (failure) {
              const problem = toApiError(failure)
              if (problem.code === 'INVALID_TOKEN') {
                onInvalidToken()
                return
              }
              if (problem.code === 'RATE_LIMITED' && problem.retryAfterSeconds) {
                countdown.start(problem.retryAfterSeconds)
              }
              // Policy failures come back as a list of plain sentences; show them all.
              setError({ message: describeError(problem), problems: problem.problems })
            }
          })(event)
        }}
      >
        <div className="grid gap-3">
          <FormField control={form.control} name="password" label="New password" required>
            {({ field, control }) => (
              <PasswordInput autoComplete="new-password" {...field} {...control} />
            )}
          </FormField>
          <PasswordStrength password={password} />
        </div>
        <FormField control={form.control} name="confirm" label="Confirm new password" required>
          {({ field, control }) => (
            <PasswordInput
              autoComplete="new-password"
              capsLockHint={false}
              {...field}
              {...control}
            />
          )}
        </FormField>
        {error ? (
          <AuthAlert>
            <p>{error.message}</p>
            {error.problems.length > 0 ? (
              <ul className="ml-4 list-disc font-normal">
                {error.problems.map((problem) => (
                  <li key={problem}>{problem}</li>
                ))}
              </ul>
            ) : null}
          </AuthAlert>
        ) : null}
        <Button
          type="submit"
          size="lg"
          loading={form.formState.isSubmitting}
          disabled={countdown.active}
        >
          {countdown.active ? `Try again in ${formatClock(countdown.remaining)}` : submitLabel}
        </Button>
      </form>
    </Form>
  )
}
