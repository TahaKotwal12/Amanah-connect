import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { paths } from '@/routes/paths'
import { PageSpinner } from '@/shared/components/PageSpinner'
import { Button } from '@/shared/components/ui/button'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { OtpInput } from '@/shared/components/ui/otp-input'
import { formatClock, useCountdown } from '@/shared/hooks/useCountdown'
import { useAuthStore, type MfaChallenge } from '@/store/authStore'
import { loginWithSecondFactor } from '../api'
import { AuthAlert, AuthCard } from '../components'
import { useFinishLogin } from '../useFinishLogin'

/** Only reachable after a correct password on an account with 2FA: no challenge, back to sign in. */
export default function TwoFactorChallengePage() {
  const mfa = useAuthStore((state) => state.mfa)
  const signedIn = useAuthStore((state) => state.accessToken !== null)
  // The code was accepted: the store has swapped the challenge for a token, and the login code is
  // about to navigate to the dashboard. Staying quiet here keeps this page from redirecting to
  // sign in on the way out (which would lose where the person was heading).
  if (mfa.status !== 'challenge' && signedIn) return <PageSpinner label="Signing you in" />
  if (mfa.status !== 'challenge') return <Navigate to={paths.login} replace />
  return <Challenge challenge={mfa.challenge} />
}

function Challenge({ challenge }: { challenge: MfaChallenge }) {
  const navigate = useNavigate()
  const location = useLocation()
  const finishLogin = useFinishLogin()
  const countdown = useCountdown()
  const [mode, setMode] = useState<'code' | 'recovery'>('code')
  const [recovery, setRecovery] = useState('')
  const [otpKey, setOtpKey] = useState(0) // a new key clears the six boxes after a wrong code
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /**
   * Leave the second step. The sign-in page throws away the unfinished challenge when it appears, so
   * nothing here clears it: doing that from this page would make its own "no challenge" redirect fire
   * first, and that redirect carries no message.
   */
  function backToSignIn(reason?: 'mfa-expired') {
    const kept: unknown = location.state
    void navigate(paths.login, { replace: true, state: reason ? { reason } : kept })
  }

  // The challenge lives five minutes. When it ends, say so rather than letting the next try fail.
  useEffect(() => {
    const wait = Math.max(0, challenge.expiresAt - Date.now())
    const timer = setTimeout(() => {
      void navigate(paths.login, { replace: true, state: { reason: 'mfa-expired' } })
    }, wait)
    return () => {
      clearTimeout(timer)
    }
  }, [challenge.expiresAt, navigate])

  async function submit(proof: { code: string } | { recoveryCode: string }) {
    setBusy(true)
    setError(null)
    try {
      await finishLogin(await loginWithSecondFactor({ mfaToken: challenge.mfaToken, ...proof }))
    } catch (failure) {
      const problem = toApiError(failure)
      if (problem.code === 'UNAUTHENTICATED' || problem.code === 'INVALID_TOKEN') {
        backToSignIn('mfa-expired') // the challenge itself is no longer valid
        return
      }
      if (
        (problem.code === 'RATE_LIMITED' || problem.code === 'ACCOUNT_LOCKED') &&
        problem.retryAfterSeconds
      ) {
        countdown.start(problem.retryAfterSeconds)
      }
      setError(describeError(problem))
      setOtpKey((key) => key + 1)
    } finally {
      setBusy(false)
    }
  }

  const blocked = busy || countdown.active

  return (
    <AuthCard
      title="Two-step verification"
      description={
        mode === 'code'
          ? 'Enter the 6-digit code from your authenticator app.'
          : 'Enter one of the recovery codes you saved when you set up two-step verification. Each works once.'
      }
    >
      {mode === 'code' ? (
        <div className="grid gap-3">
          <OtpInput
            key={otpKey}
            label="Authenticator code"
            focusOnMount
            disabled={blocked}
            invalid={error !== null}
            onComplete={(code) => void submit({ code })}
          />
          <p className="text-sm text-muted">
            We check the code as soon as you type the sixth digit.
          </p>
        </div>
      ) : (
        <form
          className="grid gap-3"
          onSubmit={(event) => {
            event.preventDefault()
            if (recovery.trim()) void submit({ recoveryCode: recovery.trim() })
          }}
        >
          <Label htmlFor="recovery-code">Recovery code</Label>
          <Input
            id="recovery-code"
            value={recovery}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            aria-invalid={error !== null || undefined}
            onChange={(event) => {
              setRecovery(event.target.value)
            }}
          />
          <Button
            type="submit"
            size="lg"
            loading={busy}
            disabled={countdown.active || !recovery.trim()}
          >
            {countdown.active ? `Try again in ${formatClock(countdown.remaining)}` : 'Verify'}
          </Button>
        </form>
      )}

      {error ? <AuthAlert>{error}</AuthAlert> : null}
      {mode === 'code' && countdown.active ? (
        <p role="status" className="text-sm font-medium text-ink">
          Try again in {formatClock(countdown.remaining)}.
        </p>
      ) : null}

      <div className="grid gap-1 text-center text-sm">
        <button
          type="button"
          className="min-h-11 rounded font-semibold text-brand-700 underline"
          onClick={() => {
            setMode(mode === 'code' ? 'recovery' : 'code')
            setError(null)
            setRecovery('')
          }}
        >
          {mode === 'code' ? 'Use a recovery code instead' : 'Use my authenticator app instead'}
        </button>
        <button
          type="button"
          className="min-h-11 rounded text-muted underline"
          onClick={() => {
            backToSignIn()
          }}
        >
          Back to sign in
        </button>
      </div>
    </AuthCard>
  )
}
