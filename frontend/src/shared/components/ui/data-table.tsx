import {
  columnVisibilityFeature,
  createColumnHelper,
  rowPaginationFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type ColumnDef,
  type PaginationState,
  type SortingState,
  type Updater,
  type ColumnVisibilityState,
  type RowData,
} from '@tanstack/react-table'
import {
  ArrowDown,
  ArrowUp,
  ChevronsUpDown,
  Columns3,
  Download,
  EllipsisVertical,
} from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { useMediaQuery } from '@/shared/hooks/useMediaQuery'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './dropdown-menu'
import { EmptyState } from './empty-state'
import { ErrorState } from './error-state'
import { Pagination } from './pagination'
import { SearchInput } from './search-input'
import { Skeleton } from './skeleton'

// Features are registered once at module scope, as TanStack Table v9 requires.
const features = tableFeatures({ columnVisibilityFeature, rowSortingFeature, rowPaginationFeature })
export type DataTableFeatures = typeof features
export type DataTableColumn<TData extends RowData> = ColumnDef<DataTableFeatures, TData>

/**
 * Typed helper for defining columns:
 *
 *   const col = createDataTableColumns<Member>()
 *   const columns = col.columns([
 *     col.accessor('fullName', { header: 'Name' }),
 *     col.accessor('status', { header: 'Status', cell: (info) => <StatusBadge status={info.getValue()} /> }),
 *   ])
 *
 * Define `columns` at module scope or in useMemo: a new array on every render resets the table.
 */
export function createDataTableColumns<TData extends RowData>() {
  return createColumnHelper<DataTableFeatures, TData>()
}

/** The API's `sort` parameter from table state: [{id:'name',desc:true}] becomes ['name,desc']. */
export function sortingToParams(sorting: SortingState): string[] {
  return sorting.map((entry) => `${entry.id},${entry.desc ? 'desc' : 'asc'}`)
}

/** The reverse, to restore table state from the URL or saved filters. */
export function paramsToSorting(params: readonly string[]): SortingState {
  return params.map((param) => {
    const [id = '', direction] = param.split(',')
    return { id, desc: direction === 'desc' }
  })
}

const NO_SORTING: SortingState = []
const SKELETON_ROWS = 5

export interface DataTableProps<TData extends RowData> {
  /** Names the table for screen readers (its caption). */
  label: string
  columns: DataTableColumn<TData>[]
  /** The current page's rows only. Keep the array stable between renders (React Query data is). */
  data: TData[]
  getRowId: (row: TData) => string
  /** Total rows on the server, across all pages. */
  rowCount: number

  /** Zero-based page and page size. The table never fetches: change these and refetch. */
  pagination: PaginationState
  onPaginationChange: (pagination: PaginationState) => void
  sorting?: SortingState
  onSortingChange?: (sorting: SortingState) => void
  /** Controlled column visibility; omit and the table remembers it for the session. */
  columnVisibility?: ColumnVisibilityState
  onColumnVisibilityChange?: (visibility: ColumnVisibilityState) => void

  /** First load: shows skeleton rows. */
  isLoading?: boolean
  /** A refetch (page change, filter): keeps the old rows, dimmed, and marks the table busy. */
  isFetching?: boolean
  error?: unknown
  onRetry?: () => void

  /** Built-in debounced search box. For other filters use the `filters` slot. */
  search?: {
    value: string
    onSearch: (value: string) => void
    placeholder?: string
    label?: string
  }
  /** Extra filter controls (status select, date range) shown next to the search box. */
  filters?: ReactNode
  /** Buttons at the end of the toolbar, for example "Add member". */
  toolbarActions?: ReactNode
  /** Shows an Export CSV button; the page decides what to export (see useCsvExport). */
  onExport?: () => void
  emptyState?: ReactNode

  /** Menu items for a row (DropdownMenuItem children). Adds a kebab menu column. */
  rowActions?: (row: TData) => ReactNode
  /** Name of the row for the menu button: "Actions for Asha Rao". */
  rowLabel?: (row: TData) => string
  /** Custom mobile card; the default stacks header and value pairs. */
  mobileCard?: (row: TData) => ReactNode

  stickyHeader?: boolean
  className?: string
}

function headerText<TData extends RowData>(column: {
  id: string
  columnDef: DataTableColumn<TData>
}): string {
  const header = column.columnDef.header
  return typeof header === 'string' ? header : column.id
}

export function DataTable<TData extends RowData>({
  label,
  columns,
  data,
  getRowId,
  rowCount,
  pagination,
  onPaginationChange,
  sorting = NO_SORTING,
  onSortingChange,
  columnVisibility,
  onColumnVisibilityChange,
  isLoading = false,
  isFetching = false,
  error,
  onRetry,
  search,
  filters,
  toolbarActions,
  onExport,
  emptyState,
  rowActions,
  rowLabel,
  mobileCard,
  stickyHeader = true,
  className,
}: DataTableProps<TData>) {
  const isDesktop = useMediaQuery('(min-width: 768px)')
  const [ownVisibility, setOwnVisibility] = useState<ColumnVisibilityState>({})
  const visibility = columnVisibility ?? ownVisibility

  const allColumns = useMemo(() => {
    if (!rowActions) return columns
    const helper = createColumnHelper<DataTableFeatures, TData>()
    const actions = helper.display({
      id: 'actions',
      header: 'Actions',
      enableHiding: false,
      enableSorting: false,
      cell: ({ row }) => {
        const name = rowLabel?.(row.original) ?? 'row'
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" aria-label={`Actions for ${name}`}>
                <EllipsisVertical aria-hidden="true" className="size-5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>{rowActions(row.original)}</DropdownMenuContent>
          </DropdownMenu>
        )
      },
    })
    return [...columns, actions]
  }, [columns, rowActions, rowLabel])

  const table = useTable({
    features,
    columns: allColumns,
    data,
    getRowId,
    rowCount,
    manualPagination: true,
    manualSorting: true,
    state: { pagination, sorting, columnVisibility: visibility },
    onSortingChange: (updater: Updater<SortingState>) => {
      const next = typeof updater === 'function' ? updater(sorting) : updater
      onSortingChange?.(next)
      // A new order starts from the first page.
      onPaginationChange({ ...pagination, pageIndex: 0 })
    },
    onPaginationChange: (updater: Updater<PaginationState>) => {
      onPaginationChange(typeof updater === 'function' ? updater(pagination) : updater)
    },
    onColumnVisibilityChange: (updater: Updater<ColumnVisibilityState>) => {
      const next = typeof updater === 'function' ? updater(visibility) : updater
      if (onColumnVisibilityChange) onColumnVisibilityChange(next)
      else setOwnVisibility(next)
    },
  })

  const rows = table.getRowModel().rows
  const hideableColumns = table.getAllLeafColumns().filter((column) => column.getCanHide())
  const hasToolbar =
    !!(search ?? filters ?? toolbarActions ?? onExport) || hideableColumns.length > 1
  const showEmpty = !isLoading && !error && rows.length === 0

  return (
    <div className={cn('grid gap-4', className)}>
      {hasToolbar ? (
        <div className="flex flex-wrap items-center gap-3">
          {search ? (
            <SearchInput
              className="w-full sm:w-72"
              value={search.value}
              onSearch={search.onSearch}
              {...(search.placeholder ? { placeholder: search.placeholder } : {})}
              {...(search.label ? { label: search.label } : {})}
            />
          ) : null}
          {filters}
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {hideableColumns.length > 1 ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="secondary">
                    <Columns3 aria-hidden="true" className="size-4" />
                    Columns
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent>
                  <DropdownMenuLabel>Show columns</DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  {hideableColumns.map((column) => (
                    <DropdownMenuCheckboxItem
                      key={column.id}
                      checked={column.getIsVisible()}
                      onCheckedChange={(checked) => {
                        column.toggleVisibility(checked)
                      }}
                      onSelect={(event) => {
                        event.preventDefault() // keep the menu open while toggling several
                      }}
                    >
                      {headerText({ id: column.id, columnDef: column.columnDef })}
                    </DropdownMenuCheckboxItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
            {onExport ? (
              <Button variant="secondary" onClick={onExport}>
                <Download aria-hidden="true" className="size-4" />
                Export CSV
              </Button>
            ) : null}
            {toolbarActions}
          </div>
        </div>
      ) : null}

      {isLoading ? (
        <p role="status" className="sr-only">
          Loading {label}
        </p>
      ) : null}

      {error ? (
        <ErrorState error={error} {...(onRetry ? { onRetry } : {})} retrying={isFetching} />
      ) : showEmpty ? (
        (emptyState ?? (
          <EmptyState
            title="Nothing to show"
            description={
              search?.value
                ? `No results for "${search.value}". Try a different search.`
                : 'There are no records here yet.'
            }
          />
        ))
      ) : isDesktop ? (
        <div
          role="region"
          aria-label={`${label} table`}
          // A scrolling region must be reachable by keyboard, or its overflow is unreadable without a mouse.
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          tabIndex={0}
          className={cn(
            'overflow-auto rounded-card border border-brand-100 bg-white shadow-soft',
            stickyHeader && 'max-h-[70vh]',
          )}
        >
          <table
            className="w-full border-collapse text-left text-sm"
            aria-busy={isFetching || undefined}
          >
            <caption className="sr-only">{label}</caption>
            <thead className={cn(stickyHeader && 'sticky top-0 z-10')}>
              {table.getHeaderGroups().map((group) => (
                <tr key={group.id} className="bg-brand-50">
                  {group.headers.map((header) => {
                    const sorted = header.column.getIsSorted()
                    const canSort = header.column.getCanSort()
                    return (
                      <th
                        key={header.id}
                        scope="col"
                        aria-sort={
                          sorted === 'asc'
                            ? 'ascending'
                            : sorted === 'desc'
                              ? 'descending'
                              : undefined
                        }
                        className="whitespace-nowrap border-b border-brand-100 px-4 py-3 font-semibold text-brand-900"
                      >
                        {header.isPlaceholder ? null : header.column.id === 'actions' ? (
                          <span className="sr-only">
                            <table.FlexRender header={header} />
                          </span>
                        ) : canSort ? (
                          <button
                            type="button"
                            onClick={(event) => header.column.getToggleSortingHandler()?.(event)}
                            className="-mx-2 inline-flex min-h-11 items-center gap-1.5 rounded px-2 hover:bg-brand-100"
                          >
                            <table.FlexRender header={header} />
                            {sorted === 'asc' ? (
                              <ArrowUp aria-hidden="true" className="size-4" />
                            ) : sorted === 'desc' ? (
                              <ArrowDown aria-hidden="true" className="size-4" />
                            ) : (
                              <ChevronsUpDown aria-hidden="true" className="size-4 text-muted" />
                            )}
                            <span className="sr-only">
                              {sorted === 'asc'
                                ? ', sorted ascending'
                                : sorted === 'desc'
                                  ? ', sorted descending'
                                  : ', click to sort'}
                            </span>
                          </button>
                        ) : (
                          <table.FlexRender header={header} />
                        )}
                      </th>
                    )
                  })}
                </tr>
              ))}
            </thead>
            <tbody className={cn(isFetching && !isLoading && 'opacity-60 transition-opacity')}>
              {isLoading
                ? Array.from({ length: SKELETON_ROWS }, (_, rowIndex) => (
                    <tr key={rowIndex} className="border-b border-brand-100">
                      {table.getVisibleLeafColumns().map((column) => (
                        <td key={column.id} className="px-4 py-3">
                          <Skeleton className="h-5 w-full max-w-40" />
                        </td>
                      ))}
                    </tr>
                  ))
                : rows.map((row) => (
                    <tr
                      key={row.id}
                      className="border-b border-brand-100 last:border-0 hover:bg-brand-50/60"
                    >
                      {row.getVisibleCells().map((cell) => (
                        <td key={cell.id} className="px-4 py-3 align-middle">
                          <table.FlexRender cell={cell} />
                        </td>
                      ))}
                    </tr>
                  ))}
            </tbody>
          </table>
        </div>
      ) : (
        <ul
          aria-label={label}
          aria-busy={isFetching || undefined}
          className={cn('grid gap-3', isFetching && !isLoading && 'opacity-60')}
        >
          {isLoading
            ? Array.from({ length: 3 }, (_, index) => (
                <li key={index} className="rounded-card border border-brand-100 bg-white p-4">
                  <Skeleton className="h-5 w-40" />
                  <Skeleton className="mt-3 h-4 w-full" />
                  <Skeleton className="mt-2 h-4 w-2/3" />
                </li>
              ))
            : rows.map((row) => {
                const cells = row.getVisibleCells()
                const actionCell = cells.find((cell) => cell.column.id === 'actions')
                const dataCells = cells.filter((cell) => cell.column.id !== 'actions')
                const [titleCell, ...rest] = dataCells
                return (
                  <li
                    key={row.id}
                    className="rounded-card border border-brand-100 bg-white p-4 shadow-soft"
                  >
                    {mobileCard ? (
                      mobileCard(row.original)
                    ) : (
                      <>
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0 font-semibold text-brand-900">
                            {titleCell ? <table.FlexRender cell={titleCell} /> : null}
                          </div>
                          {actionCell ? <table.FlexRender cell={actionCell} /> : null}
                        </div>
                        <dl className="mt-2 grid gap-1.5 text-sm">
                          {rest.map((cell) => (
                            <div
                              key={cell.id}
                              className="flex items-baseline justify-between gap-3"
                            >
                              <dt className="text-muted">
                                {headerText({
                                  id: cell.column.id,
                                  columnDef: cell.column.columnDef,
                                })}
                              </dt>
                              <dd className="min-w-0 text-right">
                                <table.FlexRender cell={cell} />
                              </dd>
                            </div>
                          ))}
                        </dl>
                      </>
                    )}
                  </li>
                )
              })}
        </ul>
      )}

      {error || showEmpty ? null : (
        <Pagination
          page={pagination.pageIndex}
          pageSize={pagination.pageSize}
          total={rowCount}
          onPageChange={(page) => {
            onPaginationChange({ ...pagination, pageIndex: page })
          }}
          onPageSizeChange={(size) => {
            onPaginationChange({ pageIndex: 0, pageSize: size })
          }}
        />
      )}
    </div>
  )
}
