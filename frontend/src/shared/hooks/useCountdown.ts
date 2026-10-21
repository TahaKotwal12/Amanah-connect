import { useCallback, useEffect, useState } from 'react'

/** "1:05" for 65 seconds. */
export function formatClock(seconds: number): string {
  const whole = Math.max(0, Math.ceil(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

/**
 * A countdown to a moment in time. `start(seconds)` sets the deadline; `remaining` ticks down once a
 * second to 0. It follows the clock rather than counting ticks, so a throttled background tab still
 * shows the right time when you come back.
 */
export function useCountdown() {
  const [deadline, setDeadline] = useState<number | null>(null)
  const [remaining, setRemaining] = useState(0)

  useEffect(() => {
    if (deadline === null) return
    const tick = () => {
      const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
      setRemaining(left)
      if (left === 0) clearInterval(timer)
    }
    const timer = setInterval(tick, 1000)
    return () => {
      clearInterval(timer)
    }
  }, [deadline])

  const start = useCallback((seconds: number) => {
    setRemaining(Math.ceil(seconds))
    setDeadline(Date.now() + seconds * 1000)
  }, [])
  const clear = useCallback(() => {
    setDeadline(null)
    setRemaining(0)
  }, [])

  return { remaining, active: remaining > 0, start, clear }
}
