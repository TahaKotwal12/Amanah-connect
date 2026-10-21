import { X } from 'lucide-react'
import { useMemo } from 'react'
import { Combobox } from '@/shared/components/ui/combobox'
import type { Uuid } from '@/types'
import { useCommunities } from '../hooks/useCommunities'

interface CommunityMultiPickerProps {
  value: Uuid[]
  onChange: (value: Uuid[]) => void
  id?: string
  'aria-invalid'?: boolean | 'true' | 'false'
  'aria-describedby'?: string | undefined
}

const PICKER_SIZE = 100

/** Choose several communities: a searchable list, and the chosen ones as removable chips. */
export function CommunityMultiPicker({
  value,
  onChange,
  id,
  'aria-invalid': invalid,
  'aria-describedby': describedBy,
}: CommunityMultiPickerProps) {
  const communities = useCommunities({ size: PICKER_SIZE, sort: ['name,asc'] })
  const names = useMemo(
    () => new Map((communities.data?.items ?? []).map((item) => [item.id, item.name])),
    [communities.data],
  )
  const options = useMemo(
    () =>
      (communities.data?.items ?? [])
        .filter((item) => !value.includes(item.id))
        .map((item) => ({ value: item.id, label: item.name })),
    [communities.data, value],
  )

  return (
    <div className="grid gap-2">
      <Combobox
        {...(id ? { id } : {})}
        {...(invalid !== undefined ? { 'aria-invalid': invalid } : {})}
        {...(describedBy ? { 'aria-describedby': describedBy } : {})}
        options={options}
        onValueChange={(next) => next && onChange([...value, next])}
        placeholder={communities.isLoading ? 'Loading communities' : 'Add a community'}
        searchPlaceholder="Search communities"
        aria-label="Add a community"
      />
      {value.length > 0 ? (
        <ul className="flex flex-wrap gap-2" aria-label="Chosen communities">
          {value.map((communityId) => {
            const name = names.get(communityId) ?? 'Community'
            return (
              <li
                key={communityId}
                className="flex items-center gap-1 rounded border border-brand-200 bg-brand-100 py-0.5 pl-2 pr-0.5 text-sm text-brand-900"
              >
                {name}
                <button
                  type="button"
                  aria-label={`Remove ${name}`}
                  onClick={() => onChange(value.filter((item) => item !== communityId))}
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
