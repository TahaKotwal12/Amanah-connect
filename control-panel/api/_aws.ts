import {
  DescribeInstancesCommand,
  EC2Client,
  StartInstancesCommand,
  StopInstancesCommand,
} from '@aws-sdk/client-ec2'
import type { VercelRequest, VercelResponse } from '@vercel/node'
import { codeMatches, toPhase, type Phase } from './_logic'

/** Settings come from the Vercel project's environment variables. Nothing here is a secret in code. */
function config() {
  const instanceId = process.env.INSTANCE_ID
  const region = process.env.AWS_REGION_NAME ?? 'ap-southeast-2'
  const siteUrl = (process.env.SITE_URL ?? '').replace(/\/+$/, '')
  if (!instanceId || !process.env.ACCESS_CODE) return null
  return { instanceId, region, siteUrl }
}

let client: EC2Client | undefined
function ec2(region: string): EC2Client {
  // The AWS SDK reads AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY from the environment by itself.
  client ??= new EC2Client({ region })
  return client
}

export interface Status {
  phase: Phase
  ec2State: string | undefined
  siteUrl: string
}

/**
 * Every endpoint starts here: only POST, a correct access code, and a server that is configured.
 * A wrong code waits a moment before answering, which makes guessing slow.
 */
export async function guard(
  req: VercelRequest,
  res: VercelResponse,
): Promise<ReturnType<typeof config> | null> {
  res.setHeader('Cache-Control', 'no-store')
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Use POST.' })
    return null
  }
  const cfg = config()
  if (!cfg) {
    res.status(500).json({ error: 'The control panel is not configured yet.' })
    return null
  }
  const header = req.headers['x-access-code']
  const given = Array.isArray(header) ? header[0] : header
  if (!codeMatches(given, process.env.ACCESS_CODE)) {
    await new Promise((resolve) => setTimeout(resolve, 700))
    res.status(401).json({ error: 'That access code is not right.' })
    return null
  }
  return cfg
}

async function describe(cfg: NonNullable<ReturnType<typeof config>>): Promise<string | undefined> {
  const out = await ec2(cfg.region).send(
    new DescribeInstancesCommand({ InstanceIds: [cfg.instanceId] }),
  )
  return out.Reservations?.[0]?.Instances?.[0]?.State?.Name
}

/** Does the live site answer? Any answer below 500 counts: a login-required 401 means it is up. */
async function siteAnswers(siteUrl: string): Promise<boolean> {
  if (!siteUrl) return false
  try {
    const response = await fetch(`${siteUrl}/api/v1/auth/me`, {
      signal: AbortSignal.timeout(4000),
    })
    return response.status < 500
  } catch {
    return false
  }
}

export async function currentStatus(cfg: NonNullable<ReturnType<typeof config>>): Promise<Status> {
  const ec2State = await describe(cfg)
  const siteUp = ec2State === 'running' ? await siteAnswers(cfg.siteUrl) : false
  return { phase: toPhase(ec2State, siteUp), ec2State, siteUrl: cfg.siteUrl }
}

export async function startServer(cfg: NonNullable<ReturnType<typeof config>>): Promise<void> {
  await ec2(cfg.region).send(new StartInstancesCommand({ InstanceIds: [cfg.instanceId] }))
}

export async function stopServer(cfg: NonNullable<ReturnType<typeof config>>): Promise<void> {
  await ec2(cfg.region).send(new StopInstancesCommand({ InstanceIds: [cfg.instanceId] }))
}

export { canStart, canStop } from './_logic'
export type { Phase }
