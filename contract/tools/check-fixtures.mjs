// Checks the shared fixtures: format, cross-record integrity, financial reconciliation,
// expected values, the public projection, agreement with the examples in openapi.yaml, and
// that deliberately broken fixtures are rejected.

import {
  applyPatch, caseDetail, caseDonors, caseLedger, caseUpdates, expectedValues, financials, forbiddenMarkers,
  listCases, loadContract, newAjv, projectPublic, readJson, readText, sameValue, site, stable, stats, validateIntegrity,
} from './lib.mjs';

const failures = [];
const fail = message => failures.push(message);

const ajv = newAjv();
const validateFull = ajv.compile(readJson('fixtures/fixture.schema.json'));
const validatePublic = ajv.compile(readJson('fixtures/fixture.public.schema.json'));
const schemaErrors = (validate, value) => (validate(value) ? [] : validate.errors.map(e => ({ code: 'schema', message: `${e.instancePath || '/'} ${e.message}` })));

/** Format first, then the cross-record checks. */
const validateFixture = fixture => {
  const errors = schemaErrors(validateFull, fixture);
  return errors.length > 0 ? errors : validateIntegrity(fixture);
};

/** The public projection: format, then a search for anything private. */
const validateProjection = (projection, markers) => {
  const errors = schemaErrors(validatePublic, projection);
  const text = JSON.stringify(projection);
  for (const marker of markers) if (text.includes(marker)) errors.push({ code: 'marker', message: `contains private marker ${marker}` });
  return errors;
};

// 1. The example is valid.
const fixture = readJson('fixtures/example.json');
for (const error of validateFixture(fixture)) fail(`example.json [${error.code}] ${error.message}`);

// 2. Reconciliation invariants of financial-terms.md.
const totals = financials(fixture);
const platform = stats(fixture);
const sum = pick => [...totals.values()].reduce((total, f) => total + pick(f), 0);
if (sum(f => f.allocated) !== platform.received) fail('sum of Case.received differs from Stats.received');
if (sum(f => f.delivered) !== platform.delivered) fail('sum of Case.delivered differs from Stats.delivered');
if (sum(f => f.transfersIn) !== sum(f => f.transfersOut)) fail('transfers in and out differ across the platform');

// 3. Expected values match what the rules compute.
const expected = readJson('fixtures/example.expected.json');
const { description: _description, ...committed } = expected;
const computed = expectedValues(fixture, Object.keys(expected.directory));
for (const key of Object.keys({ ...committed, ...computed })) {
  if (!sameValue(committed[key], computed[key])) fail(`example.expected.json "${key}" differs from the computed value:\n${stable(computed[key])}`);
}

// 4. The public projection is up to date, valid, and free of private data.
const markers = forbiddenMarkers(fixture);
const projection = projectPublic(fixture);
if (!sameValue(JSON.parse(readText('fixtures/example.public.json')), projection)) fail('example.public.json is out of date: run "npm run project"');
for (const error of validateProjection(projection, markers)) fail(`example.public.json [${error.code}] ${error.message}`);
const projectedTotals = financials(projection);
for (const [code, f] of projectedTotals) {
  if (!sameValue(f, totals.get(code))) fail(`projection changes the totals of ${code}`);
}

// 5. Every response the rules produce is valid against openapi.yaml, in both languages, and
//    contains nothing private.
const { checkNamed, exampleOf } = loadContract();
let responses = 0;
function checkResponse(schemaName, value, where) {
  responses += 1;
  for (const error of checkNamed(schemaName, value)) fail(`${where}: ${error}`);
  const text = JSON.stringify(value);
  for (const marker of markers) if (text.includes(marker)) fail(`${where}: leaks private marker ${marker}`);
}
checkResponse('Site', site(fixture), 'GET /site');
checkResponse('Stats', platform, 'GET /stats');
for (const locale of ['en', 'fr']) {
  for (const status of ['active', 'completed']) {
    for (const sort of ['newest', 'deadline', 'closest', 'least', 'featured']) {
      checkResponse('CasePage', listCases(fixture, { status, sort, locale }), `GET /cases?status=${status}&sort=${sort} (${locale})`);
    }
  }
  for (const c of fixture.cases) {
    const detail = caseDetail(fixture, c.code, locale);
    if (c.status === 'draft') {
      if (detail !== null) fail(`draft ${c.code} is readable`);
      continue;
    }
    checkResponse('CaseDetail', detail, `GET /cases/${c.code} (${locale})`);
    checkResponse('CaseUpdatePage', caseUpdates(fixture, c.code, locale), `GET /cases/${c.code}/updates (${locale})`);
    checkResponse('PublicDonorPage', caseDonors(fixture, c.code), `GET /cases/${c.code}/donors`);
    checkResponse('LedgerEntryPage', caseLedger(fixture, c.code, locale), `GET /cases/${c.code}/ledger (${locale})`);
  }
}

// 6. The examples written in openapi.yaml are what the rules compute from example.json.
//    Picture tokens are random on a real server, so they are masked on both sides.
const maskMedia = value => JSON.parse(JSON.stringify(value).replace(/\/media\/public\/[A-Za-z0-9_-]+\//g, '/media/public/TOKEN/'));
const documented = {
  getSite: site(fixture),
  getStats: platform,
  listCases: listCases(fixture, { pageSize: 1 }),
  getCase: caseDetail(fixture, 'HELP-901'),
  listCaseUpdates: caseUpdates(fixture, 'HELP-901', 'en'),
  listCaseDonors: caseDonors(fixture, 'HELP-901'),
  listCaseLedger: caseLedger(fixture, 'HELP-901', 'en', 1, 2),
};
for (const [operationId, value] of Object.entries(documented)) {
  if (!sameValue(maskMedia(exampleOf(operationId).example), maskMedia(value))) {
    fail(`the ${operationId} example in openapi.yaml differs from what example.json produces:\n${stable(maskMedia(value))}`);
  }
}

// 7. Broken fixtures are rejected, each for the stated reason.
const negatives = readJson('fixtures/negative-cases.json');
for (const { name, expect, patch } of negatives) {
  const codes = validateFixture(applyPatch(fixture, patch)).map(e => e.code);
  if (!codes.includes(expect)) fail(`negative case "${name}": expected rejection "${expect}", got ${codes.length ? codes.join(', ') : 'acceptance'}`);
}
const publicNegatives = readJson('fixtures/negative-public-cases.json');
for (const { name, expect, patch } of publicNegatives) {
  const codes = validateProjection(applyPatch(projection, patch), markers).map(e => e.code);
  if (!codes.includes(expect)) fail(`negative projection case "${name}": expected rejection "${expect}", got ${codes.length ? codes.join(', ') : 'acceptance'}`);
}
// The platform-wide bound cannot be reached with a small file, so it is tested with a low limit.
if (!validateIntegrity(fixture, { maxPlatformGross: 100000n }).some(e => e.code === 'aggregate-bound')) fail('the aggregate bound is not enforced');

if (failures.length > 0) {
  console.error(`fixtures: FAIL\n${failures.map(f => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log(`fixtures: ok (${fixture.cases.length} cases, ${fixture.ledger.length} ledger entries, ${responses} generated responses valid, ` +
  `${Object.keys(documented).length} documented examples reproduced, ${negatives.length + publicNegatives.length + 1} broken fixtures rejected)`);
