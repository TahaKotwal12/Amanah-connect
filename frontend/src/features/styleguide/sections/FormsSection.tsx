import { useState } from 'react'
import { z } from 'zod'
import { ApiError } from '@/lib/apiError'
import { isPositiveMoney } from '@/lib/money'
import {
  Button,
  Checkbox,
  Combobox,
  DatePicker,
  FileUpload,
  Form,
  FormField,
  Input,
  Label,
  MoneyInput,
  RadioGroup,
  RadioGroupItem,
  SearchInput,
  SimpleSelect,
  Switch,
  Textarea,
  applyApiFieldErrors,
  notify,
  useZodForm,
} from '@/shared/components/ui'
import { Demo, Section } from '../Section'

const GROUPS = [
  { value: 'a', label: 'Block A' },
  { value: 'b', label: 'Block B' },
  { value: 'c', label: 'Block C', disabled: true },
]

const MEMBERS = [
  { value: 'm1', label: 'Asha Rao', description: 'MEM-0001' },
  { value: 'm2', label: 'Vikram Shah', description: 'MEM-0002' },
  { value: 'm3', label: 'Meera Nair', description: 'MEM-0003' },
  { value: 'm4', label: 'Imran Qureshi', description: 'MEM-0004' },
]

const schema = z.object({
  fullName: z.string().trim().min(1, 'Enter the member name'),
  email: z.email('Enter a valid email address'),
  group: z.string().min(1, 'Choose a group'),
  amount: z
    .string()
    .refine((value) => value !== '' && isPositiveMoney(value), 'Enter an amount above zero'),
  dueDate: z.string().min(1, 'Pick a due date'),
  channel: z.enum(['email', 'sms'], { error: 'Choose how to notify the member' }),
  consent: z.literal(true, { error: 'Consent is required to send emails' }),
})

function FormDemo() {
  const form = useZodForm(schema, {
    defaultValues: {
      fullName: '',
      email: '',
      group: '',
      amount: '',
      dueDate: '',
      channel: undefined,
      consent: undefined,
    },
  })

  return (
    <Form {...form}>
      <form
        noValidate
        className="grid gap-4"
        onSubmit={(event) => {
          void form.handleSubmit((values) => {
            notify.success('Form is valid', { description: JSON.stringify(values) })
          })(event)
        }}
      >
        <FormField control={form.control} name="fullName" label="Full name" required>
          {({ field, control }) => <Input autoComplete="off" {...field} {...control} />}
        </FormField>
        <FormField
          control={form.control}
          name="email"
          label="Email"
          description="Receipts are sent here."
          required
        >
          {({ field, control }) => <Input type="email" {...field} {...control} />}
        </FormField>
        <FormField control={form.control} name="group" label="Group" required>
          {({ field, control }) => (
            <SimpleSelect
              options={GROUPS}
              placeholder="Choose a group"
              id={control.id}
              aria-invalid={control['aria-invalid']}
              aria-describedby={control['aria-describedby']}
              value={field.value}
              onValueChange={(next) => {
                field.onChange(next)
              }}
              onBlur={field.onBlur}
            />
          )}
        </FormField>
        <FormField control={form.control} name="amount" label="Amount" required>
          {({ field, control }) => (
            <MoneyInput
              value={field.value}
              onValueChange={(next) => {
                field.onChange(next)
              }}
              onBlur={field.onBlur}
              {...control}
            />
          )}
        </FormField>
        <FormField control={form.control} name="dueDate" label="Due date" required>
          {({ field, control }) => (
            <DatePicker
              value={field.value || undefined}
              onValueChange={(value) => {
                field.onChange(value ?? '')
              }}
              onBlur={field.onBlur}
              id={control.id}
              aria-invalid={control['aria-invalid']}
              aria-describedby={control['aria-describedby']}
            />
          )}
        </FormField>
        <FormField control={form.control} name="channel" label="Notify by" asGroup required>
          {({ field }) => (
            <RadioGroup
              value={field.value ?? ''}
              onValueChange={(next) => {
                field.onChange(next)
              }}
            >
              <RadioGroupItem value="email" label="Email" />
              <RadioGroupItem value="sms" label="SMS" description="Not available on every plan." />
            </RadioGroup>
          )}
        </FormField>
        <FormField control={form.control} name="consent" label="Consent" hideLabel>
          {({ field, control }) => (
            <Checkbox
              label="The member agreed to receive emails"
              checked={field.value === true}
              onCheckedChange={(checked) => {
                field.onChange(checked === true ? true : undefined)
              }}
              {...control}
            />
          )}
        </FormField>
        <div className="flex flex-wrap gap-3">
          <Button type="submit" loading={form.formState.isSubmitting}>
            Save
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              applyApiFieldErrors(
                form,
                new ApiError({
                  status: 400,
                  code: 'VALIDATION_FAILED',
                  detail: '',
                  fieldErrors: [{ field: 'email', message: 'is already used by another member' }],
                }),
                ['email'],
              )
            }}
          >
            Simulate a server error on email
          </Button>
        </div>
      </form>
    </Form>
  )
}

export function FormsSection() {
  const [group, setGroup] = useState('')
  const [member, setMember] = useState<string | undefined>(undefined)
  const [date, setDate] = useState<string | undefined>(undefined)
  const [amount, setAmount] = useState('150000.5')
  const [query, setQuery] = useState('')
  const [checked, setChecked] = useState<boolean | 'indeterminate'>('indeterminate')
  const [on, setOn] = useState(true)

  return (
    <Section id="forms" title="Form controls">
      <Demo title="Input, Textarea: normal, with value, invalid, disabled" column>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-in-1">Normal</Label>
          <Input id="sg-in-1" placeholder="Placeholder" />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-in-2">With a value</Label>
          <Input id="sg-in-2" defaultValue="Lotus Residents" />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-in-3">Invalid</Label>
          <Input
            id="sg-in-3"
            aria-invalid="true"
            aria-describedby="sg-in-3-err"
            defaultValue="not an email"
          />
          <p id="sg-in-3-err" className="text-sm font-medium text-danger">
            Enter a valid email address
          </p>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-in-4">Disabled</Label>
          <Input id="sg-in-4" disabled defaultValue="Locked" />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-ta">Textarea</Label>
          <Textarea id="sg-ta" placeholder="Notes" />
        </div>
      </Demo>

      <Demo title="Select" column>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-select">Group</Label>
          <SimpleSelect
            id="sg-select"
            options={GROUPS}
            value={group}
            onValueChange={setGroup}
            placeholder="Choose a group"
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-select-d">Disabled select</Label>
          <SimpleSelect id="sg-select-d" options={GROUPS} disabled placeholder="Not available" />
        </div>
      </Demo>

      <Demo title="Combobox (searchable)" column>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-combo">Member</Label>
          <Combobox
            id="sg-combo"
            options={MEMBERS}
            value={member}
            onValueChange={setMember}
            placeholder="Find a member"
            searchPlaceholder="Search by name or number"
          />
        </div>
      </Demo>

      <Demo title="Checkbox, Switch, RadioGroup" column>
        <Checkbox
          label="Send a receipt by email"
          description="Only members who agreed to emails."
        />
        <Checkbox
          label="Select all (indeterminate)"
          checked={checked}
          onCheckedChange={setChecked}
        />
        <Checkbox label="Disabled" disabled />
        <Switch label="Auto-generate invoices" checked={on} onCheckedChange={setOn} />
        <Switch label="Disabled switch" disabled />
        <RadioGroup defaultValue="monthly" aria-label="Frequency">
          <RadioGroupItem value="monthly" label="Monthly" />
          <RadioGroupItem value="quarterly" label="Quarterly" />
          <RadioGroupItem value="yearly" label="Yearly" description="Billed once a year." />
        </RadioGroup>
      </Demo>

      <Demo title="DatePicker" column>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-date">Received on</Label>
          <DatePicker id="sg-date" value={date} onValueChange={setDate} />
        </div>
      </Demo>

      <Demo title="MoneyInput (decimal-safe, en-IN)" column>
        <div className="grid gap-1.5">
          <Label htmlFor="sg-money">Amount</Label>
          <MoneyInput id="sg-money" value={amount} onValueChange={setAmount} />
          <p className="text-sm text-muted">
            Value sent to the API: <code>{amount === '' ? '(empty)' : amount}</code>
          </p>
        </div>
      </Demo>

      <Demo title="SearchInput (debounced 300 ms)" column>
        <SearchInput value={query} onSearch={setQuery} placeholder="Search members" />
        <p className="text-sm text-muted">
          Committed search: <code>{query || '(empty)'}</code>
        </p>
      </Demo>

      <Demo title="FormField with React Hook Form and zod (touch a field, then leave it)" column>
        <FormDemo />
      </Demo>

      <Demo title="FileUpload (PNG, JPG or PDF up to 2 MB)" column>
        <FileUpload
          accept={['image/png', 'image/jpeg', 'application/pdf']}
          maxBytes={2 * 1024 * 1024}
          hint="PNG, JPG or PDF, up to 2 MB. This demo fakes the upload."
          multiple
          upload={async (file, { onProgress, signal }) => {
            // A real page asks the API for presigned instructions, then calls
            // uploadToPresignedUrl(instructions, file, { onProgress, signal }) from @/lib/upload.
            for (let percent = 0; percent <= 100; percent += 20) {
              if (signal.aborted) throw new DOMException('Upload cancelled', 'AbortError')
              onProgress(percent)
              await new Promise((resolve) => setTimeout(resolve, 250))
            }
            if (file.name.toLowerCase().includes('fail')) {
              throw new ApiError({ status: 503, code: 'STORAGE_UNAVAILABLE', detail: '' })
            }
            return file.name
          }}
        />
        <p className="text-sm text-muted">
          Name a file with &quot;fail&quot; in it to see the error and Retry.
        </p>
      </Demo>
    </Section>
  )
}
