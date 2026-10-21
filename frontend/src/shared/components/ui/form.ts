import { zodResolver } from '@hookform/resolvers/zod'
import {
  useForm,
  type FieldValues,
  type Path,
  type Resolver,
  type UseFormProps,
  type UseFormReturn,
} from 'react-hook-form'
import type { z } from 'zod'
import { toApiError } from '@/lib/apiError'

/**
 * React Hook Form validated by a zod schema. Errors show once a field has been visited
 * (`onTouched`), then re-validate on every change.
 *
 *   const schema = z.object({ email: z.email('Enter a valid email address') })
 *   const form = useZodForm(schema, { defaultValues: { email: '' } })
 */
export function useZodForm<TSchema extends z.ZodType<FieldValues, FieldValues>>(
  schema: TSchema,
  options?: Omit<UseFormProps<z.input<TSchema>, unknown, z.output<TSchema>>, 'resolver'>,
): UseFormReturn<z.input<TSchema>, unknown, z.output<TSchema>> {
  return useForm<z.input<TSchema>, unknown, z.output<TSchema>>({
    mode: 'onTouched',
    ...options,
    // The resolver is typed for the schema's loosest shape; narrowing it to this form's input and
    // output types is what useForm expects.
    resolver: zodResolver(schema) as unknown as Resolver<
      z.input<TSchema>,
      unknown,
      z.output<TSchema>
    >,
  })
}

/**
 * Show a VALIDATION_FAILED response from the API on the matching fields, then focus the first.
 * Returns the messages whose field is not on this form, so the caller can show them elsewhere.
 */
export function applyApiFieldErrors<TFieldValues extends FieldValues>(
  form: UseFormReturn<TFieldValues, unknown, FieldValues>,
  error: unknown,
  knownFields: readonly string[],
): Record<string, string> {
  const messages = toApiError(error).fieldErrorMap()
  const unmatched: Record<string, string> = {}
  let first = true
  for (const [field, message] of Object.entries(messages)) {
    if (knownFields.includes(field)) {
      form.setError(
        field as Path<TFieldValues>,
        { type: 'server', message },
        { shouldFocus: first },
      )
      first = false
    } else {
      unmatched[field] = message
    }
  }
  return unmatched
}
