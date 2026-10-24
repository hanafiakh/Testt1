#!/usr/bin/env python3
"""Create an importable library pack (.db) from a JSONL file: one {"ref","title","body"} object per line.
  make_pack.py in.jsonl out.db --id tafsir_x --title "..." --type tafsir --author "..." --license "..." [--complete]
type: tafsir | hadith_sharh | seerah | aqeedah | fiqh | lexicon | quran_sciences | other
Only mark --complete if you verified the whole book is present."""
import argparse, json, sqlite3
from common import normalize_ar

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
for k in ("id", "title", "type", "author", "license"): ap.add_argument("--" + k, required=True)
ap.add_argument("--version", default="1"); ap.add_argument("--complete", action="store_true")
a = ap.parse_args()
con = sqlite3.connect(a.out)
con.executescript("""DROP TABLE IF EXISTS pack_meta; DROP TABLE IF EXISTS entry;
CREATE TABLE pack_meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE entry(id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT, title TEXT, body TEXT NOT NULL, body_norm TEXT NOT NULL);
CREATE INDEX ix_entry_ref ON entry(ref);""")
con.executemany("INSERT INTO pack_meta VALUES(?,?)", [("id", a.id), ("title", a.title), ("type", a.type), ("author", a.author),
                ("license", a.license), ("version", a.version), ("complete", "true" if a.complete else "false")])
n = 0
for line in open(a.src, encoding="utf-8"):
    if not line.strip(): continue
    o = json.loads(line)
    con.execute("INSERT INTO entry(ref, title, body, body_norm) VALUES(?,?,?,?)", (o.get("ref"), o.get("title"), o["body"], normalize_ar(o["body"]))); n += 1
con.commit(); con.close(); print(f"pack {a.id}: {n} entries -> {a.out}")
