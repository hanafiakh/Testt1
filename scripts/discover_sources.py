#!/usr/bin/env python3
"""Inspect candidate GitHub repos: license, archived flag, file formats, largest files.
Usage: discover_sources.py [--search]   (set GITHUB_TOKEN to avoid API rate limits)
Never assumes a repo contains a full Quran/hadith book: only lists what the API shows."""
import argparse, collections, sys, time, urllib.parse
from common import load_sources, http_json, write_report

def inspect(url):
    owner, name = url.rstrip("/").split("github.com/")[1].split("/")[:2]
    out = {"url": url}
    try:
        info = http_json(f"https://api.github.com/repos/{owner}/{name}")
        lic = info.get("license") or {}
        out.update(license=lic.get("spdx_id") or "NONE-DECLARED", archived=info.get("archived"),
                   pushed_at=info.get("pushed_at"), default_branch=info.get("default_branch"),
                   size_kb=info.get("size"), stars=info.get("stargazers_count"), description=info.get("description"))
        tree = http_json(f"https://api.github.com/repos/{owner}/{name}/git/trees/{info['default_branch']}?recursive=1")
        files = [t for t in tree.get("tree", []) if t["type"] == "blob"]
        ext = collections.Counter(t["path"].rsplit(".", 1)[-1].lower() if "." in t["path"] else "(none)" for t in files)
        out["file_count"] = len(files)
        out["tree_truncated"] = tree.get("truncated", False)
        out["extensions"] = dict(ext.most_common(12))
        out["largest"] = [{"path": t["path"], "bytes": t.get("size")} for t in sorted(files, key=lambda t: -(t.get("size") or 0))[:10]]
        out["data_files"] = [t["path"] for t in files if t["path"].lower().endswith((".json", ".db", ".sqlite", ".xml", ".csv", ".txt", ".sql"))][:60]
        out["status"] = "ok"
    except Exception as e:
        out.update(status="inaccessible", error=repr(e))
    return out

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--search", action="store_true"); a = ap.parse_args()
    src = load_sources()
    rep = {"repos": [inspect(r["url"]) for r in src["repos"]], "search": {}}
    if a.search:
        for term in src["search_terms"]:
            try:
                q = urllib.parse.quote(term)
                res = http_json(f"https://api.github.com/search/repositories?q={q}&sort=stars&per_page=8")
                rep["search"][term] = [{"repo": i["full_name"], "stars": i["stargazers_count"], "license": (i.get("license") or {}).get("spdx_id"),
                                        "desc": i.get("description")} for i in res.get("items", [])]
            except Exception as e:
                rep["search"][term] = {"error": repr(e)}
            time.sleep(6)  # unauthenticated search limit ~10/min
    p = write_report("sources_discovery.json", rep)
    for r in rep["repos"]:
        print(f"{r['url']}: {r.get('status')} license={r.get('license')} archived={r.get('archived')} files={r.get('file_count')} ext={r.get('extensions')}")
    print("report ->", p)

if __name__ == "__main__":
    main()
