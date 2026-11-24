// Size budget for the JavaScript a person downloads before the first signed-in screen is usable.
//
// "Initial authenticated bundle" = the entry chunk, everything it imports statically, plus the
// shell and first page of each role (the admin and super admin dashboards), with their static
// imports. Charts, the rich text editor and every other page are lazy chunks and are not counted.
// Gzip level 9 approximates what Nginx sends with gzip on.
//
// Usage: npm run build && npm run check:bundle      (exit 1 when over budget)
import { readFileSync, existsSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'

const BUDGET_KB = 250
const dist = new URL('../dist/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const manifestPath = join(dist, '.vite', 'manifest.json')
if (!existsSync(manifestPath)) {
  console.error('dist/.vite/manifest.json not found. Run `npm run build` first.')
  process.exit(2)
}
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))

const gz = (file) => gzipSync(readFileSync(join(dist, file)), { level: 9 }).length

/** A chunk and everything it imports statically (not dynamic imports). */
function closure(key, seen = new Set()) {
  if (seen.has(key) || !manifest[key]) return seen
  seen.add(key)
  for (const dep of manifest[key].imports ?? []) closure(dep, seen)
  return seen
}

const keys = Object.keys(manifest)
const entry = keys.find((k) => manifest[k].isEntry)
if (!entry) {
  console.error('Entry chunk not found in the manifest.')
  process.exit(2)
}

// The first signed-in screen of each role: its shell route and dashboard page.
const FIRST_SCREENS = {
  admin: [/CommunityAdminShell/, /community-admin\/pages\/DashboardPage/],
  superadmin: [/SuperAdminShell/, /superadmin\/pages\/DashboardPage/],
}
// What a role downloads: the entry, the shell, the dashboard, and their static imports.
function bundleFor(patterns) {
  const set = closure(entry)
  for (const pattern of patterns) {
    const match = keys.find((k) => pattern.test(k))
    if (!match) {
      console.error(`No chunk in the manifest matches ${pattern}`)
      process.exit(2)
    }
    closure(match, set)
    // The shell and the page each import their lazy parts dynamically; those are not counted.
  }
  const files = [...set].map((k) => manifest[k].file).filter((f) => f.endsWith('.js'))
  const rows = files.map((file) => ({ file, kb: gz(file) / 1024 }))
  return { rows, total: rows.reduce((sum, r) => sum + r.kb, 0) }
}

let failed = false
for (const [role, patterns] of Object.entries(FIRST_SCREENS)) {
  const { rows, total } = bundleFor(patterns)
  rows.sort((a, b) => b.kb - a.kb)
  console.log(`\n${role}: ${total.toFixed(1)} KB gzip (budget ${BUDGET_KB} KB)`)
  for (const r of rows.slice(0, 8)) console.log(`  ${r.kb.toFixed(1).padStart(7)} KB  ${r.file}`)
  if (total > BUDGET_KB) {
    failed = true
    console.error(`  OVER BUDGET by ${(total - BUDGET_KB).toFixed(1)} KB`)
  }
}
process.exit(failed ? 1 : 0)
