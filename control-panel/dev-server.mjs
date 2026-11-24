// Local preview of the control page. It serves public/ and PRETENDS to be the server: nothing here
// talks to AWS, so you can click through every state safely.   npm run dev   ->  http://localhost:3000
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { extname, join } from 'node:path'

const CODE = process.env.ACCESS_CODE ?? 'test'
const SECONDS = { starting: 8, booting: 8, stopping: 6 } // how long each pretend step lasts
const TYPES = { '.html': 'text/html; charset=utf-8', '.png': 'image/png' }

let phase = 'stopped'
let timer = null
function goto(next, after) {
  phase = next
  clearTimeout(timer)
  const wait = SECONDS[next]
  if (wait) timer = setTimeout(() => goto(after[next]), wait * 1000)
}
const NEXT = { starting: 'booting', booting: 'ready', stopping: 'stopped' }

function reply(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  if (url.pathname.startsWith('/api/')) {
    if (req.method !== 'POST') return reply(res, 405, { error: 'Use POST.' })
    if (req.headers['x-access-code'] !== CODE) {
      return setTimeout(() => reply(res, 401, { error: 'That access code is not right.' }), 700)
    }
    const action = url.pathname.slice(5)
    if (action === 'start' && phase === 'stopped') goto('starting', NEXT)
    if (action === 'start' && phase === 'stopping') {
      return reply(res, 409, { error: 'The server is still shutting down. Try again in a minute.' })
    }
    if (action === 'stop' && ['starting', 'booting', 'ready'].includes(phase)) goto('stopping', NEXT)
    return reply(res, action === 'status' ? 200 : 202, { phase, siteUrl: 'https://uat.amanahconnect.co.in' })
  }
  const file = url.pathname === '/' ? '/index.html' : url.pathname
  try {
    const body = readFileSync(join('public', file))
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404).end()
  }
}).listen(3000, () => {
  console.log(`Control page preview: http://localhost:3000   (access code: ${CODE})`)
})
