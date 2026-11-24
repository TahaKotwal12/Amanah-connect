import type { VercelRequest, VercelResponse } from '@vercel/node'
import { currentStatus, guard } from './_aws'

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const cfg = await guard(req, res)
  if (!cfg) return
  try {
    res.status(200).json(await currentStatus(cfg))
  } catch {
    res.status(502).json({ error: 'Could not read the server state. Try again in a moment.' })
  }
}
