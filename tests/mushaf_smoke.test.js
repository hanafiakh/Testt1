// Runs the REAL mushaf.js in a stub browser: renders a page, shows the banner play button, plays/pauses/advances. Synthetic data only.
const vm = require('vm'), fs = require('fs'), assert = require('assert');
const els = {}, ios = [], audios = [], LISTENERS = {};
function mk(tag) {
  const e = { tag, children: [], dataset: {}, style: { setProperty(k, v) { this[k] = v; }, getPropertyValue(k) { return this[k] || ''; } }, clientWidth: 600, clientHeight: 900, hidden: false, value: '', _t: '',
    classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, toggle(c, f) { f ? this._s.add(c) : this._s.delete(c); }, contains(c) { return this._s.has(c); } },
    appendChild(c) { this.children.push(c); c.parent = this; return c; }, append(c) { this.appendChild(c); }, add() {}, setAttribute() {}, addEventListener(n, f) { (this.h ||= {})[n] = f; },
    closest(sel) { let e = this; while (e) { if (sel === '.page' && e.className && e.className.startsWith('page')) return e; e = e.parent; } return null; }, scrollIntoView(o) { this.scrolled = o; },
    getBoundingClientRect() { return { width: global.NAT_W || 10 }; }, showModal() {}, close() {}, querySelector() { return null; },
    get textContent() { return this._t; }, set textContent(v) { this._t = v; if (v === '') this.children = []; },
    get id() { return this._id; }, set id(v) { this._id = v; els[v] = this; } };
  return e;
}
const pagesList = () => Object.values(els).filter(e => e.className && e.className.startsWith('page'));
const spans = () => pagesList().flatMap(p => p.children.flatMap(l => l.children || [])).filter(s => /(^|\s)w(\s|$)/.test(s.className || ''));
const qsa = sel => {
  if (sel === '.page') return pagesList();
  if (sel === '.w.hl') return spans().filter(s => s.classList._s.has('hl'));
  const m = /^\.w\[data-k="(.+)"\]$/.exec(sel); return m ? spans().filter(s => s.dataset.k === m[1]) : [];
};
const doc = { documentElement: mk('html'), getElementById: id => els[id] || (els[id] = mk('x')), createElement: mk, querySelectorAll: qsa, querySelector: s => qsa(s)[0], addEventListener() {}, fonts: { add() {} } };
class IO { constructor(cb) { this.cb = cb; ios.push(this); } observe() {} }
class FakeAudio { constructor() { this.paused = true; this.src = ''; this.h = {}; audios.push(this); } addEventListener(n, f) { this.h[n] = f; }
  play() { this.paused = false; this.h.play && this.h.play(); return Promise.resolve(); } pause() { this.paused = true; this.h.pause && this.h.pause(); } removeAttribute() { this.src = ''; } load() {} }
const layout = { page: 2, font: 'p2', layout: [{ type: 'banner', surah: 2 }, { type: 'basmala' }, { type: 'words', n: 3, words: [{ c: 'a', t: 'x', k: '2:1', pos: 1, e: false }, { c: 'b', t: '', k: '2:1', pos: 2, e: true }] }, { type: 'blank' }] };
const data = { 'api/page/2': layout, meta: { surahs: ['الفاتحة', 'البقرة'], surah_pages: [1, 2], basmala: 'بسملة' }, 'api/reciters': { reciters: [{ id: 123, name: 'قارئ', server: 'https://cdn.example/r1/' }, { id: 7, name: 'آخر', server: 'https://cdn.example/r7' }], default: 123 } };
const ctx = { console, setTimeout, clearTimeout, Promise, URLSearchParams, Option: function (a, b) { return { a, b }; }, IntersectionObserver: IO, Audio: FakeAudio, MediaMetadata: function (o) { return o; },
  FontFace: class { constructor() {} async load() { return this; } }, document: doc, matchMedia: () => ({ matches: false }), getSelection: () => null, history: { replaceState() {} }, location: { search: '', hash: '' },
  localStorage: { getItem: () => null, setItem() {} }, navigator: { mediaSession: { setActionHandler() {} } }, addEventListener(n, f) { LISTENERS[n] = f; }, caches: undefined,
  fetch: async u => ({ ok: u in data, json: async () => data[u] }) };
ctx.window = ctx; vm.createContext(ctx);
ctx.MUSHAF_CFG = { page: p => `api/page/${p}`, font: p => `f${p}`, meta: 'meta', ayahs: 'a', tafsir: null, reciters: 'api/reciters' };
vm.runInContext(fs.readFileSync(__dirname + '/../mushaf/viewer/player-core.js', 'utf8'), ctx); ctx.PlayerCore = vm.runInContext('PlayerCore', ctx);
vm.runInContext(fs.readFileSync(__dirname + '/../mushaf/viewer/ayah-core.js', 'utf8'), ctx); ctx.AyahCore = vm.runInContext('AyahCore', ctx);
vm.runInContext(fs.readFileSync(__dirname + '/../mushaf/viewer/mushaf.js', 'utf8'), ctx);      // throws here on any load-time bug

const wait = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  await wait(30);
  assert.strictEqual(els['recWrap'].hidden, false, 'reciter picker should appear');
  global.NAT_W = 400;                                                                           // each word 400px wide at 100px font -> width-bound layout
  ios[0].cb([{ isIntersecting: true, target: els['p2'] }]); await wait(30);                      // page 2 scrolls into view
  const page = els['p2']; const banner = page.children.find(c => c.className === 'banner');
  assert.ok(banner, 'banner rendered'); assert.strictEqual(banner.textContent, 'سورة البقرة');
  const btn = banner.children.find(c => c.className === 'bplay'); assert.ok(btn, 'banner has a play button');
  assert.ok(page.children.some(c => c.className === 'basmala' && c.textContent === 'بسملة'), 'basmala from meta');
  btn.onclick();                                                                              // play surah 2
  const a = audios[0]; assert.strictEqual(a.src, 'https://cdn.example/r1/002.mp3'); assert.strictEqual(a.paused, false);
  assert.strictEqual(els['player'].hidden, false); assert.ok(els['pTitle'].textContent.includes('البقرة') && els['pTitle'].textContent.includes('قارئ'));
  btn.onclick(); assert.strictEqual(a.paused, true, 'second tap pauses');
  btn.onclick(); assert.strictEqual(a.paused, false, 'third tap resumes');
  els['pNext'].onclick(); assert.strictEqual(a.src, 'https://cdn.example/r1/003.mp3', 'next surah');
  a.h.ended(); assert.strictEqual(a.src, 'https://cdn.example/r1/004.mp3', 'auto-advance at the end');
  els['reciter'].value = '7'; els['reciter'].onchange(); assert.strictEqual(a.src, 'https://cdn.example/r7/004.mp3', 'changing reciter keeps the surah');
  els['pStop'].onclick(); assert.strictEqual(els['player'].hidden, true);
  // ---- tap an ayah: recited by the chosen sheikh, words turn red, then the next ayah follows ----
  doc.getElementById('menu').hidden = true; global.NAT_W = 400;
  const word = spans()[0];                                                                          // a word of ayah 2:1
  assert.strictEqual(word.dataset.k, '2:1');
  els['pages'].h.click({ target: word });
  assert.strictEqual(a.src, 'https://verses.quran.com/Alafasy/mp3/002001.mp3', 'default sheikh: Alafasy');
  assert.ok(word.classList._s.has('hl'), 'tapped ayah turns red'); assert.strictEqual(a.paused, false);
  assert.ok(els['pTitle'].textContent.includes('آية'), els['pTitle'].textContent);
  a.h.ended();                                                                                      // ayah finished -> next ayah, not the next surah
  assert.strictEqual(a.src, 'https://verses.quran.com/Alafasy/mp3/002002.mp3', 'continues with the next ayah');
  assert.strictEqual(qsa('.w.hl').length, 0, 'the previous ayah is no longer red (2:2 is not drawn in this synthetic page)');
  els['p3'].scrolled = null; a.h.ended();
  assert.strictEqual(a.src, 'https://verses.quran.com/Alafasy/mp3/002003.mp3');
  els['pPrev'].onclick(); assert.strictEqual(a.src, 'https://verses.quran.com/Alafasy/mp3/002002.mp3', 'previous ayah');
  els['pTitle'].textContent = ''; els['ayahReciter'].value = '3'; els['ayahReciter'].onchange();
  assert.strictEqual(a.src, 'https://verses.quran.com/Sudais/mp3/002002.mp3', 'changing the sheikh keeps the ayah');
  els['pages'].h.click({ target: word });
  assert.ok(word.classList._s.has('hl') && a.src.endsWith('/Sudais/mp3/002001.mp3'), 'tap again: that ayah, with the new sheikh');
  els['pStop'].onclick(); assert.strictEqual(qsa('.w.hl').length, 0, 'stopping clears the colour'); assert.strictEqual(els['player'].hidden, true);
  // a tap on empty space (not a word) must still toggle the bars, not play
  const srcBefore = a.src; els['pages'].h.click({ target: { className: 'page', dataset: {} } }); assert.strictEqual(a.src, srcBefore);
  // ---- book-style paging: keys move one page at a time, in reading direction (RTL: left arrow = next) ----
  ios[1].cb([{ isIntersecting: true, target: els['p2'] }]);                                     // page 2 is now the current page
  LISTENERS.keydown({ key: 'ArrowLeft', target: { tagName: 'BODY' }, preventDefault() {} });
  assert.ok(els['p3'].scrolled && els['p3'].scrolled.inline === 'center' && els['p3'].scrolled.behavior === 'smooth', 'next page slides in');
  LISTENERS.keydown({ key: 'ArrowRight', target: { tagName: 'BODY' }, preventDefault() {} });
  assert.ok(els['p1'].scrolled, 'previous page');
  els['p1'].scrolled = null; ios[1].cb([{ isIntersecting: true, target: els['p1'] }]);
  LISTENERS.keydown({ key: 'ArrowRight', target: { tagName: 'BODY' }, preventDefault() {} });
  assert.ok(!els['p0'], 'no page before the first');
  LISTENERS.keydown({ key: 'ArrowLeft', target: { tagName: 'INPUT' }, preventDefault() { throw new Error('typing in a field must not turn pages'); } });
  // ---- window shrinks (floating window): the font size must follow, otherwise lines overlap ----
  const fsOf = () => parseFloat(els['p2'].style['--fs']);
  const big = fsOf(); assert.ok(big > 60 && big < 80, 'initial size ' + big);                  // 600px wide page
  els['p2'].clientWidth = 300; LISTENERS.resize(); await wait(250);
  const small = fsOf(); assert.ok(Math.abs(small - big / 2) < 1, `font must halve with the width: ${big} -> ${small}`);
  els['p2'].clientWidth = 600; LISTENERS.resize(); await wait(250);
  assert.ok(Math.abs(fsOf() - big) < 0.1, 'and grow back when the window is enlarged');
  console.log('viewer smoke test: all assertions passed'); process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
