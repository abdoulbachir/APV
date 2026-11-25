// Checks openapi.yaml on its own: references, examples, the privacy boundaries between public,
// account, request and credential schemas, the rules every identity operation must follow,
// and that deliberately wrong responses, requests and contract edits are rejected.

import { applyPatch, clone, loadContract, readJson } from './lib.mjs';

const { document, check, exampleOf } = loadContract();
const failures = [];
const fail = message => failures.push(message);

// Every schema declares who may see it:
//   public     may appear in public read responses
//   shared     primitives and envelopes used everywhere; held to the public rules
//   account    identity-side data, for the signed-in person or the sign-in screens
//   request    sent by the client (passwords, codes); never part of any response
//   credential a one-time secret; only from the operations listed below
const AUDIENCES = ['public', 'shared', 'account', 'request', 'credential'];
const PUBLIC_TAGS = ['Site', 'Cases', 'Case history'];
const IDENTITY_TAGS = ['Authentication', 'Account'];
const SESSION_STATES = ['anonymous', 'mfa_required', 'mfa_enrollment_required', 'authenticated'];
// Adding an operation that returns a secret means editing this list on purpose.
const CREDENTIAL_OPERATIONS = ['getCsrfToken', 'beginMfaEnrollment', 'confirmMfaEnrollment', 'regenerateRecoveryCodes'];
// Names that suggest private data: never in a public or shared schema.
const FORBIDDEN_PROPERTY = /real|contact|email|phone|address|note|visibility|private|password|session|token|screenshot|consent|userid|accountid|donorid|birth|role|permission|mfa|credential/i;
// Names that must never be in what an account can read about itself either.
const FORBIDDEN_ACCOUNT_PROPERTY = /password|hash|secret|token|salt|pepper|credential|sessionid|otp|failed|attempt|lock|version|ipaddress|useragent|disabled|bootstrap/i;
// A schema may only refer to schemas its audience is allowed to contain.
const MAY_CONTAIN = {
  public: ['public', 'shared'],
  shared: ['shared'],
  account: ['account', 'shared'],
  request: ['request', 'account', 'shared'],
  credential: ['credential', 'shared'],
};

/**
 * Every structural rule of the contract, as a function of the document, so the same rules can
 * be run against deliberately damaged copies. Returns { failures, counts }.
 */
function structuralRules(doc) {
  const found = [];
  const bad = message => found.push(message);
  const counts = { references: 0, objectSchemas: 0, publicOperations: 0, identityOperations: 0 };
  const schemas = doc.components.schemas;
  const resolve = pointer => pointer.slice(2).split('/').reduce((node, part) => node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], doc);
  const deref = node => (node && node.$ref ? resolve(node.$ref) : node);
  const audienceOf = name => schemas[name]?.['x-audience'];

  // References resolve.
  (function walk(node, where) {
    if (Array.isArray(node)) return node.forEach((child, i) => walk(child, `${where}/${i}`));
    if (node === null || typeof node !== 'object') return;
    if (typeof node.$ref === 'string') {
      counts.references += 1;
      if (!node.$ref.startsWith('#/') || resolve(node.$ref) === undefined) bad(`unresolved $ref ${node.$ref} at ${where}`);
    }
    for (const [key, child] of Object.entries(node)) walk(child, `${where}/${key}`);
  })(doc, '#');

  // Audiences, closed objects, forbidden property names.
  for (const [name, root] of Object.entries(schemas)) {
    const audience = root['x-audience'];
    if (!AUDIENCES.includes(audience)) bad(`schemas/${name}: x-audience must be one of ${AUDIENCES.join(', ')}`);
    (function walk(schema, where) {
      if (Array.isArray(schema)) return schema.forEach((child, i) => walk(child, `${where}/${i}`));
      if (schema === null || typeof schema !== 'object') return;
      if (schema.type === 'object' || schema.properties) {
        counts.objectSchemas += 1;
        if (schema.additionalProperties !== false) bad(`${where}: object schema must set additionalProperties: false`);
        for (const [property, definition] of Object.entries(schema.properties ?? {})) {
          if ((audience === 'public' || audience === 'shared') && FORBIDDEN_PROPERTY.test(property)) {
            bad(`${where}: property "${property}" looks private and may not be in a ${audience} schema`);
          }
          if (audience === 'account' && FORBIDDEN_ACCOUNT_PROPERTY.test(property)) {
            bad(`${where}: property "${property}" must never be returned, not even to the account itself`);
          }
          if (/Url$/.test(property) || property === 'url') {
            const targets = [definition, ...(definition.oneOf ?? [])].map(p => p.$ref);
            if (!targets.includes('#/components/schemas/MediaPath')) bad(`${where}.${property}: picture addresses must use MediaPath`);
          }
        }
      }
      for (const [key, child] of Object.entries(schema)) walk(child, `${where}/${key}`);
    })(root, `schemas/${name}`);
  }
  if (schemas.PublicCaseStatus.enum.includes('draft')) bad('PublicCaseStatus must not contain draft');
  if ('enum' in schemas.CategoryId) bad('CategoryId must not be a fixed list: categories are data');

  /** Names of every schema a node refers to, directly or through other schemas. */
  function reachable(node, names = new Set()) {
    if (Array.isArray(node)) node.forEach(child => reachable(child, names));
    else if (node !== null && typeof node === 'object') {
      const match = typeof node.$ref === 'string' && /^#\/components\/schemas\/(\w+)$/.exec(node.$ref);
      if (match && !names.has(match[1]) && schemas[match[1]]) {
        names.add(match[1]);
        reachable(schemas[match[1]], names);
      }
      Object.values(node).forEach(child => reachable(child, names));
    }
    return names;
  }

  for (const [name, schema] of Object.entries(schemas)) {
    for (const inner of reachable(schema)) {
      const allowed = MAY_CONTAIN[schema['x-audience']] ?? [];
      // A request may carry an email address or a display name, but never a whole account object.
      const wholeAccount = schema['x-audience'] === 'request' && audienceOf(inner) === 'account' && schemas[inner].type === 'object';
      if (!allowed.includes(audienceOf(inner)) || wholeAccount) bad(`schemas/${name} (${schema['x-audience']}) must not contain ${inner} (${audienceOf(inner)})`);
    }
  }

  const headerConst = (response, name) => deref(response.headers?.[name])?.schema?.const;
  for (const [route, item] of Object.entries(doc.paths)) {
    for (const [method, operation] of Object.entries(item)) {
      const id = operation.operationId;
      const tags = operation.tags ?? [];
      const isPublicRead = tags.every(tag => PUBLIC_TAGS.includes(tag));
      const isIdentity = tags.every(tag => IDENTITY_TAGS.includes(tag));
      if (tags.length !== 1 || (!isPublicRead && !isIdentity)) bad(`${id}: needs exactly one known tag`);
      const responses = Object.fromEntries(Object.entries(operation.responses).map(([status, response]) => [status, deref(response)]));
      const returned = reachable(Object.values(responses).map(response => response.content ?? {}));
      const sent = reachable(operation.requestBody ?? {});
      const needsSession = (operation.security ?? doc.security).some(requirement => 'sessionCookie' in requirement);

      for (const name of returned) {
        const audience = audienceOf(name);
        if (audience === 'request') bad(`${id}: a response contains the request schema ${name}; what a client sends is never echoed back`);
        if (audience === 'credential' && !CREDENTIAL_OPERATIONS.includes(id)) bad(`${id}: returns the secret-bearing schema ${name} but is not an approved credential operation`);
        if (audience === 'account' && !isIdentity) bad(`${id}: a public read response contains the private schema ${name}`);
      }
      for (const name of sent) {
        if (!['request', 'account', 'shared'].includes(audienceOf(name))) bad(`${id}: the request body uses ${name} (${audienceOf(name)})`);
      }
      if (CREDENTIAL_OPERATIONS.includes(id) !== (operation['x-returns-credential'] === true)) bad(`${id}: x-returns-credential must match the approved list`);

      // Rules for every operation.
      if (!responses['405']) bad(`${id}: must document 405`);
      else if (!responses['405'].headers?.Allow) bad(`${id} 405: must declare the Allow header`);
      if (responses['429'] && !responses['429'].headers?.['Retry-After']) bad(`${id} 429: must declare Retry-After`);

      if (isPublicRead) {
        counts.publicOperations += 1;
        if (method !== 'get') bad(`${id}: public read operations are GET only`);
        if (needsSession) bad(`${id}: a public read operation must not need a session`);
        if (operation.requestBody) bad(`${id}: a public read operation has no request body`);
        if (operation['x-session-state']) bad(`${id}: a public read operation does not depend on the session`);
        for (const [status, response] of Object.entries(responses)) {
          if (headerConst(response, 'Cache-Control') === 'no-store') bad(`${id} ${status}: public read responses are not no-store; they must not use an identity response`);
        }
        continue;
      }

      counts.identityOperations += 1;
      // Every answer of an identity operation, errors included, is never cached.
      for (const [status, response] of Object.entries(responses)) {
        if (headerConst(response, 'Cache-Control') !== 'no-store') bad(`${id} ${status}: every identity response must declare Cache-Control: no-store`);
      }
      if (responses['503'] && !responses['503'].headers?.['Retry-After']) bad(`${id} 503: must declare Retry-After`);
      for (const required of ['429', '500', '503']) if (!responses[required]) bad(`${id}: must document ${required}`);

      // Session states.
      const states = operation['x-session-state'];
      if (!Array.isArray(states) || states.length === 0 || states.some(state => !SESSION_STATES.includes(state)) || new Set(states).size !== states.length) {
        bad(`${id}: x-session-state must list the session states it accepts`);
      } else {
        const acceptsAnonymous = states.includes('anonymous');
        if (needsSession === acceptsAnonymous) bad(`${id}: the sessionCookie requirement must be present exactly when "anonymous" is not an accepted state`);
        if (!acceptsAnonymous && !responses['401']) bad(`${id}: needs a session, so it must document 401`);
        const everyState = states.length === SESSION_STATES.length;
        if (!everyState && !states.includes('authenticated') && !responses['409']) bad(`${id}: refuses a fully signed-in session, so it must document 409`);
      }

      if (method !== 'get') {
        const parameters = (operation.parameters ?? []).map(deref);
        const csrf = doc.components.parameters.CsrfHeader;
        if (!parameters.some(p => p.in === 'header' && p.name === csrf.name && p.required)) bad(`${id}: every request that is not GET requires the CSRF header`);
        if (!responses['403']) bad(`${id}: must document 403 for a missing or invalid CSRF token`);
        if (operation.requestBody && (!responses['413'] || !responses['415'] || !responses['400'])) bad(`${id}: a request body needs 400, 413 and 415`);
        const media = Object.keys(operation.requestBody?.content ?? {});
        if (operation.requestBody && (media.length !== 1 || media[0] !== 'application/json')) bad(`${id}: request bodies are application/json only`);
        if (operation.requestBody && operation.requestBody.required !== true) bad(`${id}: a defined request body is required`);
      } else if (operation.requestBody) {
        bad(`${id}: GET has no request body`);
      }
    }
  }

  // The CSRF token is described consistently.
  if (schemas.CsrfToken.properties.headerName.const !== doc.components.parameters.CsrfHeader.name) bad('CsrfToken.headerName must equal the name of the CsrfHeader parameter');

  // Sign-up can never choose a role or a permission.
  for (const forbidden of ['role', 'permissions', 'canDisburse', 'emailVerified', 'id', 'mfa']) {
    if (forbidden in schemas.RegisterRequest.properties) bad(`RegisterRequest must not accept "${forbidden}"`);
  }

  // A sign-in result is one of two closed forms, told apart by a constant.
  const forms = (schemas.LoginResult.oneOf ?? []).map(deref);
  if (forms.length !== 2 || !forms.some(form => form.properties?.status?.const === 'authenticated' && form.required?.includes('account'))
      || !forms.some(form => !('account' in (form.properties ?? {})) && form.properties?.status?.enum)) {
    bad('LoginResult must be oneOf a signed-in form with an account and a pending form without one');
  }
  const verified = doc.paths['/auth/mfa/verify'].post.responses['200'].content['application/json'].schema.$ref;
  if (verified !== '#/components/schemas/SignedIn') bad('verifyMfa success must be the signed-in form only');

  return { failures: found, counts };
}

// 1. The structural rules hold for the committed contract.
const { failures: structural, counts } = structuralRules(document);
structural.forEach(fail);

// 2. Every example validates against its schema, requests included.
let examples = 0;
const derefDocument = node => (node && node.$ref ? node.$ref.slice(2).split('/').reduce((n, part) => n?.[part.replace(/~1/g, '/')], document) : node);
function checkExample(schema, example, where) {
  examples += 1;
  for (const error of check(schema, example)) fail(`example at ${where}: ${error}`);
}
function checkResponse(response, where) {
  for (const [type, media] of Object.entries(derefDocument(response).content ?? {})) {
    if (media.example === undefined) fail(`no example at ${where} (${type})`);
    else checkExample(media.schema, media.example, where);
  }
}
for (const [route, item] of Object.entries(document.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    for (const [status, response] of Object.entries(operation.responses)) {
      if (!response.$ref) checkResponse(response, `${method.toUpperCase()} ${route} ${status}`);
    }
    const media = operation.requestBody?.content?.['application/json'];
    if (!media) continue;
    if (media.example === undefined) fail(`${operation.operationId}: the request body needs an example`);
    else checkExample(media.schema, media.example, `${method.toUpperCase()} ${route} request`);
  }
}
for (const [name, response] of Object.entries(document.components.responses)) checkResponse(response, `components.responses.${name}`);
for (const [name, parameter] of Object.entries(document.components.parameters)) {
  if (parameter.example !== undefined) checkExample(parameter.schema, parameter.example, `components.parameters.${name}`);
}

// 3. Wrong responses and wrong requests are rejected by the schemas.
const requestExampleOf = operationId => {
  for (const item of Object.values(document.paths)) {
    for (const operation of Object.values(item)) {
      if (operation.operationId === operationId) return operation.requestBody.content['application/json'];
    }
  }
  throw new Error(`no operation ${operationId}`);
};
const negatives = [
  ...readJson('tests/negative-responses.json').map(negative => ({ ...negative, ...exampleOf(negative.operationId) })),
  ...readJson('tests/negative-requests.json').map(negative => ({ ...negative, ...requestExampleOf(negative.operationId) })),
];
for (const { name, patch, example, schema } of negatives) {
  if (check(schema, example).length > 0) { fail(`negative case "${name}": its base example is itself invalid`); continue; }
  if (check(schema, applyPatch(example, patch)).length === 0) fail(`negative case "${name}" was accepted but must be rejected`);
}

// 4. Wrong contracts are rejected by the structural rules. Each case damages a copy of the
//    contract; the rules must then report a failure containing the expected text. This is
//    what proves the rules above actually bite.
const mutations = readJson('tests/negative-contracts.json');
for (const { name, expect, patch } of mutations) {
  let damaged;
  try {
    damaged = applyPatch(clone(document), patch);
  } catch (error) {
    fail(`contract mutation "${name}": cannot be applied (${error.message})`);
    continue;
  }
  const reported = structuralRules(damaged).failures;
  if (!reported.some(message => message.includes(expect))) {
    fail(`contract mutation "${name}": expected a failure containing "${expect}", got ${reported.length ? reported.slice(0, 3).join(' | ') : 'acceptance'}`);
  }
}

if (failures.length > 0) {
  console.error(`openapi: FAIL\n${failures.map(f => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log(`openapi: ok (${counts.references} references, ${examples} examples, ${counts.objectSchemas} closed object schemas, `
  + `${counts.publicOperations} public and ${counts.identityOperations} identity operations, ${negatives.length} wrong responses and requests rejected, `
  + `${mutations.length} wrong contracts rejected)`);
