import { Component, type ErrorInfo, type ReactNode } from 'react'
import { reportError } from '@/lib/monitoring'
import { ErrorFallback } from './ErrorFallback'

interface ErrorBoundaryProps {
  children: ReactNode
  fallback?: (error: Error, reset: () => void) => ReactNode
}

interface ErrorBoundaryState {
  error: Error | null
}

/**
 * The outermost safety net: a render error anywhere below shows a calm screen instead of a blank
 * page. Route-level errors are caught earlier by the router's errorElement; this catches the rest
 * (providers, layouts, anything outside a route).
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Only the stack is reported and logged: component props and state can hold personal data.
    reportError(error)
    console.error('Unhandled render error', error, info.componentStack)
  }

  reset = (): void => {
    this.setState({ error: null })
  }

  override render(): ReactNode {
    const { error } = this.state
    if (!error) return this.props.children
    if (this.props.fallback) return this.props.fallback(error, this.reset)
    return (
      <ErrorFallback message="An unexpected error stopped this page from loading. Reloading usually fixes it." />
    )
  }
}
