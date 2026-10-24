/* Printed-Mushaf viewer.
   Each WORD is one glyph of that page's own font (qcfNNN). Real Uthmani text is kept in data-t and used for copy.
   Data comes from config.js (CFG.page / CFG.font / CFG.meta). */
(() => {
  'use strict';
  const CFG = window.MUSHAF_CFG, TOTAL = 604, root = document.documentElement;
  const $ = id => document.getElementById(id), pagesEl = $('pages');
  const params = new URLSearchParams(location.search);
  const saved = localStorage.getItem('mushaf-theme');
  const theme = params.get('theme') || saved || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  root.dataset.theme = theme;
  const toggleTheme = () => {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('mushaf-theme', root.dataset.theme); } catch (e) {}
  };

  let meta = { surahs: [], basmala: null };
  const metaReady = fetch(CFG.meta).then(r => r.ok ? r.json() : meta).then(m => { meta = m; }).catch(() => {});
  const loaded = new Set();
  let mode = 'surah', ayahKey = null;                                  // what the audio element is playing: a whole surah or one ayah
  const pad3 = n => String(n).padStart(3, '0');
  const num = n => String(n).replace(/\d/g, d => '٠١٢٣٤٥٦٧٨٩'[d]);

  // ---- build 604 fixed-ratio placeholders: scroll positions are exact before anything loads ----
  for (let p = 1; p <= TOTAL; p++) {
    const d = document.createElement('section');
    d.className = 'page loading'; d.id = 'p' + p; d.dataset.p = num(p); d.dataset.page = p;
    d.style.setProperty('--lines', p <= 2 ? 8 : 15);
    pagesEl.appendChild(d);
  }

  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) loadPage(+e.target.dataset.page); }), { rootMargin: '0px 1800px' });
  document.querySelectorAll('.page').forEach(el => io.observe(el));

  async function loadPage(p) {
    if (loaded.has(p)) return; loaded.add(p);
    const el = document.getElementById('p' + p);
    try {
      await metaReady;
      const [data] = await Promise.all([
        fetch(CFG.page(p)).then(r => { if (!r.ok) throw new Error(r.status); return r.json(); }),
        loadFont(p)
      ]);
      render(el, data);
    } catch (err) { loaded.delete(p); el.dataset.err = String(err); }
  }

  async function loadFont(p) {
    const f = new FontFace('qcf' + p, `url(${CFG.font(p)}) format("woff2")`);
    document.fonts.add(await f.load());
  }

  function render(el, data) {
    el.classList.remove('loading'); el.textContent = '';
    const p = data.page, fam = `qcf${p}`, lines = [];
    for (const item of data.layout) {
      let d;
      if (item.type === 'words') {
        d = document.createElement('div'); d.className = 'line';
        for (const w of item.words) {
          const s = document.createElement('span'); s.className = 'w'; s.textContent = w.c; s.style.fontFamily = `${fam}, serif`;
          s.dataset.t = w.e ? '' : w.t; s.dataset.k = w.k; if (ayahKey && w.k === ayahKey) s.classList.add('hl'); d.appendChild(s);
        }
        lines.push(d);
      } else if (item.type === 'banner') {
        d = document.createElement('div'); d.className = 'banner'; d.textContent = meta.surahs[item.surah - 1] ? 'سورة ' + meta.surahs[item.surah - 1] : 'سورة ' + num(item.surah);
        if (CFG.reciters) { const b = document.createElement('button'); b.className = 'bplay'; b.dataset.s = item.surah; b.textContent = '▶'; b.setAttribute('aria-label', 'تشغيل السورة'); b.onclick = () => togglePlay(item.surah); d.appendChild(b); }
      } else if (item.type === 'basmala') {
        d = document.createElement('div'); d.className = 'basmala'; d.textContent = meta.basmala || '';   // verbatim from Tanzil 1:1; empty if not provided
      } else d = document.createElement('div');
      el.appendChild(d);
    }
    el._lines = lines; fit(el, lines);
  }

  /* Pick ONE font size per page so the widest line just fits, then let space-between stretch the others.
     Short lines (end of a surah) are centred instead of stretched, like the print. */
  function fit(el, lines) {
    const W = el.clientWidth * 0.93;                            // inner width = page minus 2*3.5% padding
    el.style.setProperty('--fs', '100px');
    let widest = 1; const nat = lines.map(l => { const w = [...l.children].reduce((a, c) => a + c.getBoundingClientRect().width, 0); widest = Math.max(widest, w); return w; });
    const rowH = (el.clientHeight - 2 * 0.022 * el.clientWidth) / (el.style.getPropertyValue('--lines') || 15);   // % padding is relative to WIDTH
    const fs = Math.min(100 * (W * 0.985) / widest, rowH * 0.92);
    el.style.setProperty('--fs', fs.toFixed(2) + 'px');
    lines.forEach((l, i) => l.classList.toggle('center', nat[i] * fs / 100 < W * 0.55));
  }

  // ---- copy gives the real Uthmani words, never private-use glyph codes ----
  document.addEventListener('copy', e => {
    const sel = getSelection(); if (!sel || sel.isCollapsed) return;
    const ws = [...document.querySelectorAll('.w')].filter(s => sel.containsNode(s, true) && s.dataset.t);
    if (!ws.length) return;
    e.clipboardData.setData('text/plain', ws.map(s => s.dataset.t).join(' ')); e.preventDefault();
  });

  // ---- navigation: #p5, bottom bar, surah list, tools menu ----
  const goto = (n, smooth) => { n = Math.max(1, Math.min(TOTAL, n | 0)); $('p' + n).scrollIntoView({ inline: 'center', block: 'nearest', behavior: smooth ? 'smooth' : 'auto' }); };
  const fromHash = () => { const m = /^#p(\d+)$/.exec(location.hash); if (m) goto(+m[1]); };
  addEventListener('hashchange', fromHash);
  $('goto').addEventListener('change', e => { location.hash = 'p' + e.target.value; $('menu').hidden = true; });

  const cur = $('cur'); let curPage = 1;
  const seen = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { curPage = +e.target.dataset.page; cur.textContent = num(e.target.dataset.page); history.replaceState(null, '', '#p' + e.target.dataset.page); } }), { rootMargin: '0px -49% 0px -49%' });
  document.querySelectorAll('.page').forEach(el => seen.observe(el));

  function fillSurahs() {
    const ul = $('sl'); ul.textContent = '';
    for (let i = 0; i < 114; i++) {
      const pg = (meta.surah_pages || [])[i], li = document.createElement('li');
      li.innerHTML = `<span>${num(i + 1)}</span><span>${meta.surahs[i] || 'سورة ' + num(i + 1)}</span><span>${pg ? 'ص ' + num(pg) : ''}</span>`;
      if (pg) li.onclick = () => { $('surahDlg').close(); location.hash = 'p' + pg; };
      ul.appendChild(li);
    }
  }
  $('btnSurahs').onclick = () => { fillSurahs(); $('surahDlg').showModal(); };
  $('dlgClose').onclick = () => $('surahDlg').close();
  $('kebab').onclick = () => { $('menu').hidden = !$('menu').hidden; };
  $('mTheme').onclick = () => { toggleTheme(); $('menu').hidden = true; };
  $('mRandom').onclick = () => {
    const pgs = (meta.surah_pages || []).filter(Boolean); if (!pgs.length) return goto(1 + Math.floor(Math.random() * TOTAL));
    location.hash = 'p' + pgs[Math.floor(Math.random() * pgs.length)]; $('menu').hidden = true;
  };

  // ---- offline: service worker + "download everything" (pages + fonts) ----
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  /* Save EVERYTHING for offline use: meta + Quran text (for tasmee') + all 604 page layouts + all 604 fonts.
     Only fetches what is not cached yet, so it can be pressed again after an interruption. */
  $('mOffline').onclick = async () => {
    const prog = $('mProg');
    if (!('serviceWorker' in navigator) || !window.caches) { prog.textContent = 'المتصفح لا يدعم الحفظ دون إنترنت'; return; }
    prog.textContent = '…'; await navigator.serviceWorker.ready;               // the worker must control the page, or nothing gets stored
    try { await navigator.storage.persist(); } catch (e) {}                      // ask the browser not to evict our files
    const all = [CFG.meta, CFG.ayahs]; for (let p = 1; p <= TOTAL; p++) all.push(CFG.page(p), CFG.font(p));
    const todo = []; for (const u of all) if (!(await caches.match(u))) todo.push(u);
    const total = todo.length, failed = []; let done = 0;
    const worker = async () => { while (todo.length) { const u = todo.pop(); try { const r = await fetch(u); if (!r.ok) failed.push(u); } catch (e) { failed.push(u); } prog.textContent = `${++done}/${total}`; } };
    await Promise.all(Array.from({ length: 6 }, worker));
    let used = ''; try { const e = await navigator.storage.estimate(); used = ` (${Math.round(e.usage / 1048576)} م.ب)`; } catch (e) {}
    let have = 0; for (const u of all) if (await caches.match(u)) have++;       // verify, do not assume
    prog.textContent = have === all.length ? `✓ محفوظ بالكامل ${have}/${all.length}${used}` : `محفوظ ${have}/${all.length} — اضغط مرة أخرى${used}`;
  };
  let deferred = null;
  addEventListener('beforeinstallprompt', e => { e.preventDefault(); deferred = e; $('mInstall').hidden = false; });
  $('mInstall').onclick = async () => { if (deferred) { deferred.prompt(); deferred = null; $('mInstall').hidden = true; } };

  // ---- recitation: whole-surah audio streamed from the reciter's CDN (needs internet; nothing is copied to this site) ----
  const PC = window.PlayerCore, AC = window.AyahCore, audio = new Audio(); audio.preload = 'none';
  let reciters = [], reciter = null, playing = 0;
  const surahName = s => (meta.surahs[s - 1] ? 'سورة ' + meta.surahs[s - 1] : 'سورة ' + num(s));
  function syncButtons() { document.querySelectorAll('.bplay').forEach(b => { const on = +b.dataset.s === playing && !audio.paused; b.classList.toggle('on', on); b.textContent = on ? '⏸' : '▶'; }); $('pPlay').textContent = audio.paused ? '▶' : '⏸'; }
  function playSurah(s) {
    if (!reciter || !s) return;
    mode = 'surah'; ayahKey = null; clearHl(); playing = s; audio.src = PC.url(reciter.server, s); audio.play().catch(() => { $('pTitle').textContent = 'تعذّر التشغيل (تحتاج إنترنت)'; });
    $('player').hidden = false; $('pTitle').textContent = `${surahName(s)} — ${reciter.name}`;
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: surahName(s), artist: reciter.name, album: 'القرآن الكريم' });
      navigator.mediaSession.setActionHandler('play', () => audio.play()); navigator.mediaSession.setActionHandler('pause', () => audio.pause());
      navigator.mediaSession.setActionHandler('nexttrack', () => playSurah(PC.next(playing))); navigator.mediaSession.setActionHandler('previoustrack', () => playSurah(PC.prev(playing)));
    }
    syncButtons();
  }
  function togglePlay(s) { if (playing === s && audio.src) { audio.paused ? audio.play() : audio.pause(); } else playSurah(s); }
  audio.addEventListener('play', syncButtons); audio.addEventListener('pause', syncButtons);
  audio.addEventListener('ended', () => {
    if (mode === 'ayah') { const n = AC.next(ayahKey); if (n) playAyah(n, 1); else syncButtons(); return; }   // ayah mode: go on to the next ayah
    const n = PC.next(playing); if (n) playSurah(n); else syncButtons();
  });
  audio.addEventListener('timeupdate', () => { if (audio.duration) $('pSeek').value = Math.round(audio.currentTime / audio.duration * 1000); });
  $('pSeek').oninput = e => { if (audio.duration) audio.currentTime = e.target.value / 1000 * audio.duration; };
  $('pPlay').onclick = () => { audio.paused ? audio.play() : audio.pause(); };
  $('pNext').onclick = () => (mode === 'ayah' ? playAyah(AC.next(ayahKey), 1) : playSurah(PC.next(playing)));
  $('pPrev').onclick = () => (mode === 'ayah' ? playAyah(AC.prev(ayahKey), -1) : playSurah(PC.prev(playing)));
  $('pStop').onclick = () => { audio.pause(); audio.removeAttribute('src'); audio.load(); playing = 0; mode = 'surah'; ayahKey = null; clearHl(); $('player').hidden = true; $('player').classList.remove('live'); syncButtons(); };
  if (CFG.reciters) fetch(CFG.reciters).then(r => r.json()).then(d => {
    reciters = d.reciters; let saved = null; try { saved = localStorage.getItem('mushaf-reciter'); } catch (e) {}
    reciter = PC.pick(reciters, saved, d.default);
    const sel = $('reciter'); reciters.forEach(r => sel.add(new Option(r.name, r.id))); sel.value = reciter.id; $('recWrap').hidden = false;
    sel.onchange = () => { reciter = PC.pick(reciters, sel.value, d.default); try { localStorage.setItem('mushaf-reciter', reciter.id); } catch (e) {} if (playing) playSurah(playing); };
  }).catch(() => {});

  // ---- tap an ayah: it is recited by the chosen sheikh, its words turn red, then the next ayah follows, and so on ----
  let ayahReciter = null;
  const clearHl = () => document.querySelectorAll('.w.hl').forEach(s => s.classList.remove('hl'));
  const setHl = key => { clearHl(); document.querySelectorAll(`.w[data-k="${key}"]`).forEach(s => s.classList.add('hl')); };
  function showAyah(key, dir) {                                         // bring the ayah into view (a new page slides in when needed)
    const el = document.querySelector(`.w[data-k="${key}"]`), page = el && el.closest ? el.closest('.page') : null;
    if (page) { const p = +page.dataset.page; if (p !== curPage) goto(p, true); }
    else goto(curPage + (dir < 0 ? -1 : 1), true);                      // its page is not drawn yet: it is the neighbouring one
  }
  function playAyah(key, dir) {
    const p = key && AC ? AC.parse(key) : null; if (!p || !ayahReciter) return;
    mode = 'ayah'; ayahKey = key; playing = 0;
    audio.src = AC.url(ayahReciter, key); audio.play().catch(() => { $('pTitle').textContent = 'تعذّر التشغيل (تحتاج إنترنت)'; });
    $('player').hidden = false; $('player').classList.add('live');
    $('pTitle').textContent = `${surahName(p.s)} — آية ${num(p.a)} — ${ayahReciter.name}`;
    setHl(key); showAyah(key, dir || 1);
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: `${surahName(p.s)} — آية ${num(p.a)}`, artist: ayahReciter.name, album: 'القرآن الكريم' });
      navigator.mediaSession.setActionHandler('play', () => audio.play()); navigator.mediaSession.setActionHandler('pause', () => audio.pause());
      navigator.mediaSession.setActionHandler('nexttrack', () => playAyah(AC.next(ayahKey), 1)); navigator.mediaSession.setActionHandler('previoustrack', () => playAyah(AC.prev(ayahKey), -1));
    }
    syncButtons();
  }
  if (AC) {
    let saved = null; try { saved = localStorage.getItem('mushaf-ayah-reciter'); } catch (e) {}
    ayahReciter = AC.pick(saved);
    const sel = $('ayahReciter'); AC.RECITERS.forEach(r => sel.add(new Option(r.name, r.id))); sel.value = ayahReciter.id;
    sel.onchange = () => { ayahReciter = AC.pick(sel.value); try { localStorage.setItem('mushaf-ayah-reciter', ayahReciter.id); } catch (e) {} if (mode === 'ayah' && ayahKey) playAyah(ayahKey, 1); };
  }

  // ---- full-screen reading: the bars hide by themselves; a tap on the page shows/hides them ----
  let uiT = null;
  const uiBusy = () => !$('menu').hidden || $('surahDlg').open;
  const showUI = () => { root.classList.remove('hide-ui'); clearTimeout(uiT); uiT = setTimeout(() => { if (!uiBusy()) root.classList.add('hide-ui'); }, 3500); };
  pagesEl.addEventListener('click', e => {
    if (e.target.closest && e.target.closest('.bplay')) return;
    const sel = getSelection && getSelection(); if (sel && !sel.isCollapsed) return;       // selecting text: leave the bars alone
    if (!$('menu').hidden) { $('menu').hidden = true; return; }
    const t = e.target;                                                                      // a tap on a WORD plays its ayah; anywhere else shows/hides the bars
    if (AC && t && t.dataset && t.dataset.k && /(^|\s)w(\s|$)/.test(t.className || '')) { playAyah(t.dataset.k, 1); return; }
    root.classList.contains('hide-ui') ? showUI() : root.classList.add('hide-ui');
  });
  pagesEl.addEventListener('scroll', () => { if (!uiBusy()) root.classList.add('hide-ui'); }, { passive: true });
  showUI();

  // ---- paging like a book: swipe left->right = next page (native horizontal scroll-snap); keys and wheel for desktop ----
  const step = d => { const n = curPage + d; if (n >= 1 && n <= TOTAL) goto(n, true); };          // +1 = next page
  addEventListener('keydown', e => {
    if (e.target && /INPUT|SELECT|TEXTAREA/.test(e.target.tagName || '')) return;
    const k = e.key;
    if (k === 'ArrowLeft' || k === 'PageDown' || k === ' ') { if (e.preventDefault) e.preventDefault(); step(1); }
    else if (k === 'ArrowRight' || k === 'PageUp') { if (e.preventDefault) e.preventDefault(); step(-1); }
  });
  let wheelLock = 0;
  pagesEl.addEventListener('wheel', e => {
    const d = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : -e.deltaX;               // RTL: scrolling right goes back
    if (!d) return; e.preventDefault();
    const now = Date.now(); if (now - wheelLock < 450) return; wheelLock = now; step(d > 0 ? 1 : -1);
  }, { passive: false });

  // ---- window resized (split screen, floating window, rotation): re-fit every rendered page, stay on the same page ----
  let rz = null;
  const refitAll = () => {
    document.querySelectorAll('.page').forEach(el => { if (el._lines) fit(el, el._lines); });
    const c = document.getElementById('p' + curPage); if (c) c.scrollIntoView({ inline: 'center', block: 'nearest' });
  };
  addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(refitAll, 150); });
  if (window.visualViewport) window.visualViewport.addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(refitAll, 150); });

  metaReady.finally(fromHash);
})();
