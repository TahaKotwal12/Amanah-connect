import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'

/**
 * Read the one-time token from an emailed link (?token=...), then take it out of the address bar.
 * That keeps it out of browser history, screenshots and the Referer header of anything the page
 * loads. The token stays in memory for the life of the page.
 */
export function useUrlToken(): string | null {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [token] = useState(() => params.get('token'))

  useEffect(() => {
    if (params.has('token')) void navigate({ search: '' }, { replace: true })
  }, [params, navigate])

  return token && token.trim() !== '' ? token : null
}
