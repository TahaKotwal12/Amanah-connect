import { act, render, screen } from '@testing-library/react'
import { OfflineBanner } from './OfflineBanner'

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value })
  act(() => {
    window.dispatchEvent(new Event(value ? 'online' : 'offline'))
  })
}

afterEach(() => {
  setOnline(true)
  vi.useRealTimers()
})

describe('OfflineBanner', () => {
  it('says nothing while online', () => {
    render(<OfflineBanner />)
    expect(screen.queryByText(/offline/i)).not.toBeInTheDocument()
  })

  it('announces the loss of connection politely and reports the return', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    render(<OfflineBanner />)
    setOnline(false)
    expect(screen.getByText(/You are offline/)).toBeInTheDocument()
    expect(screen.getByTestId('connection-status')).toHaveAttribute('aria-live', 'polite')
    setOnline(true)
    expect(screen.getByText('You are back online.')).toBeInTheDocument()
    act(() => {
      vi.advanceTimersByTime(4500)
    })
    expect(screen.queryByText('You are back online.')).not.toBeInTheDocument()
  })
})
