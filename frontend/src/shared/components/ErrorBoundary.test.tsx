import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { expectNoA11yViolations } from '@/test/a11y'
import { ErrorBoundary } from './ErrorBoundary'

function Bomb({ explode }: { explode: boolean }) {
  if (explode) throw new Error('kaboom')
  return <p>all good</p>
}

beforeEach(() => {
  // React logs caught render errors; keep the test output readable.
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('ErrorBoundary', () => {
  it('renders children when nothing throws', () => {
    render(
      <ErrorBoundary>
        <Bomb explode={false} />
      </ErrorBoundary>,
    )
    expect(screen.getByText('all good')).toBeInTheDocument()
  })

  it('replaces a crashed tree with a calm, accessible fallback', async () => {
    const { container } = render(
      <ErrorBoundary>
        <Bomb explode />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.queryByText(/kaboom/)).not.toBeInTheDocument() // no raw error text for users
    expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('supports a custom fallback that can reset', async () => {
    function Harness() {
      const [explode, setExplode] = useState(true)
      return (
        <ErrorBoundary
          fallback={(_error, reset) => (
            <button
              type="button"
              onClick={() => {
                setExplode(false)
                reset()
              }}
            >
              Try again
            </button>
          )}
        >
          <Bomb explode={explode} />
        </ErrorBoundary>
      )
    }
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(screen.getByText('all good')).toBeInTheDocument()
  })
})
