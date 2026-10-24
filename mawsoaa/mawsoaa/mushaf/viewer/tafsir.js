/* Tafsir page: one ayah at a time (Quran text from CFG.ayahs, commentary from CFG.tafsir). */
(() => {
  'use strict';
  const $ = id => document.getElementById(id), CFG = window.MUSHAF_CFG;
  const BOOKS = [[16, 'التفسير الميسر'], [91, 'تفسير السعدي'], [14, 'تفسير ابن كثير'], [15, 'تفسير الطبري'], [90, 'تفسير القرطبي'], [93, 'التفسير الوسيط'], [94, 'تفسير البغوي']];
  const num = n => String(n).replace(/\d/g, d => '٠١٢٣٤٥٦٧٨٩'[d]);
  let ayahs = null, names = [];
  BOOKS.forEach(([id, n]) => $('book').add(new Option(n, id)));
  try { $('book').value = localStorage.getItem('tafsir-book') || 16; } catch (e) {}

  if (!CFG.tafsir) { $('body').textContent = 'التفسير غير متاح في هذه النسخة من الموقع.'; return; }
  Promise.all([fetch(CFG.ayahs).then(r => r.json()), fetch(CFG.meta).then(r => r.ok ? r.json() : {}).catch(() => ({}))]).then(([a, m]) => {
    ayahs = a; names = m.surahs || [];
    for (let s = 1; s <= 114; s++) $('surah').add(new Option(`${s} — ${names[s - 1] || 'سورة ' + s}`, s));
    const m2 = /^#(\d+):(\d+)$/.exec(location.hash); if (m2) { $('surah').value = m2[1]; $('aya').value = m2[2]; show(); }
  }).catch(() => { $('body').textContent = 'تعذّر تحميل نص القرآن'; });

  async function show() {
    if (!ayahs) return;
    const s = +$('surah').value, list = ayahs[s]; let a = Math.min(Math.max(1, +$('aya').value || 1), list.length);
    $('aya').value = a; const key = `${s}:${a}`;
    try { localStorage.setItem('tafsir-book', $('book').value); } catch (e) {}
    history.replaceState(null, '', '#' + key);
    $('ayah').textContent = list[a - 1] + ' ﴿' + num(a) + '﴾';          // Quran text verbatim (Tanzil)
    $('body').textContent = 'جارٍ التحميل…'; $('src').textContent = '';
    try {
      const r = await fetch(CFG.tafsir($('book').value, key)); if (!r.ok) throw new Error(r.status);
      const d = await r.json();
      $('body').innerHTML = d.html || 'لا يوجد تفسير لهذه الآية في هذا الكتاب.';
      $('src').textContent = `${d.book} — المصدر: quran.com`;
    } catch (e) { $('body').textContent = 'تعذّر جلب التفسير (تحتاج اتصالًا بالإنترنت للآيات التي لم تُفتح من قبل).'; }
    scrollTo(0, 0);
  }
  const step = d => { const s = +$('surah').value, n = ayahs[s].length; let a = +$('aya').value + d;
    if (a < 1 && s > 1) { $('surah').value = s - 1; a = ayahs[s - 1].length; } else if (a > n && s < 114) { $('surah').value = s + 1; a = 1; }
    $('aya').value = a; show(); };
  $('go').onclick = show; $('prev').onclick = () => step(-1); $('next').onclick = () => step(1);
  $('book').onchange = show; $('surah').onchange = () => { $('aya').value = 1; show(); };
})();
