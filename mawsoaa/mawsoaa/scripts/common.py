"""Shared helpers. Standard library only."""
import hashlib, json, os, re, sys, time, urllib.request, urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(os.environ.get("MAWSOAA_DATA", ROOT / "data"))
ASSETS = Path(os.environ.get("MAWSOAA_ASSETS", ROOT / "app/src/main/assets"))
REPORTS = Path(os.environ.get("MAWSOAA_REPORTS", ROOT / "reports"))
SOURCES_FILE = ROOT / "sources" / "sources.json"
UA = "MawsoaaBuilder/1.0 (+offline islamic library builder)"

# Standard Hafs / Kufi ayah counts per surah (counts only; no Quranic text).
EXPECTED_AYAH_COUNTS = [
    7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,
    112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,
    54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,
    14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,
    29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
    11,8,3,9,5,4,7,3,6,3,5,4,5,6]
assert len(EXPECTED_AYAH_COUNTS) == 114 and sum(EXPECTED_AYAH_COUNTS) == 6236, "ayah table corrupted"

_DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640\u08D3-\u08FF]")
_MAP = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"})

def normalize_ar(s: str) -> str:
    """Search normalisation. MUST stay identical to Arabic.kt normalizeAr()."""
    s = _DIAC.sub("", s).translate(_MAP)
    return re.sub(r"\s+", " ", s).strip()

def load_sources():
    return json.loads(SOURCES_FILE.read_text(encoding="utf-8"))

def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def _req(url, headers=None):
    h = {"User-Agent": UA}
    if headers: h.update(headers)
    if "api.github.com" in url and os.environ.get("GITHUB_TOKEN"):
        h["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    return urllib.request.Request(url, headers=h)

def http_json(url, timeout=60):
    with urllib.request.urlopen(_req(url, {"Accept": "application/json"}), timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))

def download(url: str, dest: Path, retries=3):
    """Resumable download. Returns dict(status, bytes, sha256). Never fabricates on failure."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    last = None
    for attempt in range(retries):
        try:
            have = part.stat().st_size if part.exists() else 0
            hdr = {"Range": f"bytes={have}-"} if have else {}
            with urllib.request.urlopen(_req(url, hdr), timeout=60) as r:
                mode = "ab" if (have and r.status == 206) else "wb"
                with open(part, mode) as f:
                    while True:
                        b = r.read(1 << 16)
                        if not b: break
                        f.write(b)
            part.replace(dest)
            return {"status": "ok", "bytes": dest.stat().st_size, "sha256": sha256_file(dest)}
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code == 416 and part.exists():
                part.replace(dest)
                return {"status": "ok", "bytes": dest.stat().st_size, "sha256": sha256_file(dest)}
            if e.code in (401, 403, 404): break
        except Exception as e:
            last = repr(e)
        time.sleep(2 * (attempt + 1))
    return {"status": "failed", "error": last}

def write_report(name: str, obj: dict):
    REPORTS.mkdir(parents=True, exist_ok=True)
    p = REPORTS / name
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    return p

def read_report(name: str):
    p = REPORTS / name
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
