import { Bell, CircleHelp } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '../ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu'
import { Tooltip } from '../ui/tooltip'
import { UserMenu } from './UserMenu'

interface TopBarProps {
  /** The community's name, or "Platform" for the super admin. */
  contextName: string
  /** The community's logo, shown beside the name. Decorative: the name is next to it. */
  contextLogoUrl?: string | null
  /** Unread notifications. 0 hides the badge. */
  notificationCount?: number
  /** What the bell opens. Defaults to an "all caught up" note. */
  notifications?: ReactNode
  /** Where "contact support" goes. */
  supportPath: string
  settingsPath?: string
  onSignOut: () => void
}

export function TopBar({
  contextName,
  contextLogoUrl,
  notificationCount = 0,
  notifications,
  supportPath,
  settingsPath,
  onSignOut,
}: TopBarProps) {
  const [helpOpen, setHelpOpen] = useState(false)
  return (
    <header className="sticky top-0 z-30 flex min-h-16 items-center justify-between gap-3 border-b border-brand-100 bg-white px-4 md:px-8">
      <div className="flex min-w-0 items-center gap-3">
        {contextLogoUrl ? (
          <img
            src={contextLogoUrl}
            alt=""
            className="size-9 shrink-0 rounded object-contain"
            width={36}
            height={36}
          />
        ) : null}
        <p className="min-w-0 truncate font-heading text-lg font-semibold text-brand-900">
          {contextName}
        </p>
      </div>
      <div className="flex items-center gap-1">
        <Tooltip content="Help">
          <Button
            variant="ghost"
            size="icon"
            aria-label="Help"
            onClick={() => {
              setHelpOpen(true)
            }}
          >
            <CircleHelp aria-hidden="true" className="size-5" />
          </Button>
        </Tooltip>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="relative"
              aria-label={
                notificationCount > 0
                  ? `Notifications, ${notificationCount} unread`
                  : 'Notifications'
              }
            >
              <Bell aria-hidden="true" className="size-5" />
              {notificationCount > 0 ? (
                <span
                  aria-hidden="true"
                  className="absolute right-1.5 top-1.5 flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold leading-4 text-white"
                >
                  {notificationCount > 9 ? '9+' : notificationCount}
                </span>
              ) : null}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent className="w-80">
            <DropdownMenuLabel>Notifications</DropdownMenuLabel>
            <div className="px-3 pb-3 text-sm text-muted">
              {notifications ?? 'You are all caught up.'}
            </div>
          </DropdownMenuContent>
        </DropdownMenu>

        <UserMenu onSignOut={onSignOut} {...(settingsPath ? { settingsPath } : {})} />
      </div>

      <Dialog open={helpOpen} onOpenChange={setHelpOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Need help?</DialogTitle>
            <DialogDescription>
              Send a message to the Amanah Connect team from the Support page. Include what you were
              doing and what you expected, and we will reply there.
            </DialogDescription>
          </DialogHeader>
          <Button asChild>
            <Link
              to={supportPath}
              onClick={() => {
                setHelpOpen(false)
              }}
            >
              Go to Support
            </Link>
          </Button>
        </DialogContent>
      </Dialog>
    </header>
  )
}
