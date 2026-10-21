import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/apiError'
import { Button } from './button'
import { ConfirmDialog } from './confirm-dialog'
import { FileUpload } from './file-upload'
import { StatusBadge } from './status-badge'
import { CopyField } from './copy-field'
import { Stepper } from './stepper'
import { initialsOf } from './avatar'

function file(name: string, type: string, size = 1000) {
  return new File([new Uint8Array(size)], name, { type })
}

describe('Button', () => {
  it('blocks clicks and announces busy while loading', async () => {
    const onClick = vi.fn()
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    )
    const button = screen.getByRole('button', { name: /Save/ })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('does not submit forms unless asked', () => {
    render(<Button>Plain</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button')
  })

  it('can style a link as a button', () => {
    render(
      <Button asChild>
        <a href="/x">Go</a>
      </Button>,
    )
    expect(screen.getByRole('link', { name: 'Go' })).toHaveClass('bg-brand-600')
  })
})

describe('ConfirmDialog', () => {
  it('is an alert dialog that confirms and closes', async () => {
    const onConfirm = vi.fn()
    const onOpenChange = vi.fn()
    const user = userEvent.setup()
    render(
      <ConfirmDialog
        open
        onOpenChange={onOpenChange}
        title="Send reminders?"
        description="12 members"
        confirmLabel="Send"
        onConfirm={onConfirm}
      />,
    )
    expect(screen.getByRole('alertdialog', { name: 'Send reminders?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(onConfirm).toHaveBeenCalled()
    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  it('needs the exact text typed before a dangerous action can be confirmed', async () => {
    const onConfirm = vi.fn()
    const user = userEvent.setup()
    render(
      <ConfirmDialog
        open
        onOpenChange={() => undefined}
        variant="danger"
        confirmText="Lotus Residents"
        title="Suspend?"
        description="Read-only afterwards."
        confirmLabel="Suspend"
        onConfirm={onConfirm}
      />,
    )
    const confirm = screen.getByRole('button', { name: 'Suspend' })
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText(/Type Lotus Residents to confirm/), 'lotus residents')
    expect(confirm).toBeDisabled() // case matters
    await user.clear(screen.getByLabelText(/Type Lotus Residents/))
    await user.type(screen.getByLabelText(/Type Lotus Residents/), 'Lotus Residents')
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    expect(onConfirm).toHaveBeenCalled()
  })

  it('stays open when the action fails', async () => {
    const onOpenChange = vi.fn()
    const user = userEvent.setup()
    render(
      <ConfirmDialog
        open
        onOpenChange={onOpenChange}
        title="Delete?"
        description="x"
        onConfirm={() => Promise.reject(new Error('nope'))}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled()
    })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('closes on Escape', async () => {
    const onOpenChange = vi.fn()
    render(
      <ConfirmDialog
        open
        onOpenChange={onOpenChange}
        title="T"
        description="d"
        onConfirm={() => undefined}
      />,
    )
    await userEvent.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('FileUpload', () => {
  it('rejects a wrong type and an oversized file before uploading anything', async () => {
    const upload = vi.fn()
    const user = userEvent.setup({ applyAccept: false })
    const { container } = render(
      <FileUpload upload={upload} accept={['image/png']} maxBytes={2000} label="Choose logo" />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, file('notes.txt', 'text/plain'))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'notes.txt: this file type is not allowed',
    )
    await user.upload(input, file('huge.png', 'image/png', 5000))
    expect(await screen.findByRole('alert')).toHaveTextContent('over the')
    expect(upload).not.toHaveBeenCalled()
  })

  it('shows progress, then success, and reports the result', async () => {
    const onUploaded = vi.fn()
    const user = userEvent.setup()
    const { container } = render(
      <FileUpload
        accept={['image/png']}
        onUploaded={onUploaded}
        upload={async (_file, { onProgress }) => {
          onProgress(50)
          await new Promise((resolve) => setTimeout(resolve, 20))
          return 'key-1'
        }}
      />,
    )
    await user.upload(
      container.querySelector('input[type="file"]') as HTMLInputElement,
      file('logo.png', 'image/png'),
    )
    expect(await screen.findByText('Uploaded')).toBeInTheDocument()
    expect(onUploaded).toHaveBeenCalledWith(expect.any(File), 'key-1')
  })

  it('shows the failure and retries', async () => {
    let attempts = 0
    const user = userEvent.setup()
    const { container } = render(
      <FileUpload
        upload={() => {
          attempts += 1
          return attempts === 1
            ? Promise.reject(new ApiError({ status: 503, code: 'STORAGE_UNAVAILABLE', detail: '' }))
            : Promise.resolve('ok')
        }}
      />,
    )
    await user.upload(
      container.querySelector('input[type="file"]') as HTMLInputElement,
      file('a.pdf', 'application/pdf'),
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(/storage is not available/i)
    await user.click(screen.getByRole('button', { name: 'Retry a.pdf' }))
    expect(await screen.findByText('Uploaded')).toBeInTheDocument()
    expect(attempts).toBe(2)
  })
})

describe('small components', () => {
  it('StatusBadge shows icon-free text plus a known label, and humanizes unknown ones', () => {
    render(
      <>
        <StatusBadge status="OVERDUE" />
        <StatusBadge status="in_progress" />
        <StatusBadge status="SOMETHING_NEW" />
      </>,
    )
    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.getByText('Something new')).toBeInTheDocument()
  })

  it('initialsOf handles one name, many names and none', () => {
    expect(initialsOf('Asha Rao')).toBe('AR')
    expect(initialsOf('Mohammed Imran Qureshi')).toBe('MQ')
    expect(initialsOf('Cher')).toBe('C')
    expect(initialsOf('  ')).toBe('?')
  })

  it('Stepper marks the current and completed steps for assistive tech', () => {
    render(
      <Stepper
        current={1}
        steps={[
          { id: 'a', label: 'Details' },
          { id: 'b', label: 'Plan' },
          { id: 'c', label: 'Review' },
        ]}
      />,
    )
    expect(screen.getByText('Details').closest('li')).toHaveTextContent('(completed)')
    expect(screen.getByText('Plan').closest('li')).toHaveAttribute('aria-current', 'step')
  })

  it('CopyField copies and says so', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<CopyField label="Invite link" value="https://x.test/join/abc" />)
    await user.click(screen.getByRole('button', { name: /Copy/ }))
    expect(writeText).toHaveBeenCalledWith('https://x.test/join/abc')
    expect(await screen.findByText('Copied to the clipboard.')).toBeInTheDocument()
  })
})
