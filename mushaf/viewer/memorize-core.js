/* Recitation matcher (تسميع). Pure logic, no DOM: works in the browser and in Node.
   expected words (known text)  vs  heard words (speech recognizer)  ->  per-expected-word status.
   status: 'ok' | 'near' (right word, ending/article blurred) | 'wrong' (+heard) | 'skipped' | 'hidden' (not reached yet)
   Normalisation MUST match scripts/common.py normalize_ar() and Arabic.kt normalizeAr(). */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(); else root.MemorizeCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const DIAC = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640\u08D3-\u08FF]/g;
  const MAP = { 'أ': 'ا', 'إ': 'ا', 'آ': 'ا', 'ٱ': 'ا', 'ى': 'ي', 'ئ': 'ي', 'ة': 'ه', 'ؤ': 'و' };
  /* The Uthmani text and a recognizer's (standard-spelling) text write many words differently:
       - a long 'aa' is only a small mark in Uthmani (ٱلْعَٰلَمِينَ) but a real alef in standard spelling (العالمين)
       - waw + small alef stands for alef (ٱلصَّلَوٰةَ = الصلاة)
       - a lone hamza (ءَامَنُوا۟) is written with madda/alef in standard spelling (آمنوا)
     so those are bridged here, and compare() ignores alef altogether. (Search normalisation in common.py/Arabic.kt is a separate thing.) */
  const norm = s => s.replace(/\u0648(?=\u0670)/g, '\u0627').replace(DIAC, '').replace(/[أإآٱىئةؤ]/g, c => MAP[c]).replace(/\u0621/g, '')
                     .replace(/[^\u0621-\u064A0-9]/g, '').trim();
  const words = s => s.split(/\s+/).map(norm).filter(Boolean);

  function lev(a, b) {
    const m = a.length, n = b.length; if (!m) return n; if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, j) => j);
    for (let i = 1; i <= m; i++) {
      const cur = [i];
      for (let j = 1; j <= n; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = cur;
    }
    return prev[n];
  }

  /* How a heard word relates to an expected one.
     exact -> 'ok'.  Same stem but a blurred article/ending (recognizers often do this) -> 'near' (accepted, flagged).
     A letter replaced/dropped/added INSIDE the word is a different word -> 'wrong'.  Never guesses leniently. */
  const skel = w => w.replace(/\u0627/g, '');                      // alef-insensitive form
  function compare(exp, heard) {
    if (exp === heard) return 'ok';
    if (skel(exp) === skel(heard)) return 'ok';                         // same word, different spelling of the long 'aa'
    const strip = w => w.replace(/^ال/, '');
    const e = skel(strip(exp)), h = skel(strip(heard));
    if (e === h) return 'near';                                         // only the article "ال" differs
    const L = Math.min(e.length, h.length);                             // only LONG words get tolerance for a recognizer slip
    if (L >= 5) {
      let p = 0; while (p < L && e[p] === h[p]) p++;
      if ((p >= L - 2 && lev(e, h) <= 2) || lev(e, h) <= 1) return 'near';   // identical except the last 1-2 letters, or one letter off
    }
    return 'wrong';
  }

  const SCORE = { ok: 3, near: 1, wrong: -1 }, GAP = -2;   // a wrong attempt must beat 'extra word + untouched expected word'

  /* Semi-global Needleman-Wunsch: ALL heard words must be placed; unreached expected words at the end stay free (hidden). */
  function align(expected, heard) {
    const n = expected.length, m = heard.length;
    const D = Array.from({ length: m + 1 }, () => new Float64Array(n + 1));
    const T = Array.from({ length: m + 1 }, () => new Uint8Array(n + 1));       // 1 diag, 2 heard-extra (up), 3 expected-skipped (left)
    for (let j = 1; j <= n; j++) { D[0][j] = GAP * j; T[0][j] = 3; }
    for (let i = 1; i <= m; i++) { D[i][0] = GAP * i; T[i][0] = 2; }
    for (let i = 1; i <= m; i++) for (let j = 1; j <= n; j++) {
      const d = D[i - 1][j - 1] + SCORE[compare(expected[j - 1], heard[i - 1])], u = D[i - 1][j] + GAP, l = D[i][j - 1] + GAP;
      if (d >= u && d >= l) { D[i][j] = d; T[i][j] = 1; } else if (u >= l) { D[i][j] = u; T[i][j] = 2; } else { D[i][j] = l; T[i][j] = 3; }
    }
    let bestJ = 0, best = -Infinity;                                              // free trailing expected words
    for (let j = 0; j <= n; j++) if (D[m][j] >= best) { best = D[m][j]; bestJ = j; }
    const out = expected.map(() => ({ status: 'hidden' }));
    let i = m, j = bestJ; const extra = [];
    while (i > 0 || j > 0) {
      const t = T[i][j];
      if (t === 1) { const c = compare(expected[j - 1], heard[i - 1]); out[j - 1] = c === 'wrong' ? { status: 'wrong', heard: heard[i - 1] } : { status: c, heard: c === 'near' ? heard[i - 1] : undefined }; i--; j--; }
      else if (t === 2) { extra.push(heard[i - 1]); i--; }
      else { out[j - 1] = { status: 'skipped' }; j--; }
    }
    return { words: out, reached: bestJ, extra: extra.reverse() };
  }

  /* Recognizer results -> one transcript. Some Android builds return CUMULATIVE results ("بسم", "بسم الله", "بسم الله الرحمن"):
     a result that extends the previous one REPLACES it instead of being appended (otherwise words repeat and matching breaks). */
  function buildTranscript(parts) {
    const key = t => words(t).join(' '), out = []; let interim = '';
    const push = t => {
      const last = out[out.length - 1];
      if (last === undefined) return out.push(t);
      const a = key(last), b = key(t);
      if (b.startsWith(a)) out[out.length - 1] = t;          // extends the previous result
      else if (a.startsWith(b)) return;                        // shorter repeat of the previous result
      else out.push(t);
    };
    for (const p of parts) { const t = (p.t || '').trim(); if (!t) continue; if (p.final) push(t); else interim = t; }
    if (interim) push(interim);
    return out.join(' ');
  }

  /* The reciter often opens with the basmala, which is NOT part of the first ayah of most surahs: ignore it (but keep it
     when the expected text itself starts with it, e.g. Al-Fatihah 1:1). Also works for a partial / cut-off basmala. */
  function stripBasmala(exp, heard, basmala) {
    if (!basmala || !basmala.length || !heard.length) return heard;
    if (basmala.every((w, i) => exp[i] !== undefined && compare(exp[i], w) !== 'wrong')) return heard;
    let p = 0;                                                       // a spelling variant of a basmala word (edit distance <= 2) still counts as the basmala
    while (p < basmala.length && p < heard.length && (compare(basmala[p], heard[p]) !== 'wrong' || lev(basmala[p], heard[p]) <= 2)) p++;
    return heard.slice(p);
  }

  function summarize(expectedWords, statuses) {
    const byAyah = new Map();
    expectedWords.forEach((w, i) => {
      const r = byAyah.get(w.ayah) || { total: 0, good: 0 }; r.total++;
      if (statuses[i].status === 'ok' || statuses[i].status === 'near') r.good++; byAyah.set(w.ayah, r);
    });
    return [...byAyah].filter(([, r]) => r.good === r.total).map(([k]) => k);
  }

  /* expectedWords: [{text, ayah}], heardText: recognizer transcript, opts.basmala: normalised basmala words (optional). */
  function track(expectedWords, heardText, opts) {
    const exp = expectedWords.map(w => norm(w.text));
    const heard = stripBasmala(exp, words(heardText), opts && opts.basmala);
    const a = align(exp, heard);
    return { words: a.words, reached: a.reached, extra: a.extra, completedAyat: summarize(expectedWords, a.words) };
  }

  /* Keeps progress across listening sessions: when the recognizer stops (silence, browser cut-off, the user taps the mic)
     call commit(); the next session continues from where the reciter stopped instead of starting over. */
  function Tracker(expectedWords, opts) {
    let cursor = 0, committed = [];
    const run = heardText => {
      const r = track(expectedWords.slice(cursor), heardText, opts);
      const wordsAll = committed.concat(r.words);
      return { words: wordsAll, reached: cursor + r.reached, extra: r.extra, completedAyat: summarize(expectedWords, wordsAll), total: expectedWords.length };
    };
    return {
      update: run,
      /* End of a listening session. Progress stops at the FIRST mistake (wrong or skipped word), so the next session
         starts right there: the reciter repeats that word and goes on. Words after the mistake are not counted. */
      commit(heardText) {
        const r = run(heardText);
        let stop = r.reached; for (let i = cursor; i < r.reached; i++) if (r.words[i].status === 'wrong' || r.words[i].status === 'skipped') { stop = i; break; }
        committed = r.words.slice(0, stop); cursor = stop;
        return { ...r, words: committed.concat(expectedWords.slice(stop).map(() => ({ status: 'hidden' }))), reached: stop, retryFrom: stop < r.reached ? stop : null,
                 completedAyat: summarize(expectedWords, committed.concat(expectedWords.slice(stop).map(() => ({ status: 'hidden' })))) };
      },
      get cursor() { return cursor; }
    };
  }

  return { norm, words, lev, compare, align, track, Tracker, stripBasmala, buildTranscript };
});
