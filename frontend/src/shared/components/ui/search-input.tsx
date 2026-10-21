import { Search, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import { cn } from '@/shared/utils/cn'
import { fieldClasses } from './input'

interface SearchInputProps {
  /** The committed search text (what the query uses). */
  value: string
  /** Called once typing pauses for `delayMs`, and immediately on clear. */
  onSearch: (value: string) => void
  /** Accessible name; the visible hint is the placeholder. */
  label?: string
  placeholder?: string
  delayMs?: number
  className?: string
}

export function SearchInput({
  value,
  onSearch,
  label = 'Search',
  placeholder = 'Search',
  delayMs = 300,
  className,
}: SearchInputProps) {
  const [text, setText] = useState(value)
  const debounced = useDebouncedValue(text, delayMs)
  const committed = useRef(value)

  // Fire the search only for changes the person made, never for the initial or external value.
  useEffect(() => {
    if (debounced !== committed.current) {
      committed.current = debounced
      onSearch(debounced)
    }
  }, [debounced, onSearch])

  // Follow outside changes (for example "clear all filters"). The parent echoing back what was just
  // committed is not a change, so it never overwrites text typed since.
  useEffect(() => {
    if (value !== committed.current) {
      committed.current = value
      setText(value)
    }
  }, [value])

  return (
    <div className={cn('relative', className)} role="search" aria-label={label}>
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted"
      />
      <input
        type="search"
        aria-label={label}
        placeholder={placeholder}
        value={text}
        onChange={(event) => {
          setText(event.target.value)
        }}
        className={cn(fieldClasses, 'px-9 [&::-webkit-search-cancel-button]:hidden')}
      />
      {text ? (
        <button
          type="button"
          aria-label="Clear search"
          onClick={() => {
            setText('')
            committed.current = ''
            onSearch('')
          }}
          className="absolute right-1 top-1/2 flex size-9 -translate-y-1/2 items-center justify-center rounded text-muted hover:text-ink"
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      ) : null}
    </div>
  )
}
