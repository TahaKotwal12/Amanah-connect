import { X } from 'lucide-react'
import { useMemo } from 'react'
import { Combobox } from '@/shared/components/ui/combobox'
import type { Uuid } from '@/types'
import { useMembers } from '../hooks/useMembers'

const PICKER_SIZE = 100

/** Members to choose from: the first 100 by name, searchable in the box. */
function useMemberOptions() {
  const members = useMembers({ size: PICKER_SIZE, sort: ['name,asc'], status: 'ACTIVE' })
  const options = useMemo(
    () =>
      (members.data?.items ?? []).map((member) => ({
        value: member.id,
        label: `${member.fullName} (${member.memberNo})`,
      })),
    [members.data],
  )
  return { options, loading: members.isLoading }
}

interface MemberPickerProps {
  value: Uuid
  onChange: (value: Uuid) => void
  id?: string
  disabled?: boolean
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string | undefined
}

export function MemberPicker({
  value,
  onChange,
  id,
  disabled,
  'aria-invalid': invalid,
  'aria-describedby': describedBy,
}: MemberPickerProps) {
  const { options, loading } = useMemberOptions()
  return (
    <Combobox
      {...(id ? { id } : {})}
      {...(invalid !== undefined ? { 'aria-invalid': invalid } : {})}
      {...(describedBy ? { 'aria-describedby': describedBy } : {})}
      options={options}
      value={value || undefined}
      onValueChange={onChange}
      placeholder={loading ? 'Loading members' : 'Choose a member'}
      searchPlaceholder="Search members"
      {...(disabled !== undefined ? { disabled } : {})}
    />
  )
}

interface MemberMultiPickerProps {
  value: Uuid[]
  onChange: (value: Uuid[]) => void
  id?: string
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string | undefined
}

/** Several members: a searchable list, and the chosen ones as removable chips. */
export function MemberMultiPicker({
  value,
  onChange,
  id,
  'aria-invalid': invalid,
  'aria-describedby': describedBy,
}: MemberMultiPickerProps) {
  const { options, loading } = useMemberOptions()
  const names = useMemo(
    () => new Map(options.map((option) => [option.value, option.label])),
    [options],
  )
  const remaining = options.filter((option) => !value.includes(option.value))
  return (
    <div className="grid gap-2">
      <Combobox
        {...(id ? { id } : {})}
        {...(invalid !== undefined ? { 'aria-invalid': invalid } : {})}
        {...(describedBy ? { 'aria-describedby': describedBy } : {})}
        options={remaining}
        onValueChange={(next) => next && onChange([...value, next])}
        placeholder={loading ? 'Loading members' : 'Add a member'}
        searchPlaceholder="Search members"
        aria-label="Add a member"
      />
      {value.length > 0 ? (
        <ul className="flex flex-wrap gap-2" aria-label="Chosen members">
          {value.map((memberId) => {
            const name = names.get(memberId) ?? 'Member'
            return (
              <li
                key={memberId}
                className="flex items-center gap-1 rounded border border-brand-200 bg-brand-100 py-0.5 pl-2 pr-0.5 text-sm text-brand-900"
              >
                {name}
                <button
                  type="button"
                  aria-label={`Remove ${name}`}
                  onClick={() => onChange(value.filter((item) => item !== memberId))}
                  className="flex size-8 items-center justify-center rounded hover:bg-brand-200"
                >
                  <X aria-hidden="true" className="size-4" />
                </button>
              </li>
            )
          })}
        </ul>
      ) : null}
    </div>
  )
}
