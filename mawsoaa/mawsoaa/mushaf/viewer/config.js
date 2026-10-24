/* Where the viewer gets its data. Default = static files built by scripts/build_mushaf_pages.py. */
window.MUSHAF_CFG = {
  page: p => `pages/${String(p).padStart(3, '0')}.json`,
  font: p => `fonts/p${p}.woff2`,
  meta: 'meta.json',
  ayahs: 'ayahs.json',
  tafsir: null,           // static build has no tafsir service
  reciters: null          // static build has no reciter list -> no play buttons
};
