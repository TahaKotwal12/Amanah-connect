import type { Plugin } from 'vite'

/**
 * Site-wide SEO files that need the public address of the site.
 *
 * VITE_SITE_URL (for example https://amanahconnect.example) is the only source of the address: it is
 * never guessed. Without it the build still works, but the tags and files that must be absolute
 * (og:image, sitemap.xml, the Sitemap line in robots.txt) are left out, with a warning.
 */
export function siteMeta(siteUrl: string): Plugin {
  const base = siteUrl.trim().replace(/\/+$/, '')

  const jsonLd = {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'Organization',
        name: 'Amanah Connect',
        slogan: 'Connecting communities with trust',
        ...(base ? { url: `${base}/`, logo: `${base}/brand/logo-mark-124.png` } : {}),
      },
      {
        '@type': 'SoftwareApplication',
        name: 'Amanah Connect',
        applicationCategory: 'BusinessApplication',
        operatingSystem: 'Web',
        description:
          'Community management software for membership, dues and donations, budgets, complaints and announcements.',
        ...(base ? { url: `${base}/` } : {}),
        // Deliberately no offers, ratings or review counts: none are published.
      },
    ],
  }

  return {
    name: 'amanah-site-meta',
    transformIndexHtml: {
      order: 'pre',
      handler(html) {
        const withUrls = base
          ? html.replaceAll('__SITE_URL__', base)
          : html.replace(/[ \t]*<[^>]*data-needs-site-url[^>]*>\r?\n?/g, '')
        return withUrls.replace(
          '<!--JSON_LD-->',
          `<script type="application/ld+json">${JSON.stringify(jsonLd)}</script>`,
        )
      },
    },
    generateBundle() {
      const robots = [
        'User-agent: *',
        'Allow: /',
        'Disallow: /admin',
        'Disallow: /superadmin',
        'Disallow: /login',
        'Disallow: /2fa',
        ...(base ? ['', `Sitemap: ${base}/sitemap.xml`] : []),
        '',
      ].join('\n')
      this.emitFile({ type: 'asset', fileName: 'robots.txt', source: robots })

      if (base) {
        // Only pages meant to be found. Privacy and Terms are drafts (noindex) until legal review.
        const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n  <url><loc>${base}/</loc></url>\n</urlset>\n`
        this.emitFile({ type: 'asset', fileName: 'sitemap.xml', source: sitemap })
      } else {
        this.warn(
          'VITE_SITE_URL is not set: sitemap.xml and og:image were left out. Set it for production builds.',
        )
      }
    },
  }
}
