import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const contact = vi.hoisted(() => ({ email: '', phone: '', website: '' }))
vi.mock('./contact', () => ({ CONTACT: contact }))

import Contact from './sections/Contact'

const view = () =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <Contact />
      </MemoryRouter>
    </QueryClientProvider>,
  )

describe('Get in Touch', () => {
  it('shows the heading and a Contact Us button (no second demo button), and no row while nothing is filled in', () => {
    view()
    expect(screen.getByRole('heading', { level: 2, name: 'Get in Touch' })).toBeInTheDocument()
    expect(screen.getByText('We would love to hear from you.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Contact Us' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /demo/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/Email:|Phone:|Website:/)).not.toBeInTheDocument()
  })

  it('shows a row, as a working link, for each detail that is filled in', () => {
    Object.assign(contact, {
      email: 'hello@example.org',
      phone: '+91 98765 43210',
      website: 'https://example.org',
    })
    view()
    expect(screen.getByRole('link', { name: 'hello@example.org' })).toHaveAttribute(
      'href',
      'mailto:hello@example.org',
    )
    expect(screen.getByRole('link', { name: '+91 98765 43210' })).toHaveAttribute(
      'href',
      'tel:+919876543210',
    )
    expect(screen.getByRole('link', { name: 'example.org' })).toHaveAttribute(
      'href',
      'https://example.org',
    )
  })
})

describe('the Contact Us popup', () => {
  it('opens a form, sends it as a contact enquiry and says thank you', async () => {
    const { default: userEvent } = await import('@testing-library/user-event')
    const sent: unknown[] = []
    const api = await import('@/lib/axios')
    const post = vi.spyOn(api.api, 'post').mockImplementation((_url, body) => {
      sent.push(body)
      return Promise.resolve({ data: { received: true } })
    })
    const user = userEvent.setup()
    view()
    await user.click(screen.getByRole('button', { name: 'Contact Us' }))
    const dialog = await screen.findByRole('dialog', { name: 'Contact Us' })

    await user.click(within(dialog).getByRole('button', { name: 'Send message' }))
    expect(await within(dialog).findByText('Enter your name')).toBeInTheDocument()
    expect(sent).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/Your name/), 'Ayesha Khan')
    await user.type(within(dialog).getByLabelText(/^Email/), 'ayesha@example.org')
    await user.type(within(dialog).getByLabelText(/Your message/), 'Can we talk about fees?')
    await user.click(within(dialog).getByRole('button', { name: 'Send message' }))

    expect(await within(dialog).findByText('Thank you')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith(
      '/public/leads',
      expect.objectContaining({
        name: 'Ayesha Khan',
        email: 'ayesha@example.org',
        message: 'Contact enquiry: Can we talk about fees?',
      }),
    )
  })
})
