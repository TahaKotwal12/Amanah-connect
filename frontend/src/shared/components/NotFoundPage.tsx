import { useTranslation } from 'react-i18next'
import { LinkButton } from './LinkButton'

export default function NotFoundPage() {
  const { t } = useTranslation('shell')
  return (
    <section className="container flex flex-col items-center gap-4 py-24 text-center">
      <title>{t('notFoundDocTitle')}</title>
      <p className="eyebrow">{t('notFoundEyebrow')}</p>
      <h1 className="text-4xl">{t('notFoundTitle')}</h1>
      <p className="max-w-prose text-muted">{t('notFoundBody')}</p>
      <LinkButton to="/">{t('notFoundAction')}</LinkButton>
    </section>
  )
}
