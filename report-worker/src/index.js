// The problem-report Worker. The apps POST /report as JSON; the developer reads GET /reports with the key in the
// Authorization header, or types the key on the page at GET /.
// It stores only reports the apps would send: a known model, a description and, when a log is on, frames from the buds.
import catalog from "../../app/src/main/assets/models.json" with { type: "json" };
import { latest } from "./update.js";
import { scheduled as digestScheduled, queue as digestQueue } from "./digest.js";

const MODELS = new Set(catalog.whiteList.map((m) => m.name.toLowerCase()));
// "Test": a model test (Test my earbuds). It always carries the log.
const CATEGORIES = new Set(["UI", "Lag", "Connection", "Feature not working", "Battery", "Other", "Test"]);
const MAX_LOG = 1_000_000; // characters; a D1 value holds up to 2 MB
const MAX_TEXT = 5_000;
const PER_HOUR = 5; // reports per sender
// An RX frame line, as the Android and desktop packet logs write it.
const RX_FRAME = /\bRX(\[batch\])?:?\s+AA\b/i;

export default {
  // The cron fills the digest queue, and this handler posts each report to Discord.
  scheduled: digestScheduled,
  queue: digestQueue,

  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "POST" && url.pathname === "/report") return report(req, env);
    if (req.method === "POST" && url.pathname === "/ping") return ping(req, env);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) return page();
    if (req.method === "GET" && url.pathname === "/reports") return list(req, url, env);
    if (req.method === "GET" && url.pathname === "/latest") return latest(req, env);
    // Diagnostic for the digest, behind the read key: what the cron would see right now.
    if (req.method === "GET" && url.pathname === "/__digest") {
      if ((req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "") !== env.READ_KEY)
        return new Response("Not found", { status: 404 });
      const last = await env.QUICKBUDS_KV.get("digest:last_id");
      const { results } = await env.DB.prepare(
        "SELECT id, status FROM reports WHERE id > ? AND status IN ('new','working') ORDER BY id LIMIT 20",
      ).bind(Number(last) || 0).all();
      return Response.json({ last, pending: results });
    }
    return new Response("Not found", { status: 404 });
  },
};

/** Checks and stores one report. The answer text is shown to the person in the app. */
async function report(req, env) {
  let r;
  try {
    r = await req.json();
  } catch {
    return answer(400, "The report is not valid JSON.");
  }
  const model = str(r.model, 200);
  const description = str(r.description, MAX_TEXT);
  const app = str(r.app, 200);
  const crash = r.crash === true;
  const appId = APP_ID.test(str(r.id, 36).toLowerCase()) ? str(r.id, 36).toLowerCase() : null;
  const categories = Array.isArray(r.categories) ? r.categories.filter((c) => CATEGORIES.has(c)) : [];
  const log = typeof r.log === "string" ? r.log.slice(-MAX_LOG) : null;

  if (!crash && !MODELS.has(model.toLowerCase()))
    return answer(422, "QuickBuds supports OnePlus, OPPO and realme earbuds only. Pick your model from the list.");
  if (categories.includes("Test") && log === null)
    return answer(422, "A model test needs the log. Run the test again.");
  if (!crash && log !== null && !RX_FRAME.test(log))
    return answer(422, "The log is empty. Connect your earbuds first, then send the report.");
  if (!crash && !description) return answer(422, "Describe the problem: what you did and what happened.");

  const sender = await hash(env.SALT + (req.headers.get("CF-Connecting-IP") ?? ""));
  const since = new Date(Date.now() - 3600_000).toISOString();
  const { n } = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports WHERE sender = ? AND at > ?")
    .bind(sender, since).first();
  if (n >= PER_HOUR) return answer(429, "Too many reports. Try again in an hour.");

  const inserted = await env.DB.prepare(
    "INSERT INTO reports (at, sender, app, model, categories, description, crash, log, app_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(new Date().toISOString(), sender, app, model, categories.join(", "), description, crash ? 1 : 0, log, appId).run();
  // The Dev Tools crash check throws on purpose. Mark it so it never sits in the inbox as a real crash.
  if (crash && log && log.includes("Crash test from Dev Tools")) {
    await env.DB.prepare("UPDATE reports SET status = 'done', note = 'Crash logger test from Dev tools; not a defect.' WHERE id = ?")
      .bind(inserted.meta.last_row_id).run();
  }
  return answer(200, "Sent. Thank you.");
}

/** The page holds no report data. The browser sends the key that the developer types. */
function page() {
  return new Response(PAGE, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

/**
 * The developer's page: the newest reports first, as plain text, or as JSON with format=json.
 * Only "new" and "working" reports show. after=<id> shows only newer reports; limit=<n> (default 20, at most 100).
 * CONTRIB_KEY is the helper's key: the same reports without the install ID, and no counts.
 */
async function list(req, url, env) {
  // The key comes only in the Authorization header (Bearer), so no URL holds it and no log keeps it.
  const given = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  const admin = !!env.READ_KEY && given === env.READ_KEY;
  const helper = !!env.CONTRIB_KEY && given === env.CONTRIB_KEY;
  if (!admin && !helper) return new Response("Not found", { status: 404 });
  if (url.searchParams.has("stats")) return admin ? stats(url, env) : new Response("Not found", { status: 404 });
  const after = Number(url.searchParams.get("after") ?? 0) || 0;
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 20) || 20, 100);
  const { results } = await env.DB.prepare(
    `SELECT id, at, app, ${admin ? "app_id, " : ""}model, categories, description, crash, log, status, note FROM reports WHERE id > ? AND status IN ('new', 'working') ORDER BY id DESC LIMIT ?`,
  ).bind(after, limit).all();

  if (url.searchParams.get("format") === "json") return Response.json(results);
  const text = results.length === 0 ? "No reports.\n" : results.map((r) => [
    `#${r.id}  ${r.at}  ${r.app}${r.crash ? "  CRASH" : ""}  [${r.status}${r.note ? `: ${r.note}` : ""}]`,
    ...(admin ? [`Install: ${r.app_id ?? "-"}`] : []),
    `Model: ${r.model}`,
    `Categories: ${r.categories || "-"}`,
    `Description:\n${r.description || "-"}`,
    `Log:\n${r.log ?? "(not sent)"}`,
  ].join("\n")).join(`\n\n${"=".repeat(72)}\n\n`) + "\n";
  return new Response(text, { headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

function str(v, max) {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

function answer(status, message) {
  return Response.json({ ok: status === 200, message }, { status });
}

async function hash(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const PLATFORMS = new Set(["Android", "Windows", "Linux"]);

const APP_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * One app start per platform and day: the app sends {platform, version, id} at most once a day. `id` is a random
 * install ID the app made itself: it ties to no device, account or address. The Worker keeps the ID, the platform, the
 * version, the first and the last day seen, and one row per ID and day. An older app sends no ID: it only adds 1 to the day's counter.
 */
async function ping(req, env) {
  let r;
  try {
    r = await req.json();
  } catch {
    return answer(400, "Not valid JSON.");
  }
  const platform = str(r.platform, 20);
  const version = str(r.version, 20);
  const id = str(r.id, 36).toLowerCase();
  if (!PLATFORMS.has(platform) || !/^\d+(\.\d+){1,3}$/.test(version)) return answer(422, "Unknown platform or version.");
  // An app that sends `ack` stored the reply with that id: it confirms storage, not only display.
  const ackGiven = r !== null && Object.hasOwn(r, "ack");
  const ack = ackGiven && Number.isSafeInteger(r.ack) && r.ack > 0 ? r.ack : 0;
  const knownInstall = APP_ID.test(id);
  const day = new Date().toISOString().slice(0, 10);
  const batch = [env.DB.prepare(
    "INSERT INTO daily_use (day, platform, version, n) VALUES (?, ?, ?, 1) ON CONFLICT (day, platform, version) DO UPDATE SET n = n + 1",
  ).bind(day, platform, version)];
  if (knownInstall) {
    batch.push(
      env.DB.prepare(
        "INSERT INTO installs (id, platform, version, first_day, last_day) VALUES (?, ?, ?, ?, ?) " +
          "ON CONFLICT (id) DO UPDATE SET platform = excluded.platform, version = excluded.version, last_day = excluded.last_day",
      ).bind(id, platform, version, day, day),
      env.DB.prepare("INSERT OR IGNORE INTO activity (day, id) VALUES (?, ?)").bind(day, id),
    );
  }
  await env.DB.batch(batch);
  // Mark the reply the app stored before the read below. Both ids bind, so no other install's row can change.
  if (knownInstall && ack > 0) {
    try {
      await env.DB.prepare("UPDATE reports SET reply_seen = 1 WHERE id = ? AND app_id = ?").bind(ack, id).run();
    } catch {
      // The reply stays unread and the app sends the same ack on its next ping.
    }
  }
  const reply = knownInstall ? await takeReply(env, id, !ackGiven) : null;
  return Response.json({ ok: true, message: "ok", ...(reply && { reply }) });
}

/**
 * The developer's answer to a report of this install, once: the oldest unread one. The app shows it in a sheet.
 * A failed read never fails the ping. The text is the `reply` column of the report (set with SQL, see the README).
 * An old app, which sends no `ack`, gets the reply marked seen here. A new app marks it seen with its next `ack`.
 */
async function takeReply(env, id, markSeen) {
  try {
    const row = await env.DB.prepare(
      "SELECT id, reply FROM reports WHERE app_id = ? AND reply IS NOT NULL AND reply_seen = 0 ORDER BY id LIMIT 1",
    ).bind(id).first();
    if (!row) return null;
    if (markSeen) await env.DB.prepare("UPDATE reports SET reply_seen = 1 WHERE id = ?").bind(row.id).run();
    return { id: row.id, text: String(row.reply).slice(0, 600) };
  } catch {
    return null;
  }
}

/**
 * The developer's counts: GET /?stats=1[&days=30] with the key in the Authorization header. Per day and platform: the
 * active installs (distinct IDs), the new installs, and the ping count (which includes apps without an ID). Totals:
 * installs, seen in the last 7 and 30 days.
 */
async function stats(url, env) {
  const days = Math.min(Number(url.searchParams.get("days") ?? 30) || 30, 365);
  const since = new Date(Date.now() - days * 86400_000).toISOString().slice(0, 10);
  const daily = await env.DB.prepare(
    "SELECT u.day AS day, u.platform AS platform, SUM(u.n) AS pings, " +
      "(SELECT COUNT(*) FROM activity a JOIN installs i ON i.id = a.id WHERE a.day = u.day AND i.platform = u.platform) AS active, " +
      "(SELECT COUNT(*) FROM installs i WHERE i.first_day = u.day AND i.platform = u.platform) AS new_installs " +
      "FROM daily_use u WHERE u.day >= ? GROUP BY u.day, u.platform ORDER BY u.day DESC, u.platform",
  ).bind(since).all();
  const d7 = new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10);
  const d30 = new Date(Date.now() - 30 * 86400_000).toISOString().slice(0, 10);
  const totals = await env.DB.prepare(
    "SELECT COUNT(*) AS installs, SUM(last_day >= ?) AS seen_7d, SUM(last_day >= ?) AS seen_30d FROM installs",
  ).bind(d7, d30).first();
  if (url.searchParams.get("format") === "json") return Response.json({ totals, daily: daily.results });
  const lines = daily.results.map((r) => `${r.day}  ${r.platform.padEnd(8)} active ${String(r.active).padStart(4)}  new ${String(r.new_installs).padStart(4)}  pings ${r.pings}`);
  return new Response(`installs ${totals.installs}  seen 7d ${totals.seen_7d ?? 0}  seen 30d ${totals.seen_30d ?? 0}\n\n${lines.join("\n")}\n`, {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}

/**
 * The reader page. It calls /reports?format=json with the key in the Authorization header.
 * The key stays in the input and in memory. The page stores nothing.
 */
const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>QuickBuds reports</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; background: #121214; color: #fff; font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 1000px; margin: 0 auto; padding: 24px 20px 64px; }
  h1 { font-size: 20px; font-weight: 600; margin: 0 0 4px; }
  .hint { color: #a0a0a5; font-size: 13px; margin: 0 0 20px; }
  form { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; margin: 0 0 16px; }
  .field { flex: 1 1 240px; }
  label { display: block; margin: 0 0 4px; color: #a0a0a5; font-size: 13px; }
  input { width: 100%; min-height: 40px; padding: 8px 12px; border: 1px solid #34343a; border-radius: 8px; background: #1b1b1f; color: #fff; font: inherit; }
  input:hover { border-color: #4a4a52; }
  button { min-height: 40px; padding: 8px 18px; border: 0; border-radius: 8px; background: #d71920; color: #fff; font: inherit; font-weight: 600; cursor: pointer; }
  button:hover { background: #b3141a; }
  button:active { background: #971015; }
  button:disabled { background: #3a3a40; color: #a0a0a5; cursor: default; }
  :focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
  #state { min-height: 22px; margin: 0 0 16px; color: #a0a0a5; font-size: 14px; }
  #list { display: grid; gap: 12px; list-style: none; margin: 0; padding: 0; }
  .report { padding: 14px 16px; border: 1px solid #34343a; border-radius: 10px; background: #232327; }
  .meta { display: flex; flex-wrap: wrap; gap: 4px 14px; align-items: baseline; }
  .id { color: #a0a0a5; font-variant-numeric: tabular-nums; }
  .time { color: #a0a0a5; font-size: 13px; }
  .model { font-weight: 600; }
  .pill { padding: 1px 8px; border: 1px solid #34343a; border-radius: 999px; color: #a0a0a5; font-size: 12px; }
  .cat { color: #a0a0a5; font-size: 13px; }
  details { margin-top: 10px; }
  summary { padding: 4px 0; color: #a0a0a5; font-size: 13px; cursor: pointer; }
  summary:hover { color: #fff; }
  h3 { margin: 14px 0 6px; color: #a0a0a5; font-size: 12px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; }
  .desc { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .logbar { display: flex; gap: 8px; margin: 6px 0; }
  .logbar input { min-height: 32px; padding: 4px 10px; font-size: 13px; }
  .copy { min-height: 32px; padding: 4px 12px; background: #2e2e33; font-size: 13px; font-weight: 500; }
  .copy:hover { background: #3a3a41; }
  .copy:active { background: #44444c; }
  .log { max-height: 340px; margin: 0; padding: 12px; overflow: auto; border: 1px solid #2a2a2f; border-radius: 8px; background: #0c0c0e; white-space: pre-wrap; overflow-wrap: anywhere; font: 12.5px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
  @media (max-width: 520px) { form button { width: 100%; } }
  @media (prefers-reduced-motion: no-preference) { input, button, summary { transition: background-color 120ms ease-out, border-color 120ms ease-out, color 120ms ease-out; } }
</style>
</head>
<body>
<main>
  <h1>QuickBuds reports</h1>
  <p class="hint">The key stays in this tab. The page stores nothing.</p>
  <form id="load" autocomplete="off">
    <div class="field">
      <label for="key">Key</label>
      <input id="key" type="password" autocomplete="current-password" spellcheck="false" required autofocus>
    </div>
    <button id="go" type="submit">Load</button>
  </form>
  <p id="state" role="status" aria-live="polite"></p>
  <ul id="list"></ul>
</main>
<script>
  var form = document.getElementById('load');
  var keyInput = document.getElementById('key');
  var button = document.getElementById('go');
  var state = document.getElementById('state');
  var list = document.getElementById('list');

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var key = keyInput.value.trim();
    if (!key) { state.textContent = 'Type the key first.'; return; }
    load(key);
  });

  async function load(key) {
    button.disabled = true;
    state.textContent = 'Loading reports…';
    list.replaceChildren();
    try {
      var res = await fetch('/reports?format=json', {
        headers: { Authorization: 'Bearer ' + key },
        cache: 'no-store'
      });
      if (res.status === 404) { state.textContent = 'The key is wrong or missing. Check the key and try again.'; return; }
      if (!res.ok) { state.textContent = 'The Worker answered ' + res.status + '. Try again later.'; return; }
      var reports = await res.json();
      if (!Array.isArray(reports)) { state.textContent = 'The answer is not a report list. Check the Worker.'; return; }
      if (reports.length === 0) { state.textContent = 'No reports.'; return; }
      state.textContent = 'Reports: ' + reports.length + '.';
      reports.forEach(function (report) { list.append(row(report)); });
    } catch (error) {
      state.textContent = 'The page cannot reach the Worker. Check the connection and try again.';
    } finally {
      button.disabled = false;
    }
  }

  function row(report) {
    var item = document.createElement('li');
    item.className = 'report';

    var meta = document.createElement('div');
    meta.className = 'meta';
    meta.append(
      tag('span', 'id', '#' + report.id),
      tag('span', 'time', when(report.at)),
      tag('span', 'model', report.model || '-'),
      tag('span', 'pill', report.status || '-'),
      tag('span', 'cat', report.categories || '-')
    );

    var details = document.createElement('details');
    var summary = document.createElement('summary');
    summary.textContent = 'Show description and log';
    details.append(
      summary,
      tag('h3', '', 'Description'),
      tag('p', 'desc', report.description || '-'),
      tag('h3', '', 'Log')
    );

    var logText = report.log == null ? '(not sent)' : String(report.log);
    var bar = document.createElement('div');
    bar.className = 'logbar';
    var filter = document.createElement('input');
    filter.type = 'search';
    filter.placeholder = 'Filter lines';
    filter.setAttribute('aria-label', 'Filter log lines');
    var copy = document.createElement('button');
    copy.type = 'button';
    copy.className = 'copy';
    copy.textContent = 'Copy';
    var pre = document.createElement('pre');
    pre.className = 'log';
    pre.textContent = logText;

    filter.addEventListener('input', function () {
      var q = filter.value.toLowerCase();
      if (!q) { pre.textContent = logText; return; }
      var lines = logText.split('\\n').filter(function (line) { return line.toLowerCase().indexOf(q) >= 0; });
      pre.textContent = lines.length ? lines.join('\\n') : '(No line matches.)';
    });
    copy.addEventListener('click', async function () {
      try {
        await navigator.clipboard.writeText(logText);
        copy.textContent = 'Copied';
      } catch (error) {
        copy.textContent = 'Select and copy';
      }
      setTimeout(function () { copy.textContent = 'Copy'; }, 1500);
    });

    bar.append(filter, copy);
    details.append(bar, pre);
    item.append(meta, details);
    return item;
  }

  function tag(name, className, text) {
    var el = document.createElement(name);
    if (className) el.className = className;
    el.textContent = text;
    return el;
  }

  function when(value) {
    var date = new Date(value);
    return isNaN(date.getTime()) ? String(value || '') : date.toLocaleString();
  }
</script>
</body>
</html>`;

