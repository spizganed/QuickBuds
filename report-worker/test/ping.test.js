// The /ping reply flow. A fake D1 database records each statement and its binds, and returns report rows.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const APP_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const APP_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

class FakeDB {
  constructor(rows) {
    this.rows = rows;
    this.log = [];
  }

  prepare(sql) {
    return new FakeStatement(this, sql);
  }

  async batch(statements) {
    for (const statement of statements) await statement.run();
    return [];
  }
}

class FakeStatement {
  constructor(db, sql) {
    this.db = db;
    this.sql = sql;
    this.binds = [];
  }

  bind(...binds) {
    this.binds = binds;
    return this;
  }

  async first() {
    this.db.log.push({ sql: this.sql, binds: this.binds });
    if (/^SELECT id, reply FROM reports/.test(this.sql)) {
      const [appId] = this.binds;
      const row = this.db.rows
        .filter((r) => r.app_id === appId && r.reply != null && r.reply_seen === 0)
        .sort((a, b) => a.id - b.id)[0];
      return row ? { id: row.id, reply: row.reply } : null;
    }
    return null;
  }

  async run() {
    this.db.log.push({ sql: this.sql, binds: this.binds });
    if (/^UPDATE reports SET reply_seen = 1 WHERE id = \? AND app_id = \?$/.test(this.sql)) {
      const [id, appId] = this.binds;
      const row = this.db.rows.find((r) => r.id === id && r.app_id === appId);
      if (row) row.reply_seen = 1;
    } else if (/^UPDATE reports SET reply_seen = 1 WHERE id = \?$/.test(this.sql)) {
      const [id] = this.binds;
      const row = this.db.rows.find((r) => r.id === id);
      if (row) row.reply_seen = 1;
    }
    return { meta: {} };
  }
}

function envWith(rows) {
  return { DB: new FakeDB(rows) };
}

function request(body) {
  return new Request("https://quickbuds-report.test/ping", { method: "POST", body: JSON.stringify(body) });
}

const BASE = { platform: "Android", version: "4.13.0", id: APP_A };

function seenUpdates(db) {
  return db.log.filter((entry) => entry.sql.startsWith("UPDATE reports SET reply_seen = 1"));
}

test("old app, no ack: the reply comes back and is marked seen", async () => {
  const row = { id: 7, app_id: APP_A, reply: "Hello from the developer", reply_seen: 0 };
  const env = envWith([row]);
  const answer = await (await worker.fetch(request(BASE), env)).json();

  assert.equal(answer.ok, true);
  assert.deepEqual(answer.reply, { id: 7, text: "Hello from the developer" });
  assert.equal(row.reply_seen, 1);
  assert.deepEqual(seenUpdates(env.DB).map((entry) => entry.binds), [[7]]);
});

test("new app, ack 0: the reply comes back and is not marked seen", async () => {
  const row = { id: 7, app_id: APP_A, reply: "Hello", reply_seen: 0 };
  const env = envWith([row]);
  const answer = await (await worker.fetch(request({ ...BASE, ack: 0 }), env)).json();

  assert.deepEqual(answer.reply, { id: 7, text: "Hello" });
  assert.equal(row.reply_seen, 0);
  assert.deepEqual(seenUpdates(env.DB), []);
});

test("new app, ack of a stored reply: that reply is marked seen, the next one comes back unmarked", async () => {
  const stored = { id: 7, app_id: APP_A, reply: "First", reply_seen: 0 };
  const next = { id: 8, app_id: APP_A, reply: "Second", reply_seen: 0 };
  const env = envWith([stored, next]);
  const answer = await (await worker.fetch(request({ ...BASE, ack: 7 }), env)).json();

  assert.equal(stored.reply_seen, 1);
  assert.equal(next.reply_seen, 0);
  assert.deepEqual(answer.reply, { id: 8, text: "Second" });
  assert.deepEqual(seenUpdates(env.DB).map((entry) => entry.binds), [[7, APP_A]]);

  // The ack runs before the read, so the read cannot return the acknowledged reply again.
  const updateAt = env.DB.log.findIndex((entry) => entry.sql.startsWith("UPDATE reports SET reply_seen = 1"));
  const selectAt = env.DB.log.findIndex((entry) => entry.sql.startsWith("SELECT id, reply FROM reports"));
  assert.ok(updateAt < selectAt);
});

test("ack for a report of another app id changes no row", async () => {
  const other = { id: 7, app_id: APP_B, reply: "Not yours", reply_seen: 0 };
  const env = envWith([other]);
  const answer = await (await worker.fetch(request({ ...BASE, ack: 7 }), env)).json();

  assert.equal(other.reply_seen, 0);
  assert.equal(answer.reply, undefined);
  assert.deepEqual(seenUpdates(env.DB).map((entry) => entry.binds), [[7, APP_A]]);
});

test("bad ack, a string or a negative number: no UPDATE", async () => {
  for (const ack of ["7", -7]) {
    const row = { id: 7, app_id: APP_A, reply: "Hello", reply_seen: 0 };
    const env = envWith([row]);
    const answer = await (await worker.fetch(request({ ...BASE, ack }), env)).json();

    assert.equal(row.reply_seen, 0);
    assert.deepEqual(seenUpdates(env.DB), []);
    assert.deepEqual(answer.reply, { id: 7, text: "Hello" });
  }
});
