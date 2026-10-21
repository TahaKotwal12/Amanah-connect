import { useState } from 'react'
import { Link } from 'react-router'
import { z } from 'zod'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { useZodForm } from '@/shared/components/ui/form'
import { Input } from '@/shared/components/ui/input'
import { formatClock, useCountdown } from '@/shared/hooks/useCountdown'
import { requestPasswordReset } from '../api'
import { AuthAlert, AuthCard } from '../components'

const schema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address')
    .pipe(z.email('Enter a valid email address, such as name@example.com')),
})

export default function ForgotPasswordPage() {
  const form = useZodForm(schema, { mode: 'onSubmit', defaultValues: { email: '' } })
  const countdown = useCountdown()
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  if (sentTo) {
    // The same message whether or not the address has an account: this page proves nothing about it.
    return (
      <AuthCard title="Check your email">
        <p role="status" className="text-ink">
          If there is an account for <strong>{sentTo}</strong>, we have sent a link to choose a new
          password. It can take a few minutes to arrive, so look in your spam folder too. The link
          works once and expires after 30 minutes.
        </p>
        <Button asChild variant="secondary" size="lg">
          <Link to={paths.login}>Back to sign in</Link>
        </Button>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Forgot your password?"
      description="Enter your email address and we will send you a link to choose a new one."
    >
      <Form {...form}>
        <form
          noValidate
          className="grid gap-5"
          onSubmit={(event) => {
            setError(null)
            void form.handleSubmit(async ({ email }) => {
              try {
                await requestPasswordReset(email)
                setSentTo(email)
              } catch (failure) {
                const problem = toApiError(failure)
                if (problem.code === 'RATE_LIMITED' && problem.retryAfterSeconds) {
                  countdown.start(problem.retryAfterSeconds)
                }
                setError(describeError(problem))
              }
            })(event)
          }}
        >
          <FormField control={form.control} name="email" label="Email" required>
            {({ field, control }) => (
              <Input
                type="email"
                autoComplete="username"
                inputMode="email"
                {...field}
                {...control}
              />
            )}
          </FormField>
          {error ? <AuthAlert>{error}</AuthAlert> : null}
          <Button
            type="submit"
            size="lg"
            loading={form.formState.isSubmitting}
            disabled={countdown.active}
          >
            {countdown.active
              ? `Try again in ${formatClock(countdown.remaining)}`
              : 'Send the link'}
          </Button>
        </form>
      </Form>
      <p className="text-center text-sm">
        <Link to={paths.login} className="rounded font-semibold text-brand-700 underline">
          Back to sign in
        </Link>
      </p>
    </AuthCard>
  )
}
