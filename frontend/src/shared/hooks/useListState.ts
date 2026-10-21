import type { PaginationState, SortingState } from '@tanstack/react-table'
import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { paramsToSorting, sortingToParams } from '@/shared/components/ui/data-table'

const DEFAULT_PAGE_SIZE = 20
const MAX_PAGE_SIZE = 100

/**
 * Page, size, sort and filters of a list kept in the URL, so a refresh, the back button and a
 * shared link all land on the same view. Changing a filter or the sort goes back to page one.
 * Filters are plain strings; an empty string means "no filter" and is left out of the URL.
 */
export function useListState<TFilter extends string>(
  filterKeys: readonly TFilter[],
  defaultSort: readonly string[] = [],
) {
  const [params, setParams] = useSearchParams()

  const pagination: PaginationState = useMemo(() => {
    const page = Number.parseInt(params.get('page') ?? '', 10)
    const size = Number.parseInt(params.get('size') ?? '', 10)
    return {
      pageIndex: Number.isFinite(page) && page > 0 ? page : 0,
      pageSize:
        Number.isFinite(size) && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE,
    }
  }, [params])

  const sortParams = useMemo(() => {
    const fromUrl = params.getAll('sort')
    return fromUrl.length > 0 ? fromUrl : [...defaultSort]
  }, [params, defaultSort])
  const sorting: SortingState = useMemo(() => paramsToSorting(sortParams), [sortParams])

  const filters = useMemo(() => {
    const out = {} as Record<TFilter, string>
    for (const key of filterKeys) out[key] = params.get(key) ?? ''
    return out
  }, [params, filterKeys])

  const update = useCallback(
    (change: (next: URLSearchParams) => void) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous)
          change(next)
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  const setFilter = useCallback(
    (key: TFilter, value: string) => {
      update((next) => {
        if (value) next.set(key, value)
        else next.delete(key)
        next.delete('page')
      })
    },
    [update],
  )

  const setPagination = useCallback(
    (value: PaginationState) => {
      update((next) => {
        if (value.pageIndex > 0) next.set('page', String(value.pageIndex))
        else next.delete('page')
        if (value.pageSize !== DEFAULT_PAGE_SIZE) next.set('size', String(value.pageSize))
        else next.delete('size')
      })
    },
    [update],
  )

  const setSorting = useCallback(
    (value: SortingState) => {
      update((next) => {
        next.delete('sort')
        for (const entry of sortingToParams(value)) next.append('sort', entry)
        next.delete('page')
      })
    },
    [update],
  )

  return { pagination, sorting, sortParams, filters, setFilter, setPagination, setSorting }
}
