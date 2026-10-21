import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { Hero } from './Hero'
import { Navbar } from './Navbar'

/**
 * The first screen of the landing page as plain HTML, rendered from the real components at build
 * time (vite-plugin-poster.ts). It is shown while the JavaScript loads, then removed once React has
 * painted the same thing, so the page is readable and the headline is in the HTML from the first byte.
 * Nothing here is written by hand twice: change Navbar or Hero and the poster follows.
 */
export function renderPoster(): string {
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={['/']}>
      <Navbar />
      <Hero />
    </MemoryRouter>,
  )
}
