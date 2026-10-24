// Runs the REAL memorize.js in a fake browser (stub DOM + fake SpeechRecognition, real timers). Synthetic words only.
const vm = require('vm'), fs = require('fs'), assert = require('assert');
const els = {};
const mk = () => ({ _t: '', innerHTML: '', value: '1', children: [], onclick: null,
  classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); } },
  append(c) { this.children.push(c); }, add() {},
  get textContent() { return this._t; }, set textContent(v) { this._t = v; if (v === '') this.children = []; } });
const $ = id => els[id] ||= mk();
const recs = []; const R = () => recs[recs.length - 1];
let deadNext = 0, throwNext = 0;     // deadNext: next recognizers are accepted but the mic never comes on;  throwNext: start() throws
class FakeSR {
  constructor() { recs.push(this); this.running = false; this.dead = false; if (deadNext > 0) { deadNext--; this.dead = true; } }
  start() { if (throwNext > 0) { throwNext--; const e = new Error('x'); e.name = 'InvalidStateError'; throw e; } this.running = true; if (!this.dead) setTimeout(() => this.onstart && this.onstart(), 0); }
  stop() { this.running = false; setTimeout(() => this.onend && this.onend(), 0); }
  abort() { this.running = false; }
}
const results = (...items) => ({ results: items.map(([t, f]) => Object.assign([{ transcript: t }], { isFinal: !!f })) });
const ctx = { console, setTimeout, clearTimeout, Date, Promise, Option: function (a, b) { return { a, b }; },
  document: { getElementById: $, createElement: () => mk() },
  fetch: async u => ({ ok: true, json: async () => u === 'A' ? { 1: ['كتاب جميل بالحروف الكبيرة', 'يقرأ الطالب كل يوم'] } : { surahs: ['س'], basmala: 'باسم المعبود الواحد الاحد' } }) };
ctx.window = ctx; ctx.MUSHAF_CFG = { ayahs: 'A', meta: 'M' }; ctx.SpeechRecognition = FakeSR;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(__dirname + '/../mushaf/viewer/memorize-core.js', 'utf8'), ctx);
ctx.MemorizeCore = ctx.MemorizeCore || vm.runInContext('MemorizeCore', ctx);
vm.runInContext(fs.readFileSync(__dirname + '/../mushaf/viewer/memorize.js', 'utf8'), ctx);   // would throw here on e.g. a use-before-define bug
const wait = ms => new Promise(r => setTimeout(r, ms));
const wordsOf = cls => els['text'].children.filter(c => (c.className || '').includes(cls)).length;

(async () => {
  await wait(20);
  assert.strictEqual($('ver').textContent, 'v9');
  $('surah').value = '1'; $('from').value = '1'; $('to').value = '2'; $('start').onclick();
  assert.strictEqual(wordsOf('hid'), 8, 'all 8 words start blurred');

  // 1) the page says when the mic is REALLY on; correct words are revealed one by one; no stop for several seconds of silence
  $('mic').onclick(); assert.ok(R().running); assert.ok($('state').textContent.includes('جارٍ تشغيل'), $('state').textContent);
  await wait(50); assert.ok($('state').textContent.includes('أنصت إليك'), $('state').textContent);
  R().onresult(results(['كتاب', false])); assert.strictEqual(wordsOf('hid'), 7, 'first word revealed as soon as it is said');
  R().onresult(results(['كتاب جميل', false])); assert.strictEqual(wordsOf('hid'), 6);
  await wait(3200); assert.ok(R().running, 'still listening after 3 s of silence');

  // 2) the BROWSER ends its session: listening goes on, progress kept
  let n = recs.length; R().running = false; R().onend(); await wait(500);
  assert.strictEqual(recs.length, n + 1, 'listening restarted'); assert.ok(R().running);
  R().onresult(results(['بالحروف الكبيره', false]));
  assert.ok($('state').textContent.includes('الكلمة 4 من 8'), $('state').textContent); assert.strictEqual(wordsOf('hid'), 4);

  // 3) the restart is accepted but the mic never comes on (flaky Android): a watchdog starts it again, nothing stops
  deadNext = 1; n = recs.length; R().running = false; R().onend(); await wait(3800);
  assert.ok(recs.length >= n + 2, 'watchdog replaced the dead recognizer (' + (recs.length - n) + ')'); assert.ok(R().running);
  assert.ok($('state').textContent.includes('أنصت إليك'), $('state').textContent); assert.ok(!$('heard').textContent.includes('سبب التوقف'), 'it did not stop');

  // 4) start() throws (recognizer still busy): retried with growing delays instead of giving up
  R().onspeechstart();                                              // your voice: restart the 5 s countdown
  throwNext = 2; n = recs.length; R().running = false; R().onend(); await wait(2800);
  assert.ok(recs.length >= n + 3 && R().running, 'recovered after failed starts'); assert.ok(!$('heard').textContent.includes('سبب التوقف'));

  // 5) 5 s of silence stops it (progress kept) and the page says why
  R().onspeechstart(); await wait(5300);
  assert.strictEqual(R().running, false, 'stopped after 5 s of silence');
  assert.ok($('state').textContent.includes('توقفتَ عند الكلمة 4'), $('state').textContent); assert.ok($('heard').textContent.includes('صمت'), $('heard').textContent);

  // 6) a real recognizer error is reported with its code (not hidden behind a generic message)
  $('mic').onclick(); await wait(50); R().onerror({ error: 'network' }); await wait(80);
  assert.ok($('state').textContent.includes('network'), $('state').textContent); assert.ok($('heard').textContent.includes('network'), $('heard').textContent);

  // 7) a CLEAR mistake stops at once; correction beside the mic; the mistaken word stays blurred
  $('mic').onclick(); await wait(50); R().onresult(results(['يقرؤون الطالب', false]));
  await wait(120);
  assert.strictEqual(R().running, false, 'stopped at once on a clear mistake');
  assert.ok($('corr').innerHTML.includes('الصحيح'), 'correction in the dock: ' + $('corr').innerHTML);
  assert.ok($('state').textContent.includes('أعد من'), $('state').textContent); assert.ok($('heard').textContent.includes('خطأ'), $('heard').textContent);
  assert.strictEqual(wordsOf('hid'), 4, 'the mistaken word and what follows stay blurred');

  // 8) a lone doubtful interim word is not a mistake yet; repeating correctly finishes the passage
  $('mic').onclick(); await wait(50); R().onresult(results(['يقرؤون', false]));
  await wait(120); assert.ok(R().running, 'a lone interim word does not stop the recitation');
  R().onresult(results(['يقرأ الطالب كل يوم', true])); await wait(120);
  assert.ok($('state').textContent.includes('أتممتَ'), $('state').textContent);
  console.log('memorize page flow: all assertions passed'); process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
