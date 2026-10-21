import { useQuery } from '@tanstack/react-query'
import { Copy, Download } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { downloadText } from '@/lib/download'
import { describeError } from '@/lib/errors'
import { refreshAccessToken } from '@/lib/axios'
import { loadCurrentUser, logout } from '@/lib/session'
import { homePathFor, paths } from '@/routes/paths'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { CopyField } from '@/shared/components/ui/copy-field'
import { ErrorState } from '@/shared/components/ui/error-state'
import { OtpInput } from '@/shared/components/ui/otp-input'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { enableTwoFactor, startTwoFactorSetup } from '../api'
import { AuthAlert, AuthCard, AuthNotice } from '../components'
import { authKeys } from '../queryKeys'
import { destinationAfterLogin } from '../loginFlow'

export default function TwoFactorSetupPage() {
  // Held only in this component: the server shows recovery codes once, so they must not outlive the
  // page in any cache (a mutation cache would keep them for minutes).
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null)
  if (recoveryCodes) return <RecoveryCodes codes={recoveryCodes} />
  return <Scan onEnabled={setRecoveryCodes} />
}

function Scan({ onEnabled }: { onEnabled: (codes: string[]) => void }) {
  const navigate = useNavigate()
  const role = useAuthStore((state) => state.user?.role)
  const [otpKey, setOtpKey] = useState(0)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Starting setup creates a fresh secret on the server, so it must run once: a query is shared and
  // cached, which survives React's double-invoked effects in development.
  const setup = useQuery({
    queryKey: authKeys.twoFactorSetup(),
    queryFn: startTwoFactorSetup,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    meta: { silent: true },
  })

  async function verify(digits: string) {
    setBusy(true)
    setError(null)
    try {
      const { recoveryCodes } = await enableTwoFactor(digits)
      onEnabled(recoveryCodes)
    } catch (failure) {
      const problem = toApiError(failure)
      if (problem.code === 'MFA_SETUP_NOT_STARTED') void setup.refetch()
      setError(describeError(problem))
      setOtpKey((key) => key + 1)
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  if (setup.isPending) {
    return (
      <AuthCard title="Set up two-step verification">
        <div role="status" aria-label="Preparing your setup key" className="grid gap-3">
          <Skeleton className="mx-auto size-44" />
          <Skeleton className="h-11" />
        </div>
      </AuthCard>
    )
  }

  if (setup.isError) {
    const problem = toApiError(setup.error)
    if (problem.code === 'MFA_ALREADY_ENABLED') {
      return (
        <AuthCard title="Two-step verification is already on">
          <AuthNotice>
            Your account already uses an authenticator app, so there is nothing to set up.
          </AuthNotice>
          <Button
            size="lg"
            onClick={() => {
              void navigate(role ? homePathFor(role) : paths.login, { replace: true })
            }}
          >
            Continue
          </Button>
        </AuthCard>
      )
    }
    return (
      <AuthCard title="Set up two-step verification">
        <ErrorState
          title="We could not start the setup"
          error={problem}
          onRetry={() => void setup.refetch()}
          retrying={setup.isFetching}
        />
        <SignOutLink />
      </AuthCard>
    )
  }

  const { secret, otpauthUri } = setup.data
  return (
    <AuthCard
      title="Set up two-step verification"
      description="Two-step verification asks for a code from your phone each time you sign in, so a stolen password is not enough."
    >
      <ol className="grid gap-5 text-ink">
        <li className="grid gap-3">
          <p>
            <strong>1.</strong> Open an authenticator app (such as Google Authenticator, Microsoft
            Authenticator or Authy) and scan this code.
          </p>
          <div className="mx-auto rounded bg-white p-3 ring-1 ring-brand-100">
            <QRCodeSVG
              value={otpauthUri}
              size={176}
              marginSize={0}
              level="M"
              role="img"
              aria-label="QR code to add Amanah Connect to your authenticator app"
            />
          </div>
          <details className="text-sm">
            <summary className="min-h-11 cursor-pointer py-2 font-semibold text-brand-700">
              Cannot scan? Enter the key by hand
            </summary>
            <CopyField label="Setup key" value={secret} />
          </details>
        </li>
        <li className="grid gap-3">
          <p>
            <strong>2.</strong> Enter the 6-digit code the app shows.
          </p>
          <OtpInput
            key={otpKey}
            label="Authenticator code"
            focusOnMount
            disabled={busy}
            invalid={error !== null}
            onChange={setCode}
          />
        </li>
      </ol>
      {error ? <AuthAlert>{error}</AuthAlert> : null}
      <Button
        size="lg"
        loading={busy}
        disabled={code.length !== 6}
        onClick={() => void verify(code)}
      >
        Turn on two-step verification
      </Button>
      <SignOutLink />
    </AuthCard>
  )
}

function RecoveryCodes({ codes }: { codes: string[] }) {
  const navigate = useNavigate()
  const [saved, setSaved] = useState(false)
  const [copied, setCopied] = useState<'idle' | 'ok' | 'failed'>('idle')
  const [finishing, setFinishing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // These codes cannot be fetched again. Make leaving by accident hard until they are confirmed saved.
  useEffect(() => {
    if (saved) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => {
      window.removeEventListener('beforeunload', warn)
    }
  }, [saved])

  async function copyAll() {
    try {
      await navigator.clipboard.writeText(codes.join('\n'))
      setCopied('ok')
    } catch {
      setCopied('failed')
    }
  }

  async function finish() {
    setFinishing(true)
    setError(null)
    try {
      // 2FA is on now; a fresh access token has no "setup required" restriction.
      await refreshAccessToken()
      const me = await loadCurrentUser()
      await navigate(
        destinationAfterLogin({ role: me.user.role, mfaSetupRequired: me.flags.mfaSetupRequired }),
        { replace: true },
      )
    } catch (failure) {
      setError(`${describeError(failure)} Sign in again to continue.`)
      setFinishing(false)
    }
  }

  return (
    <AuthCard
      title="Save your recovery codes"
      description="If you lose your phone, each of these codes lets you sign in once. They are shown only now."
    >
      <AuthNotice>
        <p>Store them somewhere safe, such as a password manager. We cannot show them again.</p>
      </AuthNotice>
      <ul
        aria-label="Recovery codes"
        className="grid grid-cols-2 gap-2 rounded border border-brand-100 bg-brand-50 p-4 font-mono text-base tracking-wide text-ink"
      >
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-3">
        <Button variant="secondary" onClick={() => void copyAll()}>
          <Copy aria-hidden="true" className="size-4" />
          Copy codes
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            downloadText(
              'amanah-connect-recovery-codes.txt',
              `Amanah Connect recovery codes\nEach code works once.\nKeep this file somewhere safe.\n\n${codes.join('\n')}\n`,
            )
          }}
        >
          <Download aria-hidden="true" className="size-4" />
          Download
        </Button>
      </div>
      <p role="status" aria-live="polite" className="min-h-5 text-sm text-muted">
        {copied === 'ok' ? 'Copied to the clipboard.' : null}
        {copied === 'failed' ? 'Could not copy. Select the codes and copy them by hand.' : null}
      </p>
      <Checkbox
        label="I have saved these recovery codes somewhere safe"
        checked={saved}
        onCheckedChange={(checked) => {
          setSaved(checked === true)
        }}
      />
      {error ? <AuthAlert>{error}</AuthAlert> : null}
      <Button size="lg" disabled={!saved} loading={finishing} onClick={() => void finish()}>
        Continue
      </Button>
    </AuthCard>
  )
}

function SignOutLink() {
  const navigate = useNavigate()
  return (
    <p className="text-center text-sm">
      <button
        type="button"
        className="min-h-11 rounded text-muted underline"
        onClick={() => {
          void logout().then(() => navigate(paths.login, { replace: true }))
        }}
      >
        Sign out
      </button>
    </p>
  )
}
