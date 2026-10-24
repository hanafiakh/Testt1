"""Pipeline tests on SYNTHETIC placeholder data (never real Quran/hadith text, never copied into the app)."""
import json, os, sqlite3, subprocess, sys, tempfile, unittest
from pathlib import Path
SC = Path(__file__).resolve().parent.parent / "scripts"
sys.path.insert(0, str(SC))
from common import EXPECTED_AYAH_COUNTS, normalize_ar

def run(script, env, *args):
    return subprocess.run([sys.executable, str(SC / script), *args], env={**os.environ, **env}, capture_output=True, text=True)

class Pipeline(unittest.TestCase):
    def setUp(self):
        self.t = Path(tempfile.mkdtemp())
        self.env = {"MAWSOAA_DATA": str(self.t / "data"), "MAWSOAA_ASSETS": str(self.t / "assets"), "MAWSOAA_REPORTS": str(self.t / "reports")}
        q = self.t / "data/raw/tanzil"; q.mkdir(parents=True)
        self.qfile = q / "quran-uthmani.txt"
        self.qfile.write_text("\n".join(f"{s}|{a}|نَصٌّ تَجْرِيبِيٌّ {s} {a}" for s in range(1, 115) for a in range(1, EXPECTED_AYAH_COUNTS[s - 1] + 1)) + "\n# synthetic\n", encoding="utf-8")

    def test_quran_pass_build_and_roundtrip(self):
        r = run("validate_quran.py", self.env); self.assertEqual(r.returncode, 0, r.stdout)
        r = run("build_local_database.py", self.env, "--only", "quran"); self.assertEqual(r.returncode, 0, r.stderr)
        db = self.t / "assets/db/quran.db"
        con = sqlite3.connect(db)
        self.assertEqual(con.execute("select count(*) from surah").fetchone()[0], 114)
        self.assertEqual(con.execute("select count(*) from ayah").fetchone()[0], 6236)
        hit = con.execute("select a.surah,a.ayah from ayah_fts f join ayah a on a.id=f.docid where ayah_fts match ?", ("تجريبي* 114* 6*",)).fetchall()
        self.assertEqual(sorted(hit), [(6, 114), (114, 6)])   # AND of two prefix terms matches both orderings
        r = run("validate_quran.py", self.env, "--db", str(db)); self.assertEqual(r.returncode, 0, r.stdout)   # byte-identical to source

    def test_quran_missing_ayah_fails(self):
        lines = self.qfile.read_text(encoding="utf-8").splitlines()
        self.qfile.write_text("\n".join(l for l in lines if not l.startswith("2|100|")), encoding="utf-8")
        r = run("validate_quran.py", self.env); self.assertEqual(r.returncode, 1); self.assertIn("surah 2", r.stdout)
        r = run("build_local_database.py", self.env, "--only", "quran")
        self.assertFalse((self.t / "assets/db/quran.db").exists())   # refuses to build from an incomplete source

    def test_modified_text_detected(self):
        run("build_local_database.py", self.env, "--only", "quran")
        db = self.t / "assets/db/quran.db"; con = sqlite3.connect(db); con.execute("update ayah set text='x' where surah=3 and ayah=3"); con.commit()
        r = run("validate_quran.py", self.env, "--db", str(db)); self.assertEqual(r.returncode, 1)

    def test_hadith_status_never_claims_complete(self):
        h = self.t / "data/raw/hadith/the_9_books"; h.mkdir(parents=True)
        mk = lambda n: {"id": 1, "metadata": {"arabic": {"title": "كتاب تجريبي", "author": "مؤلف"}}, "chapters": [{"id": 1, "arabic": "باب"}],
                        "hadiths": [{"id": i, "chapterId": 1, "bookId": 1, "arabic": f"حديث تجريبي {i}", "english": {"narrator": "n", "text": "t"}} for i in range(1, n + 1)]}
        (h / "bukhari.json").write_text(json.dumps(mk(5), ensure_ascii=False)); (h / "ahmad.json").write_text(json.dumps(mk(3), ensure_ascii=False))
        r = run("validate_hadith.py", self.env); self.assertEqual(r.returncode, 0, r.stdout)
        rep = json.loads((self.t / "reports/hadith_validation.json").read_text())
        st = {b["slug"]: b["status"] for b in rep["books"]}
        self.assertEqual(st, {"ahmad": "partial", "bukhari": "counts-only"})
        run("build_local_database.py", self.env, "--only", "hadith")
        con = sqlite3.connect(self.t / "assets/db/hadith_bukhari.db")
        self.assertEqual(con.execute("select value from meta where key='completeness'").fetchone()[0], "counts-only")
        self.assertEqual(con.execute("select count(*) from hadith").fetchone()[0], 5)
        self.assertIsNone(con.execute("select text_en from hadith limit 1").fetchone()[0])   # English excluded by default

    def test_adhkar_nulls_not_invented(self):
        a = self.t / "data/raw/adhkar"; a.mkdir(parents=True)
        (a / "adhkar.json").write_text(json.dumps([{"id": 1, "category": "تجريبي", "audio": "/a.mp3", "array": [{"id": 1, "text": "ذكر تجريبي", "count": 3, "audio": "/1.mp3"}]}], ensure_ascii=False))
        run("build_local_database.py", self.env, "--only", "adhkar")
        con = sqlite3.connect(self.t / "assets/db/adhkar.db")
        self.assertEqual(con.execute("select count, source, takhrij, grade from dhikr").fetchone(), (3, None, None, None))

    def test_audio_catalog_and_report(self):
        m = self.t / "data/raw/mp3quran"; m.mkdir(parents=True)
        (m / "reciters_ar.json").write_text(json.dumps({"reciters": [{"id": 1, "name": "قارئ تجريبي", "moshaf": [
            {"id": 10, "name": "رواية حفص عن عاصم - مرتل", "server": "https://example.invalid/r/", "surah_total": 3, "surah_list": "1,2,114"},
            {"id": 11, "name": "x", "server": "", "surah_list": "1"}]}]}, ensure_ascii=False))
        run("build_local_database.py", self.env, "--only", "audio")
        con = sqlite3.connect(self.t / "assets/db/audio_catalog.db")
        self.assertEqual(con.execute("select id, riwaya from moshaf").fetchall(), [(10, "hafs")])
        r = run("validate_audio.py", self.env, "--catalog", str(self.t / "assets/db/audio_catalog.db")); self.assertEqual(r.returncode, 0, r.stdout)
        run("generate_content_report.py", self.env)
        self.assertIn("NOT RUN", (self.t / "reports/CONTENT_REPORT.md").read_text(encoding="utf-8"))

    def test_normalize(self):
        self.assertEqual(normalize_ar("ٱلرَّحْمَٰنِ  أَنْزَلَ"), "الرحمن انزل")



class MushafLayout(unittest.TestCase):
    """Layout logic on synthetic words (no Quran text)."""
    def W(self, n, pos, k, e=False): return {"n": n, "c": "x", "t": "t", "k": k, "pos": pos, "e": e}

    def test_surah_start_gets_banner_and_basmala(self):
        import build_mushaf_pages as b
        words = [self.W(1, 1, "1:1")] + [self.W(n, 1, "2:%d" % (n)) for n in range(5, 6)]
        words = [self.W(n, 1, "5:3") for n in range(1, 4)] + [self.W(6, 1, "6:1"), self.W(7, 1, "6:1")]   # lines 4,5 empty -> banner+basmala
        lay = b.build_layout(10, words)
        self.assertEqual(len(lay), 15)
        self.assertEqual([x["type"] for x in lay[3:6]], ["banner", "basmala", "words"])
        self.assertEqual(lay[3]["surah"], 6)

    def test_surah_9_banner_only_and_blank_fill(self):
        import build_mushaf_pages as b
        words = [self.W(n, 1, "8:70") for n in range(1, 5)] + [self.W(6, 1, "9:1")] + [self.W(n, 1, "9:2") for n in range(7, 16)]
        lay = b.build_layout(187, words)
        self.assertEqual([x["type"] for x in lay[4:6]], ["banner", "words"])   # no basmala for surah 9
        self.assertEqual(len(lay), 15)

    def test_short_page_and_api_shape_check(self):
        import build_mushaf_pages as b
        self.assertEqual(len(b.build_layout(1, [self.W(2, 1, "1:1")])), 8)
        with self.assertRaises(ValueError):
            b.normalize_words(3, [{"verse_key": "2:1", "words": [{"position": 1}]}], "v1")   # no code/line -> loud failure
        ok = b.normalize_words(3, [{"verse_key": "2:1", "words": [{"code_v1": "a", "line_number": 2, "position": 1, "text_uthmani": "t", "v1_page": 3},
                                                                   {"code_v1": "b", "line_number": 2, "position": 2, "v1_page": 4}]}], "v1")
        self.assertEqual(len(ok), 1)   # word that lives on another page's font is skipped



class SurahPages(unittest.TestCase):
    def test_map(self):
        import build_mushaf_pages as b
        r = b.surah_pages([(1, [{"type": "banner", "surah": 1}]), (2, [{"type": "blank"}]), (3, [{"type": "banner", "surah": 2}, {"type": "banner", "surah": 3}])])
        self.assertEqual((r[0], r[1], r[2], r[3]), (1, 3, 3, None))


class WordOrder(unittest.TestCase):
    def test_line_keeps_api_order_across_verses(self):
        """Regression: a printed line can hold the end of verse 2 and the start of verse 3 (pos 7,8 then pos 1..). Sorting by pos broke this."""
        import build_mushaf_pages as b
        W = lambda k, pos: {"n": 4, "c": "x", "t": "t", "k": k, "pos": pos, "e": False}
        words = [W("2:2", 7), W("2:2", 8), W("2:3", 1), W("2:3", 2)]
        lay = b.build_layout(3, words)
        line = [x for x in lay if x["type"] == "words"][0]["words"]
        self.assertEqual([(w["k"], w["pos"]) for w in line], [("2:2", 7), ("2:2", 8), ("2:3", 1), ("2:3", 2)])

if __name__ == "__main__":
    unittest.main(verbosity=2)
