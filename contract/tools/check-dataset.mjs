import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { caseDetail, caseDonors, caseLedger, caseUpdates, financials, forbiddenMarkers, listCases, loadContract, newAjv, projectPublic, readJson, root, sameValue, site, stats, validateIntegrity } from './lib.mjs';

const fixture = readJson('fixtures/dataset.json');
const projection = readJson('fixtures/dataset.public.json');
const golden = readJson('fixtures/golden/acceptance.json');
const ajv = newAjv();
for (const [file, value] of [['fixture.schema.json', fixture], ['fixture.public.schema.json', projection]]) {
  const validate = ajv.compile(readJson('fixtures/' + file));
  assert.ok(validate(value), JSON.stringify(validate.errors));
}
assert.deepEqual(validateIntegrity(fixture), []);
assert.ok(sameValue(projection, projectPublic(fixture)), 'public projection drift');
assert.deepEqual(stats(projection), stats(fixture));
const markers = forbiddenMarkers(fixture);
const noLeaks = value => {
  const json = JSON.stringify(value);
  for (const marker of markers) assert.ok(!json.includes(marker), 'private marker leaked: ' + marker);
};
noLeaks(projection);
const files = new Set();
for (const c of projection.cases) {
  if (c.cover) files.add(c.cover.file);
  for (const photo of c.photos) files.add(photo.file);
}
for (const e of projection.ledger) if (e.donor?.avatarFile) files.add(e.donor.avatarFile);
for (const u of projection.updates) {
  if (u.authorAvatarFile) files.add(u.authorAvatarFile);
  if (u.photo) files.add(u.photo.file);
}
for (const file of files) assert.ok(existsSync(path.join(root, 'fixtures', file)), 'missing fixture asset: ' + file);
const { checkNamed } = loadContract();
let count = 0;
const check = (schema, value) => {
  assert.deepEqual(checkNamed(schema, value), [], schema);
  noLeaks(value);
  count++;
};
check('Site', site(fixture));
check('Stats', stats(fixture));
for (const locale of ['en','fr']) {
  for (const status of ['active','completed']) for (const sort of ['newest','deadline','closest','least','featured']) {
    const first = listCases(fixture, { locale,status,sort });
    for (let page = 1; page <= first.totalPages; page++) check('CasePage', listCases(fixture, { locale,status,sort,page }));
  }
  for (const c of fixture.cases) {
    const detail = caseDetail(fixture, c.code, locale);
    if (c.status === 'draft') { assert.equal(detail,null); continue; }
    check('CaseDetail',detail);
    check('CaseUpdatePage',caseUpdates(fixture,c.code,locale));
    check('PublicDonorPage',caseDonors(fixture,c.code));
    check('LedgerEntryPage',caseLedger(fixture,c.code,locale));
  }
}
for (const {query,codes,itemCount,totalItems,totalPages} of golden.directory) {
  for (const locale of ['en','fr']) {
    const result = listCases(fixture,{...query,locale});
    if (codes) assert.deepEqual(result.items.map(c=>c.code),codes);
    if (itemCount !== undefined) assert.equal(result.items.length,itemCount);
    if (totalItems !== undefined) assert.equal(result.totalItems,totalItems);
    if (totalPages !== undefined) assert.equal(result.totalPages,totalPages);
  }
}
for (const [code, expected] of Object.entries(golden.cases)) {
  const detail = caseDetail(fixture, code);
  assert.deepEqual({received:detail.case.received,delivered:detail.case.delivered,available:detail.financials.available},expected);
}
for (const code of golden.missing) assert.equal(caseDetail(fixture,code),null);
const balances = [...financials(fixture).values()];
assert.equal(balances.reduce((s,b)=>s+b.allocated,0),stats(fixture).received);
assert.equal(balances.reduce((s,b)=>s+b.delivered,0),stats(fixture).delivered);
assert.equal(balances.reduce((s,b)=>s+b.transfersIn-b.transfersOut,0),0);
console.log('full dataset: ok (' + fixture.cases.length + ' cases, ' + count + ' schema-valid responses, golden assertions and media checks)');
