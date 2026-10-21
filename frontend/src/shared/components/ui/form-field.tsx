import { useId, type ReactNode } from 'react'
import {
  Controller,
  FormProvider,
  useFormContext,
  type Control,
  type ControllerFieldState,
  type ControllerRenderProps,
  type FieldPath,
  type FieldValues,
} from 'react-hook-form'
import { cn } from '@/shared/utils/cn'
import { Label } from './label'

/** <Form {...form}> provides the form to every FormField below it, so `control` can be omitted. */
export const Form = FormProvider

/** Spread these on the control: they connect it to its label, hint and error for assistive tech. */
export interface FieldControlProps {
  id: string
  name: string
  'aria-invalid': boolean
  'aria-describedby': string | undefined
  'aria-required': boolean | undefined
}

interface FormFieldProps<TFieldValues extends FieldValues, TName extends FieldPath<TFieldValues>> {
  name: TName
  /**
   * Pass `form.control` to get a typed `field.value`. Inside <Form> it can be omitted, but the
   * field then loses the form's types (value is `any`).
   */
  control?: Control<TFieldValues>
  label: ReactNode
  /** Help text under the label, announced with the control. */
  description?: ReactNode
  /** Adds the required marker and aria-required. Validation itself lives in the zod schema. */
  required?: boolean
  /** Keep the label for screen readers only. */
  hideLabel?: boolean
  /** For radio groups and other multi-control fields, whose label is not a <label for>. */
  asGroup?: boolean
  className?: string
  children: (props: {
    field: ControllerRenderProps<TFieldValues, TName>
    fieldState: ControllerFieldState
    control: FieldControlProps
  }) => ReactNode
}

/**
 * A labelled field wired to React Hook Form: label, optional hint, the control, and an inline
 * error that is announced when it appears. Validation comes from a zod schema via useZodForm.
 *
 *   <FormField name="email" label="Email" required>
 *     {({ field, control }) => <Input type="email" {...field} {...control} />}
 *   </FormField>
 */
export function FormField<
  TFieldValues extends FieldValues,
  TName extends FieldPath<TFieldValues> = FieldPath<TFieldValues>,
>({
  name,
  control,
  label,
  description,
  required,
  hideLabel,
  asGroup,
  className,
  children,
}: FormFieldProps<TFieldValues, TName>) {
  const context = useFormContext<TFieldValues>()
  const baseId = useId()
  const id = `${baseId}-control`
  const labelId = `${baseId}-label`
  const hintId = `${baseId}-hint`
  const errorId = `${baseId}-error`

  return (
    <Controller
      name={name}
      control={control ?? context.control}
      render={({ field, fieldState }) => {
        const message = fieldState.error?.message
        const describedBy =
          [description ? hintId : null, message ? errorId : null].filter(Boolean).join(' ') ||
          undefined
        const controlProps: FieldControlProps = {
          id,
          name: field.name,
          'aria-invalid': !!fieldState.error,
          'aria-describedby': describedBy,
          'aria-required': required ? true : undefined,
        }
        const labelContent = (
          <>
            {label}
            {required ? (
              <>
                <span aria-hidden="true" className="ml-0.5 text-danger">
                  *
                </span>
                <span className="sr-only"> (required)</span>
              </>
            ) : null}
          </>
        )
        return (
          <div
            className={cn('grid gap-1.5', className)}
            {...(asGroup
              ? { role: 'group', 'aria-labelledby': labelId, 'aria-describedby': describedBy }
              : {})}
          >
            {asGroup ? (
              <span
                id={labelId}
                className={cn('text-sm font-medium text-ink', hideLabel && 'sr-only')}
              >
                {labelContent}
              </span>
            ) : (
              <Label htmlFor={id} className={cn(hideLabel && 'sr-only')}>
                {labelContent}
              </Label>
            )}
            {description ? (
              <p id={hintId} className="text-sm text-muted">
                {description}
              </p>
            ) : null}
            {children({ field, fieldState, control: controlProps })}
            {message ? (
              <p id={errorId} role="alert" className="text-sm font-medium text-danger">
                {message}
              </p>
            ) : null}
          </div>
        )
      }}
    />
  )
}
