interface ImportMetaEnv {
  /** Where the dev server forwards /api (dev and preview only; not part of the bundle). */
  readonly VITE_API_URL?: string
  /** Public address of the site, no trailing slash. Used for the canonical link. */
  readonly VITE_SITE_URL?: string
  /** Turns error reporting on. Unset means off and the SDK is not downloaded. */
  readonly VITE_SENTRY_DSN?: string
  readonly VITE_SENTRY_ENV?: string
  /** Release name sent with reports so source maps can be matched. */
  readonly VITE_RELEASE?: string
}
