import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { authStore } from '@/store/authStore'
import { resetPassword } from '../api'
import { AuthAlert, AuthCard } from '../components'
import { NewPasswordForm } from '../NewPasswordForm'
import { useUrlToken } from '../useUrlToken'

export default function ResetPasswordPage() {
  const token = useUrlToken()
  const navigate = useNavigate()
  const [linkRefused, setLinkRefused] = useState(false)

  if (!token || linkRefused) {
    return (
      <AuthCard
        title="This link cannot be used"
        description="Reset links work once and expire after 30 minutes."
      >
        <AuthAlert>
          {token
            ? 'The link was already used or has expired.'
            : 'The link is incomplete or was changed.'}
        </AuthAlert>
        <Button asChild size="lg">
          <Link to={paths.forgotPassword}>Send me a new link</Link>
        </Button>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Choose a new password"
      description="For your safety you will be asked to sign in again afterwards."
    >
      <NewPasswordForm
        submitLabel="Change password"
        onInvalidToken={() => {
          setLinkRefused(true)
        }}
        onSubmit={async (password) => {
          await resetPassword(token, password)
          // The server ended every session and cleared the cookie; forget anything held here too.
          authStore.getState().clearSession()
          await navigate(paths.login, { replace: true, state: { reason: 'password-reset' } })
        }}
      />
    </AuthCard>
  )
}
