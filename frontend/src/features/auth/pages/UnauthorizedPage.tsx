import { Link, useNavigate } from 'react-router'
import { logout } from '@/lib/session'
import { homePathFor, paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { useAuthStore } from '@/store/authStore'
import { AuthCard } from '../components'

export default function UnauthorizedPage() {
  const navigate = useNavigate()
  const role = useAuthStore((state) => state.user?.role)

  return (
    <AuthCard
      title="You do not have access to that page"
      description="Your account is not allowed to open it. If you think it should be, ask the person who manages your community."
    >
      {role ? (
        <>
          <Button asChild size="lg">
            <Link to={homePathFor(role)}>Go to my dashboard</Link>
          </Button>
          <Button
            variant="secondary"
            size="lg"
            onClick={() => {
              void logout().then(() => navigate(paths.login, { replace: true }))
            }}
          >
            Sign out
          </Button>
        </>
      ) : (
        <Button asChild size="lg">
          <Link to={paths.login}>Sign in</Link>
        </Button>
      )}
    </AuthCard>
  )
}
