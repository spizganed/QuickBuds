#!/usr/bin/env python3
"""Lists the writes that the buds refused, per model, from the logs in the report Worker's database.

Status 1: the buds lack the switch. Status 2: they have it and refuse it now (for example high-quality audio on AAC).
Use: scripts/report-statuses.py            reads the database with wrangler (needs `wrangler login`)
     scripts/report-statuses.py dump.json  reads the output of `wrangler d1 execute ... --json` for `SELECT id, model, log FROM reports`
"""
import collections, json, re, subprocess, sys, os

SQL = "SELECT id, model, log FROM reports WHERE log IS NOT NULL ORDER BY id"
FRAME = re.compile(r"\b(TX|RX)(?:\[[^\]]*\])?:?\s+((?:[0-9A-F]{2}\s)+[0-9A-F]{2})(?=\s|$)")

def rows():
    if len(sys.argv) > 1:
        data = json.load(open(sys.argv[1]))
    else:
        worker = os.path.join(os.path.dirname(__file__), "../report-worker")
        out = subprocess.run(["npx", "wrangler", "d1", "execute", "quickbuds-reports", "--remote", "--json", "--command", SQL],
                             cwd=worker, capture_output=True, text=True, check=True).stdout
        data = json.loads(out[out.index("["):])
    return data[0]["results"]

def main():
    counts = collections.defaultdict(collections.Counter)
    reports = collections.defaultdict(set)
    for r in rows():
        pending = {}
        for line in r["log"].splitlines():
            m = FRAME.search(line)
            if not m:
                continue
            b = bytes(int(x, 16) for x in m.group(2).split())
            if len(b) < 9 or b[0] != 0xAA:
                continue
            group, op, seq, payload = b[4], b[5], b[6], b[9:]
            if m.group(1) == "TX":
                if not op & 0x80:
                    pending[(group, seq)] = (op, payload)
            elif op & 0x80 and (group, seq) in pending:
                tx_op, tx_payload = pending.pop((group, seq))
                if tx_op == op & 0x7F and payload and tx_op in (0x02, 0x04, 0x05):
                    # A feature write (0x0403) is keyed by its feature id and value; any other write by its first payload byte.
                    tag = tx_payload[:2].hex() if (group, tx_op) == (0x03, 0x04) else tx_payload[:1].hex()
                    key = (r["model"] or "?", f"{group:02X}{tx_op:02X}", tag)
                    counts[key][payload[0]] += 1
                    reports[key].add(r["id"])
    print("model | command | first payload bytes | status counts | reports")
    for key in sorted(counts):
        if any(status for status in counts[key]):
            print(" | ".join([key[0], key[1], key[2], str(dict(counts[key])), str(sorted(reports[key]))]))

main()
