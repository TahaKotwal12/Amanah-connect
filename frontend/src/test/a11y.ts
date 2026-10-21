import { axe } from 'jest-axe'
import { expect } from 'vitest'

/**
 * Run axe-core on rendered markup and fail with a readable list of violations.
 * Every page and component test calls this: `await expectNoA11yViolations(container)`.
 */
export async function expectNoA11yViolations(container: Element): Promise<void> {
  // Components are not pages: the "all content in landmarks" rule only applies to whole documents.
  const results = await axe(container, { rules: { region: { enabled: false } } })
  const summary = results.violations.map(
    (violation) =>
      `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.help}\n` +
      violation.nodes.map((node) => `  ${node.html}`).join('\n'),
  )
  expect(summary, summary.join('\n')).toEqual([])
}
