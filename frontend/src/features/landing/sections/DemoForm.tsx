import { useMutation } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { Button } from '@/shared/components/ui/button'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Input, Textarea } from '@/shared/components/ui/input'
import { submitLead } from '../api'
import { LEAD_FIELDS, leadSchema, toLeadRequest } from '../leadSchema'
import { Reveal } from '../motion'
import { SectionHeading } from '../SectionHeading'

const EMPTY = {
  name: '',
  email: '',
  phone: '',
  communityName: '',
  size: '',
  message: '',
  website: '',
}

export default function DemoForm() {
  const form = useZodForm(leadSchema, { defaultValues: EMPTY })
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const success = useRef<HTMLDivElement>(null)

  const mutation = useMutation({
    mutationFn: submitLead,
    meta: { silent: true }, // errors are shown inline, next to the form
  })

  useEffect(() => {
    if (sentTo) success.current?.focus()
  }, [sentTo])

  return (
    <section aria-labelledby="demo-title" className="bg-brand-50 py-16 sm:py-24">
      <div className="container grid gap-10 lg:grid-cols-[0.85fr_1.15fr] lg:gap-16">
        <div className="grid content-start gap-6">
          <SectionHeading
            id="demo-title"
            align="left"
            eyebrow="Request a demo"
            title="See it with your own community in mind"
            intro="Tell us a little about your community. We will reply by email to arrange a walk-through."
          />
          <ul className="grid gap-2 text-ink">
            <li className="flex gap-2">
              <CheckCircle2 aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-brand-600" />
              No payment details are asked for.
            </li>
            <li className="flex gap-2">
              <CheckCircle2 aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-brand-600" />
              Only your name and email are required.
            </li>
          </ul>
        </div>

        <Reveal>
          <div className="rounded-card border border-brand-100 bg-white p-6 shadow-soft sm:p-8">
            {sentTo ? (
              <div
                ref={success}
                tabIndex={-1}
                role="status"
                className="grid gap-3 text-center outline-none"
              >
                <CheckCircle2 aria-hidden="true" className="mx-auto size-10 text-success" />
                <h3 className="font-heading text-2xl font-semibold text-brand-900">Thank you</h3>
                <p className="text-ink">
                  We have your request. We will write to <strong>{sentTo}</strong> to arrange the
                  demo.
                </p>
                <div>
                  <Button
                    variant="secondary"
                    onClick={() => {
                      form.reset(EMPTY)
                      setSentTo(null)
                    }}
                  >
                    Send another request
                  </Button>
                </div>
              </div>
            ) : (
              <Form {...form}>
                <form
                  noValidate
                  aria-label="Request a demo"
                  className="grid gap-5"
                  onSubmit={(event) => {
                    setFormError(null)
                    void form.handleSubmit(async (values) => {
                      try {
                        await mutation.mutateAsync(toLeadRequest(values))
                        setSentTo(values.email)
                      } catch (error) {
                        const unmatched = applyApiFieldErrors(form, error, LEAD_FIELDS)
                        const problem = toApiError(error)
                        // Field problems are shown on the fields; anything else (rate limit,
                        // server, network) is said once above the button.
                        if (
                          problem.code !== 'VALIDATION_FAILED' ||
                          Object.keys(unmatched).length > 0
                        ) {
                          setFormError(describeError(problem))
                        }
                      }
                    })(event)
                  }}
                >
                  <div className="grid gap-5 sm:grid-cols-2">
                    <FormField control={form.control} name="name" label="Your name" required>
                      {({ field, control }) => (
                        <Input autoComplete="name" {...field} {...control} />
                      )}
                    </FormField>
                    <FormField control={form.control} name="email" label="Email" required>
                      {({ field, control }) => (
                        <Input type="email" autoComplete="email" {...field} {...control} />
                      )}
                    </FormField>
                    <FormField
                      control={form.control}
                      name="phone"
                      label="Phone"
                      description="Optional"
                    >
                      {({ field, control }) => (
                        <Input type="tel" autoComplete="tel" {...field} {...control} />
                      )}
                    </FormField>
                    <FormField
                      control={form.control}
                      name="communityName"
                      label="Community name"
                      description="Optional"
                    >
                      {({ field, control }) => (
                        <Input autoComplete="organization" {...field} {...control} />
                      )}
                    </FormField>
                  </div>
                  <FormField
                    control={form.control}
                    name="size"
                    label="Approximate number of members"
                    description="Optional. A rough number is fine."
                  >
                    {({ field, control }) => (
                      <Input
                        inputMode="numeric"
                        autoComplete="off"
                        className="sm:max-w-48"
                        {...field}
                        {...control}
                      />
                    )}
                  </FormField>
                  <FormField
                    control={form.control}
                    name="message"
                    label="Anything we should know?"
                    description="Optional"
                  >
                    {({ field, control }) => <Textarea {...field} {...control} />}
                  </FormField>

                  {/* Honeypot: off-screen and hidden from assistive tech. Real visitors never see it. */}
                  <div
                    aria-hidden="true"
                    className="absolute -left-[9999px] h-0 w-0 overflow-hidden"
                  >
                    <label>
                      Leave this field empty
                      <input
                        type="text"
                        tabIndex={-1}
                        autoComplete="off"
                        {...form.register('website')}
                      />
                    </label>
                  </div>

                  {formError ? (
                    <p
                      role="alert"
                      className="rounded border border-danger/30 bg-danger-50 p-3 text-sm font-medium text-danger"
                    >
                      {formError}
                    </p>
                  ) : null}
                  <div>
                    <Button type="submit" size="lg" loading={form.formState.isSubmitting}>
                      Request a demo
                    </Button>
                  </div>
                </form>
              </Form>
            )}
          </div>
        </Reveal>
      </div>
    </section>
  )
}
