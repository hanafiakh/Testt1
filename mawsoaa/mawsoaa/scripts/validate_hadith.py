#!/usr/bin/env python3
"""Per-book hadith validation. A book is only 'verified-complete' when sources.json provides
hadith_expected_counts[slug] AND the count matches. Otherwise status is 'counts-only' (never 'complete').
Books matching hadith_known_partial are 'partial'."""
import argparse, json, sys
from pathlib import Path
from common import DATA, load_sources, write_report

def extract(obj):
    if isinstance(obj, list): return obj, [], {}
    return obj.get("hadiths", []), obj.get("chapters", []), obj.get("metadata", {}) or {}

def title_of(meta, stem):
    ar = meta.get("arabic") if isinstance(meta, dict) else None
    return (ar.get("title") if isinstance(ar, dict) else ar) or stem

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--dir", default=str(DATA / "raw/hadith")); a = ap.parse_args()
    root = Path(a.dir)
    files = sorted(root.rglob("*.json")) if root.exists() else []
    if not files:
        print("MISSING hadith json under", root); write_report("hadith_validation.json", {"status": "missing_input", "dir": a.dir}); sys.exit(2)
    src = load_sources(); expected = src.get("hadith_expected_counts", {}); partial_rules = src.get("hadith_known_partial", [])
    books, any_fail = [], False
    for f in files:
        slug = f.stem.lower()
        b = {"slug": slug, "path": str(f.relative_to(root)), "problems": [], "warnings": []}
        try:
            hadiths, chapters, meta = extract(json.loads(f.read_text(encoding="utf-8")))
        except Exception as e:
            b.update(status="unreadable", problems=[repr(e)]); books.append(b); any_fail = True; continue
        b["title_ar"] = title_of(meta, slug)
        b["count"] = len(hadiths)
        if not hadiths: b["problems"].append("no hadith records found (unexpected JSON shape?)")
        ids = [h.get("id") for h in hadiths if isinstance(h, dict)]
        if len(ids) != len(hadiths): b["problems"].append("non-object records present")
        dup = len(ids) - len(set(ids)); 
        if dup: b["warnings"].append(f"{dup} duplicate ids")
        nums = sorted(i for i in set(ids) if isinstance(i, int))
        if nums:
            gaps = (nums[-1] - nums[0] + 1) - len(nums)
            b["id_range"] = [nums[0], nums[-1]]; b["id_gaps"] = gaps
            if gaps: b["warnings"].append(f"{gaps} gaps in id range (may be normal for this numbering)")
        empty = sum(1 for h in hadiths if isinstance(h, dict) and not (h.get("arabic") or "").strip())
        if empty: b["problems"].append(f"{empty} hadiths with empty Arabic text")
        if chapters:
            cids = {c.get("id") for c in chapters if isinstance(c, dict)}
            orphan = sum(1 for h in hadiths if isinstance(h, dict) and h.get("chapterId") not in cids)
            if orphan: b["warnings"].append(f"{orphan} hadiths reference unknown chapterId")
            b["chapters"] = len(chapters)
        rule = next((r for r in partial_rules if any(m in slug or m in str(b["title_ar"]) for m in r["match"])), None)
        if b["problems"]: b["status"] = "failed"; any_fail = True
        elif rule: b["status"] = "partial"; b["note"] = rule["note"]
        elif slug in expected: b["status"] = "verified-complete" if expected[slug] == len(hadiths) else "count-mismatch"; b["expected"] = expected[slug]
        else: b["status"] = "counts-only"; b["note"] = "no reference count configured; completeness NOT verified"
        books.append(b)
    rep = {"status": "fail" if any_fail else "pass", "books": books, "total_hadiths": sum(b.get("count", 0) for b in books)}
    write_report("hadith_validation.json", rep)
    for b in books: print(f"{b['slug']:28} {b.get('status'):18} count={b.get('count')} {b['problems'] or ''}")
    sys.exit(1 if any_fail else 0)

if __name__ == "__main__":
    main()
