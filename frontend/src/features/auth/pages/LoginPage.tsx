import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { z } from 'zod'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { useZodForm } from '@/shared/components/ui/form'
import { Input } from '@/shared/components/ui/input'
import { PasswordInput } from '@/shared/components/ui/password-input'
import { formatClock, useCountdown } from '@/shared/hooks/useCountdown'
import { authStore, useAuthStore } from '@/store/authStore'
import { login } from '../api'
import { AuthAlert, AuthCard, AuthNotice } from '../components'
import { reasonOf, type LoginReason } from '../loginFlow'
import { useFinishLogin } from '../useFinishLogin'

// The password is only checked for presence: the password rules are not repeated here, so this page
// reveals nothing about them or about which accounts exist.
const schema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address')
    .pipe(z.email('Enter a valid email address, such as name@example.com')),
  password: z.string().min(1, 'Enter your password'),
})

const REASON_TEXT: Record<LoginReason, string> = {
  'session-expired': 'Your session ended, so we signed you out. Please sign in again.',
  'mfa-expired':
    'That took a little too long, so the verification step timed out. Please sign in again.',
  'password-reset': 'Your password has been changed. Sign in with the new one.',
  'invite-accepted': 'Your account is ready. Sign in to get started.',
}

export default function LoginPage() {
  const location = useLocation()
  const finishLogin = useFinishLogin()
  const sessionExpired = useAuthStore((state) => state.sessionExpired)
  const countdown = useCountdown()
  const [formError, setFormError] = useState<string | null>(null)

  // Validate on submit, not on leaving a box: the email box is focused on arrival, and an error that
  // appeared when someone pressed "Forgot your password?" would push the link away from under the mouse.
  const form = useZodForm(schema, { mode: 'onSubmit', defaultValues: { email: '', password: '' } })
  const { setFocus } = form

  useEffect(() => {
    setFocus('email')
  }, [setFocus])

  // Arriving here means starting over: an unfinished second step from before is dropped.
  useEffect(() => {
    authStore.getState().clearMfaChallenge()
  }, [])

  const reason: LoginReason | null = sessionExpired ? 'session-expired' : reasonOf(location.state)

  async function submit(values: { email: string; password: string }) {
    setFormError(null)
    try {
      await finishLogin(await login(values))
    } catch (error) {
      const problem = toApiError(error)
      if (
        (problem.code === 'RATE_LIMITED' || problem.code === 'ACCOUNT_LOCKED') &&
        problem.retryAfterSeconds
      ) {
        countdown.start(problem.retryAfterSeconds)
      }
      // The same words for a wrong password and for an address that has no account.
      setFormError(describeError(problem))
      if (problem.code === 'INVALID_CREDENTIALS') form.resetField('password')
    }
  }

  return (
    <AuthCard title="Sign in" description="Welcome back. Sign in to manage your community.">
      {reason && !formError ? <AuthNotice>{REASON_TEXT[reason]}</AuthNotice> : null}
      <Form {...form}>
        <form
          noValidate
          className="grid gap-5"
          onSubmit={(event) => {
            void form.handleSubmit(submit)(event)
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
          <FormField control={form.control} name="password" label="Password" required>
            {({ field, control }) => (
              <PasswordInput autoComplete="current-password" {...field} {...control} />
            )}
          </FormField>
          {formError ? <AuthAlert>{formError}</AuthAlert> : null}
          <Button
            type="submit"
            size="lg"
            loading={form.formState.isSubmitting}
            disabled={countdown.active}
          >
            {countdown.active ? `Try again in ${formatClock(countdown.remaining)}` : 'Sign in'}
          </Button>
        </form>
      </Form>
      <p className="text-center text-sm">
        <Link to={paths.forgotPassword} className="rounded font-semibold text-brand-700 underline">
          Forgot your password?
        </Link>
      </p>
    </AuthCard>
  )
}
