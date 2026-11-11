#!/usr/bin/env python3
"""Turns a model-test report into one entry of testdata/row_fixtures.json.

The report must hold the machine-readable line that the app writes on Send:
    MODEL TEST JSON {"id": ...}
The entry is keyed by model and firmware: a report for an entry that already exists is not added again.

Use: scripts/import-model-test.py report.json            one report, a list, or {"reports": [...]}
     scripts/import-model-test.py report.json --dry-run  prints the entry and writes nothing
"""
import json, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
FIXTURES = os.path.join(ROOT, "testdata/row_fixtures.json")
MODELS = os.path.join(ROOT, "app/src/main/assets/models.json")
PREFIX = "MODEL TEST JSON "

# The home rows that RowRulesTest checks (RowRules constants). The other rows have their own tests.
HOME = {"wind", "game", "hires", "spatial", "golden", "eq", "dual", "anc", "earbuds"}
# The earbud settings row is always shown (RowRules), and the model test does not mark it.
ALWAYS = {"earbuds"}

SCALAR_ARRAY = re.compile(r"\[\n\s+((?:\"[^\"\n]*\"|\d+|true|false)(?:,\n\s+(?:\"[^\"\n]*\"|\d+|true|false))*)\n\s+\]")


def report_list(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("reports"), list):
        return data["reports"]
    return [data]


def test_json(report):
    for line in reversed((report.get("log") or "").splitlines()):
        at = line.find(PREFIX)
        if at >= 0:
            return json.loads(line[at + len(PREFIX):])
    return None


def header(report):
    return (report.get("log") or "").splitlines()[0] if report.get("log") else ""


def platform(report):
    text = (report.get("app") or "") + " " + header(report)
    return "Windows" if "Windows" in text else "Android"


def entry(report, t):
    status = t.get("status") or {}
    ids = sorted(status, key=lambda k: int(k, 16))
    return {
        "model": t["name"],
        "report": int(report.get("id") or 0),
        "platform": platform(report),
        "firmware": t.get("fw") or "",
        "productId": t.get("id") or "",
        "features": " ".join(f"{int(k, 16):02X}={status[k]}" for k in ids),
        "capabilities": t.get("caps") or "",
        "expect": expect(t),
        "uncertain": [],
    }


def expect(t):
    return sorted((set(t.get("shown") or []) & HOME) | ALWAYS)


def rules_say(t):
    return sorted((set(t.get("expected") or []) & HOME) | ALWAYS)


def compact_arrays(text):
    """The file's own style: an array of plain values stays on one line."""
    return SCALAR_ARRAY.sub(
        lambda m: "[" + ", ".join(re.findall(r"\"[^\"\n]*\"|\d+|true|false", m.group(1))) + "]", text)


def main():
    args = [a for a in sys.argv[1:] if a != "--dry-run"]
    dry = "--dry-run" in sys.argv[1:]
    if len(args) != 1:
        sys.exit(__doc__.strip().splitlines()[-1])
    with open(FIXTURES, encoding="utf-8") as f:
        data = json.load(f)
    fixtures = data["fixtures"]
    names = {m["name"] for m in json.load(open(MODELS, encoding="utf-8"))["whiteList"]}

    with open(args[0], encoding="utf-8") as f:
        reports = report_list(json.load(f))

    added = 0
    for report in reports:
        t = test_json(report)
        if t is None:
            print(f"skip: report {report.get('id', '?')} has no {PREFIX.strip()} line")
            continue
        if t.get("name") not in names:
            print(f"skip: {t.get('name')!r} is not in models.json")
            continue
        if rules_say(t) != expect(t):
            print(f"warn: {t['name']} fw {t.get('fw') or '?'}: models.json says {rules_say(t)}, "
                  f"the app showed {expect(t)}")
        e = entry(report, t)
        same = [f for f in fixtures if f.get("model") == e["model"] and f.get("firmware", "") == e["firmware"]]
        if same:
            print(f"skip: {e['model']} fw {e['firmware'] or '?'} is already fixture {same[0].get('report')}")
            continue
        if dry:
            print(json.dumps(e, indent=2, ensure_ascii=False))
        else:
            fixtures.append(e)
        added += 1
        print(f"add: {e['model']} fw {e['firmware'] or '?'} from report {e['report']}")

    if added and not dry:
        with open(FIXTURES, "w", encoding="utf-8") as f:
            f.write(compact_arrays(json.dumps(data, indent=2, ensure_ascii=False)) + "\n")
    print(f"{added} added, {len(fixtures)} entries")


main()
