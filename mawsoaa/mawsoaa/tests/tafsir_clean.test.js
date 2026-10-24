// The sanitizer from api/tafsir.js, tested on hostile input.
const assert = require('assert');
const ALLOWED = new Set(['p', 'br', 'b', 'strong', 'i', 'em', 'h1', 'h2', 'h3', 'h4', 'ul', 'ol', 'li', 'blockquote']);
function clean(html) {
  return String(html || '').replace(/<(script|style)[\s\S]*?<\/\1>/gi, '').replace(/<\/?([a-zA-Z0-9]+)[^>]*>/g, (m, t) => {
    t = t.toLowerCase(); if (!ALLOWED.has(t)) return ''; return t === 'br' ? '<br>' : (m.startsWith('</') ? `</${t}>` : `<${t}>`); });
}
assert.strictEqual(clean('<p class="x" onclick="evil()">نص</p>'), '<p>نص</p>');
assert.strictEqual(clean('<img src=x onerror=alert(1)>نص'), 'نص');
assert.strictEqual(clean('<script>alert(1)</script>نص'), 'نص');
assert.strictEqual(clean('<a href="javascript:alert(1)">رابط</a>'), 'رابط');
assert.strictEqual(clean('<STYLE>*{}</STYLE><b>ب</b><br/>'), '<b>ب</b><br>');
assert.ok(!/on\w+\s*=|javascript:|<script/i.test(clean('<svg onload=alert(1)><p onmouseover=x>ن</p>')));
console.log('tafsir sanitizer: all assertions passed');
