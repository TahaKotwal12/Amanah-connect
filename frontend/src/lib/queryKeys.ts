/**
 * Query key factories. Keys are hierarchical (scope, resource, kind, params) so a mutation can
 * invalidate a whole resource (`memberKeys.all`), just its lists (`memberKeys.lists()`), or one
 * record (`memberKeys.detail(id)`) without string guessing.
 *
 * Each feature declares its own in features/<feature>/queryKeys.ts using this helper.
 */
export function resourceKeys<TFilters = void>(scope: string, resource: string) {
  const all = [scope, resource] as const
  return {
    all,
    lists: () => [...all, 'list'] as const,
    list: (filters: TFilters) => [...all, 'list', filters] as const,
    details: () => [...all, 'detail'] as const,
    detail: (id: string) => [...all, 'detail', id] as const,
  }
}
