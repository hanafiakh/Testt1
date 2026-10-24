const assert = require('assert'), A = require('../mushaf/viewer/ayah-core.js');
assert.strictEqual(A.COUNTS.length, 114); assert.strictEqual(A.COUNTS.reduce((a, b) => a + b, 0), 6236);
const alafasy = A.pick(7); assert.strictEqual(A.url(alafasy, '2:255'), 'https://verses.quran.com/Alafasy/mp3/002255.mp3');
assert.strictEqual(A.url(A.pick(6), '114:6'), 'https://mirrors.quranicaudio.com/everyayah/Husary_64kbps/114006.mp3');
assert.strictEqual(A.url(A.pick(2), '9:1'), 'https://verses.quran.com/AbdulBaset/Murattal/mp3/009001.mp3');
assert.strictEqual(A.next('1:7'), '2:1');   assert.strictEqual(A.next('2:1'), '2:2');   assert.strictEqual(A.next('114:6'), null);
assert.strictEqual(A.prev('2:1'), '1:7');   assert.strictEqual(A.prev('2:2'), '2:1');   assert.strictEqual(A.prev('1:1'), null);
assert.strictEqual(A.parse('1:8'), null); assert.strictEqual(A.parse('115:1'), null); assert.strictEqual(A.parse('x'), null); assert.strictEqual(A.parse('0:1'), null);
assert.throws(() => A.url(alafasy, '2:287'));
assert.strictEqual(A.pick('nope').id, 7);                                  // unknown saved id -> default (Alafasy)
assert.strictEqual(A.RECITERS.length, 12); assert.ok(A.RECITERS.every(r => /^https:\/\/.+\/$/.test(r.base)));
assert.strictEqual(new Set(A.RECITERS.map(r => r.id)).size, 12);
// walking forward from the first ayah visits all 6236 ayat exactly once and ends at 114:6
let k = '1:1', n = 1; while (A.next(k)) { k = A.next(k); n++; } assert.strictEqual(n, 6236); assert.strictEqual(k, '114:6');
console.log('ayah-core: all assertions passed');
