import { useCallback, useState } from 'react'

const KEY = 'amanah.sidebar-collapsed'

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false // storage blocked (private window): just start expanded
  }
}

/** Sidebar open/closed, remembered on this device. A convenience only: nothing depends on it. */
export function useSidebarCollapsed() {
  const [collapsed, setCollapsed] = useState(read)
  const toggle = useCallback(() => {
    setCollapsed((current) => {
      const next = !current
      try {
        localStorage.setItem(KEY, next ? '1' : '0')
      } catch {
        // not remembered: fine
      }
      return next
    })
  }, [])
  return [collapsed, toggle] as const
}
