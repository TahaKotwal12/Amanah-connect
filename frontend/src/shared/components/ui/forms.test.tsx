import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { z } from 'zod'
import { ApiError } from '@/lib/apiError'
import { isPositiveMoney } from '@/lib/money'
import { expectNoA11yViolations } from '@/test/a11y'
import { Checkbox } from './checkbox'
import { Combobox } from './combobox'
import { DatePicker } from './date-picker'
import { Form, FormField } from './form-field'
import { applyApiFieldErrors, useZodForm } from './form'
import { Input } from './input'
import { MoneyInput } from './money-input'
import { RadioGroup, RadioGroupItem } from './radio-group'
import { SearchInput } from './search-input'
import { SimpleSelect } from './select'
import { Switch } from './switch'
import { Button } from './button'

describe('FormField with React Hook Form and zod', () => {
  const schema = z.object({
    email: z.email('Enter a valid email address'),
    amount: z.string().refine((v) => v !== '' && isPositiveMoney(v), 'Enter an amount above zero'),
  })

  function Harness({ onValid }: { onValid: (values: unknown) => void }) {
    const form = useZodForm(schema, { defaultValues: { email: '', amount: '' } })
    return (
      <Form {...form}>
        <form noValidate onSubmit={(event) => void form.handleSubmit(onValid)(event)}>
          <FormField
            control={form.control}
            name="email"
            label="Email"
            description="Receipts go here"
            required
          >
            {({ field, control }) => <Input type="email" {...field} {...control} />}
          </FormField>
          <FormField control={form.control} name="amount" label="Amount">
            {({ field, control }) => (
              <MoneyInput
                value={field.value}
                onValueChange={(v) => {
                  field.onChange(v)
                }}
                onBlur={field.onBlur}
                {...control}
              />
            )}
          </FormField>
          <Button type="submit">Save</Button>
          <Button
            type="button"
            onClick={() => {
              applyApiFieldErrors(
                form,
                new ApiError({
                  status: 400,
                  code: 'VALIDATION_FAILED',
                  detail: '',
                  fieldErrors: [
                    { field: 'email', message: 'is already used' },
                    { field: 'nope', message: 'not on this form' },
                  ],
                }),
                ['email', 'amount'],
              )
            }}
          >
            Server error
          </Button>
        </form>
      </Form>
    )
  }

  it('labels the control, ties the hint to it and marks it required', () => {
    render(<Harness onValid={() => undefined} />)
    const email = screen.getByLabelText(/Email/)
    expect(email).toHaveAccessibleDescription('Receipts go here')
    expect(email).toHaveAttribute('aria-required', 'true')
    expect(email).toHaveAttribute('aria-invalid', 'false')
  })

  it('shows an inline error per field on submit, announces it, and does not submit', async () => {
    const onValid = vi.fn()
    const user = userEvent.setup()
    const { container } = render(<Harness onValid={onValid} />)
    await user.click(screen.getByRole('button', { name: 'Save' }))

    const alerts = await screen.findAllByRole('alert')
    expect(alerts.map((a) => a.textContent)).toEqual([
      'Enter a valid email address',
      'Enter an amount above zero',
    ])
    const email = screen.getByLabelText(/Email/)
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription('Receipts go here Enter a valid email address')
    expect(onValid).not.toHaveBeenCalled()
    await expectNoA11yViolations(container)
  })

  it('submits typed values once valid, with money as a canonical string', async () => {
    const onValid = vi.fn()
    const user = userEvent.setup()
    render(<Harness onValid={onValid} />)
    await user.type(screen.getByLabelText(/Email/), 'asha@example.com')
    await user.type(screen.getByLabelText('Amount'), '1500.5')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(onValid).toHaveBeenCalled()
    })
    expect(onValid.mock.calls[0]?.[0]).toEqual({ email: 'asha@example.com', amount: '1500.50' })
  })

  it('shows server field errors on the matching field and reports the rest', async () => {
    const user = userEvent.setup()
    render(<Harness onValid={() => undefined} />)
    await user.click(screen.getByRole('button', { name: 'Server error' }))
    expect(await screen.findByText('is already used')).toBeInTheDocument()
    expect(screen.queryByText('not on this form')).not.toBeInTheDocument()
  })
})

describe('MoneyInput', () => {
  function Harness({ initial = '' }: { initial?: string }) {
    const [value, setValue] = useState(initial)
    return (
      <>
        <label htmlFor="m">Amount</label>
        <MoneyInput id="m" value={value} onValueChange={setValue} />
        <output data-testid="value">{value === '' ? '(empty)' : value}</output>
        <button type="button">elsewhere</button>
      </>
    )
  }
  const value = () => screen.getByTestId('value').textContent

  it('reports a canonical decimal string while typing', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.type(screen.getByLabelText('Amount'), '1500.5')
    expect(value()).toBe('1500.50')
  })

  it('ignores letters and a third decimal', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.type(screen.getByLabelText('Amount'), '12ab.345')
    expect(screen.getByLabelText('Amount')).toHaveValue('12.34')
    expect(value()).toBe('12.34')
  })

  it('formats with Indian grouping at rest and edits as plain digits', async () => {
    const user = userEvent.setup()
    render(<Harness initial="150000.5" />)
    const input = screen.getByLabelText('Amount')
    expect(input).toHaveValue('1,50,000.50')
    await user.click(input)
    expect(input).toHaveValue('150000.5')
    await user.click(screen.getByRole('button', { name: 'elsewhere' }))
    expect(input).toHaveValue('1,50,000.50')
  })

  it('accepts a pasted amount with the rupee sign and commas', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByLabelText('Amount'))
    await user.paste('₹ 1,50,000.5')
    expect(value()).toBe('150000.50')
  })

  it('reports an empty value when cleared', async () => {
    const user = userEvent.setup()
    render(<Harness initial="10.00" />)
    await user.clear(screen.getByLabelText('Amount'))
    expect(value()).toBe('(empty)')
  })

  it('shows the rupee sign without it being part of the value', () => {
    render(<Harness initial="10" />)
    expect(screen.getByText('₹')).toBeInTheDocument()
  })
})

describe('SimpleSelect', () => {
  it('opens, lets you choose with the keyboard or mouse, and reports the value', async () => {
    const onValueChange = vi.fn()
    const user = userEvent.setup()
    render(
      <>
        <label htmlFor="g">Group</label>
        <SimpleSelect
          id="g"
          placeholder="Choose"
          onValueChange={onValueChange}
          options={[
            { value: 'a', label: 'Block A' },
            { value: 'b', label: 'Block B' },
            { value: 'c', label: 'Block C', disabled: true },
          ]}
        />
      </>,
    )
    const trigger = screen.getByRole('combobox', { name: 'Group' })
    expect(trigger).toHaveTextContent('Choose')
    await user.click(trigger)
    expect(screen.getByRole('option', { name: 'Block C' })).toHaveAttribute('aria-disabled', 'true')
    await user.click(screen.getByRole('option', { name: 'Block B' }))
    expect(onValueChange).toHaveBeenCalledWith('b')
  })
})

describe('Combobox', () => {
  const options = [
    { value: 'm1', label: 'Asha Rao', description: 'MEM-0001' },
    { value: 'm2', label: 'Vikram Shah', description: 'MEM-0002' },
    { value: 'm3', label: 'Meera Nair', description: 'MEM-0003' },
  ]

  function Harness({ onChange }: { onChange: (v: string) => void }) {
    const [value, setValue] = useState<string | undefined>(undefined)
    return (
      <>
        <label htmlFor="c">Member</label>
        <Combobox
          id="c"
          options={options}
          value={value}
          onValueChange={(v) => {
            setValue(v)
            onChange(v)
          }}
          placeholder="Find a member"
        />
      </>
    )
  }

  it('filters as you type (also by description) and selects with Enter', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<Harness onChange={onChange} />)
    const trigger = screen.getByRole('combobox', { name: 'Member' })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    await user.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')

    await user.type(screen.getByPlaceholderText('Search'), 'mem-0002')
    expect(screen.getAllByRole('option')).toHaveLength(1)
    expect(screen.getByRole('option', { name: /Vikram Shah/ })).toBeInTheDocument()

    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('m2')
    expect(screen.getByRole('combobox', { name: 'Member' })).toHaveTextContent('Vikram Shah')
  })

  it('says so when nothing matches', async () => {
    const user = userEvent.setup()
    render(<Harness onChange={() => undefined} />)
    await user.click(screen.getByRole('combobox', { name: 'Member' }))
    await user.type(screen.getByPlaceholderText('Search'), 'zzz')
    expect(screen.getByText('Nothing found.')).toBeInTheDocument()
  })
})

describe('Checkbox, Switch, RadioGroup', () => {
  it('toggles a checkbox from its label', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<Checkbox label="Send receipt" onCheckedChange={onChange} />)
    await user.click(screen.getByText('Send receipt'))
    expect(onChange).toHaveBeenCalledWith(true)
    expect(screen.getByRole('checkbox', { name: 'Send receipt' })).toBeChecked()
  })

  it('toggles a checkbox with the keyboard', async () => {
    const user = userEvent.setup()
    render(<Checkbox label="Agree" />)
    await user.tab()
    await user.keyboard(' ')
    expect(screen.getByRole('checkbox', { name: 'Agree' })).toBeChecked()
  })

  it('shows the indeterminate state', () => {
    render(<Checkbox label="Some" checked="indeterminate" />)
    expect(screen.getByRole('checkbox', { name: 'Some' })).toHaveAttribute('aria-checked', 'mixed')
  })

  it('flips a switch', async () => {
    const user = userEvent.setup()
    render(<Switch label="Auto-generate" />)
    const control = screen.getByRole('switch', { name: 'Auto-generate' })
    expect(control).not.toBeChecked()
    await user.click(control)
    expect(control).toBeChecked()
  })

  it('moves a radio group with the arrow keys', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(
      <RadioGroup aria-label="Frequency" defaultValue="monthly" onValueChange={onChange}>
        <RadioGroupItem value="monthly" label="Monthly" />
        <RadioGroupItem value="yearly" label="Yearly" />
      </RadioGroup>,
    )
    await user.tab() // into the group: the checked radio takes focus
    expect(screen.getByRole('radio', { name: 'Monthly' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('radio', { name: 'Yearly' })).toHaveFocus()
    await user.keyboard(' ')
    expect(onChange).toHaveBeenLastCalledWith('yearly')
    expect(screen.getByRole('radio', { name: 'Yearly' })).toBeChecked()
  })
})

describe('DatePicker', () => {
  it('shows the chosen date as written and reports a plain yyyy-MM-dd string', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(
      <>
        <label htmlFor="d">Due date</label>
        <DatePicker id="d" value="2026-04-08" onValueChange={onChange} />
      </>,
    )
    const trigger = screen.getByRole('button', { name: /Due date/ })
    expect(trigger).toHaveTextContent('08 Apr 2026')
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: /April 15th, 2026/ }))
    expect(onChange).toHaveBeenCalledWith('2026-04-15')
  })

  it('shows the placeholder when empty and blocks days outside min and max', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(
      <DatePicker aria-label="Date" onValueChange={onChange} min="2026-04-10" max="2026-04-20" />,
    )
    expect(screen.getByRole('button', { name: 'Date' })).toHaveTextContent('Pick a date')
    await user.click(screen.getByRole('button', { name: 'Date' }))
    const early = screen.getAllByRole('button', { name: /April 5th, 2026/ })[0]
    expect(early).toBeDisabled()
  })
})

describe('SearchInput', () => {
  it('waits for a pause before searching, and clears immediately', async () => {
    const onSearch = vi.fn()
    const user = userEvent.setup()
    render(<SearchInput value="" onSearch={onSearch} label="Search members" delayMs={400} />)

    await user.type(screen.getByRole('searchbox', { name: 'Search members' }), 'asha')
    expect(onSearch).not.toHaveBeenCalled() // still typing: nothing sent per keystroke
    await waitFor(() => {
      expect(onSearch).toHaveBeenCalledWith('asha')
    })
    expect(onSearch).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: 'Clear search' }))
    expect(onSearch).toHaveBeenLastCalledWith('')
    expect(screen.getByRole('searchbox')).toHaveValue('')
  })

  it('does not search on first render', async () => {
    const onSearch = vi.fn()
    render(<SearchInput value="initial" onSearch={onSearch} delayMs={20} />)
    await new Promise((resolve) => setTimeout(resolve, 80))
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('follows an outside change to the value', () => {
    const { rerender } = render(<SearchInput value="a" onSearch={() => undefined} />)
    rerender(<SearchInput value="" onSearch={() => undefined} />)
    expect(screen.getByRole('searchbox')).toHaveValue('')
  })
})
