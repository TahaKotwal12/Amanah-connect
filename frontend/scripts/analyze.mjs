// Builds with the bundle visualizer on (works the same on Windows, macOS and Linux).
// Output: reports/bundle.html (treemap with gzip sizes). Run `npm run check:bundle` for the budget.
import { spawnSync } from 'node:child_process'

const result = spawnSync('npx', ['vite', 'build'], {
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, ANALYZE: '1' },
})
process.exit(result.status ?? 1)
