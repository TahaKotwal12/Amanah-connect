import { CircleCheck } from 'lucide-react'
import { useState } from 'react'
import { useParams } from 'react-router'
import { z } from 'zod'
import { isApiError, toApiError } from '@/lib/apiError'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { FormField as FieldSpec, PublicInviteView } from '@/types'
import { usePublicInvite, useRegisterWithInvite } from '../hooks/useInvites'
import { CommunityHeader } from './CommunityHeader'

/** Same pattern as the backend (MemberDtos.PHONE). */
const PHONE = /^[0-9+()\-. ]{5,30}$/

type JoinKey = 'fullName' | 'email' | 'phone' | 'group'
const KEYS: readonly JoinKey[] = ['fullName', 'email', 'phone', 'group']

/**
 * The form is what the community's invite asks for: each field says whether it is required and how
 * long it may be. A field the invite does not list is left out and always passes.
 */
function schemaFor(fields: readonly FieldSpec[]) {
  const rule = (key: JoinKey) => {
    const spec = fields.find((field) => field.name === key)
    if (!spec) return z.string()
    const max = spec.maxLength ?? 254
    const tooLong = `Use ${max} characters or fewer`
    const kind =
      spec.type === 'email'
        ? z.email('Enter a valid email address').max(max, tooLong)
        : spec.type === 'tel'
          ? z.string().trim().regex(PHONE, 'Enter a valid phone number')
          : z.string().trim().max(max, tooLong)
    return spec.required
      ? z
          .string()
          .trim()
          .min(1, `Enter your ${spec.label.toLowerCase()}`)
          .pipe(kind)
      : z.union([z.literal(''), kind])
  }
  return z.object({
    fullName: rule('fullName'),
    email: rule('email'),
    phone: rule('phone'),
    group: rule('group'),
    consentEmail: z.boolean(),
  })
}
type JoinValues = z.output<ReturnType<typeof schemaFor>>

function Message({ title, children }: { title: string; children: string }) {
  return (
    <div className="rounded-card border border-brand-100 bg-white p-6 shadow-soft">
      <h1 className="font-heading text-2xl font-semibold text-brand-900">{title}</h1>
      <p className="mt-2 text-muted">{children}</p>
    </div>
  )
}

function JoinForm({ token, view }: { token: string; view: PublicInviteView }) {
  const register = useRegisterWithInvite(token)
  const [done, setDone] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  // A honeypot: people never see or fill it, many bots do, and the server drops those.
  const [trap, setTrap] = useState('')
  const asked = view.fields.filter((field): field is FieldSpec & { name: JoinKey } =>
    KEYS.includes(field.name as JoinKey),
  )
  const form = useZodForm(schemaFor(view.fields), {
    defaultValues: {
      fullName: '',
      email: '',
      phone: '',
      group: view.defaultGroup ?? '',
      consentEmail: false,
    },
  })

  async function submit(values: JoinValues) {
    setFormError(null)
    try {
      await register.mutateAsync({
        fullName: values.fullName,
        email: values.email,
        consentEmail: values.consentEmail,
        ...(values.phone ? { phone: values.phone } : {}),
        ...(values.group ? { group: values.group } : {}),
        ...(trap ? { website: trap } : {}),
      })
      setDone(true)
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, [...KEYS, 'consentEmail'])
      const rest = Object.values(unmatched)
      if (rest.length > 0) setFormError(rest.join(' '))
      else if (isApiError(error) && error.status === 429)
        setFormError('Too many tries from this device. Please wait a little and try again.')
      else if (isApiError(error) && error.status === 404)
        setFormError('This invitation is no longer valid. Ask the community for a new link.')
      else setFormError(toApiError(error).message)
    }
  }

  if (done) {
    return (
      <div role="status" className="rounded-card border border-brand-100 bg-white p-6 shadow-soft">
        <CircleCheck aria-hidden="true" className="size-10 text-success" />
        <h1 className="mt-3 font-heading text-2xl font-semibold text-brand-900">
          Thank you, your registration was received
        </h1>
        <p className="mt-2 text-muted">
          A committee member at {view.communityName} will look at it. Once it is approved you become
          a member, and you will hear from the community by email.
        </p>
      </div>
    )
  }

  return (
    <div className="rounded-card border border-brand-100 bg-white p-6 shadow-soft">
      <h1 className="font-heading text-2xl font-semibold text-brand-900">
        Join {view.communityName}
      </h1>
      <p className="mt-1 text-muted">
        Fill in your details. A committee member approves each registration.
      </p>
      <Form {...form}>
        <form
          noValidate
          onSubmit={(event) => {
            void form.handleSubmit(submit)(event)
          }}
          className="mt-5 grid gap-4"
        >
          {formError ? (
            <p
              role="alert"
              className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
            >
              {formError}
            </p>
          ) : null}
          {asked.map((field) => {
            // The group is asked only when the link did not already decide it.
            if (field.name === 'group' && view.defaultGroup) return null
            return (
              <FormField
                key={field.name}
                control={form.control}
                name={field.name}
                label={field.name === 'group' ? view.groupLabel : field.label}
                required={field.required}
              >
                {({ field: f, control }) => (
                  <Input
                    {...f}
                    {...control}
                    type={field.type === 'email' ? 'email' : field.type === 'tel' ? 'tel' : 'text'}
                    maxLength={field.maxLength ?? undefined}
                    autoComplete={
                      field.name === 'fullName'
                        ? 'name'
                        : field.name === 'email'
                          ? 'email'
                          : field.name === 'phone'
                            ? 'tel'
                            : 'off'
                    }
                  />
                )}
              </FormField>
            )
          })}
          <Checkbox
            label={`I agree that ${view.communityName} may email me about dues, receipts and announcements`}
            description="Optional. You can still join if you leave this unticked."
            checked={form.watch('consentEmail') === true}
            onCheckedChange={(checked) =>
              form.setValue('consentEmail', checked === true, { shouldDirty: true })
            }
          />
          <div aria-hidden="true" className="absolute -left-[9999px] h-0 w-0 overflow-hidden">
            <label>
              Leave this empty
              <input
                type="text"
                name="website"
                tabIndex={-1}
                autoComplete="off"
                value={trap}
                onChange={(event) => setTrap(event.target.value)}
              />
            </label>
          </div>
          <Button type="submit" loading={register.isPending} className="w-full">
            Send my registration
          </Button>
        </form>
      </Form>
    </div>
  )
}

export default function JoinPage() {
  const { token = '' } = useParams()
  const invite = usePublicInvite(token)

  if (invite.isLoading) {
    return <Skeleton className="h-96" role="status" aria-label="Loading the invitation" />
  }
  if (invite.isError || !invite.data) {
    // The same message for an unknown, expired, used-up or revoked link: nothing to learn from it.
    return (
      <Message title="This invitation cannot be used">
        The link may have expired, been used up, or been withdrawn. Ask the community for a new link.
      </Message>
    )
  }
  return (
    <>
      <CommunityHeader name={invite.data.communityName} logoUrl={invite.data.logoUrl} />
      <JoinForm token={token} view={invite.data} />
    </>
  )
}
