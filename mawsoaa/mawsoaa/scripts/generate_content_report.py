#!/usr/bin/env python3
"""Aggregate manifests + validation reports into reports/CONTENT_REPORT.md. Prints 'NOT RUN' instead of guessing."""
import json, glob
from common import ROOT, DATA, ASSETS, REPORTS, load_sources, read_report

def mb(n): return f"{n/1048576:.1f} MB"

def main():
    L = ["# تقرير المحتوى — Content Report", ""]
    src = load_sources()
    L += ["## المصادر والتراخيص", "", "| المصدر | فُحص | الترخيص/الملاحظة |", "|---|---|---|"]
    for r in src["repos"]: L.append(f"| {r['url']} | {'نعم' if r.get('inspected_in_session') else 'لا (شغّل discover_sources.py)'} | {r.get('license_note','—')} |")
    L += ["", "## التنزيل الفعلي", ""]
    dm = DATA / "download_manifest.json"
    if dm.exists():
        for k, v in json.loads(dm.read_text()).items():
            files = v.get("files", {}); L.append(f"- **{k}**: {v['status']} — {len(files)} ملف، {mb(sum(x.get('bytes',0) for x in files.values()))} — {v.get('license')}")
    else: L.append("لم يُشغَّل download_sources.py (NOT RUN).")
    q = read_report("quran_validation.json")
    L += ["", "## القرآن", ""]
    L.append(f"- الحالة: **{q['status']}** — سور {q.get('surahs')}، آيات {q.get('ayahs')} (العد: {q.get('counting_system')})" if q and 'surahs' in q else "- NOT RUN / ملف المصدر غير موجود")
    for p in (q or {}).get("problems", []): L.append(f"  - مشكلة: {p}")
    for w in (q or {}).get("warnings", []): L.append(f"  - تنبيه: {w}")
    L.append("- رواية ورش: **غير مضمّنة** (لا مصدر كامل موثّق بعد).")
    h = read_report("hadith_validation.json")
    L += ["", "## الحديث", ""]
    if h and "books" in h:
        L += ["| الكتاب | العدد | الحالة |", "|---|---|---|"]
        for b in h["books"]: L.append(f"| {b.get('title_ar')} ({b['slug']}) | {b.get('count')} | {b.get('status')} {('— '+b['note']) if b.get('note') else ''} |")
        L.append(f"\nالمجموع: {h['total_hadiths']}. ‏«counts-only» تعني أن الاكتمال **لم يُتحقق منه** (أضف الرقم المرجعي في hadith_expected_counts).")
    else: L.append("NOT RUN")
    L += ["", "## قواعد البيانات المبنية", ""]
    mp = ASSETS / "db/content_manifest.json"
    if mp.exists():
        L += ["| الملف | الحجم | السجلات |", "|---|---|---|"]
        tot = 0
        for f in json.loads(mp.read_text())["files"]:
            tot += f["bytes"]; L.append(f"| {f['name']} | {mb(f['bytes'])} | {f['rows']} |")
        L.append(f"\nمجموع قواعد البيانات المضمّنة: {mb(tot)} (قبل ضغط APK).")
    else: L.append("لم تُبنَ (NOT RUN).")
    a = read_report("audio_validation.json")
    L += ["", "## الصوت", ""]
    if a: L.append(f"- الحالة: {a['status']} — كتالوج: {a.get('catalog')}")
    else: L.append("NOT RUN. عدد التسجيلات المنزّلة فعليًا على الجهاز يظهر في شاشة التنزيلات داخل التطبيق.")
    apks = glob.glob(str(ROOT / "app/build/outputs/apk/**/*.apk"), recursive=True)
    L += ["", "## بناء APK", ""]
    L += [f"- {p}: {mb(__import__('os').path.getsize(p))}" for p in apks] or ["- لم يُبنَ APK (NOT BUILT)."]
    L += ["", "## ما يحتاج استكمالًا", "", "- رواية ورش، التفاسير، شروح الحديث، السيرة، العقيدة، الفقه: تُضاف كحزم عبر make_pack.py بعد التحقق من الترخيص والاكتمال.",
          "- التخريج وأحكام الحديث ومصدر كل ذكر: غير متوفرة في المصادر الحالية وتُخزَّن NULL.", "- أرقام حجم حزم الصوت تُقاس من الخادم داخل التطبيق قبل التنزيل."]
    REPORTS.mkdir(exist_ok=True); out = REPORTS / "CONTENT_REPORT.md"; out.write_text("\n".join(L) + "\n", encoding="utf-8"); print("->", out)

if __name__ == "__main__":
    main()
