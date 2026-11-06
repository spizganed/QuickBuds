// The update check: the edge cache serves the fresh answer, and KV is written only when the release changes.
import { test } from "node:test";
import assert from "node:assert/strict";
import { latest } from "../src/update.js";

const RELEASE = { tag_name: "v5.6.1", html_url: "u", body: "b", published_at: "t", assets: [{ name: "a", browser_download_url: "d" }] };

function setup() {
  const store = new Map();
  globalThis.caches = {
    default: {
      match: async (key) => store.get(key)?.clone(),
      put: async (key, response) => void store.set(key, response),
    },
  };
  let fetches = 0;
  globalThis.fetch = async () => (fetches++, Response.json(RELEASE));
  const writes = [];
  const kv = new Map();
  const env = { QUICKBUDS_KV: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => (writes.push(k), kv.set(k, v)) } };
  return { env, writes, fetches: () => fetches };
}

test("a second call within the cache time makes no GitHub call and no KV write", async () => {
  const { env, writes, fetches } = setup();
  const first = await latest(new Request("https://x.test/update"), env);
  const second = await latest(new Request("https://x.test/update"), env);
  assert.equal(first.status, 200);
  assert.deepEqual(await second.json(), { ...RELEASE });
  assert.equal(fetches(), 1);
  assert.deepEqual(writes, ["release:stale"]);
});

test("an unchanged release after the cache expires does not write KV again", async () => {
  const { env, writes } = setup();
  await latest(new Request("https://x.test/update"), env);
  globalThis.caches.default.match = async () => undefined; // the 5 minutes pass
  await latest(new Request("https://x.test/update"), env);
  assert.equal(writes.length, 1);
});
