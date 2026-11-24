import { WifiOff } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useOnlineStatus } from '@/lib/useOnlineStatus'

/**
 * Tells the person when the browser loses its connection. It is a status message, not an alert:
 * screen readers announce it politely (a live region, not role="status", so it never clashes with
 * the page's own status messages) and it does not steal focus. When the connection comes back
 * it says so briefly, then goes away.
 */
export function OfflineBanner() {
  const { t } = useTranslation('shell')
  const online = useOnlineStatus()
  const [justBack, setJustBack] = useState(false)

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    const handleOnline = () => {
      setJustBack(true)
      timer = setTimeout(() => {
        setJustBack(false)
      }, 4000)
    }
    const handleOffline = () => {
      clearTimeout(timer)
      setJustBack(false)
    }
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    return () => {
      clearTimeout(timer)
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [])

  return (
    <div aria-live="polite" aria-atomic="true" data-testid="connection-status">
      {!online ? (
        <div className="sticky top-0 z-[60] flex items-start gap-3 bg-warning px-4 py-2 text-sm text-white">
          <WifiOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <p>
            <strong className="font-semibold">{t('offlineTitle')}.</strong> {t('offlineBody')}
          </p>
        </div>
      ) : justBack ? (
        <p className="sticky top-0 z-[60] bg-success px-4 py-2 text-sm text-white">
          {t('backOnline')}
        </p>
      ) : null}
    </div>
  )
}
