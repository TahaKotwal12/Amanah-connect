/**
 * English strings. The only locale for now.
 *
 * Convention: one namespace per area, keys in camelCase, plain sentences with `{{values}}` for
 * anything that varies (never build a sentence by gluing pieces). To add a language, copy this
 * file to `<code>.ts`, translate the values, register it in ./index.ts and set `lng`.
 *
 * Migrated so far: the app shell (skip link, offline banner, loading, 404) and the install and
 * update prompts. The API error messages live in lib/errors.ts (one map, keyed by the backend's
 * stable codes) and page copy still lives in the components. Move a page over by replacing its
 * literals with `t('area.key')`.
 */
export const en = {
  common: {
    skipToContent: 'Skip to main content',
    loading: 'Loading',
    loadingPage: 'Loading page',
    retry: 'Try again',
    close: 'Close',
  },
  shell: {
    offlineTitle: 'You are offline',
    offlineBody: 'Changes you make now will not be saved. We will reconnect on our own.',
    backOnline: 'You are back online.',
    notFoundEyebrow: 'Error 404',
    notFoundTitle: 'We could not find that page',
    notFoundBody:
      'The link may be old or mistyped. Check the address, or head back to the home page.',
    notFoundAction: 'Go to the home page',
    notFoundDocTitle: 'Page not found | Amanah Connect',
    installTitle: 'Install Amanah Connect',
    installBody: 'Add it to your home screen to open it like an app.',
    installAction: 'Install',
    installDismiss: 'Not now',
    updateReady: 'A new version is ready.',
    updateAction: 'Reload',
  },
} as const
