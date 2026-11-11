// The two read keys of /reports: READ_KEY sees everything, CONTRIB_KEY sees reports without the install ID and no counts.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const ROW = { id: 5, at: "2026-10-27T10:00:00Z", app: "QuickBuds 5.3.4 Android", app_id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  model: "realme Buds T200", categories: "Other", description: "Bass", crash: 0, log: "RX: AA 01", status: "new", note: null };

// A fake D1 database that returns ROW with only the columns the SELECT names.
function envWith(keys) {
  const sql = [];
  const DB = {
    sql,
    prepare(text) {
      sql.push(text);
      const cols = text.match(/^SELECT (.+?) FROM/)[1].split(",").map((c) => c.trim());
      const row = Object.fromEntries(cols.map((c) => [c, ROW[c]]));
      const statement = { bind: () => statement, all: async () => ({ results: [row] }), first: async () => ({}) };
      return statement;
    },
  };
  return { DB, ...keys };
}

const KEYS = { READ_KEY: "admin-key", CONTRIB_KEY: "helper-key" };

function get(path, key) {
  return new Request(`https://quickbuds-report.test${path}`, { headers: key ? { Authorization: `Bearer ${key}` } : {} });
}

test("READ_KEY: the report carries the install ID", async () => {
  const res = await worker.fetch(get("/reports?format=json", "admin-key"), envWith(KEYS));
  assert.equal(res.status, 200);
  assert.equal((await res.json())[0].app_id, ROW.app_id);
});

test("CONTRIB_KEY: the report has the log but no install ID, in JSON and in text", async () => {
  const json = await (await worker.fetch(get("/reports?format=json", "helper-key"), envWith(KEYS))).json();
  assert.equal(json[0].log, ROW.log);
  assert.equal("app_id" in json[0], false);

  const text = await (await worker.fetch(get("/reports", "helper-key"), envWith(KEYS))).text();
  assert.match(text, /Log:\nRX: AA 01/);
  assert.doesNotMatch(text, /Install:|aaaaaaaa/);
});

test("CONTRIB_KEY: no counts and no digest diagnostic", async () => {
  const env = envWith(KEYS);
  assert.equal((await worker.fetch(get("/reports?stats=1", "helper-key"), env)).status, 404);
  assert.equal((await worker.fetch(get("/__digest", "helper-key"), env)).status, 404);
  assert.deepEqual(env.DB.sql, []);
});

test("no key, a wrong key, or an empty key with no CONTRIB_KEY set: 404", async () => {
  assert.equal((await worker.fetch(get("/reports"), envWith(KEYS))).status, 404);
  assert.equal((await worker.fetch(get("/reports", "wrong"), envWith(KEYS))).status, 404);
  const noHelper = new Request("https://quickbuds-report.test/reports", { headers: { Authorization: "Bearer " } });
  assert.equal((await worker.fetch(noHelper, envWith({ READ_KEY: "admin-key" }))).status, 404);
});
