// Runs the Lighthouse audits (e2e/lighthouse.spec.ts) one at a time against the production build.
// Output: reports/lighthouse/*.json (full reports) and reports/lighthouse/summary.md.
import { spawnSync } from 'node:child_process'

const result = spawnSync('npx', ['playwright', 'test', 'e2e/lighthouse.spec.ts', '--workers=1'], {
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, LIGHTHOUSE: '1' },
})
process.exit(result.status ?? 1)
