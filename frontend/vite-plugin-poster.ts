import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { createServer, type Plugin, type ResolvedConfig } from 'vite'

const ROOT_DIV = '<div id="root"></div>'

/** Right after the poster: on any page other than "/" it is deleted before it can paint or linger. */
const GUARD =
  "<script>if(location.pathname!=='/')document.getElementById('poster').remove()</script>"

/**
 * Put the prerendered first screen in front of the app. `inert` and `aria-hidden` keep it out of the
 * tab order and away from screen readers (the live page replaces it); crawlers still read it.
 */
export function injectPoster(html: string, posterHtml: string): string {
  // The poster is a copy of live markup: drop ids and label references so the page never has two.
  const copy = posterHtml.replace(/\s(?:id|aria-labelledby|aria-controls)="[^"]*"/g, '')
  if (!html.includes(ROOT_DIV))
    throw new Error('index.html has no <div id="root"></div> to put the poster before')
  return html.replace(
    ROOT_DIV,
    `<div id="poster" class="poster" inert aria-hidden="true">${copy}</div>\n    ${GUARD}\n    ${ROOT_DIV}`,
  )
}

/**
 * On "/" the page is already readable from the poster, so the app's JavaScript is not fetched until
 * after the first paint (two animation frames: rendering waits for the stylesheet, so this cannot
 * fire earlier). That keeps the bundle off the path to the first paint. Every other page, which has
 * nothing to show without the app, loads it at once.
 */
export function deferEntryScript(html: string, landingChunks: readonly string[] = []): string {
  const match = /<script type="module" crossorigin src="([^"]+)"><\/script>\s*/.exec(html)
  if (!match) throw new Error('index.html has no entry <script type="module"> to defer')
  // On "/" the chunks the landing page needs are fetched in the same step as the entry, in
  // parallel, instead of being discovered one round trip later when the router asks for them.
  const loader =
    "<script>(function(){function l(){if(location.pathname==='/'){var p=" +
    JSON.stringify(landingChunks) +
    ";for(var i=0;i<p.length;i++){var k=document.createElement('link');k.rel='modulepreload';k.crossOrigin='';k.href=p[i];document.head.appendChild(k)}}var s=document.createElement('script');s.type='module';s.crossOrigin='';s.src=" +
    JSON.stringify(match[1]) +
    ";document.head.appendChild(s)}if(location.pathname==='/'){requestAnimationFrame(function(){requestAnimationFrame(l)})}else{l()}})()</script>"
  return html
    .replace(match[0], '')
    .replace(/\s*<link rel="modulepreload"[^>]*>/g, '')
    .replace(
      '</body>',
      `    ${loader}
  </body>`,
    )
}

/** After `vite build`, render the landing page's first screen and write it into dist/index.html. */
export function poster(): Plugin {
  let config: ResolvedConfig
  let landingChunks: string[] = []
  return {
    name: 'amanah-poster',
    apply: 'build',
    configResolved(resolved) {
      config = resolved
    },
    generateBundle(_options, bundle) {
      const chunks = Object.values(bundle).filter((item) => item.type === 'chunk')
      const byFile = new Map(chunks.map((chunk) => [chunk.fileName, chunk]))
      const closure = (start: string[]): Set<string> => {
        const seen = new Set<string>()
        const visit = (file: string) => {
          if (seen.has(file)) return
          seen.add(file)
          byFile.get(file)?.imports.forEach(visit)
        }
        start.forEach(visit)
        return seen
      }
      const entry = chunks.find((chunk) => chunk.isEntry)
      const alreadyLoaded = closure(entry ? [entry.fileName] : [])
      const landing = chunks
        .filter((chunk) =>
          /features\/landing\/(MarketingLayout|pages\/LandingPage)\.tsx$/.test(
            chunk.facadeModuleId ?? '',
          ),
        )
        .map((chunk) => chunk.fileName)
      landingChunks = [...closure(landing)]
        .filter((file) => !alreadyLoaded.has(file))
        .map((file) => '/' + file)
    },
    // Start the three brand fonts with the page. They are optional (see index.css), so they only
    // help if they are ready for the first paint, which is exactly what a preload is for.
    transformIndexHtml: {
      order: 'post',
      handler(html, context) {
        const files = Object.keys(context.bundle ?? {}).filter((name) =>
          /(inter|playfair-display|cinzel)-latin-(wght|600)-normal-[\w-]+\.woff2$/.test(name),
        )
        const links = files
          .map(
            (name) =>
              `<link rel="preload" as="font" type="font/woff2" crossorigin href="/${name}" />`,
          )
          .join('\n    ')
        return links ? html.replace('</head>', `    ${links}\n  </head>`) : html
      },
    },
    async closeBundle() {
      if (config.build.ssr) return
      // A throwaway server just to load the TSX components in Node. It must not read vite.config.ts
      // again (that would load this plugin and recurse), so the little it needs is repeated here.
      const server = await createServer({
        configFile: false,
        root: config.root,
        logLevel: 'silent',
        appType: 'custom',
        server: { middlewareMode: true, hmr: false, watch: null },
        resolve: {
          alias: { '@': path.resolve(fileURLToPath(new URL('.', import.meta.url)), 'src') },
        },
      })
      try {
        const module = (await server.ssrLoadModule('/src/features/landing/poster.tsx')) as {
          renderPoster: () => string
        }
        const file = path.resolve(config.root, config.build.outDir, 'index.html')
        const html = await readFile(file, 'utf8')
        await writeFile(
          file,
          deferEntryScript(injectPoster(html, module.renderPoster()), landingChunks),
        )
      } finally {
        await server.close()
      }
    },
  }
}
