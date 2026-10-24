/* Offline support WITHOUT stale code:
   - app files (html/js/css): NETWORK FIRST, cache only as the offline fallback  -> updates reach you immediately when online
   - Quran data (pages, fonts, text, meta, tafsir already opened): CACHE FIRST    -> instant and works offline
   - Audio is streamed from the reciter's CDN and is NOT handled here. */
const SHELL = 'shell-v13', DATA = 'data-v13';
const FILES = ['./', 'index.html', 'memorize.html', 'tafsir.html', 'tafsir.js', 'mushaf.css', 'config.js', 'player-core.js', 'ayah-core.js', 'mushaf.js', 'memorize.js', 'memorize-core.js', 'manifest.webmanifest', 'api/meta', 'api/icon/192'];
self.addEventListener('install', e => e.waitUntil(caches.open(SHELL).then(c => Promise.all(FILES.map(f => c.add(f).catch(() => {})))).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== SHELL && k !== DATA).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => {
  const req = e.request; const u = new URL(req.url);
  if (req.method !== 'GET' || (u.origin !== location.origin && u.hostname !== 'verses.quran.com')) return;   // fonts may come from quran.com (CORS *)
  const isData = /\/(pages|fonts)\/|\/api\/(page|ayahs|meta|tafsir|reciters)|ayahs\.json$|meta\.json$|\.woff2$/.test(req.url);
  const store = (res) => { if (res && res.ok) { const copy = res.clone(); caches.open(isData ? DATA : SHELL).then(c => c.put(req, copy)); } return res; };
  if (isData) e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(store)));
  else e.respondWith(fetch(req).then(store).catch(() => caches.match(req)));
});
