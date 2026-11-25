// Same-origin HTTPS front for the production-profile run: serves the real-mode dist with the
// project's production headers and forwards /api and /media/public unchanged to the backend.
import https from 'node:https'; import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
const [dist, policyFile, key, cert, port, backendPort] = process.argv.slice(2);
const { productionHeaders } = await import(policyFile);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg' };
https.createServer({ key: fs.readFileSync(key), cert: fs.readFileSync(cert) }, (req, res) => {
  const url = new URL(req.url, 'https://localhost');
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/media/public/')) {
    const upstream = http.request({ host: '127.0.0.1', port: backendPort, method: req.method, path: req.url, headers: req.headers }, r => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
    upstream.on('error', () => { res.writeHead(502, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end('{"error":{"code":"unavailable","message":"upstream"}}'); });
    req.pipe(upstream); return;
  }
  let file = path.join(dist, path.normalize(url.pathname));
  if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html');
  res.writeHead(200, { ...productionHeaders, 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream', ...(file.endsWith('index.html') ? { 'Cache-Control': 'no-store' } : {}) });
  fs.createReadStream(file).pipe(res);
}).listen(Number(port), '127.0.0.1', () => console.log('tls proxy ready'));
