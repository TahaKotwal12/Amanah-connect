import type { VercelRequest, VercelResponse } from '@vercel/node'
import { fail, currentStatus, guard } from './_aws'

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const cfg = await guard(req, res)
  if (!cfg) return
  try {
    res.status(200).json(await currentStatus(cfg))
  } catch (error) {
    fail(res, 'read the server state', error)
  }
}
