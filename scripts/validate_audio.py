#!/usr/bin/env python3
"""Audio validation.
  --catalog DB   check audio_catalog.db (surah lists sane, servers present)
  --probe N      HEAD-check N random remote surah URLs (needs internet)
  --dir DIR      verify downloaded files (<DIR>/<moshafId>/NNN.mp3): non-empty + MP3/ID3 header"""
import argparse, random, sqlite3, sys, urllib.request
from pathlib import Path
from common import ASSETS, UA, write_report

def mp3_ok(p: Path):
    with open(p, "rb") as f: h = f.read(3)
    return p.stat().st_size > 1024 and (h == b"ID3" or (len(h) >= 2 and h[0] == 0xFF and (h[1] & 0xE0) == 0xE0))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default=str(ASSETS / "db/audio_catalog.db")); ap.add_argument("--probe", type=int, default=0); ap.add_argument("--dir")
    a = ap.parse_args()
    rep, problems = {"catalog": None, "probe": None, "files": None}, []
    if Path(a.catalog).exists():
        con = sqlite3.connect(a.catalog)
        rows = con.execute("select id, reciter_id, server, surah_list from moshaf").fetchall()
        bad = []
        for mid, rid, server, sl in rows:
            nums = [int(x) for x in (sl or "").split(",") if x.strip().isdigit()]
            if not server or not nums or any(n < 1 or n > 114 for n in nums): bad.append(mid)
        rep["catalog"] = {"reciters": con.execute("select count(*) from reciter").fetchone()[0], "moshafs": len(rows), "invalid_moshafs": bad}
        if bad: problems.append(f"{len(bad)} moshafs with empty server/invalid surah list")
        if a.probe:
            pool = []
            for mid, rid, server, sl in rows:
                for n in random.sample([int(x) for x in sl.split(",") if x], k=min(1, len(sl.split(",")))):
                    pool.append((mid, n, f"{server.rstrip('/')}/{n:03d}.mp3"))
            sample, results = random.sample(pool, min(a.probe, len(pool))), []
            for mid, n, url in sample:
                try:
                    r = urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA}), timeout=20)
                    results.append({"url": url, "status": r.status, "bytes": r.headers.get("Content-Length"), "type": r.headers.get("Content-Type")})
                except Exception as e:
                    results.append({"url": url, "error": repr(e)})
            rep["probe"] = results
            fails = [r for r in results if "error" in r or r["status"] != 200]
            if fails: problems.append(f"{len(fails)}/{len(results)} probe URLs failed")
    else:
        rep["catalog"] = "missing"; 
    if a.dir:
        files = list(Path(a.dir).rglob("*.mp3")); bad = [str(f) for f in files if not mp3_ok(f)]
        parts = [str(f) for f in Path(a.dir).rglob("*.part")]
        rep["files"] = {"count": len(files), "bad": bad, "incomplete_parts": parts}
        if bad: problems.append(f"{len(bad)} corrupt mp3 files")
    rep["status"] = "fail" if problems else "pass"; rep["problems"] = problems
    write_report("audio_validation.json", rep); print(rep["status"].upper(), rep); sys.exit(1 if problems else 0)

if __name__ == "__main__":
    main()
