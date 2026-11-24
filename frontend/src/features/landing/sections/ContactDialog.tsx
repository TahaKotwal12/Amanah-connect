import { useMutation } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { useState } from 'react'
import { z } from 'zod'
import { toApiError } from '@/lib/apiError'
import { describeError } from '@/lib/errors'
import { Button } from '@/shared/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/shared/components/ui/dialog'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input, Textarea } from '@/shared/components/ui/input'
import { submitLead } from '../api'
import { leadSchema, toLeadRequest } from '../leadSchema'

/** The demo form's rules, with the message required (it is the point of writing to us). */
const contactSchema = leadSchema.extend({
  message: z
    .string()
    .trim()
    .min(1, 'Write us a few words')
    .max(1900, 'Use 1,900 characters or fewer'),
})

const EMPTY = {
  name: '',
  email: '',
  phone: '',
  communityName: '',
  size: '',
  message: '',
  website: '',
}
const FIELDS = ['name', 'email', 'phone', 'message'] as const

/** The "Contact Us" button and the form it opens. Messages arrive as leads, marked as enquiries. */
export function ContactDialog() {
  const [open, setOpen] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useZodForm(contactSchema, { defaultValues: EMPTY })
  const mutation = useMutation({ mutationFn: submitLead, meta: { silent: true } })

  function onOpenChange(next: boolean) {
    setOpen(next)
    if (!next) {
      // A fresh form next time; what was sent stays sent.
      setSentTo(null)
      setFormError(null)
      form.reset(EMPTY)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button variant="premium" size="lg">
          Contact Us
        </Button>
      </DialogTrigger>
      <DialogContent>
        {sentTo ? (
          <div role="status" className="grid gap-3 py-4 text-center">
            <CheckCircle2 aria-hidden="true" className="mx-auto size-10 text-success" />
            <DialogTitle className="text-2xl">Thank you</DialogTitle>
            <DialogDescription className="text-ink">
              We have your message. We will reply to <strong>{sentTo}</strong>.
            </DialogDescription>
            <div>
              <Button
                variant="secondary"
                onClick={() => {
                  onOpenChange(false)
                }}
              >
                Close
              </Button>
            </div>
          </div>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Contact Us</DialogTitle>
              <DialogDescription>
                Tell us what you need. Only your name, email and message are required.
              </DialogDescription>
            </DialogHeader>
            <Form {...form}>
              <form
                noValidate
                aria-label="Contact us"
                className="grid gap-4"
                onSubmit={(event) => {
                  setFormError(null)
                  void form.handleSubmit(async (values) => {
                    try {
                      const request = toLeadRequest(values)
                      await mutation.mutateAsync({
                        ...request,
                        message: `Contact enquiry: ${values.message}`,
                      })
                      setSentTo(values.email)
                    } catch (error) {
                      const unmatched = applyApiFieldErrors(form, error, FIELDS)
                      const problem = toApiError(error)
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
                <FormField control={form.control} name="name" label="Your name" required>
                  {({ field, control }) => <Input autoComplete="name" {...field} {...control} />}
                </FormField>
                <div className="grid gap-4 sm:grid-cols-2">
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
                </div>
                <FormField control={form.control} name="message" label="Your message" required>
                  {({ field, control }) => <Textarea rows={4} {...field} {...control} />}
                </FormField>

                {/* Honeypot: off-screen and hidden from assistive tech. Real visitors never see it. */}
                <div
                  aria-hidden="true"
                  className="absolute -start-[9999px] h-0 w-0 overflow-hidden"
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
                    Send message
                  </Button>
                </div>
              </form>
            </Form>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
