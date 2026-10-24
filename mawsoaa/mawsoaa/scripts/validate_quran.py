#!/usr/bin/env python3
"""Validate Tanzil-format Quran text ('sura|aya|text') and optionally a built quran.db against it.
Exit 0 = all checks passed, 1 = failed, 2 = input missing. Never edits the text."""
import argparse, re, sqlite3, sys
import xml.etree.ElementTree as ET
from common import DATA, ASSETS, EXPECTED_AYAH_COUNTS, sha256_file, write_report

LINE = re.compile(r"^(\d+)\|(\d+)\|(.*)$")
ARABIC_OK = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF\s\u200c\u200d\u200f\u06DD\u06DE\u06E9]")

def parse(path):
    verses, bad = {}, []
    for i, line in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
        if not line.strip() or line.startswith("#"): continue
        m = LINE.match(line)
        if not m: bad.append(i); continue
        verses[(int(m[1]), int(m[2]))] = m[3]
    return verses, bad

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", default=str(DATA / "raw/tanzil/quran-uthmani.txt"))
    ap.add_argument("--xml", default=str(DATA / "raw/tanzil/quran-data.xml"))
    ap.add_argument("--db", default=None, help="optionally compare a built quran.db to the source text")
    a = ap.parse_args()
    from pathlib import Path
    if not Path(a.text).exists():
        print("MISSING:", a.text); write_report("quran_validation.json", {"status": "missing_input", "path": a.text}); sys.exit(2)
    verses, bad_lines = parse(a.text)
    problems, warnings = [], []
    per = {}
    for (s, ay) in verses: per.setdefault(s, []).append(ay)
    if len(per) != 114: problems.append(f"surah count {len(per)} != 114")
    for s in range(1, 115):
        got = sorted(per.get(s, []))
        exp = EXPECTED_AYAH_COUNTS[s - 1]
        if got != list(range(1, exp + 1)):
            problems.append(f"surah {s}: have {len(got)} ayahs, expected {exp}; missing={sorted(set(range(1, exp+1)) - set(got))[:10]}")
    if len(verses) != 6236: problems.append(f"total ayahs {len(verses)} != 6236 (counting system: Hafs/Kufi)")
    empties = [k for k, t in verses.items() if not t.strip()]
    if empties: problems.append(f"{len(empties)} empty verses, first {empties[:5]}")
    if bad_lines: problems.append(f"{len(bad_lines)} unparsable lines, first {bad_lines[:5]}")
    odd = {}
    for k, t in verses.items():
        for ch in t:
            if not ARABIC_OK.match(ch): odd[ch] = odd.get(ch, 0) + 1
    if odd: warnings.append("non-Arabic characters present: " + ", ".join(f"U+{ord(c):04X}x{n}" for c, n in sorted(odd.items())[:12]))
    meta = {}
    if Path(a.xml).exists():
        root = ET.parse(a.xml).getroot()
        suras = root.findall(".//sura")
        meta = {"suras": len(suras), "juzs": len(root.findall(".//juz")), "quarters": len(root.findall(".//quarter")), "pages": len(root.findall(".//page"))}
        for sa in suras:
            if int(sa.get("ayas")) != EXPECTED_AYAH_COUNTS[int(sa.get("index")) - 1]:
                problems.append(f"metadata ayas mismatch in surah {sa.get('index')}")
        if meta["juzs"] != 30: warnings.append(f"juz entries {meta['juzs']} != 30")
        if meta["quarters"] != 240: warnings.append(f"hizb-quarter entries {meta['quarters']} != 240")
        if meta["pages"] != 604: warnings.append(f"page entries {meta['pages']} != 604")
    else:
        warnings.append("quran-data.xml missing: juz/hizb/page/surah names will be absent from quran.db")
    db_mismatch = None
    if a.db:
        con = sqlite3.connect(a.db)
        rows = {(s, ay): t for s, ay, t in con.execute("select surah, ayah, text from ayah")}
        db_mismatch = [k for k in verses if rows.get(k) != verses[k]]
        extra = [k for k in rows if k not in verses]
        if db_mismatch or extra: problems.append(f"DB differs from source: {len(db_mismatch)} mismatched, {len(extra)} extra")
    rep = {"status": "pass" if not problems else "fail", "file": a.text, "sha256": sha256_file(Path(a.text)),
           "surahs": len(per), "ayahs": len(verses), "counting_system": "Hafs/Kufi (6236)", "metadata": meta,
           "db_checked": bool(a.db), "problems": problems, "warnings": warnings}
    write_report("quran_validation.json", rep)
    print(rep["status"].upper(), f"surahs={len(per)} ayahs={len(verses)}")
    for p in problems: print(" PROBLEM:", p)
    for w in warnings: print(" warn:", w)
    sys.exit(0 if not problems else 1)

if __name__ == "__main__":
    main()
