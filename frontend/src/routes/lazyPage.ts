import type { ComponentType } from 'react'
import type { LazyRouteFunction, RouteObject } from 'react-router'

/**
 * Code-split a page: each route's module becomes its own hashed chunk, fetched on first
 * navigation. Pages are default exports.
 *
 *   { path: 'members', lazy: lazyPage(() => import('@/features/community-admin/pages/MembersPage')) }
 */
export function lazyPage(
  load: () => Promise<{ default: ComponentType }>,
): LazyRouteFunction<RouteObject> {
  return async () => {
    const module = await load()
    return { Component: module.default }
  }
}
