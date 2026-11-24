import type { VercelRequest, VercelResponse } from '@vercel/node'
import { canStart, currentStatus, guard, startServer } from './_aws'

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const cfg = await guard(req, res)
  if (!cfg) return
  try {
    const before = await currentStatus(cfg)
    // Already on, or on its way: nothing to do. Still stopping: it must finish before it can start.
    if (before.phase === 'stopping') {
      res.status(409).json({ error: 'The server is still shutting down. Try again in a minute.' })
      return
    }
    if (canStart(before.ec2State)) await startServer(cfg)
    res.status(202).json(await currentStatus(cfg))
  } catch {
    res.status(502).json({ error: 'Could not start the server. Try again in a moment.' })
  }
}
