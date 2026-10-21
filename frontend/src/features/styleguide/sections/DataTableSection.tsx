import { Eye, Pencil } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { PaginationState, SortingState } from '@tanstack/react-table'
import { ApiError } from '@/lib/apiError'
import { compareMoney, formatMoney } from '@/lib/money'
import { formatDate } from '@/lib/dates'
import {
  Button,
  DataTable,
  DropdownMenuItem,
  SimpleSelect,
  StatusBadge,
  createDataTableColumns,
  notify,
} from '@/shared/components/ui'
import { useCsvExport } from '@/shared/hooks/useCsvExport'
import type { MoneyString } from '@/types'
import { Demo, Section } from '../Section'

/** Obviously made-up sample rows, so the table has something to page and sort. */
interface SampleMember {
  id: string
  memberNo: string
  fullName: string
  group: string
  status: 'ACTIVE' | 'INACTIVE'
  outstanding: MoneyString
  joinedOn: string
}

const FIRST = ['Asha', 'Vikram', 'Meera', 'Imran', 'Kavya', 'Rohan', 'Sana']
const LAST = ['Rao', 'Shah', 'Nair', 'Qureshi', 'Iyer', 'Desai', 'Khan']

const ROWS: SampleMember[] = Array.from({ length: 47 }, (_, i) => ({
  id: `sample-${i + 1}`,
  memberNo: `MEM-${String(i + 1).padStart(4, '0')}`,
  fullName: `${FIRST[i % FIRST.length] ?? ''} ${LAST[(i * 3) % LAST.length] ?? ''}`,
  group: `Block ${'ABC'[i % 3] ?? 'A'}`,
  status: i % 7 === 0 ? 'INACTIVE' : 'ACTIVE',
  outstanding: i % 4 === 0 ? '0.00' : `${(i + 1) * 125}.50`,
  joinedOn: `2025-${String((i % 12) + 1).padStart(2, '0')}-${String((i % 27) + 1).padStart(2, '0')}`,
}))

const col = createDataTableColumns<SampleMember>()
const columns = col.columns([
  col.accessor('memberNo', { header: 'No.' }),
  col.accessor('fullName', { header: 'Name' }),
  col.accessor('group', { header: 'Group' }),
  col.accessor('status', {
    header: 'Status',
    cell: (info) => <StatusBadge status={info.getValue()} />,
  }),
  col.accessor('outstanding', {
    header: 'Outstanding',
    cell: (info) => <span className="tabular-nums">{formatMoney(info.getValue())}</span>,
  }),
  col.accessor('joinedOn', { header: 'Joined', cell: (info) => formatDate(info.getValue()) }),
])

const EXPORT_COLUMNS = [
  { header: 'Member no.', value: (m: SampleMember) => m.memberNo },
  { header: 'Name', value: (m: SampleMember) => m.fullName },
  { header: 'Outstanding', value: (m: SampleMember) => m.outstanding },
]

type Mode = 'data' | 'loading' | 'error' | 'empty'

export function DataTableSection() {
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: 10 })
  const [sorting, setSorting] = useState<SortingState>([])
  const [search, setSearch] = useState('')
  const [group, setGroup] = useState('all')
  const [mode, setMode] = useState<Mode>('data')
  const exportCsv = useCsvExport('members-sample', EXPORT_COLUMNS)

  // This stands in for the server: it filters, sorts and slices exactly as the API would.
  const { page, total, all } = useMemo(() => {
    let rows = ROWS.filter(
      (row) =>
        (group === 'all' || row.group === group) &&
        (search === '' ||
          `${row.fullName} ${row.memberNo}`.toLowerCase().includes(search.toLowerCase())),
    )
    const sort = sorting[0]
    if (sort) {
      const key = sort.id as keyof SampleMember
      rows = [...rows].sort((a, b) => {
        const result =
          key === 'outstanding'
            ? compareMoney(a.outstanding, b.outstanding)
            : String(a[key]).localeCompare(String(b[key]))
        return sort.desc ? -result : result
      })
    }
    const start = pagination.pageIndex * pagination.pageSize
    return { page: rows.slice(start, start + pagination.pageSize), total: rows.length, all: rows }
  }, [pagination, sorting, search, group])

  return (
    <Section id="data-table" title="DataTable">
      <Demo title="State">
        {(['data', 'loading', 'error', 'empty'] as const).map((option) => (
          <Button
            key={option}
            size="sm"
            variant={mode === option ? 'primary' : 'secondary'}
            aria-pressed={mode === option}
            onClick={() => {
              setMode(option)
            }}
          >
            {option === 'data' ? 'Rows' : option[0]?.toUpperCase() + option.slice(1)}
          </Button>
        ))}
      </Demo>
      <p className="max-w-prose text-sm text-muted">
        Server-style: the table never fetches, it reports page, sort and search, and this page
        answers from sample data. Resize below 768 px to see the card layout.
      </p>
      <DataTable
        label="Sample members"
        columns={columns}
        data={mode === 'empty' ? [] : page}
        getRowId={(row) => row.id}
        rowCount={mode === 'empty' ? 0 : total}
        pagination={pagination}
        onPaginationChange={setPagination}
        sorting={sorting}
        onSortingChange={setSorting}
        isLoading={mode === 'loading'}
        error={
          mode === 'error'
            ? new ApiError({ status: 503, code: 'INTERNAL_ERROR', detail: '' })
            : undefined
        }
        onRetry={() => {
          setMode('data')
        }}
        search={{
          value: search,
          onSearch: (value) => {
            setSearch(value)
            setPagination((p) => ({ ...p, pageIndex: 0 }))
          },
          placeholder: 'Search name or number',
          label: 'Search sample members',
        }}
        filters={
          <SimpleSelect
            aria-label="Filter by group"
            className="w-40"
            value={group}
            onValueChange={(value) => {
              setGroup(value)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            options={[
              { value: 'all', label: 'All groups' },
              { value: 'Block A', label: 'Block A' },
              { value: 'Block B', label: 'Block B' },
              { value: 'Block C', label: 'Block C' },
            ]}
          />
        }
        onExport={() => {
          exportCsv(all)
          notify.success(`Exported ${all.length} rows`)
        }}
        rowLabel={(row) => row.fullName}
        rowActions={(row) => (
          <>
            <DropdownMenuItem
              onSelect={() => {
                notify.info(`View ${row.fullName}`)
              }}
            >
              <Eye aria-hidden="true" className="size-4" /> View
            </DropdownMenuItem>
            <DropdownMenuItem
              onSelect={() => {
                notify.info(`Edit ${row.fullName}`)
              }}
            >
              <Pencil aria-hidden="true" className="size-4" /> Edit
            </DropdownMenuItem>
          </>
        )}
      />
    </Section>
  )
}
