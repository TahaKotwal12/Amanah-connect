import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter, createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import StyleguidePage from '@/features/styleguide/StyleguidePage'
import { expectNoA11yViolations } from '@/test/a11y'
import { Avatar } from './avatar'
import { Badge } from './badge'
import { Button } from './button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from './card'
import { Checkbox } from './checkbox'
import { Combobox } from './combobox'
import { ConfirmDialog } from './confirm-dialog'
import { CopyField } from './copy-field'
import { DatePicker } from './date-picker'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './dropdown-menu'
import { EmptyState } from './empty-state'
import { ErrorState } from './error-state'
import { FileUpload } from './file-upload'
import { Input, Textarea } from './input'
import { Label } from './label'
import { MoneyInput } from './money-input'
import { PageHeader } from './page-header'
import { Pagination } from './pagination'
import { QrCard } from './qr-card'
import { RadioGroup, RadioGroupItem } from './radio-group'
import { SearchInput } from './search-input'
import { SimpleSelect } from './select'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './sheet'
import { Skeleton } from './skeleton'
import { StatCard } from './stat-card'
import { KNOWN_STATUSES, StatusBadge } from './status-badge'
import { Stepper } from './stepper'
import { Switch } from './switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs'
import { Tooltip } from './tooltip'

/** Every component in its states, as [name, element]. Each one must pass axe on its own. */
const cases: Array<[string, ReactElement]> = [
  [
    'Button variants and states',
    <div key="b">
      <Button>Primary</Button>
      <Button variant="secondary">Secondary</Button>
      <Button variant="ghost">Ghost</Button>
      <Button variant="danger">Danger</Button>
      <Button variant="premium">Premium</Button>
      <Button loading>Saving</Button>
      <Button disabled>Disabled</Button>
      <Button size="icon" aria-label="Close" />
    </div>,
  ],
  [
    'Input and Textarea, including invalid',
    <div key="i">
      <Label htmlFor="a">Name</Label>
      <Input id="a" />
      <Label htmlFor="b">Email</Label>
      <Input id="b" aria-invalid="true" aria-describedby="e" />
      <p id="e">Enter a valid email</p>
      <Label htmlFor="c">Notes</Label>
      <Textarea id="c" />
    </div>,
  ],
  [
    'Select',
    <div key="s">
      <Label htmlFor="sel">Group</Label>
      <SimpleSelect id="sel" options={[{ value: 'a', label: 'A' }]} />
    </div>,
  ],
  [
    'Combobox',
    <div key="c">
      <Label htmlFor="cb">Member</Label>
      <Combobox id="cb" options={[{ value: 'a', label: 'Asha' }]} />
    </div>,
  ],
  [
    'Checkbox, Switch, RadioGroup',
    <div key="x">
      <Checkbox label="Agree" />
      <Checkbox label="Some" checked="indeterminate" />
      <Switch label="Auto" />
      <RadioGroup aria-label="Frequency">
        <RadioGroupItem value="a" label="Monthly" />
        <RadioGroupItem value="b" label="Yearly" />
      </RadioGroup>
    </div>,
  ],
  [
    'DatePicker',
    <div key="d">
      <Label htmlFor="dp">Due</Label>
      <DatePicker id="dp" value="2026-04-08" />
    </div>,
  ],
  [
    'MoneyInput',
    <div key="m">
      <Label htmlFor="mi">Amount</Label>
      <MoneyInput id="mi" value="1500.00" onValueChange={() => undefined} />
    </div>,
  ],
  ['SearchInput', <SearchInput key="si" value="" onSearch={() => undefined} />],
  [
    'Card',
    <Card key="card">
      <CardHeader>
        <CardTitle>Title</CardTitle>
        <CardDescription>Description</CardDescription>
      </CardHeader>
      <CardContent>Body</CardContent>
      <CardFooter>Footer</CardFooter>
    </Card>,
  ],
  [
    'Badge and every StatusBadge',
    <div key="bd">
      <Badge variant="success">Ok</Badge>
      <Badge variant="warning">Careful</Badge>
      <Badge variant="danger">Bad</Badge>
      <Badge variant="premium">Pro</Badge>
      <Badge variant="gold">Gold</Badge>
      {KNOWN_STATUSES.map((status) => (
        <StatusBadge key={status} status={status} />
      ))}
    </div>,
  ],
  [
    'Tabs',
    <Tabs key="t" defaultValue="a">
      <TabsList aria-label="Sections">
        <TabsTrigger value="a">One</TabsTrigger>
        <TabsTrigger value="b">Two</TabsTrigger>
      </TabsList>
      <TabsContent value="a">First</TabsContent>
      <TabsContent value="b">Second</TabsContent>
    </Tabs>,
  ],
  [
    'Skeleton group',
    <div key="sk" role="status" aria-label="Loading">
      <Skeleton className="h-4" />
    </div>,
  ],
  [
    'EmptyState',
    <EmptyState key="es" title="No members" description="Add one." action={<Button>Add</Button>} />,
  ],
  ['ErrorState', <ErrorState key="er" message="Could not load" onRetry={() => undefined} />],
  [
    'Pagination',
    <Pagination
      key="p"
      page={3}
      pageSize={20}
      total={400}
      onPageChange={() => undefined}
      onPageSizeChange={() => undefined}
    />,
  ],
  [
    'StatCard (trend, loading)',
    <div key="st">
      <StatCard
        label="Collected"
        value="₹1,000.00"
        trend={{ direction: 'up', value: '5%', good: true }}
      />
      <StatCard
        label="Overdue"
        value="₹10.00"
        trend={{ direction: 'up', value: '5%', good: false }}
      />
      <StatCard label="Loading" value="" loading />
    </div>,
  ],
  [
    'Avatar',
    <div key="av">
      <Avatar name="Asha Rao" />
      <Avatar name="" />
    </div>,
  ],
  [
    'FileUpload',
    <FileUpload
      key="fu"
      upload={() => Promise.resolve('k')}
      accept={['image/png']}
      hint="PNG only"
    />,
  ],
  [
    'Stepper',
    <Stepper
      key="stp"
      current={1}
      steps={[
        { id: 'a', label: 'One' },
        { id: 'b', label: 'Two' },
        { id: 'c', label: 'Three' },
      ]}
    />,
  ],
  ['CopyField', <CopyField key="cf" label="Link" value="https://x.test" />],
  ['QrCard', <QrCard key="qr" title="Scan" value="https://x.test" alt="QR code for x.test" />],
  [
    'PageHeader with Breadcrumbs',
    <MemoryRouter key="ph">
      <PageHeader
        title="Members"
        description="Everyone"
        breadcrumbs={[{ label: 'Home', to: '/' }, { label: 'Members' }]}
        actions={<Button>Add</Button>}
      />
    </MemoryRouter>,
  ],
]

describe('axe: every component has no accessibility violations', () => {
  it.each(cases)('%s', async (_name, element) => {
    const { container } = render(element)
    await expectNoA11yViolations(container)
  })

  it('Tooltip (open)', async () => {
    const user = userEvent.setup()
    render(
      <Tooltip content="Helpful">
        <Button>Hover</Button>
      </Tooltip>,
    )
    await user.hover(screen.getByRole('button', { name: 'Hover' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Helpful')
    await expectNoA11yViolations(document.body)
  })

  it('Dialog, ConfirmDialog and Sheet (open)', async () => {
    render(
      <>
        <Dialog open>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Dialog</DialogTitle>
              <DialogDescription>Body</DialogDescription>
            </DialogHeader>
          </DialogContent>
        </Dialog>
      </>,
    )
    await expectNoA11yViolations(document.body)
  })

  it('ConfirmDialog with typed confirmation (open)', async () => {
    render(
      <ConfirmDialog
        open
        onOpenChange={() => undefined}
        variant="danger"
        confirmText="Delete"
        title="Delete everything?"
        description="This cannot be undone."
        onConfirm={() => undefined}
      />,
    )
    await expectNoA11yViolations(document.body)
  })

  it('Sheet (open)', async () => {
    render(
      <Sheet open>
        <SheetContent>
          <SheetHeader>
            <SheetTitle>Add member</SheetTitle>
            <SheetDescription>Form</SheetDescription>
          </SheetHeader>
        </SheetContent>
      </Sheet>,
    )
    await expectNoA11yViolations(document.body)
  })

  it('DropdownMenu, Select, Combobox and DatePicker (open)', async () => {
    const user = userEvent.setup()
    const { unmount } = render(
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button>Menu</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent>
          <DropdownMenuItem>Edit</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>,
    )
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    await expectNoA11yViolations(document.body)
    unmount()

    const select = render(
      <SimpleSelect aria-label="Group" options={[{ value: 'a', label: 'Block A' }]} />,
    )
    await user.click(screen.getByRole('combobox', { name: 'Group' }))
    await expectNoA11yViolations(document.body)
    select.unmount()

    const combo = render(<Combobox aria-label="Member" options={[{ value: 'a', label: 'Asha' }]} />)
    await user.click(screen.getByRole('combobox', { name: 'Member' }))
    await expectNoA11yViolations(document.body)
    combo.unmount()

    render(<DatePicker aria-label="Date" value="2026-04-08" />)
    await user.click(screen.getByRole('button', { name: 'Date' }))
    await expectNoA11yViolations(document.body)
  })
})

describe('axe: the styleguide, which shows every component and state at once', () => {
  it('has no violations', async () => {
    const router = createMemoryRouter([{ path: '/styleguide', element: <StyleguidePage /> }], {
      initialEntries: ['/styleguide'],
    })
    const { container } = render(<RouterProvider router={router} />)
    await screen.findByRole('heading', { level: 1, name: 'Styleguide' })
    await expectNoA11yViolations(container)
  }, 30_000)
})
