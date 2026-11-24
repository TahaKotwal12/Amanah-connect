import { Copy, Download } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { downloadText } from '@/lib/download'
import { describeError } from '@/lib/errors'
import { notify } from '@/lib/notify'
import { checkPassword } from '@/lib/passwordPolicy'
import { loadCurrentUser } from '@/lib/session'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { CopyField } from '@/shared/components/ui/copy-field'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Label } from '@/shared/components/ui/label'
import { OtpInput } from '@/shared/components/ui/otp-input'
import { PasswordInput } from '@/shared/components/ui/password-input'
import { PasswordStrength } from '@/shared/components/ui/password-strength'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { useAuthStore } from '@/store/authStore'
import { enableTwoFactor, startTwoFactorSetup } from '../../../auth/api'
import { useChangePassword, useDisableTwoFactor } from '../../hooks/useSettingsActions'

/** Change the password and turn two-step verification on or off. */
export function SecurityTab() {
  return (
    <div className="grid max-w-2xl gap-10">
      <ChangePassword />
      <TwoFactor />
    </div>
  )
}

// ---- change password --------------------------------------------------------------------------

function ChangePassword() {
  const email = useAuthStore((state) => state.user?.email)
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [error, setError] = useState<string | null>(null)

  const policy = checkPassword(next, email)
  const mismatch = again !== '' && again !== next
  const ready = current !== '' && policy.ok && again === next

  async function submit() {
    setError(null)
    try {
      await change.mutateAsync({ currentPassword: current, newPassword: next })
      setCurrent('')
      setNext('')
      setAgain('')
      notify.success('Your password was changed')
    } catch (failure) {
      const problem = toApiError(failure)
      setError(problem.problems.length > 0 ? problem.problems.join(' ') : describeError(problem))
    }
  }

  return (
    <section aria-labelledby="password-heading">
      <h2 id="password-heading" className="font-heading text-lg font-semibold text-brand-900">
        Change your password
      </h2>
      <form
        noValidate
        className="mt-4 grid gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          if (ready) void submit()
        }}
      >
        <div className="grid gap-1.5">
          <Label htmlFor="current-password">Current password</Label>
          <PasswordInput
            id="current-password"
            autoComplete="current-password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="new-password">New password</Label>
          <PasswordInput
            id="new-password"
            autoComplete="new-password"
            value={next}
            onChange={(event) => setNext(event.target.value)}
            aria-describedby="new-password-strength"
          />
          <div id="new-password-strength">
            <PasswordStrength password={next} {...(email ? { email } : {})} />
          </div>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="repeat-password">Type the new password again</Label>
          <PasswordInput
            id="repeat-password"
            autoComplete="new-password"
            value={again}
            onChange={(event) => setAgain(event.target.value)}
            aria-invalid={mismatch || undefined}
            capsLockHint={false}
          />
          {mismatch ? (
            <p role="alert" className="text-sm text-danger">
              The two passwords are not the same.
            </p>
          ) : null}
        </div>
        {error ? (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : null}
        <div>
          <Button type="submit" loading={change.isPending} disabled={!ready}>
            Change the password
          </Button>
        </div>
      </form>
    </section>
  )
}

// ---- two-step verification --------------------------------------------------------------------

function TwoFactor() {
  const enabled = useAuthStore((state) => state.totpEnabled)
  const required = useAuthStore((state) => state.mfaMandatory)
  const [turningOn, setTurningOn] = useState(false)
  const [turningOff, setTurningOff] = useState(false)

  return (
    <section aria-labelledby="mfa-heading">
      <h2 id="mfa-heading" className="font-heading text-lg font-semibold text-brand-900">
        Two-step verification
      </h2>
      <p className="mt-2 text-sm text-muted">
        Asks for a code from an authenticator app on your phone when you sign in, so a stolen
        password is not enough.
      </p>
      <p
        role="status"
        className="mt-3 inline-flex rounded bg-brand-50 px-3 py-1 text-sm font-semibold text-brand-900"
      >
        {enabled ? 'On' : 'Off'}
      </p>
      <div className="mt-3">
        {enabled ? (
          required ? (
            <p className="text-sm text-muted">
              The platform requires two-step verification, so it cannot be turned off.
            </p>
          ) : (
            <Button variant="secondary" onClick={() => setTurningOff(true)}>
              Turn off
            </Button>
          )
        ) : (
          <Button onClick={() => setTurningOn(true)}>Turn on</Button>
        )}
      </div>
      <TurnOnDialog open={turningOn} onOpenChange={setTurningOn} />
      <TurnOffDialog open={turningOff} onOpenChange={setTurningOff} />
    </section>
  )
}

function TurnOnDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  // Mounted only while open, so every opening starts a fresh setup (a new secret) from nothing.
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        {open ? <TurnOnBody onClose={() => onOpenChange(false)} /> : null}
      </DialogContent>
    </Dialog>
  )
}

function TurnOnBody({ onClose }: { onClose: () => void }) {
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)
  const [code, setCode] = useState('')
  const [otpKey, setOtpKey] = useState(0)
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)

  async function start() {
    setStarting(true)
    setError(null)
    try {
      setSetup(await startTwoFactorSetup())
    } catch (failure) {
      setError(describeError(failure))
    } finally {
      setStarting(false)
    }
  }

  async function verify(digits: string) {
    setBusy(true)
    setError(null)
    try {
      const { recoveryCodes } = await enableTwoFactor(digits)
      setCodes(recoveryCodes)
      await loadCurrentUser() // the shell and this tab now know it is on
    } catch (failure) {
      setError(describeError(failure))
      setOtpKey((key) => key + 1)
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  if (codes) {
    return (
      <>
        <DialogHeader>
          <DialogTitle>Save your recovery codes</DialogTitle>
          <DialogDescription>
            Each code works once if you lose your phone. They are shown only now: the server cannot
            show them again.
          </DialogDescription>
        </DialogHeader>
        <ul
          aria-label="Recovery codes"
          className="grid grid-cols-2 gap-2 rounded bg-brand-50 p-3 font-mono text-sm"
        >
          {codes.map((recovery) => (
            <li key={recovery}>{recovery}</li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            onClick={() =>
              void navigator.clipboard
                .writeText(codes.join('\n'))
                .then(() => notify.success('The codes were copied'))
                .catch(() =>
                  notify.error('Could not copy. Select the codes and copy them by hand.'),
                )
            }
          >
            <Copy aria-hidden="true" className="size-4" />
            Copy
          </Button>
          <Button
            variant="secondary"
            onClick={() => downloadText('amanah-recovery-codes.txt', `${codes.join('\n')}\n`)}
          >
            <Download aria-hidden="true" className="size-4" />
            Download
          </Button>
        </div>
        <Checkbox
          label="I have saved these codes somewhere safe"
          checked={saved}
          onCheckedChange={(checked) => setSaved(checked === true)}
        />
        <DialogFooter>
          <Button disabled={!saved} onClick={onClose}>
            Done
          </Button>
        </DialogFooter>
      </>
    )
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Turn on two-step verification</DialogTitle>
        <DialogDescription>
          Use an authenticator app such as Google Authenticator, Microsoft Authenticator or Authy.
        </DialogDescription>
      </DialogHeader>
      {setup ? (
        <div className="grid gap-4">
          <div className="mx-auto rounded bg-white p-3 ring-1 ring-brand-100">
            <QRCodeSVG
              value={setup.otpauthUri}
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
            <CopyField label="Setup key" value={setup.secret} />
          </details>
          <div className="grid gap-2">
            <p className="text-sm">Enter the 6-digit code the app shows.</p>
            <OtpInput
              key={otpKey}
              label="Authenticator code"
              focusOnMount
              disabled={busy}
              invalid={error !== null}
              onChange={setCode}
            />
          </div>
          {error ? (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button variant="secondary" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button loading={busy} disabled={code.length !== 6} onClick={() => void verify(code)}>
              Turn on
            </Button>
          </DialogFooter>
        </div>
      ) : starting ? (
        <Skeleton className="mx-auto size-44" role="status" aria-label="Preparing your setup key" />
      ) : (
        <>
          {error ? (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={() => void start()}>Start</Button>
          </DialogFooter>
        </>
      )}
    </>
  )
}

function TurnOffDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const disable = useDisableTwoFactor()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [otpKey, setOtpKey] = useState(0)
  const [useRecovery, setUseRecovery] = useState(false)
  const [recovery, setRecovery] = useState('')
  const [error, setError] = useState<string | null>(null)

  const proof = useRecovery ? recovery.trim() !== '' : code.length === 6
  async function submit() {
    setError(null)
    try {
      await disable.mutateAsync({
        password,
        ...(useRecovery ? { recoveryCode: recovery.trim() } : { code }),
      })
      await loadCurrentUser()
      notify.success('Two-step verification is off')
      onOpenChange(false)
      setPassword('')
      setCode('')
      setRecovery('')
    } catch (failure) {
      setError(describeError(failure))
      setOtpKey((key) => key + 1)
      setCode('')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Turn off two-step verification?</DialogTitle>
          <DialogDescription>
            Your account will be protected by the password alone. Enter your password and a code to
            confirm it is you.
          </DialogDescription>
        </DialogHeader>
        <form
          noValidate
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (password !== '' && proof) void submit()
          }}
        >
          <div className="grid gap-1.5">
            <Label htmlFor="off-password">Password</Label>
            <PasswordInput
              id="off-password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          {useRecovery ? (
            <div className="grid gap-1.5">
              <Label htmlFor="off-recovery">Recovery code</Label>
              <PasswordInput
                id="off-recovery"
                autoComplete="off"
                capsLockHint={false}
                value={recovery}
                onChange={(event) => setRecovery(event.target.value)}
              />
            </div>
          ) : (
            <div className="grid gap-1.5">
              <p className="text-sm font-medium text-ink">Authenticator code</p>
              <OtpInput
                key={otpKey}
                label="Authenticator code"
                disabled={disable.isPending}
                invalid={error !== null}
                onChange={setCode}
              />
            </div>
          )}
          <button
            type="button"
            className="min-h-11 justify-self-start text-sm font-semibold text-brand-700 underline"
            onClick={() => setUseRecovery((on) => !on)}
          >
            {useRecovery ? 'Use an authenticator code instead' : 'Use a recovery code instead'}
          </button>
          {error ? (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="danger"
              loading={disable.isPending}
              disabled={password === '' || !proof}
            >
              Turn off
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
