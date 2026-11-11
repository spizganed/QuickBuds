#!/usr/bin/env python3
"""Fails when a locale misses a translatable string of values/strings.xml. Lint does not catch it."""
import glob, os, sys
import xml.etree.ElementTree as ET

res = os.path.join(os.path.dirname(__file__), "../app/src/main/res")

def names(path, all_=False):
    root = ET.parse(path).getroot()
    return {e.get("name") for e in root if e.tag in ("string", "plurals", "string-array")
            and (all_ or e.get("translatable") != "false")}

base = names(f"{res}/values/strings.xml")
bad = 0
for f in sorted(glob.glob(f"{res}/values-*/strings.xml")):
    missing = base - names(f, True)
    if missing:
        bad += 1
        print(f"{os.path.basename(os.path.dirname(f))}: {', '.join(sorted(missing))}")
sys.exit(1 if bad else 0)
