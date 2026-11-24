import { notify } from './notify'
import i18n from '@/i18n'

/**
 * Service worker registration, the "install" prompt and the "new version" prompt. Production only:
 * in development a worker would serve stale files and hide changes.
 */

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').then((registration) => {
      registration.addEventListener('updatefound', () => {
        const worker = registration.installing
        worker?.addEventListener('statechange', () => {
          // "installed" with an existing controller = an update, not the very first install.
          if (worker.state === 'installed' && navigator.serviceWorker.controller) {
            notify.info(i18n.t('shell:updateReady'), {
              id: 'sw-update',
              duration: Infinity,
              action: {
                label: i18n.t('shell:updateAction'),
                onClick: () => {
                  worker.postMessage('SKIP_WAITING')
                  navigator.serviceWorker.addEventListener('controllerchange', () => {
                    window.location.reload()
                  })
                },
              },
            })
          }
        })
      })
    })
  })
}

/**
 * Chrome and Edge fire `beforeinstallprompt` when the app qualifies. Keep the event and offer a
 * one-time, dismissible toast; never open the browser prompt without a click.
 */
export function offerInstall(): void {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault()
    const prompt = event as BeforeInstallPromptEvent
    notify.info(i18n.t('shell:installTitle'), {
      id: 'pwa-install',
      description: i18n.t('shell:installBody'),
      duration: 15_000,
      action: {
        label: i18n.t('shell:installAction'),
        onClick: () => {
          void prompt.prompt()
        },
      },
    })
  })
}
