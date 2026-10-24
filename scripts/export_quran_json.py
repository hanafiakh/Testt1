#!/usr/bin/env python3
"""Export Tanzil text (verbatim) to ayahs.json {"<surah>": ["ayah1 text", ...]} for the memorization page. Does not alter text."""
import json, sys
from common import DATA, EXPECTED_AYAH_COUNTS
from validate_quran import parse
src = DATA / "raw/tanzil/quran-uthmani.txt"
if not src.exists(): sys.exit(f"missing {src}")
v, bad = parse(src)
if bad or len(v) != 6236: sys.exit("source failed validation; run validate_quran.py")
out = {str(s): [v[(s, a)] for a in range(1, EXPECTED_AYAH_COUNTS[s - 1] + 1)] for s in range(1, 115)}
dest = DATA / "mushaf/ayahs.json"; dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"); print("->", dest)
