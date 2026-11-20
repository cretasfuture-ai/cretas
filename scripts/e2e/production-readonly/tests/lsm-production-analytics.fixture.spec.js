'use strict';

const fs = require('node:fs/promises');
const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');

test.use({ headless: false, viewport: { width: 1920, height: 1080 }, locale: 'zh-CN' });

const HUB = 'lsm-production-data-analysis-readonly';
const COST = 'lsm-production-cost-readonly';
const DATA_API = '/smartbi-api/api/smartbi/factory/external-production';
const source = limitation => `<div class="blacklake-source">
  <div class="blacklake-source__details"><strong>数据来源　黑湖</strong>
  <span>快照采集　<time datetime="2026-11-20T00:00:00Z">2026年11月20日 08:00</time>（北京时间）</span></div>
  <div class="el-alert__title">出成率与生产成本 · ${limitation}</div></div>`;
const limitation = '黑湖数据不含投入量，无法计算';
const table = (headers, cells) => `<table><thead><tr>${headers.map(text => `<th>${text}</th>`).join('')}</tr></thead>
  <tbody class="el-table__body"><tr>${cells.map(text => `<td>${text}</td>`).join('')}</tr></tbody></table>`;
const shell = content => `<div class="app-layout"><header class="app-header">本地只读验收 · 生产管理与分析</header>
  <aside class="app-sidebar"><div class="sidebar-logo"><span class="logo-title">测试工厂</span></div></aside>${content}</div>`;
const login = `<form><input placeholder="用户名"><input type="password"><button type="submit">登录</button></form>
  <script>document.querySelector('form').onsubmit = async (event) => {
    event.preventDefault();
    const [username, password] = [...event.target.querySelectorAll('input')].map(input => input.value);
    const response = await fetch('/api/mobile/auth/unified-login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ username, password }) });
    const body = await response.json();
    localStorage.setItem('cretas_user', JSON.stringify({ ...body.data, factoryId: 'LIUSHANMEN' }));
    history.pushState({}, '', '/dashboard');
    document.body.innerHTML = ${JSON.stringify(shell('首页驾驶舱'))};
  };</script>`;

async function runFixture(page, testInfo, { scenarios = [HUB, COST], factoryId = 'LIUSHANMEN', cost = 'limitation', zero = false, errors = false, write = false } = {}) {
  const requests = [];
  const fetched = `<script>fetch('${DATA_API}');</script>`;
  const hub = `<main class="production-analysis-hub"><div role="tab" aria-selected="true">生产数据分析</div>
    <section aria-label="黑湖生产数据分析">${source(zero ? '0%' : limitation)}
      <div class="blacklake-card"><h3>每日报工趋势</h3><select aria-label="工序"><option>成品入库</option></select>
        <select aria-label="产品"><option>全部产品</option></select><div role="img" aria-label="每日良品与不良数量趋势图" style="height:40px"></div>
        ${table(['日期', '良品（盒）', '不良（盒）', '不良率'], ['2026-11-19', '10', '1', '9.09%'])}</div>
      <div class="blacklake-card"><h3>成品入库良品排行（盒）</h3>
        ${table(['产品', '良品（盒）'], ['测试产品', '10'])}</div>
    </section></main>${fetched}`;
  const costDisplay = {
    limitation: source(limitation),
    empty: '<div class="fca-empty-title">该工厂暂无生产成本数据</div><div class="fca-empty-desc">报工时填写材料、人工成本后，生产成本分析将在此处展示。</div>',
    data: '<div class="fca-kpi-card">总成本 ¥12.50</div><div class="fca-chart-body"><canvas></canvas></div>',
  };
  const costPage = `<main class="fca-root"><h2>生产成本分析</h2><p>统计期间：2026年11月01日至2026年11月20日</p>${costDisplay[cost]}</main>${fetched}`
    + (errors ? '<img src="/missing.png"><script>fetch("/api/mobile/LIUSHANMEN/fixture-error"); console.error("fixture cost error");</script>' : '')
    + (write ? '<script>fetch("/api/mobile/LIUSHANMEN/fixture-mutation", { method: "POST" }).catch(() => {});</script>' : '');
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    requests.push(`${req.method} ${url.pathname}${url.search}`);
    if (url.pathname === '/api/mobile/auth/unified-login') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, data: { factoryUser: { factoryId, username: 'fixture-admin', role: 'factory_super_admin', factoryName: '测试工厂' } } }));
    } else if (url.pathname === DATA_API || url.pathname.endsWith('/fixture-error')) {
      res.writeHead(url.pathname === DATA_API ? 200 : 500, { 'Content-Type': 'application/json' });
      res.end('{}');
    } else {
      const html = { '/login': login, '/dashboard': shell('首页驾驶舱'), '/production/analysis-query': shell(hub), '/production-analytics/cost': shell(costPage) }[url.pathname];
      res.writeHead(html ? 200 : url.pathname === '/favicon.ico' ? 204 : 404, { 'Content-Type': 'text/html; charset=UTF-8' });
      res.end(html ? `<!doctype html><html><body>${html}</body></html>` : '');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const report = await runSuiteWithPage(page, {
      scenarios, expectedFactoryId: 'LIUSHANMEN', expectedUsername: 'fixture-admin', username: 'fixture-admin', password: 'fixture-only',
      baseUrl: `http://127.0.0.1:${server.address().port}`, evidenceDir: testInfo.outputPath('evidence'),
    });
    return { report, requests };
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

test('one guarded UI login drives the Blacklake hub and independent cost scenario', async ({ page }, testInfo) => {
  const { report, requests } = await runFixture(page, testInfo);
  expect(report.scenarios.map(item => item.result), JSON.stringify(report.scenarios)).toEqual(['PASS', 'PASS']);
  expect(report.tenant.factoryId).toBe('LIUSHANMEN');
  expect(report.scenarios[0].pageEvidence[1]).toMatchObject({ dailyRows: 1, productRows: 1, snapshotVisible: true, yieldAndCostUnavailable: true });
  expect(report.scenarios[1].pageEvidence[1]).toMatchObject({ state: 'limitation', limitation: `出成率与生产成本 · ${limitation}` });
  expect(requests.filter(item => item.startsWith('POST'))).toEqual(['POST /api/mobile/auth/unified-login']);
  expect(requests).toContain('GET /production/analysis-query?tab=data-analysis');
  expect(requests).toContain('GET /production-analytics/cost');
  expect(requests.filter(item => item === `GET ${DATA_API}`)).toHaveLength(2);
  for (const item of report.scenarios) {
    expect(item.pageEvidence.at(-1)).toMatchObject({ consoleErrorCount: 0, http4xxCount: 0, http5xxCount: 0 });
    expect(item.screenshots).toHaveLength(1);
    expect((await fs.stat(item.screenshots[0])).size).toBeGreaterThan(0);
  }
  expect(report.actualBusinessWrites).toBe(0);
  expect(report.blockedMutationAttempts).toHaveLength(0);
});

for (const cost of ['empty', 'data']) {
  test(`cost can run alone and records ${cost}`, async ({ page }, testInfo) => {
    const { report, requests } = await runFixture(page, testInfo, { scenarios: [COST], cost });
    expect(report.scenarios).toHaveLength(1);
    expect(report.scenarios[0].result).toBe('PASS');
    expect(report.scenarios[0].pageEvidence[1].state).toBe(cost);
    expect(requests.some(item => item.includes('/production/analysis-query'))).toBe(false);
  });
}

test('a local storage tenant cannot substitute for the live login response', async ({ page }, testInfo) => {
  const { report, requests } = await runFixture(page, testInfo, { factoryId: 'F006' });
  expect(report.scenarios.map(item => [item.scenario, item.result])).toEqual([['ui-login', 'TOOL_ERROR']]);
  expect(JSON.stringify(report.scenarios)).toContain('Live login response did not prove factory LIUSHANMEN');
  expect(requests.some(item => item.startsWith('GET /production'))).toBe(false);
});

test('zero placeholder, HTTP errors, console errors and a blocked write cannot pass', async ({ page }, testInfo) => {
  const { report, requests } = await runFixture(page, testInfo, { zero: true, errors: true, write: true });
  expect(report.scenarios.map(item => item.result)).toEqual(['UNVERIFIED', 'TOOL_ERROR']);
  expect(report.scenarios[0].pageEvidence[1].yieldAndCostUnavailable).toBe(false);
  const counts = report.scenarios[1].pageEvidence.at(-1);
  expect(counts).toMatchObject({ http4xxCount: 1, http5xxCount: 1 });
  expect(counts.consoleErrorCount).toBeGreaterThan(0);
  expect(report.blockedMutationAttempts).toHaveLength(1);
  expect(report.actualBusinessWrites).toBe(0);
  expect(requests).not.toContain('POST /api/mobile/LIUSHANMEN/fixture-mutation');
});
