import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const script = readFileSync(new URL('../public/theme-init.js', import.meta.url), 'utf8');

test('theme initializer is a same-origin blocking external script in head', () => {
  const head = html.match(/<head>([\s\S]*?)<\/head>/)?.[1];
  assert.ok(head);
  assert.match(head, /<script src="\/theme-init\.js"><\/script>/);
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
  for (const [, attrs, body] of scripts) {
    assert.match(attrs, /\bsrc="\/[^"]+"/);
    assert.equal(body.trim(), '', 'inline script is forbidden');
  }
});

test('prepaint theme preserves preference, defaults and storage-failure behavior', () => {
  for (const [value, expected] of [['dark', 'dark'], ['light', 'light'], [null, 'light'], ['unexpected', 'light']]) {
    const document = { documentElement: { dataset: {} } };
    runInNewContext(script, { document, localStorage: { getItem: key => {
      assert.equal(key, 'apv-theme');
      return value;
    } } });
    assert.equal(document.documentElement.dataset.theme, expected);
  }
  const document = { documentElement: { dataset: {} } };
  runInNewContext(script, { document, localStorage: { getItem() { throw new Error('storage denied'); } } });
  assert.equal(document.documentElement.dataset.theme, 'light');
});
