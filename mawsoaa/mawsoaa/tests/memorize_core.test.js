// Synthetic placeholder words only (not Quran text).
const assert = require('assert'), M = require('../mushaf/viewer/memorize-core.js');
const E = 'كتاب جميل بالحروف الكبيرة يقرأ الطالب كل يوم'.split(' ').map((t, i) => ({ text: t, ayah: i < 4 ? 1 : 2 }));
const st = r => r.words.map(w => w.status).join(',');

let r = M.track(E, 'كتاب جميل بالحروف الكبيرة');
assert.strictEqual(st(r), 'ok,ok,ok,ok,hidden,hidden,hidden,hidden'); assert.deepStrictEqual(r.completedAyat, [1]);

r = M.track(E, 'كتاب بالحروف الكبيرة');                       // skipped a word
assert.strictEqual(st(r), 'ok,skipped,ok,ok,hidden,hidden,hidden,hidden'); assert.deepStrictEqual(r.completedAyat, []);

r = M.track(E, 'كتاب جميل بالحروف الكبيره');                   // taa marbuta/ha normalisation -> ok
assert.strictEqual(st(r), 'ok,ok,ok,ok,hidden,hidden,hidden,hidden');

r = M.track(E, 'كتاب جميل بالحروف الكبيرة يقرأ الطبيب');       // wrong ending (الطالب vs الطلاب): letter inside -> wrong
assert.strictEqual(r.words[5].status, 'wrong'); assert.strictEqual(r.words[5].heard, 'الطبيب');

r = M.track(E, 'كتاب جميل بالحروف الكبيرة يقرا الطالب');         // hamza/alef normalised
assert.strictEqual(r.words[4].status, 'ok');

r = M.track(E, 'كتاب جميل بالحروفن');                           // long word, blurred ending -> near: accepted but flagged
assert.strictEqual(r.words[2].status, 'near'); assert.strictEqual(r.words[2].heard, 'بالحروفن');
assert.strictEqual(M.track(E, 'كتاب جميلن').words[1].status, 'wrong');   // short word: any added letter is a mistake

r = M.track(E, 'كتاب جميل جميل بالحروف');                        // repeated word is extra, alignment recovers
assert.strictEqual(st(r).startsWith('ok,ok,ok'), true); assert.strictEqual(r.extra.length, 1);

assert.strictEqual(M.compare('الطيب', 'الطبي'), 'wrong');          // letters swapped inside
assert.strictEqual(M.compare('مالك', 'مالكم'), 'wrong');          // added letter is NOT forgiven
assert.strictEqual(M.compare('انعمت', 'انقمت'), 'wrong');         // replaced letter is NOT forgiven

// ---- basmala at the start is ignored (synthetic stand-in words), unless the expected text itself starts with it ----
const B = M.words('باسم المعبود الواحد الاحد');
const E2 = 'الم ذلك كتاب'.split(' ').map(t => ({ text: t, ayah: 1 }));
r = M.track(E2, 'باسم المعبود الواحد الاحد الم ذلك', { basmala: B });
assert.strictEqual(st(r), 'ok,ok,hidden');                          // basmala not counted as mistakes
r = M.track(E2, 'باسم المعبود', { basmala: B });
assert.strictEqual(st(r), 'hidden,hidden,hidden');                  // said only (part of) the basmala and stopped -> nothing revealed, nothing wrong
const E3 = 'باسم المعبود الواحد الاحد ثم كلمة'.split(' ').map(t => ({ text: t, ayah: 1 }));
r = M.track(E3, 'باسم المعبود الواحد الاحد', { basmala: B });
assert.strictEqual(st(r), 'ok,ok,ok,ok,hidden,hidden');             // expected itself begins with the basmala -> kept

// ---- progress survives stop/start (silence, browser cut-off) ----
const T = M.Tracker(E, {});
T.update('كتاب جميل');                                              // live (interim) view
let u = T.commit('كتاب جميل');                                      // session 1 ended
assert.strictEqual(T.cursor, 2);
u = T.update('بالحروف الكبيرة');                                    // session 2 starts fresh but continues at word 3
assert.strictEqual(u.words.slice(0, 4).map(w => w.status).join(','), 'ok,ok,ok,ok');
assert.deepStrictEqual(u.completedAyat, [1]);
T.commit('بالحروف الكبيرة');
u = T.update('');                                                   // silence: nothing new -> nothing changes, nothing lost
assert.strictEqual(u.reached, 4); assert.strictEqual(u.words[3].status, 'ok');

// ---- a mistake stops progress AT the mistake; repeating that word fixes it and the recitation goes on ----
const T2 = M.Tracker(E, {});
let c = T2.commit('كتاب جميلن بالحروف');                               // 'جميلن' is a wrong ending of a short word -> mistake at word 2
assert.strictEqual(T2.cursor, 1); assert.strictEqual(c.retryFrom, 1);
assert.strictEqual(c.words[1].status, 'hidden');                       // the mistaken word and everything after it are NOT revealed
let live = T2.update('جميلن');                                         // while listening it is shown as wrong, with what was heard
assert.strictEqual(live.words[1].status, 'wrong'); assert.strictEqual(live.words[1].heard, 'جميلن');
live = T2.update('جميل بالحروف الكبيرة');                              // reciter repeats from the mistaken word
assert.strictEqual(live.words.slice(0, 4).map(w => w.status).join(','), 'ok,ok,ok,ok'); assert.deepStrictEqual(live.completedAyat, [1]);
T2.commit('جميل بالحروف الكبيرة'); assert.strictEqual(T2.cursor, 4);

// ---- recognizer result handling (cumulative / repeated results must not duplicate words) ----
const BT = M.buildTranscript;
assert.strictEqual(BT([{ t: 'كتاب', final: true }, { t: 'كتاب جميل', final: true }, { t: 'كتاب جميل بالحروف', final: true }]), 'كتاب جميل بالحروف');   // cumulative
assert.strictEqual(BT([{ t: 'كتاب جميل', final: true }, { t: 'كتاب جميل', final: true }]), 'كتاب جميل');                                         // exact repeat
assert.strictEqual(BT([{ t: 'كتاب جميل', final: true }, { t: 'بالحروف الكبيرة', final: true }]), 'كتاب جميل بالحروف الكبيرة');                  // genuinely new phrase is appended
assert.strictEqual(BT([{ t: 'كتاب جميل', final: true }, { t: 'كتاب جميل بالحروف', final: false }]), 'كتاب جميل بالحروف');                      // interim extends the final
assert.strictEqual(BT([{ t: ' ', final: true }]), '');
r = M.track(E, BT([{ t: 'كتاب', final: true }, { t: 'كتاب جميل', final: true }]));
assert.strictEqual(st(r), 'ok,ok,hidden,hidden,hidden,hidden,hidden,hidden');                    // no phantom progress from repeated words

// ---- a spelling variant of a basmala word (recognizer wording) is still the basmala, not a mistake ----
r = M.track(E2, 'باسم المعبود الواحد الاحدد الم ذلك', { basmala: B });                // 'الاحدد' is 1 letter away from 'الاحد'
assert.strictEqual(st(r), 'ok,ok,hidden');
r = M.track(E2, 'باسم المعبود الواحد', { basmala: B });                               // cut short: nothing revealed, nothing wrong
assert.strictEqual(st(r), 'hidden,hidden,hidden');
r = M.track(E2, 'باسم المعبد الواحد الاحد ذلك', { basmala: B });                      // a different word replacing one basmala word, then recitation
assert.strictEqual(r.words[0].status === 'wrong' || r.words[0].status === 'skipped' || r.words[0].status === 'hidden', true);

// ---- spelling bridge: the Uthmani text vs the standard spelling a speech recognizer writes (the 'العالمين' bug) ----
const same = (u, i) => M.compare(M.norm(u), M.norm(i));
for (const [u, i] of [['ٱلْعَٰلَمِينَ', 'العالمين'], ['ٱلْعَـٰلَمِينَ', 'العالمين'], ['ٱلْكِتَٰبُ', 'الكتاب'], ['ٱلصَّلَوٰةَ', 'الصلاة'], ['ٱلزَّكَوٰةَ', 'الزكاة'],
                      ['ءَامَنُوا۟', 'آمنوا'], ['ٱلسَّمَٰوَٰتِ', 'السماوات'], ['ٱلرَّحْمَٰنِ', 'الرحمن'], ['لَهُۥ', 'له']])
  assert.strictEqual(same(u, i), 'ok', `${u} vs ${i}`);
assert.strictEqual(same('ٱلْحَمْدُ', 'الحمد لله'), 'wrong');                                  // different words are still different
assert.strictEqual(same('كَفَرُوا۟', 'كتبوا'), 'wrong'); assert.strictEqual(same('نَعْبُدُ', 'نستعين'), 'wrong');
// a whole ayah-like sequence read correctly in standard spelling completes with no mistake and no stop
const EU = ['ٱلْحَمْدُ', 'لِلَّهِ', 'رَبِّ', 'ٱلْعَٰلَمِينَ'].map(t => ({ text: t, ayah: 2 }));
r = M.track(EU, 'الحمد لله رب العالمين');
assert.strictEqual(r.words.map(w => w.status).join(','), 'ok,ok,ok,ok'); assert.deepStrictEqual(r.completedAyat, [2]);
const T3 = M.Tracker(EU, {}); T3.update('الحمد لله رب العالمين'); const done = T3.commit('الحمد لله رب العالمين');
assert.strictEqual(done.reached, 4); assert.strictEqual(done.retryFrom, null);                 // no mistake -> progress is NOT rolled back
console.log('memorize-core: all assertions passed');
