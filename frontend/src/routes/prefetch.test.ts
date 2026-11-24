import { hasPrefetch } from './prefetch'
import { paths } from './paths'

describe('route prefetch', () => {
  it('has a loader for every navigation destination of both shells', () => {
    const nav = (
      [...Object.values(paths.admin), ...Object.values(paths.superadmin)] as unknown[]
    ).filter((value): value is string => typeof value === 'string')
    // Pages that are not in a sidebar (the roots and detail pages have no loader on purpose).
    const notInNav = new Set<string>([paths.admin.root, paths.superadmin.root])
    const missing = nav.filter((path) => !notInNav.has(path) && !hasPrefetch(path))
    expect(missing).toEqual([])
  })
})
