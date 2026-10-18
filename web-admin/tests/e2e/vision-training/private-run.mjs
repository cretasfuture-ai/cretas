/** Actual component/Python persistence fixture. No Java/platform auth claim. */
import { createServer, loadConfigFromFile } from 'vite';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createConnection } from 'node:net';
import { mkdir, open } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.platform !== 'win32') throw Error('This private fixture runs on the local Windows host');
const out = process.env.CRETAS_VISION_UI_EVIDENCE_DIR;
if (!out) throw Error('Explicit code-external evidence directory required');
const webRoot = fileURLToPath(new URL('../../../', import.meta.url));
const repo = resolve(webRoot, '..');
const state = resolve(out, 'runtime-state');
if (state.toLowerCase().startsWith(repo.toLowerCase() + '\\')) throw Error('Fixture state must be outside source tree');
await mkdir(out, { recursive: true });
for (const port of [18086, 31760]) {
  const occupied = await new Promise(resolve => {
    const socket = createConnection({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => resolve(false));
  });
  if (occupied) throw Error(`Private fixture port ${port} is already owned`);
}
const secret = randomBytes(32).toString('hex');
const log = await open(resolve(out, 'python-main.log'), 'w');
const python = spawn(process.env.CRETAS_VISION_PYTHON || 'B:\\anaconda3\\python.exe',
  ['-m', 'uvicorn', 'main:app', '--host', '127.0.0.1', '--port', '18086', '--lifespan', 'off'], {
    cwd: resolve(repo, 'backend/python'), stdio: ['ignore', log.fd, log.fd], windowsHide: true,
    env: { ...process.env, PYTHON_ENV: 'test', JWT_AUTH_ENABLED: 'true', INTERNAL_API_SECRET: secret,
      JWT_SECRET: randomBytes(32).toString('hex'), VISION_TRAINING_DRAFT_DIR: state,
      VISION_TRAINING_REGISTRY_FILE: '' },
  });
let vite;
try {
  let ready = false;
  for (let n = 0; n < 100; n++) {
    if (python.exitCode !== null) throw Error('Private Python exited before route availability');
    try {
      const response = await fetch('http://127.0.0.1:18086/openapi.json');
      const schema = await response.json();
      ready = !!schema.paths['/api/vision-training/platform/factories/{factory_id}/templates'];
    } catch { /* bounded startup poll */ }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  if (!ready) throw Error('Private Python route startup timeout');
  const proxy = {
    target: 'http://127.0.0.1:18086', changeOrigin: false,
    rewrite(path) {
      const match = /^\/api\/platform\/factory-assembly\/factories\/(VISION-UI-[AB])\/vision-training(\/.*)$/.exec(path);
      if (!match) throw Error('Unexpected private proxy path');
      return `/api/vision-training/platform/factories/${match[1]}${match[2]}`;
    },
    configure(server) {
      server.on('proxyReq', (request, incoming) => {
        const match = /\/factories\/(VISION-UI-[AB])\//.exec(incoming.url || '');
        if (!match) throw Error('Unexpected private proxy scope');
        request.setHeader('X-Internal-Secret', secret);
        request.setHeader('X-Factory-Id', match[1]);
        request.setHeader('X-User-Id', '1');
        request.setHeader('X-User-Role', 'super_admin');
        request.setHeader('X-Actor-Type', 'PLATFORM_ADMIN');
      });
    },
  };
  const loaded = await loadConfigFromFile({ command: 'serve', mode: 'test' }, resolve(webRoot, 'vite.config.ts'), webRoot);
  if (!loaded) throw Error('Project Vite configuration is unavailable');
  // Replace the proxy table: merging leaves the general /api route ahead of
  // this private proxy and can accidentally send acceptance to another owner.
  vite = await createServer({ ...loaded.config, root: webRoot, configFile: false,
    server: { host: '127.0.0.1', port: 31760, strictPort: true,
      proxy: { '/api/platform/factory-assembly/factories': proxy } } });
  await vite.listen();
  const result = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('./browser.mjs', import.meta.url))],
      { cwd: webRoot, env: process.env, stdio: 'inherit', windowsHide: true });
    child.once('error', reject); child.once('exit', resolve);
  });
  if (result !== 0) throw Error(`Private UI acceptance exited ${result}`);
} finally {
  if (vite) await vite.close();
  python.kill();
  await log.close();
}
