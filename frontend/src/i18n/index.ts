import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { en } from './en'

/**
 * Lightweight i18n: English only, bundled (no async loading, so no flash of keys). Text direction
 * follows the language; today that is always left to right. Layout uses logical CSS (ms-, me-, ps-,
 * pe-, text-start, start-0) so a right-to-left language only needs a locale file and `dir="rtl"`.
 */
export const RTL_LANGUAGES = new Set(['ar', 'he', 'fa', 'ur'])

void i18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'common',
  ns: Object.keys(en),
  resources: { en },
  interpolation: { escapeValue: false }, // React already escapes
  returnNull: false,
})

export function applyDocumentLanguage(language: string): void {
  document.documentElement.lang = language
  document.documentElement.dir = RTL_LANGUAGES.has(language.split('-')[0] ?? '') ? 'rtl' : 'ltr'
}

i18n.on('languageChanged', applyDocumentLanguage)

export default i18n
