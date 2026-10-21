import { useState } from 'react'
import { Link } from 'react-router'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { acceptInvite } from '../api'
import { AuthAlert, AuthCard, AuthNotice } from '../components'
import { NewPasswordForm } from '../NewPasswordForm'
import { useUrlToken } from '../useUrlToken'

export default function AcceptInvitePage() {
  const token = useUrlToken()
  const [linkRefused, setLinkRefused] = useState(false)
  const [accepted, setAccepted] = useState<{ mfaSetupRequired: boolean } | null>(null)

  if (accepted) {
    return (
      <AuthCard
        title="Your account is ready"
        description="Your password is set. Sign in to get started."
      >
        {accepted.mfaSetupRequired ? (
          <AuthNotice>
            <p>
              For your security you will be asked to set up two-step verification right after you
              sign in. Have your authenticator app ready.
            </p>
          </AuthNotice>
        ) : null}
        <Button asChild size="lg">
          <Link to={paths.login} state={{ reason: 'invite-accepted' }}>
            Go to sign in
          </Link>
        </Button>
      </AuthCard>
    )
  }

  if (!token || linkRefused) {
    return (
      <AuthCard
        title="This invitation cannot be used"
        description="Invitations work once and expire after 48 hours."
      >
        <AuthAlert>
          {token
            ? 'The invitation was already used or has expired.'
            : 'The link is incomplete or was changed.'}
        </AuthAlert>
        <p className="text-sm text-muted">
          Ask the person who invited you to send a new invitation. If you already set a password,
          you can{' '}
          <Link to={paths.login} className="font-semibold text-brand-700 underline">
            sign in
          </Link>{' '}
          now.
        </p>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Set your password"
      description="Choose a password to finish creating your account."
    >
      <NewPasswordForm
        submitLabel="Create my account"
        onInvalidToken={() => {
          setLinkRefused(true)
        }}
        onSubmit={async (password) => {
          setAccepted(await acceptInvite(token, password))
        }}
      />
    </AuthCard>
  )
}
