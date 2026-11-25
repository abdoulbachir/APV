// Artifact-level smoke check; no fixture generation is required to inspect a real build.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';

const mock = process.argv.includes('--mock');
function walk(dir) {
  return readdirSync(dir).flatMap(name => {
    const file = path.join(dir, name);
    return statSync(file).isDirectory() ? walk(file) : [file];
  });
}
const files = walk('dist');
assert.ok(files.includes(path.join('dist', 'index.html')), 'missing built index');
assert.ok(files.some(file => /\.js$/.test(file)), 'missing built JavaScript');
const markers = [/HELP-\d{3}\b/, /PRIVATE-REALNAME-/, /PRIVATE-CONTACT-/, /DRAFT-NAME-/, /Sample household/, /Mariam D\./, /Nadia K\./, /MockHopeWindowArtwork0001/];
let fixtureFound = false;
for (const file of files) {
  assert.ok(!/dataset(?:\.public)?\.json$/.test(file), 'raw dataset published: ' + file);
  if (!/\.(?:js|mjs|html|json|map|css|txt)$/i.test(file)) continue;
  const content = readFileSync(file, 'utf8');
  if (mock) {
    fixtureFound ||= content.includes('HELP-014') && content.includes('Sample household');
    assert.ok(!/PRIVATE-REALNAME-|PRIVATE-CONTACT-|DRAFT-NAME-/.test(content), 'private fixture leaked: ' + file);
  } else {
    for (const marker of markers) assert.ok(!marker.test(content), 'fictional fixture marker ' + marker + ' in ' + file);
  }
}
if (mock) assert.ok(fixtureFound, 'mock build must contain the fictional public dataset');
console.log(mock ? 'Explicit mock artifact contains public fictional cases only.' : 'Real artifact contains no fictional fixture markers.');
