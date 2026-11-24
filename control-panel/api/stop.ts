import type { VercelRequest, VercelResponse } from '@vercel/node'
import { canStop, currentStatus, guard, stopServer } from './_aws'

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const cfg = await guard(req, res)
  if (!cfg) return
  try {
    const before = await currentStatus(cfg)
    if (canStop(before.ec2State)) await stopServer(cfg)
    res.status(202).json(await currentStatus(cfg))
  } catch {
    res.status(502).json({ error: 'Could not stop the server. Try again in a moment.' })
  }
}
