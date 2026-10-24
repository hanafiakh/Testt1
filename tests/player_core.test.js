const assert = require('assert'), P = require('../mushaf/viewer/player-core.js');
assert.strictEqual(P.url('https://cdn.example/r1/', 1), 'https://cdn.example/r1/001.mp3');
assert.strictEqual(P.url('https://cdn.example/r1', 114), 'https://cdn.example/r1/114.mp3');     // server without trailing slash
assert.throws(() => P.url('https://x/', 0)); assert.throws(() => P.url('https://x/', 115));
assert.strictEqual(P.next(113), 114); assert.strictEqual(P.next(114), null); assert.strictEqual(P.prev(1), null); assert.strictEqual(P.prev(2), 1);
const L = [{ id: 5 }, { id: 123 }, { id: 9 }];
assert.strictEqual(P.pick(L, '9', 123).id, 9); assert.strictEqual(P.pick(L, 'gone', 123).id, 123); assert.strictEqual(P.pick(L, null, 77).id, 5); assert.strictEqual(P.pick([], null, 1), null);
console.log('player-core: all assertions passed');
