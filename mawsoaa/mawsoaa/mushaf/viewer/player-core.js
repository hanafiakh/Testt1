/* Player logic without DOM: URL building and queue rules. Works in browser and Node. */
(function (root, factory) { if (typeof module === 'object' && module.exports) module.exports = factory(); else root.PlayerCore = factory(); })(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const pad3 = n => String(n).padStart(3, '0');
  const url = (server, surah) => { if (!(surah >= 1 && surah <= 114)) throw new Error('surah 1..114'); return server.replace(/\/?$/, '/') + pad3(surah) + '.mp3'; };
  const next = s => (s >= 114 ? null : s + 1);          // after the last surah: stop
  const prev = s => (s <= 1 ? null : s - 1);
  const pick = (list, saved, def) => list.find(r => String(r.id) === String(saved)) || list.find(r => r.id === def) || list[0] || null;
  return { url, next, prev, pick, pad3 };
});
