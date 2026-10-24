#!/usr/bin/env python3
"""Download every entry in sources.json 'downloads'. Resumable. Records sha256 in data/download_manifest.json.
Usage: download_sources.py [--only ID ...] [--force] [--list]"""
import argparse, json, time
from pathlib import Path
from common import DATA, ASSETS, load_sources, download, http_json, write_report

def dest_path(d):
    return ASSETS / d[len("@assets/"):] if d.startswith("@assets/") else DATA / d

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*"); ap.add_argument("--force", action="store_true"); ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    src = load_sources()
    items = [d for d in src["downloads"] if not a.only or d["id"] in a.only]
    if a.list:
        for d in items: print(d["id"], d["kind"], d.get("url") or d.get("repo"), "->", d["dest"]); return
    man_path = DATA / "download_manifest.json"
    manifest = json.loads(man_path.read_text()) if man_path.exists() else {}
    failed_required = False
    for d in items:
        entries = []
        if d["kind"] == "file":
            entries.append((d["url"], dest_path(d["dest"])))
        elif d["kind"] == "github_tree":
            try:
                tree = http_json(f"https://api.github.com/repos/{d['repo']}/git/trees/{d['ref']}?recursive=1")
                if tree.get("truncated"): print("WARNING: tree truncated for", d["id"])
                for t in tree["tree"]:
                    if t["type"] == "blob" and t["path"].startswith(d["prefix"]) and t["path"].endswith(d["ext"]):
                        entries.append((f"https://raw.githubusercontent.com/{d['repo']}/{d['ref']}/{t['path']}",
                                        dest_path(d["dest"]) / t["path"][len(d["prefix"]):]))
            except Exception as e:
                manifest[d["id"]] = {"status": "failed", "error": repr(e), "license": d.get("license")}
                failed_required |= d.get("required", False); print("FAIL", d["id"], e); continue
        results = {}
        for url, dest in entries:
            if dest.exists() and not a.force:
                results[str(dest.relative_to(dest_path(d["dest"]).parent if d["kind"]=="github_tree" else dest.parent))] = {"status": "cached", "bytes": dest.stat().st_size}
                continue
            r = download(url, dest)
            r["url"] = url
            results[dest.name if d["kind"] == "file" else str(dest.relative_to(dest_path(d["dest"])))] = r
            print(("OK  " if r["status"] == "ok" else "FAIL"), url, r.get("error", ""))
        bad = [k for k, v in results.items() if v["status"] == "failed"]
        manifest[d["id"]] = {"status": "failed" if bad or not results else "ok", "files": results, "license": d.get("license"),
                             "url_verified": d.get("url_verified"), "notes": d.get("notes"), "time": time.strftime("%Y-%m-%d %H:%M:%S")}
        if (bad or not results) and d.get("required"): failed_required = True
    DATA.mkdir(parents=True, exist_ok=True)
    man_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("manifest ->", man_path)
    if failed_required:
        print("\nRequired sources failed. If offline, run this on a connected machine and copy data/ back.")
        raise SystemExit(1)

if __name__ == "__main__":
    main()
