// jest-axe ships no types, and @types/jest-axe drags in @types/jest, whose globals clash with
// Vitest's. This declares only what src/test/a11y.ts uses.
declare module 'jest-axe' {
  export interface AxeNode {
    html: string
  }
  export interface AxeViolation {
    id: string
    impact?: string | null
    help: string
    nodes: AxeNode[]
  }
  export interface AxeResults {
    violations: AxeViolation[]
  }
  export function axe(html: Element | string, options?: unknown): Promise<AxeResults>
}
