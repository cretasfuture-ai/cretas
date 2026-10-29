'use strict';

const http = require('node:http');
const fs = require('node:fs');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');

function pageHtml() {
  return `<!doctype html><html><body><div id="app">Cretas F006</div><div id="app-loader" style="display:none"></div>
    <input data-testid="factory-analysis-input"><button data-testid="factory-analysis-submit">分析</button>
    <div>智能数据分析 并发只读分析详情验证页面 F006 业务系统 全部历史期间</div><article class="turn turn--assistant"><div data-testid="factory-analysis-facts"></div><div role="alert" data-testid="factory-analysis-error"></div><div id="cards"></div></article><script>
      document.querySelector('[data-testid="factory-analysis-submit"]').onclick=async()=>{try {const input=document.querySelector('[data-testid="factory-analysis-input"]');
        const r=await fetch('/api/mobile/F006/smart-bi/factory-analysis',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:'business',question:input.value,analysisSubject:null,periodType:'all',year:2026,startMonth:1,endMonth:12})});
        const b=await r.json(); if(!b.success){document.querySelector('[data-testid="factory-analysis-error"]').textContent='分析失败，请重试';return;} document.querySelector('[data-testid="factory-analysis-facts"]').textContent=b.data.facts.map(f=>f.label+' '+f.value+' '+f.unit).join(' | '); document.querySelector('#cards').innerHTML='<div data-testid="factory-chart-card-sales-stock"><canvas></canvas><button>查看原始明细</button></div><div data-testid="factory-chart-card-customer-channel"><canvas></canvas><button>查看原始明细</button></div><button data-testid="factory-analysis-export">Excel</button>'; document.querySelectorAll('#cards button:not([data-testid])').forEach((button,index)=>button.onclick=()=>fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({context_id:index===0?'ctx-sales':'ctx-channel',page:1,page_size:50,filters:[]})})); document.querySelector('[data-testid="factory-analysis-export"]').onclick=async()=>{const r=await fetch('/smartbi-api/api/smartbi/financial-dashboard/export-excel',{method:'POST'});const a=document.createElement('a');a.href=URL.createObjectURL(await r.blob());a.download='commerce.xlsx';a.click();};}catch(error){document.querySelector('[data-testid="factory-analysis-error"]').textContent='网络错误，请重试';}};

    </script></body></html>`;
}

function loginHtml() {
  return `<!doctype html><html><body><div id="app">Login</div><div id="app-loader" style="display:none"></div>
    <input placeholder="用户名"><input placeholder="密码" type="password"><button class="login-button">登录</button>
    <script>document.querySelector('.login-button').onclick=async()=>{const username=document.querySelector('input[placeholder="用户名"]').value; const actorId=document.querySelector('input[placeholder="密码"]').value; const response=await fetch('/api/mobile/auth/unified-login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username})}); await response.json(); localStorage.setItem('cretas_user',JSON.stringify({username,userId:actorId,factoryUser:{username,userId:actorId,id:actorId,factoryId:'F006'},factoryId:'F006',factoryName:'Cretas F006',role:'admin'})); location.href='/home';};</script>
  </body></html>`;
}

test('parallel harness runs two fresh contexts and closes each context', async ({ page }) => {
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture');
    if (url.pathname === '/login') { response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(loginHtml()); return; }
    if (url.pathname === '/home' || url.pathname.startsWith('/smart-bi/analysis')) { response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(pageHtml()); return; }
    if (url.pathname.endsWith('/unified-login')) {
      let raw = ''; request.on('data', chunk => { raw += chunk; }); request.on('end', () => {
        const username = JSON.parse(raw || '{}').username || 'unknown'; response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ success: true, data: { username, factoryUser: { username, id: username, factoryId: 'F006' }, factoryId: 'F006', factoryName: 'Cretas F006', role: 'admin' } }));
      }); return;
    }
    if (url.pathname.endsWith('/factory-analysis')) {
      response.writeHead(200, { 'content-type': 'application/json' }); setTimeout(() => response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', source: 'business', period: { label: '全部历史', periodType: 'all', year: 2026 }, status: 'answered', catalog: [{ analysis: { familyId: 'commerce-joint.sales.stock' } }, { analysis: { familyId: 'commerce-joint.customer.channel' } }], recommendations: [{ id: 'sales-stock', analysis: { familyId: 'commerce-joint.sales.stock' }, source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-sales' }, unit: '个', kpis: [{ label: '订单', value: 3, unit: '个' }, { label: '发货', value: 3, unit: '个' }, { label: '库存', value: 7, unit: '个' }] }, { id: 'customer-channel', analysis: { familyId: 'commerce-joint.customer.channel' }, source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-channel' }, unit: '元', kpis: [{ label: '渠道销售金额', value: 30, unit: '元' }] }], facts: [{ id: 'fact-orders', candidateId: 'sales-stock', label: '订单', value: 3, unit: '个', source: 'commerce-joint · 同厂关联' }, { id: 'fact-shipments', candidateId: 'sales-stock', label: '发货', value: 3, unit: '个', source: 'commerce-joint · 同厂关联' }, { id: 'fact-stock', candidateId: 'sales-stock', label: '库存', value: 7, unit: '个', source: 'commerce-joint · 同厂关联' }, { id: 'fact-channel', candidateId: 'customer-channel', label: '渠道销售金额', value: 30, unit: '元', source: 'commerce-joint · 同厂关联' }] } })), 100); return;
    }
    if (url.pathname.endsWith('/factory-details')) {
      let raw = ''; request.on('data', chunk => { raw += chunk; }); request.on('end', () => { const candidateId = JSON.parse(raw || '{}').context_id === 'ctx-channel' ? 'customer-channel' : 'sales-stock'; response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', candidateId, source: 'commerce-joint · 同厂关联', period: '全部历史', rows: [{ id: 'fixture-row' }], total: 1 } })); }); return;
    }
    if (url.pathname.endsWith('/export-excel')) { response.writeHead(200, { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': 'attachment; filename=commerce.xlsx' }); response.end('fixture'); return; }
    response.writeHead(404); response.end();
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  const initialContexts=page.context().browser().contexts().length;
  try {
    const result = await runSuiteWithPage(page, {
      baseUrl,
      scenarios: ['smartbi-commerce-joint-readonly', 'smartbi-parallel-recovery-readonly'],
      parallelRuns: [
        { username: 'f006_admin_1309', password: '1309', expectedUsername: 'f006_admin_1309', expectedActorId: '1309', expectedFactoryId: 'F006', runId: 'fixture-1', smartbiCommerceJoint: { expectedFamilies: ['commerce-joint.sales.stock', 'commerce-joint.customer.channel'], expectedKpisByUnit: { 个: { orders: 3, shipments: 3, stock: 7 } }, expectedChannelAmount: 30 } },
        { username: 'f006_admin_1551', password: '1551', expectedUsername: 'f006_admin_1551', expectedActorId: '1551', expectedFactoryId: 'F006', runId: 'fixture-2', smartbiCommerceJoint: { expectedFamilies: ['commerce-joint.sales.stock', 'commerce-joint.customer.channel'], expectedKpisByUnit: { 个: { orders: 3, shipments: 3, stock: 7 } }, expectedChannelAmount: 30 } },
      ],
    });
    fs.writeFileSync('test-output/sc40/root-fixture-report.json',JSON.stringify(result,null,2));
    expect(result.result).toBe('PASS');
    expect(result.allBusinessScenariosPassed).toBe(true);
    expect(result.distinctActors).toBe(true);
    expect(result.concurrentRequestsObserved).toBe(true);
    expect(page.context().browser().contexts().length).toBe(initialContexts);
    for(const item of result.runs){
      expect(item.cleanup.closed).toBe(true);
      expect(item.result.network.expectedTransportFailures.length).toBe(1);
      expect(item.result.network.failedRequests.length).toBe(0);
      expect(item.result.network.httpErrors.length).toBe(0);
      expect(item.result.console.consoleErrors.length).toBe(0);
      expect(item.result.console.pageErrors.length).toBe(0);
    }
    expect(result.parallel).toBe(true);
    expect(result.runs).toHaveLength(2);
    expect(result.runs.every(run => run.result?.tenant?.factoryId === 'F006')).toBe(true);
    expect(result.runs.every(run => run.result?.scenarios?.some(scenario => JSON.stringify(scenario).includes('retriedOnline')))).toBe(true);
    expect(result.runs.every(run => run.result?.scenarios?.some(scenario => scenario.scenario === 'smartbi-commerce-joint-readonly' && scenario.result === 'PASS'))).toBe(true);
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
