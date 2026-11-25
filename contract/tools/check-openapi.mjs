// Checks openapi.yaml on its own: references, examples, privacy constraints, and that
// deliberately wrong responses are rejected.

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

// 3. Privacy constraints on the public schemas.
const FORBIDDEN_PROPERTY = /real|contact|email|phone|address|note|visibility|private|password|session|token|screenshot|consent|userid|accountid|donorid|birth/i;
let objectSchemas = 0;
(function walk(schema, where) {
  if (Array.isArray(schema)) return schema.forEach((child, i) => walk(child, `${where}/${i}`));
  if (schema === null || typeof schema !== 'object') return;
  if (schema.type === 'object' || schema.properties) {
    objectSchemas += 1;
    if (schema.additionalProperties !== false) fail(`${where}: object schema must set additionalProperties: false`);
    for (const [name, property] of Object.entries(schema.properties ?? {})) {
      if (FORBIDDEN_PROPERTY.test(name)) fail(`${where}: property "${name}" looks private and may not be in a public schema`);
      if (/Url$/.test(name) || name === 'url') {
        const targets = [property, ...(property.oneOf ?? [])].map(p => p.$ref);
        if (!targets.includes('#/components/schemas/MediaPath')) fail(`${where}.${name}: picture addresses must use MediaPath`);
      }
    }
  }
  for (const [key, child] of Object.entries(schema)) walk(child, `${where}/${key}`);
})(document.components.schemas, 'schemas');
if (document.components.schemas.PublicCaseStatus.enum.includes('draft')) fail('PublicCaseStatus must not contain draft');
if ('enum' in document.components.schemas.CategoryId) fail('CategoryId must not be a fixed list: categories are data');

// 4. Wrong responses are rejected.
const negatives = readJson('tests/negative-responses.json');
for (const { name, operationId, patch } of negatives) {
  const { example, schema } = exampleOf(operationId);
  if (check(schema, example).length > 0) { fail(`negative case "${name}": its base example is itself invalid`); continue; }
  if (check(schema, applyPatch(example, patch)).length === 0) fail(`negative case "${name}" was accepted but must be rejected`);
}

if (failures.length > 0) {
  console.error(`openapi: FAIL\n${failures.map(f => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log(`openapi: ok (${references} references, ${examples} examples, ${objectSchemas} closed object schemas, ${negatives.length} wrong responses rejected)`);
