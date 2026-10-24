/* Tasmee' page. Blurred text -> speak -> correct words are revealed; corrections are written NEXT TO THE MIC (never between ayat).
   - Stops by itself after SILENCE_MS without speech, keeps your progress, and continues from there when you tap the mic again.
   - A mistake stops progress AT that word: the correction appears beside the mic and you repeat from that word.
   - A leading basmala is ignored (it is not part of the first ayah of most surahs).
   Speech engine: the browser's SpeechRecognition (Chrome needs internet). Without it, tap a blurred word to reveal it. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id), CFG = window.MUSHAF_CFG, Core = window.MemorizeCore;
  const textEl = $('text'), stateEl = $('state'), corrEl = $('corr'), heardEl = $('heard'), mic = $('mic');
  const VERSION = 'v9', SILENCE_MS = 5000, FIRST_WORD_MS = 8000;
  if ($('ver')) $('ver').textContent = VERSION;
  let ayahs = null, basmala = [], expected = [], tracker = null;
  let listening = false, stopping = null, rec = null, sessionText = '', lastResultAt = 0, silenceT = null, safetyT = null;

  Promise.all([fetch(CFG.ayahs).then(r => r.json()), fetch(CFG.meta).then(r => r.ok ? r.json() : {}).catch(() => ({}))]).then(([a, m]) => {
    ayahs = a; basmala = Core.words(m.basmala || '');
    for (let s = 1; s <= 114; s++) $('surah').add(new Option(`${s} — ${(m.surahs || [])[s - 1] || 'سورة ' + s}`, s));
  }).catch(() => setState('تعذّر تحميل نص القرآن'));

  const setState = t => { stateEl.textContent = t; };

  $('start').onclick = () => {
    if (!ayahs) return;
    if (listening) finish('manual');
    const s = +$('surah').value, list = ayahs[s], from = Math.max(1, +$('from').value), to = Math.min(list.length, +$('to').value || from);
    expected = [];
    for (let a = from; a <= to; a++) list[a - 1].split(/\s+/).filter(Boolean).forEach(w => expected.push({ text: w, ayah: a }));
    tracker = Core.Tracker(expected, { basmala }); sessionText = ''; heardEl.textContent = '';
    show(tracker.update('')); setState('اضغط الميكروفون وابدأ (يمكنك البدء بالبسملة).');
  };

  // ---- text: only colours on the words themselves. No captions between the ayat. ----
  function show(res) {
    textEl.textContent = ''; let last = 0;
    const mark = a => { const n = document.createElement('span'); n.className = 'num'; n.textContent = '﴿' + a + '﴾'; textEl.append(n); };
    expected.forEach((w, i) => {
      if (w.ayah !== last && last) mark(last);
      last = w.ayah;
      const st = res.words[i] || { status: 'hidden' }, el = document.createElement('span');
      const err = st.status === 'wrong' || st.status === 'skipped';
      el.className = 'mw ' + (st.status === 'hidden' ? 'hid' : err ? 'hid err' : st.status); el.textContent = w.text;   // mistakes stay blurred: only the dock shows the correction
      if (st.status === 'hidden') el.onclick = () => el.classList.remove('hid');        // practice board: tap to reveal
      textEl.append(el);
    });
    if (expected.length) mark(last);
    // ---- corrections live in the dock, beside the mic (newest first) ----
    corrEl.textContent = '';
    const items = [];
    res.words.forEach((w, i) => {
      if (w.status === 'wrong') items.push(`قلتَ: <b>${w.heard}</b> ← الصحيح: <b>${expected[i].text}</b>`);
      else if (w.status === 'skipped') items.push(`تركتَ: <b>${expected[i].text}</b>`);
      else if (w.status === 'near') items.push(`راجع نطق: <b>${expected[i].text}</b>`);
    });
    items.reverse().forEach(h => { const d = document.createElement('div'); d.innerHTML = h; corrEl.append(d); });
  }

  function progressLine(r) {
    const bad = r.words.filter(w => w.status === 'wrong' || w.status === 'skipped').length;
    if (r.reached >= r.total && !bad) return '✅ أتممتَ المقطع بلا أخطاء';
    return `الكلمة ${r.reached} من ${r.total}` + (bad ? ` • ${bad} ${bad === 1 ? 'خطأ' : 'أخطاء'}` : '');
  }

  // ---- listening ----
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const REASON = { silence: 'صمت 5 ثوانٍ', mistake: 'خطأ في كلمة', done: 'اكتمل المقطع', manual: 'ضغطتَ الميكروفون', error: 'مشكلة في الميكروفون' };
  let lastError = '', started = false, prevStarted = true, noStart = 0, startT = null;

  function armSilence(ms) {
    clearTimeout(silenceT);
    silenceT = setTimeout(() => finish('silence'), ms);
  }

  // the first wrong/skipped word of the live view, and whether it is a CLEAR mistake (not just an unfinished interim guess)
  function firstMistake(live) {
    for (let i = tracker.cursor; i < live.reached; i++) { const w = live.words[i]; if (w.status === 'wrong' || w.status === 'skipped') return { i, w }; }
    return null;
  }
  function isClear(live, m, lastResultIsFinal) {
    const later = live.reached - (m.i + 1);                          // words heard AFTER the mistaken one
    return lastResultIsFinal || (m.w.status === 'wrong' ? later >= 1 : later >= 2);
  }

  /* (Re)start listening. Starting can fail or silently do nothing (typical on Android when it is retried too fast):
     retry with growing delays instead of giving up, and never claim "listening" unless the browser reports the mic is on. */
  function restartLater(delay, attempt) {
    setTimeout(() => {
      if (!listening || stopping) return;
      try { begin(); }
      catch (e) { if (attempt < 4) restartLater(350 * (attempt + 2), attempt + 1); else { lastError = (e && e.name) || 'start'; settle('error'); } }
    }, delay);
  }

  function begin() {
    noStart = prevStarted ? 0 : noStart + 1;                          // sessions in a row that never actually started
    if (noStart >= 5) { lastError = 'no-audio'; return settle('error'); }
    rec = new SR(); rec.lang = 'ar-SA'; rec.continuous = true; rec.interimResults = true;
    const me = rec, live = f => (...a) => { if (me === rec) f(...a); };   // ignore late events from a recognizer we already replaced
    started = false; prevStarted = false;
    rec.onstart = rec.onaudiostart = live(() => {
      if (started) return; started = true; prevStarted = true; clearTimeout(startT);
      if (listening && !stopping) setState(tracker.cursor ? `أنصت إليك… (من الكلمة ${tracker.cursor + 1})` : 'أنصت إليك… ابدأ القراءة');
    });
    rec.onspeechstart = live(() => { if (listening && !stopping) armSilence(SILENCE_MS); });   // your voice was detected: restart the 5 s countdown
    rec.onresult = live(e => {
      started = true; prevStarted = true; clearTimeout(startT);
      const parts = []; for (let i = 0; i < e.results.length; i++) parts.push({ t: e.results[i][0].transcript, final: e.results[i].isFinal });
      sessionText = Core.buildTranscript(parts);                     // handles repeated / cumulative results
      heardEl.textContent = 'سمعتُ: ' + sessionText;                     // exactly what the recognizer returned (to tell recognizer errors from matching errors)
      lastResultAt = Date.now();
      const r = tracker.update(sessionText); show(r); setState(progressLine(r));
      if (r.reached >= r.total && !r.words.some(w => w.status === 'wrong' || w.status === 'skipped')) return finish('done');
      const m = firstMistake(r);                                      // a CLEAR mistake stops the recitation at once and shows the correction
      if (m && isClear(r, m, e.results[e.results.length - 1].isFinal)) return finish('mistake');
      if (listening && !stopping) armSilence(SILENCE_MS);             // otherwise keep listening: it takes 5 s of silence to stop
    });
    rec.onerror = live(e => {
      if (e.error === 'no-speech' || e.error === 'aborted') return;   // harmless: onend follows and we listen again
      lastError = e.error; finish('error');
    });
    rec.onend = live(() => {
      clearTimeout(startT);
      if (stopping) return settle();
      if (!listening) return;
      // The BROWSER ended the session by itself (it does that every so often). That is not a reason to stop:
      // unless there is a mistake, keep the progress and listen again. Only 5 s of silence or a clear mistake stops us.
      const lv = tracker.update(sessionText), m = firstMistake(lv);
      if (m) return settle('mistake');
      tracker.commit(sessionText); sessionText = ''; rec = null;
      restartLater(350, 0);
    });
    rec.start();
    clearTimeout(startT);
    startT = setTimeout(() => {                                      // watchdog: "start" was accepted but the mic never came on
      if (listening && !stopping && !started && me === rec) { const old = rec; rec = null; try { old.abort(); } catch (_) {} restartLater(300, 0); }
    }, 2500);
  }

  /* finish(): ask the recognizer to stop (it may still deliver the last words), then settle() commits progress. */
  function finish(reason) {
    if (!listening || stopping) return;
    stopping = reason; clearTimeout(silenceT); mic.classList.remove('on');
    if (!rec) return settle();                                       // between two sessions: nothing to wait for
    try { rec.stop(); } catch (_) {}
    safetyT = setTimeout(settle, 1500);                              // in case onend never fires
  }

  function settle(reasonOverride) {
    if (!listening) return;
    clearTimeout(safetyT); clearTimeout(silenceT); clearTimeout(startT);
    const reason = reasonOverride || stopping || 'manual';
    listening = false; stopping = null; mic.classList.remove('on'); rec = null;
    // find the first mistake of this session BEFORE committing (commit rolls progress back to it)
    const live = tracker.update(sessionText); let mistake = null;
    for (let i = tracker.cursor; i < live.reached; i++) { const w = live.words[i]; if (w.status === 'wrong' || w.status === 'skipped') { mistake = { i, w }; break; } }
    const r = tracker.commit(sessionText); sessionText = ''; show(r);
    heardEl.textContent = 'سبب التوقف: ' + (REASON[reason] || reason) + (reason === 'error' && lastError ? ` (${lastError})` : '');
    if (mistake) {                                                   // correction beside the mic; the mistaken word stays hidden until repeated correctly
      const want = expected[mistake.i].text;
      corrEl.innerHTML = mistake.w.status === 'wrong' ? `قلتَ: <b>${mistake.w.heard}</b> ← الصحيح: <b>${want}</b>` : `تركتَ: <b>${want}</b>`;
      return setState(`فيه خطأ — أعد من «${want}»`);
    }
    if (reason === 'done') return setState(progressLine(r));
    if (reason === 'error') return setState(`تعذّر الاستماع${lastError ? ' (' + lastError + ')' : ''} — اضغط الميكروفون للمتابعة`);
    setState(reason === 'silence' ? `توقفتَ عند الكلمة ${r.reached} من ${r.total} — اضغط الميكروفون للمتابعة`
                                   : `${progressLine(r)} — اضغط الميكروفون للمتابعة`);
  }

  mic.onclick = () => {
    if (!tracker) return setState('اختر السورة والآيات ثم «ابدأ».');
    if (!SR) return setState('متصفحك لا يدعم التعرّف الصوتي. اضغط على الكلمة المضببة لكشفها.');
    if (listening) return finish('manual');
    if (tracker.cursor >= expected.length) return setState('أتممتَ المقطع. اضغط «ابدأ» للإعادة.');
    sessionText = ''; lastResultAt = 0; listening = true; stopping = null; mic.classList.add('on');
    lastError = ''; prevStarted = true; noStart = 0;
    corrEl.textContent = ''; heardEl.textContent = '';
    setState('جارٍ تشغيل الميكروفون… انتظر حتى تظهر «أنصت إليك»');   // do not speak before the browser says the mic is really on
    armSilence(FIRST_WORD_MS);
    try { begin(); } catch (_) { restartLater(300, 0); }
  };
})();
