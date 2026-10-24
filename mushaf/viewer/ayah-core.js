/* Per-ayah audio: URL building, ayah order, reciter list. No DOM: works in the browser and in Node.
   Every reciter below was checked on 2:255, 114:6 and 9:1 (HTTP 200, audio/mpeg) before being listed. Files are named SSSAAA.mp3. */
(function (root, factory) { if (typeof module === 'object' && module.exports) module.exports = factory(); else root.AyahCore = factory(); })(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const COUNTS = [7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,7,3,6,3,5,4,5,6];
  const Q = 'https://verses.quran.com/', E = 'https://mirrors.quranicaudio.com/everyayah/';
  const RECITERS = [
    { id: 7,  name: 'مشاري العفاسي',                 base: Q + 'Alafasy/mp3/' },
    { id: 3,  name: 'عبدالرحمن السديس',              base: Q + 'Sudais/mp3/' },
    { id: 10, name: 'سعود الشريم',                    base: Q + 'Shuraym/mp3/' },
    { id: 5,  name: 'هاني الرفاعي',                   base: Q + 'Rifai/mp3/' },
    { id: 4,  name: 'أبو بكر الشاطري',               base: Q + 'Shatri/mp3/' },
    { id: 2,  name: 'عبد الباسط عبد الصمد — مرتّل',   base: Q + 'AbdulBaset/Murattal/mp3/' },
    { id: 1,  name: 'عبد الباسط عبد الصمد — مجوّد',   base: Q + 'AbdulBaset/Mujawwad/mp3/' },
    { id: 9,  name: 'محمد صديق المنشاوي — مرتّل',     base: Q + 'Minshawi/Murattal/mp3/' },
    { id: 8,  name: 'محمد صديق المنشاوي — مجوّد',     base: Q + 'Minshawi/Mujawwad/mp3/' },
    { id: 6,  name: 'محمود خليل الحصري',              base: E + 'Husary_64kbps/' },
    { id: 12, name: 'محمود خليل الحصري — المعلّم',    base: E + 'Husary_Muallim_128kbps/' },
    { id: 11, name: 'محمد الطبلاوي',                  base: E + 'Mohammad_al_Tablaway_128kbps/' }
  ];
  const pad = n => String(n).padStart(3, '0');
  const parse = key => { const m = /^(\d{1,3}):(\d{1,3})$/.exec(String(key)); if (!m) return null; const s = +m[1], a = +m[2]; return (s >= 1 && s <= 114 && a >= 1 && a <= COUNTS[s - 1]) ? { s, a } : null; };
  const url = (rec, key) => { const p = parse(key); if (!p) throw new Error('bad ayah key: ' + key); return rec.base + pad(p.s) + pad(p.a) + '.mp3'; };
  const next = key => { const p = parse(key); if (!p) return null; if (p.a < COUNTS[p.s - 1]) return p.s + ':' + (p.a + 1); return p.s < 114 ? (p.s + 1) + ':1' : null; };   // continues into the next surah
  const prev = key => { const p = parse(key); if (!p) return null; if (p.a > 1) return p.s + ':' + (p.a - 1); return p.s > 1 ? (p.s - 1) + ':' + COUNTS[p.s - 2] : null; };
  const pick = id => RECITERS.find(r => String(r.id) === String(id)) || RECITERS[0];     // default: first = Alafasy
  return { COUNTS, RECITERS, parse, url, next, prev, pick, pad };
});
