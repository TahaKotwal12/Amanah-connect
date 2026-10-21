interface ImportMetaEnv {
  /** Where the dev server forwards /api (dev and preview only; not part of the bundle). */
  readonly VITE_API_URL?: string
  /** Public address of the site, no trailing slash. Used for the canonical link. */
  readonly VITE_SITE_URL?: string
}
