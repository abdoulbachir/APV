// Writes the project's production headers (security/content-policy.mjs) as a Caddy snippet, so the
// test site sends exactly the policy the frontend build and its checks use.
import { writeFileSync } from 'node:fs';
import { productionHeaders } from '../../security/content-policy.mjs';
const quote = value => { if (/["\n\\]/.test(value)) throw new Error('A header value cannot be written as a Caddy string: ' + value); return `"${value}"`; };
const lines = Object.entries(productionHeaders).map(([name, value]) => `\t${name} ${quote(value)}`);
writeFileSync(process.argv[2], `header {\n${lines.join('\n')}\n}\n`);
