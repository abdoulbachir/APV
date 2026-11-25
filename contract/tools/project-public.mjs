// Writes fixtures/example.public.json: the sanitized dataset the frontend mock may bundle.
// Usage: node tools/project-public.mjs [input.json] [output.json]

import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { projectPublic, readJson, root } from './lib.mjs';

const [input = 'fixtures/example.json', output = input.replace(/\.json$/, '.public.json')] = process.argv.slice(2);
writeFileSync(path.join(root, output), `${JSON.stringify(projectPublic(readJson(input)), null, 2)}\n`);
console.log(`wrote ${output}`);
