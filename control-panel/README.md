# Server control page

A page the client opens to **start** and **shut down** the UAT server, so it only runs (and only costs
money) when it is being used. It is hosted on Vercel. Three small functions in `api/` talk to AWS; the
page in `public/` is plain HTML.

```
client's browser ──► Vercel page + /api/*  ──► AWS EC2 (start / stop / state of ONE server)
```

Without the access code nobody can start or stop anything.

## What it costs

| State | Per month (Sydney) |
|---|---|
| Server running all month | about $17 (t3.small) + $2.40 disk + $3.60 fixed IP |
| Server off all month | about **$6** (disk + fixed IP only) |

The server is billed by the second while it runs, so a few hours of use a week costs very little.

## One-time setup

### 1. An AWS user that can do only this

In the AWS console: **IAM → Policies → Create policy → JSON**, name it `amanah-uat-power`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["ec2:StartInstances", "ec2:StopInstances"],
      "Resource": "arn:aws:ec2:ap-southeast-2:295190500536:instance/i-02d86c27d0e54cad4"
    },
    { "Effect": "Allow", "Action": "ec2:DescribeInstances", "Resource": "*" }
  ]
}
```

It can start and stop **that one server** and read its state. It cannot create, delete or change anything.

Then **IAM → Users → Create user** `amanah-control-panel` (no console access), attach that policy, open the
user → **Security credentials → Create access key → Application running outside AWS**. Keep the two values
(access key ID and secret) for step 2. They are shown once.

### 2. Put the page on Vercel

1. Vercel → **Add New → Project** → import the GitHub repo.
2. **Root Directory:** `control-panel`. **Framework Preset:** Other. Leave the build settings empty.
3. **Environment Variables** (mark the secret ones **Sensitive**):

   | Name | Value |
   |---|---|
   | `AWS_ACCESS_KEY_ID` | from step 1 |
   | `AWS_SECRET_ACCESS_KEY` | from step 1 |
   | `INSTANCE_ID` | `i-02d86c27d0e54cad4` |
   | `ACCESS_CODE` | a long code you choose (for example four random words) |
   | `SITE_URL` | `https://uat.amanahconnect.co.in` |

   `AWS_REGION_NAME` is optional; it defaults to `ap-southeast-2`.
4. **Deploy.** Give the client the Vercel link, and the access code **separately** (not in the same message).

To change the code later: change `ACCESS_CODE` in Vercel and redeploy. To cut access completely: delete the
access key in IAM.

## What the client sees

1. Opens the link, types the code, presses Enter.
2. **Start the server.** The page says "Starting…" then "Almost ready…" (about two to three minutes in all), then
   "The server is on and ready" with an **Open Amanah Connect** button.
3. When finished: **Shut the server down** (asks to confirm).

## Things to know

- **Data is safe.** The database is on Neon, uploaded files are on the server's disk, and both survive a
  shutdown. The app starts by itself when the server starts.
- **The address does not change** (it has a fixed IP).
- **Deploys while the server is off.** Pushing code while the server is off does not fail: the deploy is skipped
  with a note, and the server updates itself the next time it is started (`deploy/on-boot.sh`).
- **The HTTPS certificate renews only while the server is on.** It renews by itself when the server is running
  within 30 days of expiry. If the server is left off for months, start it once a month.
- **Forgotten shutdowns.** The server stays on until someone shuts it down. If that becomes a habit, an
  automatic "off after N idle hours" can be added.

## Develop and test

```
cd control-panel
npm install
npm run check   # types
npm test        # the decision logic (states, access code)
```
