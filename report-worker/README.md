# Report Worker

This is the backend of the problem report. It is a Cloudflare Worker on the free plan. It uses a D1 database. The apps post
each report to `/report`. The Worker stores only reports with a known model, a description and, when the report has a log,
frames from the buds. A model test ("Test" category) always has the log. A crash report skips the model check and the description check. One sender can post 5 reports an hour.

## Deploy (once)

From this folder, with a free Cloudflare account:

```bash
npx wrangler login
npx wrangler d1 create quickbuds-reports        # copy the database_id into wrangler.toml
npx wrangler d1 execute quickbuds-reports --remote --file schema.sql
npx wrangler secret put READ_KEY                # a long random string: the key to read the reports
npx wrangler secret put SALT                    # another random string
npx wrangler deploy
```

`openssl rand -hex 24` makes a random string. A change to `src/index.js` or `models.json` needs only
`npx wrangler deploy`.

## Free plan margin

Free plan limits per day: KV 100,000 reads and 1,000 writes. Workers 100,000 requests. D1 5,000,000 rows read and
100,000 rows written. Queues 10,000 operations.

KV, from the code:
- Reads: at most 96 for the cron (every 15 minutes) and 288 for the update check (one refresh every 5 minutes).
- Writes: one when a new release comes out, and one for each digest run that finds new reports.
- Was: the update check wrote 2 keys on every refresh, up to 576 writes a day. Cloudflare sent the 50% alert on Oct 30, Oct 31 and Nov 2. Is: the fresh answer lives in the edge cache, which has no daily limit.

D1, measured 2026-11-06 (`npx wrangler d1 info quickbuds-reports`, last 24 h): 49,794 rows read (1% of the limit), 1,317 rows written (1.3%), 6.09 MB stored.

Workers requests and Queues: not measured yet. Read them in the Cloudflare dashboard (Workers & Pages, then Metrics) and write them here.

## Read the reports

Open `https://quickbuds-report.<subdomain>.workers.dev/` in a browser. Type the key and click Load. The page keeps
the key in memory only. The page stores nothing.

A wrong key shows a message. The page shows the id, the time, the model, the status and the category of each report.
Open a report to read the description and the log. The filter box hides log lines that do not match. The Copy button
copies the full log.

The page reads `/reports?format=json` with the `Authorization: Bearer <READ_KEY>` header. The Worker does not read the
key from the URL, so a URL never holds the key.

- The text list: `curl -H "Authorization: Bearer $READ_KEY" https://quickbuds-report.<subdomain>.workers.dev/reports`
- For a script: add `?format=json`, and keep the header.
- Only newer reports: add `?after=<id>`. More reports: add `?limit=100`.
- The apps send a daily count and a reply acknowledgement to `/ping`.
- Counts: use `/reports?stats=1`, and `?days=<n>`.

The key is the only access control. Keep the key private.

## The helper key

`CONTRIB_KEY` is a second read key for a helper. It shows the same reports, with the full log, but without the install ID.
It gets no counts (`?stats=1`) and no `/__digest`. No key can write: status changes and replies stay in D1.

```bash
openssl rand -hex 24 | npx wrangler secret put CONTRIB_KEY   # make or replace the key
npx wrangler secret delete CONTRIB_KEY                        # remove the helper's access
```

## Mark a report

Each report has a `status`. The page shows only `new` and `working` reports.

- `new`: not read yet.
- `working`: a fix is in progress.
- `done`: fixed, or answered.
- `unsupported`: the buds are not a supported model.
- `no_log`: the log is missing or shows nothing.
- `no_description`: the description is empty or unclear.
- `needs_btsnoop`: only a btsnoop capture can show the cause.

```bash
npx wrangler d1 execute quickbuds-reports --remote --command "UPDATE reports SET status = 'done', note = 'fixed in 4.13.0' WHERE id = 7"
```

## Delete old reports

```bash
npx wrangler d1 execute quickbuds-reports --remote --command "DELETE FROM reports WHERE at < '2026-01-01'"
```

## Reply to a report

A report that carries an install ID can get one answer. Write it with SQL, 600 characters at most, plain text:

```bash
npx wrangler d1 execute quickbuds-reports --remote --command "UPDATE reports SET reply = 'Text' WHERE id = 42"
```

The next daily ping of that install returns the reply. The app stores the reply, then sends its id in the `ack` field of
the following ping. The Worker marks the reply as seen only after that ping. Until then the reply stays unread, so a lost
answer comes again.

The `ack` field holds the id of the last reply the app stored, or `0` when it stored none. The Worker marks the row
that matches both the id and the app's install ID. An app that sends no `ack` field gets the old behaviour: the Worker
marks the reply as seen when it sends it.

A database made before replies needs the two `ALTER TABLE` lines in `schema.sql` first.
