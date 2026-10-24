#!/usr/bin/env python3
"""Build the data for a printed-Mushaf view (604 pages, 15 lines, one glyph font per page).

What it does
  1. For every page asks the Quran.com API v4 for the page's words: glyph code (code_v1 / code_v2), line number, real Uthmani text.
  2. Downloads the per-page font  pNNN.woff2  (QCF).
  3. Writes pages/NNN.json with a ready layout: word lines + surah banners + basmala lines + blanks.
  4. Checks: 604 pages, every ayah 1:1 .. 114:6 covered, <=15 lines per page.  --package makes mushaf_pack.zip (viewer + data).

NOT VERIFIED from this session (no network): endpoint/field names and font URLs below.  The validation step fails loudly
if the API shape differs, so nothing broken is silently shipped.  Fonts belong to King Fahd Complex (via quran.com / QUL):
CHECK THE LICENSE before publishing.  Usage:
  python3 scripts/build_mushaf_pages.py --pages 1-604 [--version v1|v2] [--tanzil-xml ..] [--tanzil-txt ..] [--package]
"""
import argparse, json, shutil, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from common import DATA, ROOT, EXPECTED_AYAH_COUNTS, http_json, download

API = "https://api.quran.com/api/v4/verses/by_page/{page}?words=true&per_page=300&mushaf={mushaf}&word_fields={fields}"
FONT = "https://verses.quran.com/fonts/quran/hafs/{v}/woff2/p{page}.woff2"
VERS = {"v1": dict(mushaf=2, code="code_v1", pg="v1_page"), "v2": dict(mushaf=1, code="code_v2", pg="v2_page")}
OUT = DATA / "mushaf"

def lines_in_page(page): return 8 if page <= 2 else 15

def normalize_words(page, verses, version):
    cfg = VERS[version]; words = []
    for v in verses:
        for w in v.get("words", []):
            if w.get(cfg["pg"], page) != page:      # a verse may continue on another page's font
                continue
            code = w.get(cfg["code"])
            if not code or w.get("line_number") is None: raise ValueError(f"page {page}: word without code/line ({v.get('verse_key')}); API shape changed?")
            words.append({"n": int(w["line_number"]), "c": code, "t": w.get("text_uthmani", ""), "k": v["verse_key"],
                          "pos": w.get("position", 0), "e": w.get("char_type_name") == "end"})
    return words

def build_layout(page, words):
    """Lines 1..N. Lines without words are surah banners / basmala / blanks."""
    total = lines_in_page(page); by = {}
    for w in words: by.setdefault(w["n"], []).append(w)
    layout, gaps = [], 0
    def flush_gaps(first):
        nonlocal gaps
        starts = first["pos"] == 1 and first["k"].endswith(":1")
        out = []
        if gaps and starts:
            s = int(first["k"].split(":")[0]); out.append({"type": "banner", "surah": s})
            rest = gaps - 1
            if s not in (1, 9) and rest >= 1: out.append({"type": "basmala"}); rest -= 1   # 1 and 9: no separate basmala line
            out += [{"type": "blank"}] * rest
        else: out += [{"type": "blank"}] * gaps
        gaps = 0; return out
    for n in range(1, total + 1):
        if n in by:
            layout += flush_gaps(by[n][0]); layout.append({"type": "words", "n": n, "words": by[n]})   # keep API order: sorting by pos would interleave verses
        else: gaps += 1
    layout += [{"type": "blank"}] * gaps
    return layout

def surah_pages(layouts):
    """layouts: iterable of (page, layout). First page on which each surah's banner appears (page 1 banner = Al-Fatihah)."""
    out = {}
    for page, lay in layouts:
        for item in lay:
            if item.get("type") == "banner": out.setdefault(item["surah"], page)
    return [out.get(i) for i in range(1, 115)]

def parse_range(s):
    a, _, b = s.partition("-"); return range(int(a), int(b or a) + 1)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", default="1-604"); ap.add_argument("--version", default="v1", choices=VERS)
    ap.add_argument("--tanzil-xml", default=str(DATA / "raw/tanzil/quran-data.xml")); ap.add_argument("--tanzil-txt", default=str(DATA / "raw/tanzil/quran-uthmani.txt"))
    ap.add_argument("--package", action="store_true"); ap.add_argument("--no-fonts", action="store_true")
    a = ap.parse_args()
    (OUT / "pages").mkdir(parents=True, exist_ok=True); (OUT / "fonts").mkdir(exist_ok=True)
    cfg = VERS[a.version]; problems = []
    for p in parse_range(a.pages):
        dest = OUT / "pages" / f"{p:03d}.json"
        if not dest.exists():
            try:
                url = API.format(page=p, mushaf=cfg["mushaf"], fields=",".join([cfg["code"], cfg["pg"], "line_number", "text_uthmani", "char_type_name", "position"]))
                words = normalize_words(p, http_json(url)["verses"], a.version)
                lay = build_layout(p, words)
                if len(lay) != lines_in_page(p): raise ValueError(f"layout has {len(lay)} lines, expected {lines_in_page(p)}")
                dest.write_text(json.dumps({"page": p, "font": f"p{p}", "layout": lay}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            except Exception as e:
                problems.append(f"page {p}: {e!r}"); print("FAIL page", p, e); continue
        if not a.no_fonts and not (OUT / "fonts" / f"p{p}.woff2").exists():
            r = download(FONT.format(v=a.version, page=p), OUT / "fonts" / f"p{p}.woff2")
            if r["status"] != "ok": problems.append(f"font p{p}: {r.get('error')}")
        if p % 50 == 0: print("page", p)
    # ---- meta (surah names + basmala come from Tanzil, verbatim) ----
    meta = {"surahs": [None] * 114, "basmala": None, "version": a.version}
    if Path(a.tanzil_xml).exists():
        for s in ET.parse(a.tanzil_xml).getroot().findall(".//sura"): meta["surahs"][int(s.get("index")) - 1] = s.get("name")
    if Path(a.tanzil_txt).exists():
        for line in open(a.tanzil_txt, encoding="utf-8"):
            if line.startswith("1|1|"): meta["basmala"] = line.rstrip("\n").split("|", 2)[2]; break
    # ---- validation over whatever pages exist ----
    seen = set(); present = sorted((OUT / "pages").glob("*.json"))
    for f in present:
        for item in json.loads(f.read_text(encoding="utf-8"))["layout"]:
            for w in item.get("words", []): seen.add(w["k"])
    meta["surah_pages"] = surah_pages((json.loads(f.read_text(encoding="utf-8"))["page"], json.loads(f.read_text(encoding="utf-8"))["layout"]) for f in present)
    (OUT / "meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    want = {f"{s}:{i}" for s in range(1, 115) for i in range(1, EXPECTED_AYAH_COUNTS[s - 1] + 1)}
    full = len(present) == 604
    if full and want - seen: problems.append(f"{len(want - seen)} ayahs missing from pages, e.g. {sorted(want - seen)[:5]}")
    print(f"pages built: {len(present)}/604; ayahs covered: {len(seen & want)}/6236; problems: {len(problems)}")
    for x in problems[:20]: print(" -", x)
    if a.package and not problems and full:
        for f in (ROOT / "mushaf/viewer").iterdir(): shutil.copy(f, OUT / f.name)
        zp = DATA / "mushaf_pack.zip"
        with zipfile.ZipFile(zp, "w", zipfile.ZIP_STORED) as z:     # woff2 is already compressed
            for f in OUT.rglob("*"):
                if f.is_file(): z.write(f, f.relative_to(OUT))
        print("package ->", zp, f"{zp.stat().st_size/1048576:.1f} MB")
    sys.exit(1 if problems else 0)

if __name__ == "__main__":
    main()
