// Run from web-admin: node src/views/system/product-processes/workflow/__tests__/workflowFullscreen.browser.mjs
// Real hub, parent ElTabs, ProductProcesses, Editor and VueFlow; all API responses stay local.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';
import { chromium } from '@playwright/test';

const root = process.cwd();
const out = resolve(root, '../.playwright-mcp/workflow-fullscreen');
await mkdir(out, { recursive: true });
const entry = `
import {createApp,h} from 'vue';
import ElementPlus from 'element-plus';
import {createPinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import 'element-plus/dist/index.css';
import '/src/style.css';
import Hub from '/src/views/production/process-hub/index.vue';
localStorage.setItem('cretas_user',JSON.stringify({userType:'factory',factoryUser:{factoryId:'LOCAL_FULLSCREEN',role:'factory_super_admin'}}));
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});
await router.push('/production/process-hub?tab=product-processes&productTypeId=PT');
await router.isReady();
createApp({render:()=>h(Hub)}).use(createPinia()).use(ElementPlus).use(router).mount('#app');
`;
const server = await createServer({
  root, configFile: false, cacheDir: resolve(out, 'vite-cache'),
  resolve: { alias: { '@': resolve(root, 'src') } },
  server: { host: '127.0.0.1', port: 5198, strictPort: true },
  plugins: [vue(), {
    name: 'workflow-fullscreen-fixture',
    resolveId(id) { if (id === 'virtual:fullscreen') return '\0virtual:fullscreen'; },
    load(id) { if (id === '\0virtual:fullscreen') return entry; },
    configureServer(s) {
      s.middlewares.use(async (req, res, next) => {
        if (req.url !== '/fullscreen-fixture') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(await s.transformIndexHtml('/fullscreen-fixture', '<div id="app"></div><script type="module" src="/@id/__x00__virtual:fullscreen"></script>'));
      });
    },
  }],
});
const report = { environment: 'local mocked API; no production persistence or OS cursor pixel claim', result: 'UNVERIFIED', cases: [], requests: [], errors: [], externalRequests: [] };
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.hostname !== '127.0.0.1') { report.externalRequests.push(url.origin); return route.abort(); }
    if (!url.pathname.startsWith('/api/')) return route.continue();
    report.requests.push({ path: url.pathname, method: request.method() });
    let data = [];
    if (url.pathname.endsWith('/product-process-workflows/PT')) data = {
      id: 1, productTypeId: 'PT', schemaVersion: 1, status: 'DRAFT', version: 1, lockVersion: 0,
      nodes: [
        { id: 'raw', kind: 'RAW_MATERIAL', position: { x: 30, y: 50 }, data: { name: '本地原料', skuId: 'RM', baseUnit: 'kg' } },
        { id: 'process', kind: 'PROCESS', position: { x: 650, y: 50 }, data: { workProcessId: 'P', processName: '本地工序', ports: [{ id: 'in', direction: 'INPUT', ordinal: 0 }, { id: 'out', direction: 'OUTPUT', ordinal: 1 }] } },
        { id: 'semi', kind: 'SEMI_FINISHED', position: { x: 1250, y: 50 }, data: { name: '本地半成品', skuId: 'S', baseUnit: 'kg' } },
      ], edges: [], viewport: { x: 20, y: 20, zoom: 0.8 },
    };
    else if (url.pathname.endsWith('/activation')) data = null;
    else if (url.pathname.endsWith('/product-types/options')) data = { content: [
      { id: 'PT', name: '本地全屏验收', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
      { id: 'S', name: '本地半成品', unit: 'kg', productCategory: 'SEMI_FINISHED' },
    ] };
    else if (url.pathname.endsWith('/product-types/PT')) data = { id: 'PT', name: '本地全屏验收', unit: 'kg', productCategory: 'FINISHED_PRODUCT' };
    else if (url.pathname.endsWith('/raw-material-types/active')) data = [{ id: 'RM', name: '本地原料', unit: 'kg', category: '原料' }];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  });
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1024, height: 768 }]) {
    const page = await context.newPage();
    await page.setViewportSize(viewport);
    page.setDefaultTimeout(15000);
    page.on('pageerror', e => report.errors.push(e.message));
    await page.goto('http://127.0.0.1:5198/fullscreen-fixture');
    await page.locator('.material-node').first().waitFor();
    if (await page.getByTestId('workflow-immersive-enter').isVisible()) await page.getByTestId('workflow-immersive-enter').click();
    const parentTabs = page.locator('.process-config-hub > .hub-tabs > .el-tabs__header');
    const editor = page.locator('.workflow-editor');
    const nodeIds = await page.locator('.vue-flow__node').evaluateAll(es => es.map(e => e.getAttribute('data-id')).sort());
    const record = { viewport, states: [] };
    async function capture(name, immersive) {
      await page.waitForTimeout(450);
      const box = await editor.boundingBox();
      assert(box);
      const tabsVisible = await parentTabs.isVisible();
      assert.equal(tabsVisible, !immersive, 'Parent hub tabs must match immersive state');
      if (immersive) {
        assert(Math.abs(box.x) <= 1 && Math.abs(box.y) <= 1, 'Fullscreen origin');
        assert(Math.abs(box.width - viewport.width) <= 1, 'Fullscreen width');
        assert(Math.abs(box.height - viewport.height) <= 1, 'Fullscreen height');
      }
      assert.deepEqual(await page.locator('.vue-flow__node').evaluateAll(es => es.map(e => e.getAttribute('data-id')).sort()), nodeIds, 'No node lost during layout operations');
      const screenshot = resolve(out, `${viewport.width}-${name}.png`);
      await page.screenshot({ path: screenshot, fullPage: true });
      record.states.push({ name, editor: box, tabsVisible, screenshot });
    }
    await capture('fullscreen', true);
    await page.getByRole('button', { name: '适应画布', exact: true }).click();
    await capture('fit', true);
    const dock = page.locator('.workflow-ai-dock');
    const expandedHeight = (await dock.boundingBox()).height;
    await dock.getByText('收起', { exact: true }).click();
    assert((await dock.boundingBox()).height < expandedHeight, 'Collapsing AI releases canvas space');
    await capture('ai-collapsed', true);
    await page.getByTestId('workflow-immersive-exit').click();
    await capture('exit', false);
    assert.equal(await page.evaluate(() => document.body.classList.contains('workflow-immersive')), false);
    await page.getByTestId('workflow-immersive-enter').click();
    await capture('reenter', true);
    record.nodeIdsPreserved = nodeIds;
    report.cases.push(record);
    await page.close();
  }
  assert.deepEqual(report.errors, []);
  assert.deepEqual(report.externalRequests, []);
  assert(report.requests.every(r => r.method === 'GET'), 'No business write requests expected');
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL'; report.failure = error.stack; process.exitCode = 1;
} finally {
  await browser?.close(); await server.close();
  await writeFile(resolve(out, 'result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ result: report.result, cases: report.cases.length, errors: report.errors, failure: report.failure }));
}
