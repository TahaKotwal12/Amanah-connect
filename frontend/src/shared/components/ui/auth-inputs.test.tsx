import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { expectNoA11yViolations } from '@/test/a11y'
import { OtpInput } from './otp-input'
import { PasswordInput } from './password-input'
import { PasswordStrength } from './password-strength'

describe('PasswordInput', () => {
  it('hides the password, and a labelled toggle shows it', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <>
        <label htmlFor="pw">Password</label>
        <PasswordInput id="pw" defaultValue="secret-value" />
      </>,
    )
    const input = screen.getByLabelText('Password')
    expect(input).toHaveAttribute('type', 'password')

    const toggle = screen.getByRole('button', { name: 'Show password' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await user.click(toggle)
    expect(input).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(input).toHaveAttribute('type', 'password')
    await expectNoA11yViolations(container)
  })

  it('is reachable by keyboard: Tab to the box, Tab to the toggle, Space presses it', async () => {
    const user = userEvent.setup()
    render(<PasswordInput aria-label="Password" />)
    await user.tab()
    expect(screen.getByLabelText('Password')).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Show password' })).toHaveFocus()
    await user.keyboard(' ')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
  })

  it('says when Caps Lock is on, and stops saying so when it goes off or focus leaves', () => {
    render(<PasswordInput aria-label="Password" />)
    const input = screen.getByLabelText('Password')
    expect(screen.queryByText('Caps Lock is on.')).not.toBeInTheDocument()

    fireEvent.keyDown(input, { key: 'A', modifierCapsLock: true })
    expect(screen.getByText('Caps Lock is on.')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Caps Lock is on.') // announced politely

    fireEvent.keyUp(input, { key: 'CapsLock', modifierCapsLock: false })
    expect(screen.queryByText('Caps Lock is on.')).not.toBeInTheDocument()

    fireEvent.keyDown(input, { key: 'A', modifierCapsLock: true })
    fireEvent.blur(input)
    expect(screen.queryByText('Caps Lock is on.')).not.toBeInTheDocument()
  })

  it('can leave the hint out', () => {
    render(<PasswordInput aria-label="Password" capsLockHint={false} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})

describe('PasswordStrength', () => {
  it('shows nothing for an empty password', () => {
    render(<PasswordStrength password="" />)
    expect(screen.queryByText(/Password strength/)).not.toBeInTheDocument()
  })

  it('says in words how strong it is and what is still missing', () => {
    const { rerender } = render(<PasswordStrength password="short" />)
    expect(screen.getByRole('status')).toHaveTextContent('Password strength: Too short')
    expect(screen.getByText('Use at least 10 characters.')).toBeInTheDocument()

    rerender(<PasswordStrength password="onlylowercaseletters" />)
    expect(screen.getByRole('status')).toHaveTextContent('Password strength: Weak')
    expect(screen.getByText(/Mix at least three/)).toBeInTheDocument()

    rerender(<PasswordStrength password="Abcdefghijklmn1234" />)
    expect(screen.getByRole('status')).toHaveTextContent('Password strength: Strong')
    expect(screen.getByText('This password meets the rules.')).toBeInTheDocument()
  })

  it('passes axe', async () => {
    const { container } = render(<PasswordStrength password="Abcdefg123" />)
    await expectNoA11yViolations(container)
  })
})

describe('OtpInput', () => {
  function Harness({ onComplete }: { onComplete?: (code: string) => void }) {
    const [value, setValue] = useState('')
    return (
      <>
        <OtpInput
          label="Authenticator code"
          onChange={setValue}
          onComplete={onComplete}
          focusOnMount
        />
        <output data-testid="value">{value}</output>
      </>
    )
  }
  const boxes = () => screen.getAllByRole('textbox')
  const value = () => screen.getByTestId('value').textContent

  it('has six labelled boxes in a group, and the first takes focus', () => {
    const { container } = render(<Harness />)
    expect(screen.getByRole('group', { name: 'Authenticator code' })).toBeInTheDocument()
    expect(boxes()).toHaveLength(6)
    expect(boxes()[0]).toHaveAccessibleName('Authenticator code, digit 1 of 6')
    expect(boxes()[0]).toHaveFocus()
    expect(boxes()[0]).toHaveAttribute('autocomplete', 'one-time-code')
    return expectNoA11yViolations(container)
  })

  it('moves to the next box after each digit and reports the code when complete', async () => {
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(<Harness onComplete={onComplete} />)
    await user.keyboard('123')
    expect(boxes()[3]).toHaveFocus()
    expect(value()).toBe('123')
    expect(onComplete).not.toHaveBeenCalled()
    await user.keyboard('456')
    expect(value()).toBe('123456')
    expect(onComplete).toHaveBeenCalledTimes(1)
    expect(onComplete).toHaveBeenCalledWith('123456')
  })

  it('ignores anything that is not a digit', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.keyboard('1a-b2')
    expect(value()).toBe('12')
  })

  it('Backspace on an empty box steps back and clears the previous digit', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.keyboard('12')
    expect(boxes()[2]).toHaveFocus()
    await user.keyboard('{Backspace}')
    expect(boxes()[1]).toHaveFocus()
    expect(value()).toBe('1')
  })

  it('moves with the arrow keys', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.keyboard('123')
    await user.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(boxes()[1]).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(boxes()[2]).toHaveFocus()
  })

  it('spreads a pasted code across the boxes, ignoring spaces and dashes', async () => {
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(<Harness onComplete={onComplete} />)
    await user.click(boxes()[0] as HTMLElement)
    await user.paste('123 456')
    expect(boxes().map((box) => (box as HTMLInputElement).value)).toEqual([
      '1',
      '2',
      '3',
      '4',
      '5',
      '6',
    ])
    expect(onComplete).toHaveBeenCalledWith('123456')
  })

  it('takes a whole code from autofill into the first box', () => {
    const onComplete = vi.fn()
    render(<Harness onComplete={onComplete} />)
    fireEvent.change(boxes()[0] as HTMLElement, { target: { value: '654321' } })
    expect(value()).toBe('654321')
    expect(onComplete).toHaveBeenCalledWith('654321')
  })

  it('can be disabled while a code is being checked', () => {
    render(<OtpInput label="Code" disabled />)
    for (const box of boxes()) expect(box).toBeDisabled()
  })
})
