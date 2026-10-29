'use strict';

const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { inspectCommercePage, inspectPurchaseInventoryPage, inspectLegacyPage, validateCommerceOptions, validateLegacyOptions } = require('../scenarios/smartbi-focused-acceptance-readonly');

const expected = validateCommerceOptions({
  expectedFamilies: ['commerce-joint.sales.stock', 'commerce-joint.customer.channel'],
  expectedKpisByUnit: { 个: { orders: 3, shipments: 3, stock: 7 } },
  expectedChannelAmount: 30,
});

const candidates = [
  { id: 'sales-stock', analysis: { familyId: 'commerce-joint.sales.stock' }, source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-sales', expiresAt: '2099-01-01T00:00:00.000Z' }, unit: '个', kpis: [
    { label: '订单', value: 3 }, { label: '发货', value: 3 }, { label: '库存', value: 7 },
  ] },
  { id: 'customer-channel', analysis: { familyId: 'commerce-joint.customer.channel' }, source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-channel', expiresAt: '2099-01-01T00:00:00.000Z' }, unit: '元', kpis: [
    { label: '渠道销售金额', value: 30, unit: '元' },
  ] },
];

function responseBody({ families = candidates, amount = 30, source = 'business' } = {}) {
  const selected = families;
  return { success: true, data: {
    factoryId: 'F006', source, period: { label: '全部历史', periodType: 'all', year: 2026 }, status: 'answered',
    recommendations: selected,
    facts: selected.length ? [
      { id: 'fact-orders', candidateId: 'sales-stock', label: '订单', unit: '个', value: 3 },
      { id: 'fact-shipments', candidateId: 'sales-stock', label: '发货', unit: '个', value: 3 },
      { id: 'fact-stock', candidateId: 'sales-stock', label: '库存', unit: '个', value: 7 },
      { id: 'fact-channel', candidateId: 'customer-channel', label: '渠道销售金额', unit: '元', value: amount },
    ].map((fact) => ({ ...fact, source: 'commerce-joint · 同厂关联' })) : [],
  } };
}

function html() {
  return `<!doctype html><html><body>
    <input data-testid="factory-analysis-input"><button data-testid="factory-analysis-submit">分析</button>
    <section class="turn turn--assistant">
      <div data-testid="factory-analysis-facts"></div>
      <div data-testid="factory-chart-card-sales-stock"><canvas></canvas><button>查看原始明细</button></div>
      <div data-testid="factory-chart-card-customer-channel"><canvas></canvas><button>查看原始明细</button></div>
      <button>Excel</button>
    </section>
    <script>
      document.querySelector('[data-testid="factory-analysis-submit"]').onclick = async () => {
        const response=await fetch('/api/mobile/F006/smart-bi/factory-analysis', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({source:'business', question:document.querySelector('[data-testid="factory-analysis-input"]').value})});
        const body=await response.json(); document.querySelector('[data-testid="factory-analysis-facts"]').innerText=body.data.facts.map(f=>f.label+' '+f.value+' '+f.unit).join(' | ');
      };
      document.querySelectorAll('[data-testid^="factory-chart-card-"] button').forEach((button, index) => button.onclick = async () => {
        await fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({context_id:index === 0 ? 'ctx-sales' : 'ctx-channel', page:1, page_size:50, filters:[]})});
      });
      document.querySelector('.turn--assistant > button').onclick = async () => {
        const response = await fetch('/smartbi-api/api/smartbi/financial-dashboard/export-excel', {method:'POST'});
        const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'factory.xlsx'; link.click();
      };
    </script>
  </body></html>`;
}

async function withFixture(t, options, callback) {
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture');
    if (request.method === 'GET' && url.pathname === '/fixture') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(html()); return;
    }
    if (url.pathname === '/api/mobile/F006/smart-bi/factory-analysis') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify(responseBody(options))); return;
    }
    if (url.pathname === '/smartbi-api/api/smartbi/financial-dashboard/factory-details') {
      let raw = ''; request.on('data', (chunk) => { raw += chunk; }); request.on('end', () => {
        const detailRequest = JSON.parse(raw || '{}');
        const candidate = detailRequest.context_id === 'ctx-sales' ? candidates[0] : candidates[1];
        response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify({ success: true, data: {
          rows: [{ id: detailRequest.context_id }], total: 1, page: 1, pageSize: 50, factoryId: 'F006', candidateId: candidate.id,
          source: candidate.source, period: '全部历史', columns: ['id'], hasMore: false,
        } }));
      }); return;
    }
    if (url.pathname.endsWith('/export-excel')) {
      response.writeHead(200, { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': 'attachment; filename=factory.xlsx' }); response.end('fixture'); return;
    }
    response.writeHead(404); response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const evidenceDir = path.join('test-results', 'smartbi-focused-fixture'); fs.mkdirSync(evidenceDir, { recursive: true });
  try {
    await t.page.goto(`http://127.0.0.1:${server.address().port}/fixture`, { waitUntil: 'domcontentloaded' });
    const result = await inspectCommercePage(t.page, {
      evidenceDir,
      screenshot: (name) => t.page.screenshot({ path: path.join(evidenceDir, `${name}.png`) }),
    }, expected);
    await callback(result);
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test('focused commerce fixture proves positive DOM, async requests, download and screenshot', async ({ page }, testInfo) => {
  await withFixture({ page }, {}, async (result) => {
    expect(result.assessment.result).toBe('PASS');
    expect(result.assessment.checks.plannerAiEvidence).toBe(true);
    expect(result.assessment.checks['kpi:个:orders']).toBe(true);
    expect(result.assessment.checks.channelAmount).toBe(true);
    expect(result.detailsRequests.every((item) => item.requestMatches)).toBe(true);
    expect(result.screenshot).toBeTruthy();
  });
});

test('focused commerce fixture rejects missing family, wrong KPI, and source mismatch', async ({ page }) => {
  await withFixture({ page }, { families: [candidates[0]] }, async (result) => expect(result.assessment.result).toBe('UNVERIFIED'));
  await withFixture({ page }, { amount: 31 }, async (result) => expect(result.assessment.result).toBe('UNVERIFIED'));
  await withFixture({ page }, { source: 'other-source' }, async (result) => expect(result.assessment.result).toBe('UNVERIFIED'));
});

test('focused fixture contains no mutation method', async ({ page }) => {
  const methods = [];
  const requests = [];
  page.on('request', (request) => { methods.push(request.method()); requests.push(request.url()); });
  await withFixture({ page }, {}, async () => {});
  expect(methods.filter((method) => !['GET', 'POST'].includes(method))).toEqual([]);
  expect(requests.some((url) => /\/api\/.*(?:delete|update|create|save|upload)/iu.test(url))).toBe(false);
});

const legacyExpected = validateLegacyOptions({
  uploadIds: { production: 5285, quality: 5286, cost: 5287 },
  expected: { totalCost: 2500, materialCost: 1600, laborCost: 500, equipmentCost: 300 },
});

function legacyHtml(selectedId = 5287) {
  return `<!doctype html><html><body>
    <div class="header-actions"><div class="el-select"><input value=""><div class="el-select-dropdown__item">全部数据</div><div class="el-select-dropdown__item" style="display:none">批次 QA-F006 (3 表)</div></div></div>
    <div class="sheet-tabs"><div class="el-tabs__item" style="display:none">成本</div></div>
    <div id="kpis"></div>
    <script>
      async function loadHistory() { await fetch('/api/mobile/F006/smart-bi/uploads'); const option = document.querySelectorAll('.el-select-dropdown__item')[1]; option.style.display='block'; option.onclick = async () => { document.querySelector('.el-select input').value='QA.xlsx'; const tab=document.querySelector('.sheet-tabs .el-tabs__item'); tab.style.display='block'; tab.onclick=async()=>{const r=await fetch('/smartbi-api/api/smartbi/analysis-cache/${selectedId}'); await r.json(); tab.setAttribute('aria-selected','true'); document.querySelector('#kpis').innerText='总成本\\n2,500\\n材料成本\\n1,600\\n人工成本\\n500\\n设备成本\\n300';}; } }
      document.querySelector('.el-select').onclick = () => { document.querySelector('.el-select-dropdown__item').style.display='block'; };
      setTimeout(loadHistory, 300);
    </script>
  </body></html>`;
}

async function withLegacyFixture(t, selectedId, callback) {
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture');
    if (url.pathname === '/fixture') { response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(legacyHtml(selectedId)); return; }
    if (url.pathname === '/api/mobile/F006/smart-bi/uploads' && request.method === 'GET') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify({ success: true, data: [
        { id: 5287, fileName: 'QA.xlsx', sheetName: '成本', createdAt: '2026-10-29T04:00:02Z' },
        { id: 5286, fileName: 'QA.xlsx', sheetName: '质检', createdAt: '2026-10-29T04:00:01Z' },
        { id: 5285, fileName: 'QA.xlsx', sheetName: '生产', createdAt: '2026-10-29T04:00:00Z' },
      ] })); return;
    }
    if (/\/analysis-cache\/\d+$/u.test(url.pathname) && request.method === 'GET') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify({ success: true, cached: true, kpiSummary: [] })); return;
    }
    response.writeHead(404); response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    await t.page.goto(`http://127.0.0.1:${server.address().port}/fixture`, { waitUntil: 'domcontentloaded' });
    await callback(await inspectLegacyPage(t.page, '', legacyExpected));
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test('legacy fixture proves history batch, cost tab and selected upload source', async ({ page }) => {
  await withLegacyFixture({ page }, 5287, async (result) => {
    expect(result.assessment.result).toBe('PASS');
    expect(result.assessment.checks.sourceDataVerified).toBe(true);
    expect(result.assessment.checks.costTabSelected).toBe(true);
  });
});

test('legacy fixture rejects same-money response from the wrong upload source', async ({ page }) => {
  await withLegacyFixture({ page }, 9999, async (result) => {
    expect(result.assessment.result).not.toBe('PASS');
    expect(result.assessment.checks.sourceDataVerified).toBe(false);
  });
});

test('purchase inventory fixture proves four same-unit facts, detail voucher scope and export', async ({ page }, testInfo) => {
  const candidate = { id: 'purchase-inventory-kg', source: 'purchase-batch-lineage · 已确认收货与物料批次双向来源', unit: 'kg', detailContext: { id: 'ctx-purchase' }, analysis: { familyId: 'purchase-batch-lineage.purchase.inventory' }, kpis: [
    { label: '收货', value: 595, unit: 'kg' }, { label: '已用', value: 20, unit: 'kg' }, { label: '预留', value: 5, unit: 'kg' }, { label: '当前可用', value: 570, unit: 'kg' },
  ] };
  const html = `<!doctype html><html><body><input data-testid="factory-analysis-input"><button data-testid="factory-analysis-submit">分析</button><div data-testid="factory-analysis-facts">收货 595 kg 已用 20 kg 预留 5 kg 当前可用 570 kg</div><section class="turn turn--assistant"><div data-testid="factory-chart-card-purchase-inventory-kg"><canvas></canvas><button>查看原始明细</button></div><button>Excel</button></section><script>
    document.querySelector('[data-testid="factory-analysis-submit"]').onclick=async()=>fetch('/api/mobile/F006/smart-bi/factory-analysis',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({question:document.querySelector('[data-testid="factory-analysis-input"]').value,source:'business'})});
    document.querySelector('[data-testid^="factory-chart-card-"] button').onclick=async()=>fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({context_id:'ctx-purchase',page:1,page_size:50,filters:[]})});
    document.querySelector('.turn--assistant > button').onclick=async()=>{const r=await fetch('/smartbi-api/api/smartbi/financial-dashboard/export-excel',{method:'POST'});const a=document.createElement('a');a.href=URL.createObjectURL(await r.blob());a.download='purchase.xlsx';a.click();};</script></body></html>`;
  const server = http.createServer((request, response) => { const url = new URL(request.url, 'http://fixture');
    if (url.pathname === '/fixture') { response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(html); return; }
    if (url.pathname.endsWith('/factory-analysis')) { response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', source: 'business', period: { label: '全部历史' }, limitations: ['库存为当前时点快照，不代表历史期末余额'], status: 'answered', recommendations: [candidate], facts: candidate.kpis.map((item, index) => ({ ...item, id: `fact-${index}`, candidateId: candidate.id, source: candidate.source })) } })); return; }
    if (url.pathname.endsWith('/factory-details')) { response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' }); response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', candidateId: candidate.id, source: candidate.source, period: '全部历史', rows: [{ 收货单ID: 'R1', 收货行ID: 'RL1', 物料批次ID: 'B1', 数量单位: 'kg', 批次入库数量: 595, 批次已用数量: 20, 批次预留数量: 5, 当前可用数量: 570 }], total: 1, page: 1, pageSize: 50 } })); return; }
    if (url.pathname.endsWith('/export-excel')) { response.writeHead(200, { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': 'attachment; filename=purchase.xlsx' }); response.end('fixture'); return; }
    response.writeHead(404); response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const evidenceDir = path.join('test-results', 'smartbi-focused-fixture'); fs.mkdirSync(evidenceDir, { recursive: true });
  try { await page.goto(`http://127.0.0.1:${server.address().port}/fixture`, { waitUntil: 'domcontentloaded' }); const result = await inspectPurchaseInventoryPage(page, { evidenceDir, screenshot: (name) => page.screenshot({ path: path.join(evidenceDir, `${name}.png`) }) }, { expectedKpisByUnit: { kg: { received: 595, used: 20, reserved: 5, available: 570 } } }); expect(result.assessment.result).toBe('PASS'); } finally { server.closeAllConnections?.(); await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); }
});
