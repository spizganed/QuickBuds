// The report digest. A cron reads the new reports from D1 and puts one message on the queue. The queue
// consumer posts each message to one Discord webhook. The secret stays in the Worker.
const LAST_ID = "digest:last_id";
const PER_RUN = 20; // the free plan holds a small number of queue writes a day
const MAX_DESCRIPTION = 400;
const MAX_CONTENT = 1900; // a Discord message holds 2000 characters

/** Reads the reports newer than the marker and puts one message on the queue for each. */
export async function scheduled(event, env, ctx) {
  const lastId = Number(await env.QUICKBUDS_KV.get(LAST_ID)) || 0;
  // First run with no marker: start from the newest report. Never replay the history into the channel.
  if (!lastId) {
    const newest = await env.DB.prepare("SELECT MAX(id) AS m FROM reports").first("m");
    await env.QUICKBUDS_KV.put(LAST_ID, String(newest ?? 0));
    return;
  }
  const { results } = await env.DB.prepare(
    "SELECT id, app, model, categories, description, status FROM reports " +
      "WHERE id > ? AND status IN ('new', 'working') ORDER BY id LIMIT ?",
  ).bind(lastId, PER_RUN).all();
  if (results.length === 0) return;

  for (const report of results) {
    // The queue wraps this object as message.body. Do not nest it under another "body" key.
    await env.DIGEST.send({ id: report.id, text: format(report) });
  }
  await env.QUICKBUDS_KV.put(LAST_ID, String(results[results.length - 1].id));
}

/** Posts one Discord message for each queued report. A failed post stays in the queue for a retry. */
export async function queue(batch, env) {
  if (!env.DISCORD_WEBHOOK) {
    console.log("DIGEST: DISCORD_WEBHOOK is not set. Drop the batch.");
    batch.ackAll();
    return;
  }
  for (const message of batch.messages) {
    try {
      await post(env.DISCORD_WEBHOOK, message.body.text);
      message.ack();
    } catch (error) {
      console.log(`DIGEST: report ${message.body.id} failed: ${error.message}`);
      if (error.retryAfter) message.retry({ delaySeconds: error.retryAfter });
      else message.retry();
    }
  }
}

/** The plain text of one report: a header line, the description and the app version. */
function format(report) {
  const app = String(report.app ?? "").replace(/^QuickBuds\s+/i, "").trim();
  const description = String(report.description ?? "").trim().slice(0, MAX_DESCRIPTION);
  const lines = [
    `#${report.id} · ${report.model} · ${report.categories || "-"} · ${report.status}`,
    "",
    description || "(no description)",
  ];
  if (app) lines.push("", `app: ${app}`);
  return lines.join("\n").slice(0, MAX_CONTENT);
}

/** Sends one message to the webhook. A 429 answer tells the queue how long to wait. */
async function post(webhook, text) {
  const res = await fetch(webhook, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content: String(text).slice(0, MAX_CONTENT), allowed_mentions: { parse: [] } }),
  });
  if (res.ok) return;
  const error = new Error(`Discord answered ${res.status}`);
  if (res.status === 429) {
    const body = await res.json().catch(() => ({}));
    if (body.retry_after > 0) error.retryAfter = Math.ceil(body.retry_after);
  }
  throw error;
}
