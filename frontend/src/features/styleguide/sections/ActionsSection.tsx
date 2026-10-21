import { Download, Pencil, Trash2 } from 'lucide-react'
import {
  Avatar,
  Badge,
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  KNOWN_STATUSES,
  StatusBadge,
  Tooltip,
  notify,
} from '@/shared/components/ui'
import { Demo, Section } from '../Section'

export function ActionsSection() {
  return (
    <Section id="actions" title="Actions, labels and feedback">
      <Demo title="Button variants">
        <Button>Primary</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="danger">Danger</Button>
        <Button variant="premium">Premium</Button>
      </Demo>
      <Demo title="Button sizes, icon and states">
        <Button size="sm">Small</Button>
        <Button size="md">Medium</Button>
        <Button size="lg">Large</Button>
        <Button size="icon" variant="secondary" aria-label="Download">
          <Download aria-hidden="true" className="size-5" />
        </Button>
        <Button loading>Saving</Button>
        <Button disabled>Disabled</Button>
        <Button variant="danger" loading>
          Deleting
        </Button>
      </Demo>

      <Demo title="Badge">
        <Badge>Neutral</Badge>
        <Badge variant="brand">Brand</Badge>
        <Badge variant="gold">Gold</Badge>
        <Badge variant="success">Success</Badge>
        <Badge variant="warning">Warning</Badge>
        <Badge variant="danger">Danger</Badge>
        <Badge variant="premium">Premium</Badge>
      </Demo>
      <Demo title="StatusBadge: colour, icon and text together">
        {KNOWN_STATUSES.map((status) => (
          <StatusBadge key={status} status={status} />
        ))}
        <StatusBadge status="SOMETHING_NEW" />
        <StatusBadge status="overdue" label="Overdue by 3 days" />
      </Demo>

      <Demo title="Avatar">
        <Avatar name="Asha Rao" size="sm" />
        <Avatar name="Asha Rao" />
        <Avatar name="Asha Rao" size="lg" />
        <Avatar name="Mohammed Imran Qureshi" />
        <Avatar name="" />
      </Demo>

      <Demo title="Tooltip (hover or focus)">
        <Tooltip content="Appears on hover and on keyboard focus">
          <Button variant="secondary">Hover or focus me</Button>
        </Tooltip>
      </Demo>

      <Demo title="DropdownMenu">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="secondary">Row actions</Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuLabel>Member</DropdownMenuLabel>
            <DropdownMenuItem>
              <Pencil aria-hidden="true" className="size-4" /> Edit
            </DropdownMenuItem>
            <DropdownMenuItem disabled>Disabled item</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem destructive>
              <Trash2 aria-hidden="true" className="size-4" /> Deactivate
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </Demo>

      <Demo title="Toast helpers (notify)">
        <Button
          variant="secondary"
          onClick={() => {
            notify.success('Member added')
          }}
        >
          Success
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            notify.info('Import is running')
          }}
        >
          Info
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            notify.warning('Plan limit is close', { description: '45 of 50 members used.' })
          }}
        >
          Warning
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            notify.error('Could not save')
          }}
        >
          Error
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            notify.apiError(new Error('x'))
          }}
        >
          From an error
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            void notify.promise(new Promise((resolve) => setTimeout(resolve, 1500)), {
              loading: 'Sending receipts',
              success: 'Receipts sent',
            })
          }}
        >
          Promise
        </Button>
      </Demo>
    </Section>
  )
}
