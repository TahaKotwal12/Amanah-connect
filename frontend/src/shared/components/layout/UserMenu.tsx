import { ChevronDown, LogOut, Settings } from 'lucide-react'
import { Link } from 'react-router'
import { useAuthStore } from '@/store/authStore'
import { Avatar } from '../ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu'

const ROLE_LABEL = { SUPER_ADMIN: 'Super admin', COMMUNITY_ADMIN: 'Community admin' } as const

interface UserMenuProps {
  onSignOut: () => void
  /** Adds a Settings item. */
  settingsPath?: string
}

export function UserMenu({ onSignOut, settingsPath }: UserMenuProps) {
  const user = useAuthStore((state) => state.user)
  if (!user) return null
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label={`Account menu for ${user.fullName}`}
          className="flex min-h-11 items-center gap-2 rounded px-2 hover:bg-brand-50"
        >
          <Avatar name={user.fullName} size="sm" />
          <span className="hidden text-sm font-medium text-ink sm:block">{user.fullName}</span>
          <ChevronDown aria-hidden="true" className="hidden size-4 text-muted sm:block" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="min-w-60">
        <DropdownMenuLabel className="grid gap-0.5 normal-case">
          <span className="text-sm font-semibold text-ink">{user.fullName}</span>
          <span className="font-normal">{user.email}</span>
          <span className="font-normal">{ROLE_LABEL[user.role]}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {settingsPath ? (
          <DropdownMenuItem asChild>
            <Link to={settingsPath}>
              <Settings aria-hidden="true" className="size-4" />
              Settings
            </Link>
          </DropdownMenuItem>
        ) : null}
        <DropdownMenuItem onSelect={onSignOut}>
          <LogOut aria-hidden="true" className="size-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
