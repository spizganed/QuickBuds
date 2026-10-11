// The problem-report Worker. The apps POST /report as JSON; the developer reads GET /?key=<READ_KEY>.
// It stores only reports the apps would send: a known model, a description and, when a log is on, frames from the buds.
import catalog from "../../app/src/main/assets/models.json";

const MODELS = new Set(catalog.whiteList.map((m) => m.name.toLowerCase()));
const CATEGORIES = new Set(["UI", "Lag", "Connection", "Feature not working", "Battery", "Other"]);
const MAX_LOG = 1_000_000; // characters; a D1 value holds up to 2 MB
const MAX_TEXT = 5_000;
const PER_HOUR = 5; // reports per sender
// An RX frame line, as the Android and desktop packet logs write it.
const RX_FRAME = /\bRX(\[batch\])?:?\s+AA\b/i;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "POST" && url.pathname === "/report") return report(req, env);
    if (req.method === "GET" && url.pathname === "/") return list(url, env);
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
  const categories = Array.isArray(r.categories) ? r.categories.filter((c) => CATEGORIES.has(c)) : [];
  const log = typeof r.log === "string" ? r.log.slice(-MAX_LOG) : null;

  if (!crash && !MODELS.has(model.toLowerCase()))
    return answer(422, "QuickBuds supports OnePlus, OPPO and realme earbuds only. Pick your model from the list.");
  if (!crash && log !== null && !RX_FRAME.test(log))
    return answer(422, "The log is empty. Connect your earbuds first, then send the report.");
  if (!crash && !description) return answer(422, "Describe the problem: what you did and what happened.");

  const sender = await hash(env.SALT + (req.headers.get("CF-Connecting-IP") ?? ""));
  const since = new Date(Date.now() - 3600_000).toISOString();
  const { n } = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports WHERE sender = ? AND at > ?")
    .bind(sender, since).first();
  if (n >= PER_HOUR) return answer(429, "Too many reports. Try again in an hour.");

  await env.DB.prepare(
    "INSERT INTO reports (at, sender, app, model, categories, description, crash, log) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(new Date().toISOString(), sender, app, model, categories.join(", "), description, crash ? 1 : 0, log).run();
  return answer(200, "Sent. Thank you.");
}

/**
 * The developer's page: the newest reports first, as plain text, or as JSON with format=json.
 * after=<id> shows only newer reports; limit=<n> (default 20, at most 100).
 */
async function list(url, env) {
  if (!env.READ_KEY || url.searchParams.get("key") !== env.READ_KEY) return new Response("Not found", { status: 404 });
  const after = Number(url.searchParams.get("after") ?? 0) || 0;
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 20) || 20, 100);
  const { results } = await env.DB.prepare(
    "SELECT id, at, app, model, categories, description, crash, log FROM reports WHERE id > ? ORDER BY id DESC LIMIT ?",
  ).bind(after, limit).all();

  if (url.searchParams.get("format") === "json") return Response.json(results);
  const text = results.length === 0 ? "No reports.\n" : results.map((r) => [
    `#${r.id}  ${r.at}  ${r.app}${r.crash ? "  CRASH" : ""}`,
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
