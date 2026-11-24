import { render, screen } from '@testing-library/react'
import { lazy } from 'react'
import { DeferredSection } from './DeferredSection'

describe('DeferredSection', () => {
  it('keeps the rest of the page when one section cannot load', async () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const Broken = lazy(() =>
      Promise.reject(new Error('Failed to fetch dynamically imported module')),
    )
    render(
      <main>
        <p>The rest of the page</p>
        <DeferredSection id="broken" minHeight={100}>
          <Broken />
        </DeferredSection>
      </main>,
    )
    expect(await screen.findByRole('alert')).toHaveTextContent('This section could not be loaded.')
    expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument()
    expect(screen.getByText('The rest of the page')).toBeInTheDocument()
    quiet.mockRestore()
  })
})
