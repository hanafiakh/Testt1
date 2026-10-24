#!/usr/bin/env python3
"""Build SQLite content DBs into app/src/main/assets/db/ plus content_manifest.json.
Text is copied verbatim; only a separate *_norm column (search) is derived. Missing inputs are skipped and reported."""
import argparse, bisect, json, shutil, sqlite3, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path
from common import DATA, ASSETS, ROOT, EXPECTED_AYAH_COUNTS, normalize_ar, sha256_file, read_report, load_sources
from validate_quran import parse as parse_quran
from validate_hadith import extract

OUT = ASSETS / "db"

def fresh(name):
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / name
    if p.exists(): p.unlink()
    con = sqlite3.connect(p)
    con.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    return con, p

def put_meta(con, **kv):
    con.executemany("INSERT OR REPLACE INTO meta VALUES(?,?)", [(k, None if v is None else str(v)) for k, v in kv.items()])

def finish(con, p, results, tables):
    con.commit(); con.execute("VACUUM"); con.close()
    c = sqlite3.connect(p)
    rows = {t: c.execute(f"select count(*) from {t}").fetchone()[0] for t in tables}; c.close()
    results.append({"name": p.name, "bytes": p.stat().st_size, "sha256": sha256_file(p), "rows": rows})
    print("built", p.name, rows)

def assign(points, s, a):
    """points: sorted [(sura, aya, index)] -> index of the last point <= (s, a)"""
    i = bisect.bisect_right(points, (s, a, 10**9)) - 1
    return points[i][2] if i >= 0 else None

def build_quran(results):
    txt = DATA / "raw/tanzil/quran-uthmani.txt"
    if not txt.exists(): print("SKIP quran.db (no", txt, ")"); return
    verses, bad = parse_quran(txt)
    if len(verses) != 6236 or bad:
        print("REFUSING to build quran.db: source failed validation (run validate_quran.py)"); return
    names, juz, quarter, page = {}, [], [], []
    xml = DATA / "raw/tanzil/quran-data.xml"
    if xml.exists():
        root = ET.parse(xml).getroot()
        for s in root.findall(".//sura"):
            names[int(s.get("index"))] = (s.get("name"), s.get("ename"), s.get("type"), int(s.get("order") or 0) or None)
        grab = lambda tag: sorted((int(e.get("sura")), int(e.get("aya")), int(e.get("index"))) for e in root.findall(f".//{tag}"))
        juz, quarter, page = grab("juz"), grab("quarter"), grab("page")
    con, p = fresh("quran.db")
    con.executescript("""
      CREATE TABLE surah(id INTEGER PRIMARY KEY, name TEXT, name_en TEXT, type TEXT, revelation_order INTEGER, ayah_count INTEGER NOT NULL);
      CREATE TABLE ayah(id INTEGER PRIMARY KEY, surah INTEGER NOT NULL, ayah INTEGER NOT NULL, text TEXT NOT NULL, text_norm TEXT NOT NULL,
                        juz INTEGER, hizb_quarter INTEGER, page INTEGER, UNIQUE(surah, ayah));
      CREATE INDEX ix_ayah_page ON ayah(page); CREATE INDEX ix_ayah_juz ON ayah(juz);
      CREATE VIRTUAL TABLE ayah_fts USING fts4(text_norm, tokenize=unicode61);""")
    for s in range(1, 115):
        n = names.get(s, (None, None, None, None))
        con.execute("INSERT INTO surah VALUES(?,?,?,?,?,?)", (s, n[0], n[1], n[2], n[3], EXPECTED_AYAH_COUNTS[s - 1]))
    i = 0
    for (s, a), t in sorted(verses.items()):
        i += 1; norm = normalize_ar(t)
        con.execute("INSERT INTO ayah VALUES(?,?,?,?,?,?,?,?)", (i, s, a, t, norm, assign(juz, s, a), assign(quarter, s, a), assign(page, s, a)))
        con.execute("INSERT INTO ayah_fts(docid, text_norm) VALUES(?,?)", (i, norm))
    put_meta(con, riwaya="hafs", riwaya_ar="حفص عن عاصم", script="uthmani", source="Tanzil.net (verbatim, unmodified)",
             source_sha256=sha256_file(txt), counting="Hafs/Kufi 6236", has_navigation_metadata=bool(xml.exists()),
             warsh="NOT INCLUDED: no complete verified Warsh source yet", built=time.strftime("%Y-%m-%d"))
    finish(con, p, results, ["surah", "ayah"])

def build_adhkar(results):
    f = DATA / "raw/adhkar/adhkar.json"
    if not f.exists(): print("SKIP adhkar.db"); return
    data = json.loads(f.read_text(encoding="utf-8"))
    cats = data if isinstance(data, list) else data.get("categories") or data.get("data") or []
    con, p = fresh("adhkar.db")
    con.executescript("""
      CREATE TABLE category(id INTEGER PRIMARY KEY, title TEXT NOT NULL, audio_path TEXT);
      CREATE TABLE dhikr(id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER NOT NULL, ord INTEGER, text TEXT NOT NULL, text_norm TEXT NOT NULL,
                         count INTEGER, source TEXT, takhrij TEXT, grade TEXT, audio_path TEXT);
      CREATE INDEX ix_dhikr_cat ON dhikr(category_id);
      CREATE VIRTUAL TABLE dhikr_fts USING fts4(text_norm, tokenize=unicode61);""")
    for ci, c in enumerate(cats, 1):
        cid = c.get("id", ci)
        con.execute("INSERT INTO category VALUES(?,?,?)", (cid, c.get("category", ""), c.get("audio")))
        for k, d in enumerate(c.get("array", []), 1):
            text = d.get("text", ""); norm = normalize_ar(text)
            cur = con.execute("INSERT INTO dhikr(category_id, ord, text, text_norm, count, source, takhrij, grade, audio_path) VALUES(?,?,?,?,?,NULL,NULL,NULL,?)",
                              (cid, d.get("id", k), text, norm, d.get("count"), d.get("audio")))
            con.execute("INSERT INTO dhikr_fts(docid, text_norm) VALUES(?,?)", (cur.lastrowid, norm))
    put_meta(con, source="rn0x/Adhkar-json (Hisn al-Muslim)", source_fields_missing="source,takhrij,grade -> stored as NULL, never invented", built=time.strftime("%Y-%m-%d"))
    finish(con, p, results, ["category", "dhikr"])

def ar_title(x):
    if isinstance(x, dict): return x.get("title") or x.get("name") or x.get("arabic")
    return x

def build_hadith(results, with_english):
    root = DATA / "raw/hadith"
    files = sorted(root.rglob("*.json")) if root.exists() else []
    if not files: print("SKIP hadith dbs"); return
    vrep = read_report("hadith_validation.json") or {}
    status = {b["slug"]: b for b in vrep.get("books", [])}
    for f in files:
        slug = f.stem.lower()
        hadiths, chapters, meta = extract(json.loads(f.read_text(encoding="utf-8")))
        if not hadiths: print("SKIP", f.name, "(no hadiths)"); continue
        title = ar_title(meta.get("arabic")) if isinstance(meta, dict) and meta.get("arabic") else slug
        author = meta.get("arabic", {}).get("author") if isinstance(meta.get("arabic"), dict) else None
        con, p = fresh(f"hadith_{slug}.db")
        con.executescript("""
          CREATE TABLE chapter(id INTEGER PRIMARY KEY, title_ar TEXT);
          CREATE TABLE hadith(rowid INTEGER PRIMARY KEY AUTOINCREMENT, number INTEGER, chapter_id INTEGER, arabic TEXT NOT NULL, arabic_norm TEXT NOT NULL,
                              narrator_en TEXT, text_en TEXT, grade TEXT, grade_source TEXT);
          CREATE INDEX ix_h_num ON hadith(number); CREATE INDEX ix_h_ch ON hadith(chapter_id);
          CREATE VIRTUAL TABLE hadith_fts USING fts4(arabic_norm, tokenize=unicode61);""")
        for c in chapters:
            if isinstance(c, dict): con.execute("INSERT OR REPLACE INTO chapter VALUES(?,?)", (c.get("id"), ar_title(c.get("arabic"))))
        for h in hadiths:
            ar = h.get("arabic") or ""; en = h.get("english") or {}
            cur = con.execute("INSERT INTO hadith(number, chapter_id, arabic, arabic_norm, narrator_en, text_en) VALUES(?,?,?,?,?,?)",
                              (h.get("id"), h.get("chapterId"), ar, normalize_ar(ar), en.get("narrator") if with_english else None, en.get("text") if with_english else None))
            con.execute("INSERT INTO hadith_fts(docid, arabic_norm) VALUES(?,?)", (cur.lastrowid, normalize_ar(ar)))
        st = status.get(slug, {})
        put_meta(con, slug=slug, title_ar=title, author=author, completeness=st.get("status", "unverified"), completeness_note=st.get("note"),
                 source="AhmedBaset/hadith-json v1.2.0 (scraped from sunnah.com)", number_note="'number' is the source id, not necessarily the printed-edition numbering",
                 english_included=with_english, built=time.strftime("%Y-%m-%d"))
        finish(con, p, results, ["chapter", "hadith"])

def build_audio(results):
    f = DATA / "raw/mp3quran/reciters_ar.json"
    if not f.exists(): print("SKIP audio_catalog.db"); return
    data = json.loads(f.read_text(encoding="utf-8")); recs = data.get("reciters", data if isinstance(data, list) else [])
    con, p = fresh("audio_catalog.db")
    con.executescript("""
      CREATE TABLE reciter(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
      CREATE TABLE moshaf(id INTEGER PRIMARY KEY, reciter_id INTEGER NOT NULL, name TEXT, riwaya TEXT, server TEXT NOT NULL, surah_total INTEGER, surah_list TEXT NOT NULL);""")
    for r in recs:
        con.execute("INSERT OR REPLACE INTO reciter VALUES(?,?)", (r["id"], r["name"]))
        for m in r.get("moshaf", []):
            nm = m.get("name") or ""
            riw = "warsh" if "ورش" in nm else "hafs" if "حفص" in nm else None
            if not m.get("server") or not m.get("surah_list"): continue
            con.execute("INSERT OR REPLACE INTO moshaf VALUES(?,?,?,?,?,?,?)", (m["id"], r["id"], nm, riw, m["server"], m.get("surah_total"), m["surah_list"]))
    put_meta(con, source="mp3quran.net API v3 snapshot", note="whole-surah files: <server>/<NNN>.mp3 ; availability = surah_list", built=time.strftime("%Y-%m-%d"))
    finish(con, p, results, ["reciter", "moshaf"])

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--with-english", action="store_true", help="also store English hadith text (larger APK)")
    ap.add_argument("--only", nargs="*", choices=["quran", "adhkar", "hadith", "audio"]); a = ap.parse_args()
    results = []
    for name, fn in [("quran", build_quran), ("adhkar", build_adhkar), ("hadith", lambda r: build_hadith(r, a.with_english)), ("audio", build_audio)]:
        if not a.only or name in a.only: fn(results)
    # keep entries for DBs not rebuilt this run
    mp = OUT / "content_manifest.json"
    old = {x["name"]: x for x in json.loads(mp.read_text())["files"]} if mp.exists() else {}
    for r in results: old[r["name"]] = r
    old = {k: v for k, v in old.items() if (OUT / k).exists()}
    mp.write_text(json.dumps({"generated": time.strftime("%Y-%m-%d %H:%M:%S"), "files": list(old.values())}, ensure_ascii=False, indent=2), encoding="utf-8")
    (ASSETS / "legal").mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "sources/sources.json", ASSETS / "legal/sources.json")
    print("manifest ->", mp, "| files:", len(old))

if __name__ == "__main__":
    main()
