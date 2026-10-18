import { createServer, loadConfigFromFile } from 'vite';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const webRoot = fileURLToPath(new URL('../../../', import.meta.url));
const configPath = resolve(webRoot, 'vite.config.ts');
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'test' }, configPath, webRoot);
if (!loaded) throw new Error('Project Vite configuration is unavailable');

const vite = await createServer({
  ...loaded.config,
  root: webRoot,
  configFile: false,
  server: {
    host: '127.0.0.1',
    port: 31761,
    strictPort: true,
    proxy: {
      '/smartbi-api': {
        target: 'http://127.0.0.1:18090',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/smartbi-api/, ''),
      },
      '/api': {
        target: 'http://127.0.0.1:10060',
        changeOrigin: true,
      },
    },
  },
});

let child;
try {
  await vite.listen();
  if (process.env.VISION_ENTRY_SKIP_BROWSER === 'true') {
    await new Promise((resolveHold) => {
      process.once('SIGINT', resolveHold);
      process.once('SIGTERM', resolveHold);
    });
  } else {
    child = spawn(process.execPath, [fileURLToPath(new URL('./platform-browser.mjs', import.meta.url))], {
      cwd: webRoot,
      env: { ...process.env, VISION_ENTRY_BASE: process.env.VISION_ENTRY_BASE || 'http://127.0.0.1:31761' },
      stdio: 'inherit',
      windowsHide: true,
    });
    const exitCode = await new Promise((resolveExit, reject) => {
      child.once('error', reject);
      child.once('exit', (code, signal) => resolveExit(code ?? (signal ? 1 : 0)));
    });
    if (exitCode !== 0) throw new Error(`Platform browser acceptance exited with code ${exitCode}`);
  }
} finally {
  if (child && child.exitCode === null) child.kill();
  await vite.close();
}
