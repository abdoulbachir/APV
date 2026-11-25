// Checks openapi.yaml on its own: references, examples, the privacy boundaries between public,
// account, request and credential schemas, and that deliberately wrong responses and requests
// are rejected.

import { applyPatch, loadContract, readJson } from './lib.mjs';

const { document, check, resolve, exampleOf } = loadContract();
const failures = [];
const fail = message => failures.push(message);

// 1. Every $ref resolves.
let references = 0;
(function walk(node, where) {
  if (Array.isArray(node)) return node.forEach((child, i) => walk(child, `${where}/${i}`));
  if (node === null || typeof node !== 'object') return;
  if (typeof node.$ref === 'string') {
    references += 1;
    if (!node.$ref.startsWith('#/') || resolve(node.$ref) === undefined) fail(`unresolved $ref ${node.$ref} at ${where}`);
  }
  for (const [key, child] of Object.entries(node)) walk(child, `${where}/${key}`);
})(document, '#');

// 2. Every example validates against its schema.
let examples = 0;
const deref = node => (node && node.$ref ? resolve(node.$ref) : node);
function checkExample(schema, example, where) {
  examples += 1;
  for (const error of check(schema, example)) fail(`example at ${where}: ${error}`);
}
function checkResponse(response, where) {
  for (const [type, media] of Object.entries(deref(response).content ?? {})) {
    if (media.example === undefined) fail(`no example at ${where} (${type})`);
    else checkExample(media.schema, media.example, where);
  }
}
for (const [route, item] of Object.entries(document.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    for (const [status, response] of Object.entries(operation.responses)) {
      if (!response.$ref) checkResponse(response, `${method.toUpperCase()} ${route} ${status}`);
    }
  }
}
for (const [name, response] of Object.entries(document.components.responses)) checkResponse(response, `components.responses.${name}`);
for (const [name, parameter] of Object.entries(document.components.parameters)) {
  if (parameter.example !== undefined) checkExample(parameter.schema, parameter.example, `components.parameters.${name}`);
}

// 3. Privacy boundaries.
//
// Every schema declares who may see it:
//   public     may appear in public read responses
//   shared     primitives and envelopes used everywhere; held to the public rules
//   account    the signed-in person's own data; only from identity operations
//   request    sent by the client (passwords, codes); never part of any response
//   credential a one-time secret; only from the operations listed below
const AUDIENCES = ['public', 'shared', 'account', 'request', 'credential'];
const PUBLIC_TAGS = ['Site', 'Cases', 'Case history'];
const IDENTITY_TAGS = ['Authentication', 'Account'];
// Adding an operation that returns a secret means editing this list on purpose.
const CREDENTIAL_OPERATIONS = ['getCsrfToken', 'beginMfaEnrollment', 'confirmMfaEnrollment', 'regenerateRecoveryCodes'];
// Names that suggest private data: never in a public or shared schema.
const FORBIDDEN_PROPERTY = /real|contact|email|phone|address|note|visibility|private|password|session|token|screenshot|consent|userid|accountid|donorid|birth|role|permission|mfa|credential/i;
// Names that must never be in what an account can read about itself either.
const FORBIDDEN_ACCOUNT_PROPERTY = /password|hash|secret|token|salt|pepper|credential|sessionid|otp|failed|attempt|lock|version|ipaddress|useragent|disabled|bootstrap/i;

const schemas = document.components.schemas;
const audienceOf = name => schemas[name]?.['x-audience'];
for (const [name, schema] of Object.entries(schemas)) {
  if (!AUDIENCES.includes(schema['x-audience'])) fail(`schemas/${name}: x-audience must be one of ${AUDIENCES.join(', ')}`);
}

let objectSchemas = 0;
for (const [name, root] of Object.entries(schemas)) {
  const audience = root['x-audience'];
  (function walk(schema, where) {
    if (Array.isArray(schema)) return schema.forEach((child, i) => walk(child, `${where}/${i}`));
    if (schema === null || typeof schema !== 'object') return;
    if (schema.type === 'object' || schema.properties) {
      objectSchemas += 1;
      if (schema.additionalProperties !== false) fail(`${where}: object schema must set additionalProperties: false`);
      for (const [property, definition] of Object.entries(schema.properties ?? {})) {
        if ((audience === 'public' || audience === 'shared') && FORBIDDEN_PROPERTY.test(property)) {
          fail(`${where}: property "${property}" looks private and may not be in a ${audience} schema`);
        }
        if (audience === 'account' && FORBIDDEN_ACCOUNT_PROPERTY.test(property)) {
          fail(`${where}: property "${property}" must never be returned, not even to the account itself`);
        }
        if (/Url$/.test(property) || property === 'url') {
          const targets = [definition, ...(definition.oneOf ?? [])].map(p => p.$ref);
          if (!targets.includes('#/components/schemas/MediaPath')) fail(`${where}.${property}: picture addresses must use MediaPath`);
        }
      }
    }
    for (const [key, child] of Object.entries(schema)) walk(child, `${where}/${key}`);
  })(root, `schemas/${name}`);
}
if (schemas.PublicCaseStatus.enum.includes('draft')) fail('PublicCaseStatus must not contain draft');
if ('enum' in schemas.CategoryId) fail('CategoryId must not be a fixed list: categories are data');

/** Names of every schema a node refers to, directly or through other schemas. */
function reachable(node, found = new Set()) {
  if (Array.isArray(node)) node.forEach(child => reachable(child, found));
  else if (node !== null && typeof node === 'object') {
    const match = typeof node.$ref === 'string' && /^#\/components\/schemas\/(\w+)$/.exec(node.$ref);
    if (match && !found.has(match[1])) {
      found.add(match[1]);
      reachable(schemas[match[1]], found);
    }
    Object.values(node).forEach(child => reachable(child, found));
  }
  return found;
}

// A schema may only refer to schemas its audience is allowed to contain.
const MAY_CONTAIN = {
  public: ['public', 'shared'],
  shared: ['shared'],
  account: ['account', 'shared'],
  request: ['request', 'account', 'shared'],
  credential: ['credential', 'shared'],
};
for (const [name, schema] of Object.entries(schemas)) {
  for (const inner of reachable(schema)) {
    const allowed = MAY_CONTAIN[schema['x-audience']] ?? [];
    // A request may carry an email address or a display name, but never a whole account object.
    const wholeAccount = schema['x-audience'] === 'request' && audienceOf(inner) === 'account' && schemas[inner].type === 'object';
    if (!allowed.includes(audienceOf(inner)) || wholeAccount) fail(`schemas/${name} (${schema['x-audience']}) must not contain ${inner} (${audienceOf(inner)})`);
  }
}

let publicOperations = 0;
let identityOperations = 0;
for (const [route, item] of Object.entries(document.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    const id = operation.operationId;
    const tags = operation.tags ?? [];
    const isPublicRead = tags.every(tag => PUBLIC_TAGS.includes(tag));
    const isIdentity = tags.every(tag => IDENTITY_TAGS.includes(tag));
    if (tags.length !== 1 || (!isPublicRead && !isIdentity)) fail(`${id}: needs exactly one known tag`);
    const responses = Object.fromEntries(Object.entries(operation.responses).map(([status, response]) => [status, deref(response)]));
    const returned = reachable(Object.values(responses).map(response => response.content ?? {}));
    const sent = reachable(operation.requestBody ?? {});
    const needsSession = (operation.security ?? document.security).some(requirement => 'sessionCookie' in requirement);

    for (const name of returned) {
      const audience = audienceOf(name);
      if (audience === 'request') fail(`${id}: a response contains the request schema ${name}; what a client sends is never echoed back`);
      if (audience === 'credential' && !CREDENTIAL_OPERATIONS.includes(id)) fail(`${id}: returns the secret-bearing schema ${name} but is not an approved credential operation`);
      if (audience === 'account' && !isIdentity) fail(`${id}: a public read response contains the private schema ${name}`);
    }
    for (const name of sent) {
      if (!['request', 'account', 'shared'].includes(audienceOf(name))) fail(`${id}: the request body uses ${name} (${audienceOf(name)})`);
    }
    if (CREDENTIAL_OPERATIONS.includes(id) !== (operation['x-returns-credential'] === true)) fail(`${id}: x-returns-credential must match the approved list`);
    if (!responses['405']) fail(`${id}: must document 405`);

    if (isPublicRead) {
      publicOperations += 1;
      if (method !== 'get') fail(`${id}: public read operations are GET only`);
      if (needsSession) fail(`${id}: a public read operation must not need a session`);
      if (operation.requestBody) fail(`${id}: a public read operation has no request body`);
      continue;
    }

    identityOperations += 1;
    // Every identity response that can carry private data, or follows a session change, is never cached.
    for (const [status, response] of Object.entries(responses)) {
      if (!status.startsWith('2') && status !== '401' && status !== '403') continue;
      const cache = deref(response.headers?.['Cache-Control']);
      if (cache?.schema?.const !== 'no-store') fail(`${id} ${status}: identity responses must declare Cache-Control: no-store`);
    }
    if (needsSession && !responses['401']) fail(`${id}: needs a session, so it must document 401`);
    if (method !== 'get') {
      const parameters = (operation.parameters ?? []).map(deref);
      if (!parameters.some(p => p.in === 'header' && p.name === 'X-XSRF-TOKEN' && p.required)) fail(`${id}: every request that is not GET requires the CSRF header`);
      if (!responses['403']) fail(`${id}: must document 403 for a missing or invalid CSRF token`);
      if (operation.requestBody && (!responses['413'] || !responses['415'] || !responses['400'])) fail(`${id}: a request body needs 400, 413 and 415`);
      const media = Object.keys(operation.requestBody?.content ?? {});
      if (operation.requestBody && (media.length !== 1 || media[0] !== 'application/json')) fail(`${id}: request bodies are application/json only`);
    }
  }
}

// Sign-up can never choose a role or a permission.
for (const forbidden of ['role', 'permissions', 'canDisburse', 'emailVerified', 'id', 'mfa']) {
  if (forbidden in schemas.RegisterRequest.properties) fail(`RegisterRequest must not accept "${forbidden}"`);
}
// Request examples must be valid too.
for (const [route, item] of Object.entries(document.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    const media = operation.requestBody?.content?.['application/json'];
    if (!media) continue;
    if (media.example === undefined) fail(`${operation.operationId}: the request body needs an example`);
    else checkExample(media.schema, media.example, `${method.toUpperCase()} ${route} request`);
  }
}

// 4. Wrong responses are rejected.
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

if (failures.length > 0) {
  console.error(`openapi: FAIL\n${failures.map(f => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log(`openapi: ok (${references} references, ${examples} examples, ${objectSchemas} closed object schemas, ${publicOperations} public and ${identityOperations} identity operations, ${negatives.length} wrong responses and requests rejected)`);
