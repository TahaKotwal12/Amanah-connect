import { render } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { waitFor } from '@testing-library/react'
import { deferEntryScript, injectPoster } from '../../../vite-plugin-poster.ts'
import { renderPoster } from './poster'
import { RootLayout } from '@/shared/components/layout/RootLayout'

const shell = [
  '<html><head>',
  '<script type="module" crossorigin src="/assets/index-abc.js"></script>',
  '<link rel="modulepreload" crossorigin href="/assets/react-def.js">',
  '<link rel="stylesheet" href="/assets/index.css">',
  '</head><body>',
  '<div id="root"></div>',
  '</body></html>',
].join('\n')

describe('renderPoster', () => {
  it('is the real first screen: headline, calls to action, proof points', () => {
    const html = renderPoster()
    expect(html).toContain('Run your community with clarity, care and complete transparency.')
    expect(html).toContain('Request a demo')
    expect(html).toContain('Members never need to log in')
    expect(html).toContain('Sample data') // the mock says it is a mock
  })
})

describe('injectPoster', () => {
  const out = injectPoster(shell, '<h1 id="hero-title" aria-labelledby="x">Hello</h1>')

  it('puts the poster before the app, out of reach of keyboards and screen readers', () => {
    expect(out).toMatch(
      /<div id="poster" class="poster" inert aria-hidden="true">.*<\/div>\s*<script>.*<\/script>\s*<div id="root">/s,
    )
  })

  it('removes ids and label references so the page never has two of them', () => {
    expect(out).toContain('<h1>Hello</h1>')
    expect(out).not.toContain('hero-title')
  })

  it('adds a guard so other pages never show it', () => {
    expect(out).toContain("location.pathname!=='/'")
    expect(out).toContain("getElementById('poster').remove()")
  })

  it('refuses to build silently without a place to put it', () => {
    expect(() => injectPoster('<html></html>', 'x')).toThrow(/no <div id="root">/)
  })
})

describe('deferEntryScript', () => {
  const out = deferEntryScript(shell, ['/assets/Landing-1.js', '/assets/sheet-2.js'])

  it('takes the script and its preloads out of the head, so nothing blocks the first paint', () => {
    expect(out).not.toMatch(/<script type="module"/)
    expect(out).not.toContain('rel="modulepreload"')
    expect(out).toContain('rel="stylesheet"') // the stylesheet stays: the poster needs it
  })

  it('loads the entry after two animation frames on "/" and at once elsewhere', () => {
    expect(out).toContain('"/assets/index-abc.js"')
    expect(out).toContain("location.pathname==='/'")
    expect(out).toMatch(/requestAnimationFrame\(function\(\)\{requestAnimationFrame\(l\)\}\)/)
    expect(out).toContain('else{l()}')
  })

  it('fetches the landing page chunks in parallel with the entry, on "/" only', () => {
    expect(out).toContain('["/assets/Landing-1.js","/assets/sheet-2.js"]')
    expect(out.indexOf("if(location.pathname==='/'){var p=")).toBeGreaterThan(-1)
  })

  it('fails the build rather than ship a page with no entry script', () => {
    expect(() => deferEntryScript('<html><body></body></html>')).toThrow(/entry/)
  })
})

describe('RootLayout hands over from the poster', () => {
  afterEach(() => {
    document.getElementById('poster')?.remove()
  })

  it('hides the poster once the real page has rendered, and deletes it much later', async () => {
    const poster = document.createElement('div')
    poster.id = 'poster'
    document.body.append(poster)

    const router = createMemoryRouter([
      { element: <RootLayout />, children: [{ index: true, element: <p>live page</p> }] },
    ])
    render(<RouterProvider router={router} />)

    expect(poster.hidden).toBe(false) // not before the page is painted
    await waitFor(() => {
      expect(poster.hidden).toBe(true)
    })
    expect(document.getElementById('poster')).toBe(poster) // hidden, not deleted: it is still the first paint
  })

  it('does nothing on pages that have no poster', () => {
    const router = createMemoryRouter([
      { element: <RootLayout />, children: [{ index: true, element: <p>login</p> }] },
    ])
    expect(() => render(<RouterProvider router={router} />)).not.toThrow()
  })
})
