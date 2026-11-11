CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at TEXT NOT NULL,          -- UTC, ISO 8601
  sender TEXT NOT NULL,      -- salted hash of the sender's IP, for the rate limit only
  app TEXT NOT NULL,         -- "QuickBuds 4.10.0 Android" or "... Windows"
  model TEXT NOT NULL,
  categories TEXT NOT NULL,  -- comma-separated, the form's words
  description TEXT NOT NULL,
  crash INTEGER NOT NULL,    -- 1: sent from the crash dialog
  log TEXT,                  -- null when the person turned the log off
  app_id TEXT,               -- the app's own install ID (UUID); null when the app sends none
  status TEXT NOT NULL DEFAULT 'new',  -- new, working, done, unsupported, no_log, no_description, needs_btsnoop
  note TEXT,                 -- the developer's one-line reason for the status
  reply TEXT,                -- the developer's answer to the person; shown once in the app
  reply_seen INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS reports_sender_at ON reports (sender, at);

-- Active installs per day: one count per app start per day, no ID (the /ping route).
CREATE TABLE IF NOT EXISTS daily_use (
  day TEXT NOT NULL,         -- UTC date
  platform TEXT NOT NULL,    -- Android, Windows, Linux
  version TEXT NOT NULL,
  n INTEGER NOT NULL,
  PRIMARY KEY (day, platform, version)
);

-- Install IDs (the /ping route). The ID is a random UUID the app made itself; it ties to no device, account or address.
CREATE TABLE IF NOT EXISTS installs (
  id TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  version TEXT NOT NULL,
  first_day TEXT NOT NULL,
  last_day TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS activity (
  day TEXT NOT NULL,
  id TEXT NOT NULL,
  PRIMARY KEY (day, id)
);
-- Run once on a database made before the install IDs (it fails when the column exists, which is fine):
-- ALTER TABLE reports ADD COLUMN app_id TEXT;
-- Replies (the /ping answer carries one unread reply per install). Run once on a database made before replies:
-- ALTER TABLE reports ADD COLUMN reply TEXT;
-- ALTER TABLE reports ADD COLUMN reply_seen INTEGER NOT NULL DEFAULT 0;
-- Write a reply: UPDATE reports SET reply = 'text' WHERE id = 42;
