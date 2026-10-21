import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/apiError'
import { formatMoney } from '@/lib/money'
import { expectNoA11yViolations } from '@/test/a11y'
import { clearViewportMock, mockViewport } from '@/test/render'
import {
  DataTable,
  createDataTableColumns,
  paramsToSorting,
  sortingToParams,
  type DataTableProps,
} from './data-table'
import { DropdownMenuItem } from './dropdown-menu'
import { pageWindow } from './pagination'

interface Row {
  id: string
  name: string
  amount: string
}
const col = createDataTableColumns<Row>()
const columns = col.columns([
  col.accessor('name', { header: 'Name' }),
  col.accessor('amount', {
    header: 'Amount',
    cell: (info) => formatMoney(info.getValue()),
  }),
])
const rows: Row[] = [
  { id: '1', name: 'Asha Rao', amount: '1500.00' },
  { id: '2', name: 'Vikram Shah', amount: '250000.50' },
]

function setup(overrides: Partial<DataTableProps<Row>> = {}) {
  const props = {
    label: 'Members',
    columns,
    data: rows,
    getRowId: (row: Row) => row.id,
    rowCount: 45,
    pagination: { pageIndex: 0, pageSize: 20 },
    onPaginationChange: vi.fn(),
    sorting: [],
    onSortingChange: vi.fn(),
    ...overrides,
  } satisfies DataTableProps<Row>
  const view = render(<DataTable {...props} />)
  return { props, ...view }
}

afterEach(clearViewportMock)

describe('DataTable (desktop)', () => {
  it('renders an accessible table with formatted cells', async () => {
    const { container } = setup()
    const table = screen.getByRole('table', { name: 'Members' })
    expect(within(table).getAllByRole('columnheader')).toHaveLength(2)
    expect(within(table).getByRole('cell', { name: '₹2,50,000.50' })).toBeInTheDocument()
    expect(screen.getByText('Showing 1 to 20 of 45')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('asks for a sort, cycling ascending then descending, and returns to page one', async () => {
    const user = userEvent.setup()
    const { props, rerender } = setup()
    await user.click(screen.getByRole('button', { name: /^Name/ }))
    expect(props.onSortingChange).toHaveBeenLastCalledWith([{ id: 'name', desc: false }])
    expect(props.onPaginationChange).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 20 })

    rerender(<DataTable {...props} sorting={[{ id: 'name', desc: false }]} />)
    expect(screen.getByRole('columnheader', { name: /Name/ })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )
    await user.click(screen.getByRole('button', { name: /^Name/ }))
    expect(props.onSortingChange).toHaveBeenLastCalledWith([{ id: 'name', desc: true }])
  })

  it('pages by reporting the new zero-based page', async () => {
    const user = userEvent.setup()
    const { props } = setup({ pagination: { pageIndex: 1, pageSize: 20 } })
    expect(screen.getByText('Showing 21 to 40 of 45')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    expect(props.onPaginationChange).toHaveBeenLastCalledWith({ pageIndex: 2, pageSize: 20 })
    await user.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(props.onPaginationChange).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 20 })
  })

  it('lets people hide and show columns', async () => {
    const user = userEvent.setup()
    setup()
    // The page is aria-hidden while the menu is open, so close it before looking at the table.
    await user.click(screen.getByRole('button', { name: 'Columns' }))
    await user.click(screen.getByRole('menuitemcheckbox', { name: 'Amount' }))
    expect(screen.getByRole('menuitemcheckbox', { name: 'Amount' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('columnheader', { name: /Amount/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: '₹1,500.00' })).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Name/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Columns' }))
    await user.click(screen.getByRole('menuitemcheckbox', { name: 'Amount' }))
    await user.keyboard('{Escape}')
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toBeInTheDocument()
  })

  it('shows skeleton rows while loading, and tells screen readers', () => {
    setup({ isLoading: true })
    expect(screen.getByRole('status')).toHaveTextContent('Loading Members')
    expect(screen.queryByText('Asha Rao')).not.toBeInTheDocument()
  })

  it('keeps old rows (marked busy) during a refetch', () => {
    setup({ isFetching: true })
    expect(screen.getByRole('table')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('Asha Rao')).toBeInTheDocument()
  })

  it('shows a friendly error with a retry', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    setup({ error: new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: '' }), onRetry })
    expect(screen.getByRole('alert')).toHaveTextContent(/could not reach the server/i)
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows an empty state, mentioning the search when there is one', () => {
    setup({
      data: [],
      rowCount: 0,
      search: { value: 'zzz', onSearch: vi.fn() },
    })
    expect(screen.getByText(/No results for "zzz"/)).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument()
  })

  it('debounces the search box and offers CSV export', async () => {
    const user = userEvent.setup()
    const onSearch = vi.fn()
    const onExport = vi.fn()
    setup({ search: { value: '', onSearch, label: 'Search members' }, onExport })
    await user.type(screen.getByRole('searchbox', { name: 'Search members' }), 'asha')
    await waitFor(() => {
      expect(onSearch).toHaveBeenCalledWith('asha')
    })
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    expect(onExport).toHaveBeenCalled()
  })

  it('adds a row action menu named for the row', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    setup({
      rowLabel: (row) => row.name,
      rowActions: (row) => (
        <DropdownMenuItem
          onSelect={() => {
            onEdit(row.id)
          }}
        >
          Edit
        </DropdownMenuItem>
      ),
    })
    await user.click(screen.getByRole('button', { name: 'Actions for Vikram Shah' }))
    await user.click(screen.getByRole('menuitem', { name: 'Edit' }))
    expect(onEdit).toHaveBeenCalledWith('2')
  })
})

describe('DataTable (phone)', () => {
  it('shows each row as a card with labelled values instead of a table', async () => {
    mockViewport(false)
    const { container } = setup({
      rowLabel: (row) => row.name,
      rowActions: () => <DropdownMenuItem>Edit</DropdownMenuItem>,
    })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    const cards = within(screen.getByRole('list', { name: 'Members' })).getAllByRole('listitem')
    expect(cards).toHaveLength(2)
    expect(within(cards[1] as HTMLElement).getByText('Vikram Shah')).toBeInTheDocument()
    expect(within(cards[1] as HTMLElement).getByText('Amount')).toBeInTheDocument()
    expect(within(cards[1] as HTMLElement).getByText('₹2,50,000.50')).toBeInTheDocument()
    expect(
      within(cards[1] as HTMLElement).getByRole('button', { name: 'Actions for Vikram Shah' }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('accepts a custom card', () => {
    mockViewport(false)
    setup({ mobileCard: (row) => <p>Custom {row.name}</p> })
    expect(screen.getByText('Custom Asha Rao')).toBeInTheDocument()
  })
})

describe('table helpers', () => {
  it('converts sorting to API params and back', () => {
    const sorting = [
      { id: 'fullName', desc: true },
      { id: 'joinedOn', desc: false },
    ]
    expect(sortingToParams(sorting)).toEqual(['fullName,desc', 'joinedOn,asc'])
    expect(paramsToSorting(['fullName,desc', 'joinedOn,asc'])).toEqual(sorting)
  })

  it('windows page numbers with gaps, keeping first, last and neighbours', () => {
    expect(pageWindow(0, 5)).toEqual([0, 1, 2, 3, 4])
    expect(pageWindow(0, 20)).toEqual([0, 1, 'gap', 19])
    expect(pageWindow(10, 20)).toEqual([0, 'gap', 9, 10, 11, 'gap', 19])
    expect(pageWindow(19, 20)).toEqual([0, 'gap', 18, 19])
  })
})
